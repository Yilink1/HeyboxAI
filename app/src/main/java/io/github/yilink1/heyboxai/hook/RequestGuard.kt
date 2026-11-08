package io.github.yilink1.heyboxai.hook

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 对宿主发起的批量请求做节流和熔断：
 * - 请求之间加随机间隔，避免短时间内的整齐突发；
 * - 连续失败或遇到 403/429 立即熔断，本轮不再向服务端发任何请求，不做紧循环重试。
 */
object RequestGuard {

    /** 尽力从异常链中取出 HTTP 状态码（Retrofit HttpException.code() 或 "HTTP 403" 形式的消息） */
    fun httpCode(t: Throwable?): Int? {
        var cur = t
        var depth = 0
        while (cur != null && depth < 6) {
            try {
                val m = cur.javaClass.methods.firstOrNull {
                    it.name == "code" && it.parameterCount == 0 && it.returnType == Int::class.javaPrimitiveType
                }
                (m?.invoke(cur) as? Int)?.let { if (it in 100..599) return it }
            } catch (ignored: Throwable) {
            }
            Regex("""HTTP (\d{3})""").find(cur.message ?: "")?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
            cur = cur.cause
            depth++
        }
        return null
    }

    fun isRateLimitOrBlocked(t: Throwable?): Boolean = httpCode(t).let { it == 403 || it == 429 }

    fun sleepJitter(minMs: Long, maxMs: Long) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(minMs, maxMs + 1))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    class Breaker(private val maxConsecutiveFailures: Int = 3) {
        private val failures = AtomicInteger(0)
        private val trippedFlag = AtomicBoolean(false)

        val tripped: Boolean get() = trippedFlag.get()

        fun onSuccess() {
            failures.set(0)
        }

        /** 返回是否已熔断 */
        fun onFailure(t: Throwable?): Boolean {
            if (isRateLimitOrBlocked(t) || failures.incrementAndGet() >= maxConsecutiveFailures) {
                trippedFlag.set(true)
            }
            return tripped
        }
    }
}
