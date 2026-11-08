package io.github.yilink1.heyboxai.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * 通用 OkHttp 响应数据嗅探器（跨版本免疫）
 *
 * 核心机制：
 * 动态识别小黑盒底层 OkHttp 中的 RealCall 执行方法（getResponseWithInterceptorChain / execute 等），
 * 在响应返回瞬间零损耗拷贝 JSON 内容送入 HeyboxDataRepository。
 */
object HeyboxNetworkSniffer {

    private const val TAG = "HeyboxAI_Sniffer"

    fun install(module: XposedModule, classLoader: ClassLoader) {
        try {
            // 兼容 OkHttp 4.x 和 OkHttp 3.x
            val realCallClass = try {
                classLoader.loadClass("okhttp3.internal.connection.RealCall")
            } catch (e: Throwable) {
                try {
                    classLoader.loadClass("okhttp3.RealCall")
                } catch (e2: Throwable) {
                    null
                }
            } ?: run {
                Log.w(TAG, "未找到 okhttp3 RealCall 类")
                return
            }

            // 寻找执行请求并返回 Response 的核心方法
            val targetMethod = findResponseMethod(realCallClass)
            if (targetMethod == null) {
                Log.w(TAG, "未在 RealCall 中找到返回 Response 的方法")
                return
            }

            module.hook(targetMethod)
                .setPriority(XposedInterface.PRIORITY_LOWEST)
                .intercept { chain ->
                    val response = chain.proceed()
                    try {
                        inspectResponse(response)
                    } catch (t: Throwable) {
                        Log.w(TAG, "嗅探 Response 异常: ${t.message}")
                    }
                    response
                }

            Log.i(TAG, "成功注入 OkHttp 网络层嗅探器: method=${targetMethod.name}")

        } catch (e: Throwable) {
            Log.e(TAG, "安装网络嗅探器失败", e)
        }
    }

    private fun findResponseMethod(clazz: Class<*>): Method? {
        val methods = clazz.declaredMethods
        // 1. 优先匹配 getResponseWithInterceptorChain (同步与异步请求最终均汇聚于此)
        for (m in methods) {
            if (m.name.contains("getResponseWithInterceptorChain") && m.parameterCount == 0) {
                m.isAccessible = true
                return m
            }
        }
        // 2. 次级匹配 execute()
        for (m in methods) {
            if (m.name == "execute" && m.parameterCount == 0) {
                m.isAccessible = true
                return m
            }
        }
        return null
    }

    private fun inspectResponse(response: Any?) {
        if (response == null) return
        val rClass = response.javaClass

        // 获取 request -> url
        val requestMethod = rClass.methods.firstOrNull { it.name == "request" && it.parameterCount == 0 }
        val requestObj = requestMethod?.invoke(response) ?: return
        val urlMethod = requestObj.javaClass.methods.firstOrNull { it.name == "url" && it.parameterCount == 0 }
        val urlObj = urlMethod?.invoke(requestObj) ?: return
        val urlStr = urlObj.toString()

        // 广泛匹配小黑盒帖子与评论各类 API (兼容普通帖、图文帖、资讯帖、长评)
        val isTarget = urlStr.contains("bbs/app/api/link/post") ||
                       urlStr.contains("bbs/app/link/tree") ||
                       urlStr.contains("bbs/app/comment/sub/comments") ||
                       urlStr.contains("bbs/app/api/news") ||
                       urlStr.contains("bbs/app/link/detail") ||
                       urlStr.contains("news/app/api/link/post") ||
                       urlStr.contains("api/link/post") ||
                       urlStr.contains("comment/sub")
        if (!isTarget) {
            return
        }

        // 探测 peekBody 方法（OkHttp 原生支持，零消耗读取指定字节，不影响原有 Stream 消费）
        val peekBodyMethod = rClass.methods.firstOrNull {
            it.name == "peekBody" && it.parameterCount == 1 && it.parameterTypes[0] == java.lang.Long.TYPE
        }

        if (peekBodyMethod != null) {
            // 最多 peek 1MB 文本内容
            val peekedBody = peekBodyMethod.invoke(response, 1024L * 1024L)
            if (peekedBody != null) {
                val stringMethod = peekedBody.javaClass.methods.firstOrNull { it.name == "string" && it.parameterCount == 0 }
                val jsonText = stringMethod?.invoke(peekedBody) as? String
                if (!jsonText.isNullOrBlank()) {
                    HeyboxDataRepository.onNetworkResponse(urlStr, jsonText)
                }
            }
        }
    }
}
