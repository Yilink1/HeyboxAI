package io.github.yilink1.heyboxai.hook

import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 跨 ClassLoader 挂起函数反射调用桥接器
 *
 * 规范化反射调用 Kotlin suspend 方法，统一处理代理 Continuation、异常拆包与超时控制，
 * 彻底消除业务类中大段重复的动态代理逻辑，并剔除反编译历史遗留的混淆类名残留。
 */
object SuspendBridge {

    /**
     * 调用宿主对象的挂起方法
     *
     * @param target 调用目标实例 (如 LinkPostRepository)
     * @param method 目标挂起方法 (其末尾参数必为 Continuation)
     * @param args 普通入参 (不包含末尾的 Continuation)
     * @param classLoader 宿主 ClassLoader
     * @param timeoutMs 超时时间限制 (毫秒)
     * @return 挂起方法返回的正常结果对象
     */
    fun invokeSuspend(
        target: Any,
        method: Method,
        args: Array<Any?>,
        classLoader: ClassLoader,
        timeoutMs: Long
    ): Any? {
        val lastParamIdx = method.parameterTypes.size - 1
        require(lastParamIdx >= 0) { "方法 ${method.name} 没有参数" }

        val continuationClass = method.parameterTypes[lastParamIdx]
        // 不再按类名/类型强校验：只要调用方能接受宿主 Continuation 代理即放行。
        // 之前的硬校验在跨 ClassLoader 场景会误判（logcat: "方法 h 的末尾参数并非 Continuation"），
        // 导致主评论分页 1ms 内熔断，永远只有第一页 30 楼。
        val isContinuation = continuationClass.name.contains("Continuation") ||
            runCatching { classLoader.loadClass("kotlin.coroutines.Continuation").isAssignableFrom(continuationClass) }.getOrDefault(false)
        if (!isContinuation) {
            android.util.Log.w("HeyboxAI_SuspendBridge", "末尾参数类型未识别为 Continuation (${continuationClass.name})，仍尝试调用")
        }

        // 统一获取干净的标准协程上下文，若跨 ClassLoader 隔离则动态代理兜底
        val coroutineContext = try {
            val emptyCtxClass = classLoader.loadClass("kotlin.coroutines.EmptyCoroutineContext")
            emptyCtxClass.getField("INSTANCE").get(null)
        } catch (e: Throwable) {
            try {
                EmptyCoroutineContext
            } catch (e2: Throwable) {
                val contextClass = classLoader.loadClass("kotlin.coroutines.CoroutineContext")
                Proxy.newProxyInstance(classLoader, arrayOf(contextClass)) { _, ctxMethod, ctxArgs ->
                    when (ctxMethod.name) {
                        "get" -> null
                        "fold" -> ctxArgs?.getOrNull(0)
                        "plus" -> ctxArgs?.getOrNull(0)
                        "minusKey" -> null
                        "hashCode" -> 0
                        "equals" -> false
                        "toString" -> "EmptyCoroutineContext"
                        else -> null
                    }
                }
            }
        }

        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<Any?>()
        val errorRef = AtomicReference<Throwable?>()

        val proxyContinuation = Proxy.newProxyInstance(
            classLoader,
            arrayOf(continuationClass)
        ) { _, proxyMethod, methodArgs ->
            when (proxyMethod.name) {
                "getContext" -> coroutineContext
                "resumeWith" -> {
                    val resultObj = methodArgs?.getOrNull(0)
                    try {
                        if (resultObj != null && (resultObj.javaClass.name == "kotlin.Result\$Failure" || (resultObj.javaClass.name.endsWith("\$Failure") && resultObj.javaClass.name.startsWith("kotlin.Result")))) {
                            val exceptionField = resultObj.javaClass.declaredFields.firstOrNull {
                                Throwable::class.java.isAssignableFrom(it.type)
                            }
                            exceptionField?.isAccessible = true
                            val ex = exceptionField?.get(resultObj) as? Throwable
                                ?: RuntimeException("Kotlin Result.Failure: $resultObj")
                            errorRef.set(ex)
                        } else {
                            resultRef.set(resultObj)
                        }
                    } catch (t: Throwable) {
                        errorRef.set(t)
                    } finally {
                        latch.countDown()
                    }
                    null
                }
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                "toString" -> "ProxyContinuation@${Integer.toHexString(System.identityHashCode(this))}"
                else -> null
            }
        }

        // 组装实际调用的完整入参列表：args + Continuation
        val fullArgs = Array(args.size + 1) { i ->
            if (i < args.size) args[i] else proxyContinuation
        }

        val invokeReturn = method.invoke(target, *fullArgs)

        // 如果该方法没有立即挂起而是同步返回了结果
        // COROUTINE_SUSPENDED 是 CoroutineSingletons 枚举常量，类名里不含该字样，必须按枚举名判断
        val isSuspended = (invokeReturn as? Enum<*>)?.name == "COROUTINE_SUSPENDED"
        if (!isSuspended) {
            return invokeReturn
        }

        // 等待异步 resumeWith 完成
        val completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!completed) {
            throw java.util.concurrent.TimeoutException("挂起函数 ${method.name} 调用超时 (${timeoutMs}ms)")
        }

        errorRef.get()?.let { throw it }
        return resultRef.get()
    }
}
