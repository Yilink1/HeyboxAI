package io.github.yilink1.heyboxai.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class AiClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String
) {
    companion object {
        // 共享连接池，避免每次请求都新建 OkHttpClient（宿主进程内长期运行）
        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)  // 推理模型首 token 可能很慢，放宽至 120s
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** 把网络层 / 解析层异常翻成用户看得懂的中文，避免把 "Unable to resolve host ..." 原样展示 */
        fun friendlyError(e: Throwable): String = when {
            e is java.net.SocketTimeoutException -> "连接超时，请检查网络或代理"
            e is java.net.UnknownHostException -> "无法解析接口域名，请检查网址与网络"
            e is javax.net.ssl.SSLException -> "安全连接失败，请检查系统时间或代理证书"
            e is java.net.ConnectException -> "无法连接到服务器，请检查接口地址"
            e is IllegalArgumentException -> "接口地址或 API Key 格式不正确"
            e is java.io.InterruptedIOException || e.message == "Canceled" || e.message == "Socket closed" -> "连接已中断"
            else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        }

        /** 从错误响应体里取出服务商给的原因（OpenAI 兼容格式 error.message），截断防止刷屏 */
        private fun extractErrorDetail(body: String?): String {
            if (body.isNullOrBlank()) return ""
            return try {
                val obj = com.google.gson.JsonParser.parseString(body).asJsonObject
                val err = obj.get("error")
                val msg = when {
                    err == null || err.isJsonNull -> obj.get("message")?.asString
                    err.isJsonObject -> err.asJsonObject.get("message")?.asString
                    else -> err.asString
                }
                (msg ?: "").trim().take(160)
            } catch (ignored: Exception) {
                ""
            }
        }

        private fun describeHttpError(code: Int, body: String?): String {
            val base = when (code) {
                400 -> "请求被服务商拒绝，可能是上下文过长或参数不兼容"
                401 -> "API Key 无效或未授权"
                402 -> "账户余额不足"
                403 -> "无权访问此模型或服务受限"
                404 -> "模型名称不存在或接口地址错误"
                413 -> "内容过长，请缩小分析范围"
                429 -> "请求过于频繁或额度已用尽"
                in 500..599 -> "AI 服务端暂时异常"
                else -> "API 请求失败"
            }
            val detail = extractErrorDetail(body)
            return if (detail.isNotEmpty()) "$base ($code)：$detail" else "$base ($code)"
        }
    }

    private val gson = Gson()

    data class Message(val role: String, val content: String)
    data class TestResult(val success: Boolean, val message: String, val latencyMs: Long = 0)

    private fun normalizeBaseUrl(): String {
        var url = baseUrl.trim()
        // 兼容用户直接粘贴完整端点的情况
        url = url.removeSuffix("/").removeSuffix("/chat/completions").removeSuffix("/")
        return "$url/"
    }

    /**
     * 测试连通性：优先走真正用到的 chat/completions（max_tokens=1），
     * 这样能同时验证 Key、端点和模型名；没填模型时退化为 GET /models。
     */
    suspend fun testConnection(): TestResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val request = if (model.isNotBlank()) {
                val body = mapOf(
                    "model" to model,
                    "messages" to listOf(mapOf("role" to "user", "content" to "hi")),
                    "max_tokens" to 1,
                    "stream" to false
                )
                Request.Builder()
                    .url("${normalizeBaseUrl()}chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(gson.toJson(body).toRequestBody(JSON))
                    .build()
            } else {
                Request.Builder()
                    .url("${normalizeBaseUrl()}models")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()
            }

            client.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - start
                if (response.isSuccessful) {
                    TestResult(true, "连接成功 (响应延迟: ${latency}ms)", latency)
                } else {
                    val code = response.code
                    val desc = when (code) {
                        401 -> "API Key 无效或未授权"
                        403 -> "无权访问此模型或服务受限"
                        404 -> "模型名称不存在或接口地址错误"
                        429 -> "请求过于频繁或额度已用尽"
                        in 500..599 -> "AI 服务端暂时异常"
                        else -> "HTTP $code"
                    }
                    TestResult(false, "$desc ($code)", latency)
                }
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - start
            val msg = when {
                e is java.net.SocketTimeoutException -> "连接超时，请检查网络或代理"
                e is java.net.UnknownHostException -> "无法解析接口域名，请检查网址"
                else -> e.message ?: "未知网络错误"
            }
            TestResult(false, "网络错误: $msg", latency)
        }
    }

    /**
     * 自动拉取当前供应商支持的模型列表
     */
    suspend fun fetchModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val endpoint = "${normalizeBaseUrl()}models"
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("获取失败 (HTTP ${response.code})"))
                }

                val body = response.body?.string() ?: return@withContext Result.failure(Exception("响应为空"))
                val json = gson.fromJson(body, JsonObject::class.java)
                val dataArray = json.getAsJsonArray("data") ?: return@withContext Result.failure(Exception("服务商返回的数据格式不兼容"))

                val list = mutableListOf<String>()
                for (elem in dataArray) {
                    if (elem.isJsonObject) {
                        val id = elem.asJsonObject.get("id")?.asString
                        if (!id.isNullOrBlank()) {
                            list.add(id)
                        }
                    }
                }
                list.sort()
                Result.success(list)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @Volatile
    private var activeCall: okhttp3.Call? = null

    /**
     * 强行立即切断底层 TCP Socket / HTTP 连接（0ms 立即断网停生成）
     */
    fun cancelActiveCall() {
        try {
            activeCall?.cancel()
        } catch (ignored: Throwable) {}
    }

    /**
     * 发送聊天流式请求，逐段返回 AI 内容。
     * 协程被取消时会主动 cancel 底层 HTTP 调用，立即断开连接停止计费。
     */
    fun chatStream(messages: List<Message>): Flow<String> = flow {
        val endpoint = "${normalizeBaseUrl()}chat/completions"

        val bodyMap = mapOf(
            "model" to model,
            "messages" to messages,
            "stream" to true
        )
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(gson.toJson(bodyMap).toRequestBody(JSON))
            .build()

        val call = client.newCall(request)
        activeCall = call
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion {
            try { call.cancel() } catch (ignored: Throwable) {}
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val code = response.code
                    val humanError = describeHttpError(code, runCatching { response.body?.string() }.getOrNull())
                    throw IOException(humanError)
                }

                val bodyStream = response.body?.byteStream()
                    ?: throw IOException("响应流为空")

                val reader = BufferedReader(InputStreamReader(bodyStream, Charsets.UTF_8))
                var line: String?
                var sawSse = false
                val nonSse = StringBuilder()  // 供应商忽略 stream 参数返回整包 JSON 时的兜底缓冲
                while (reader.readLine().also { line = it } != null) {
                    currentCoroutineContext().ensureActive()
                    val l = line?.trim() ?: continue
                    // 部分供应商返回 "data:{...}"（冒号后无空格）
                    if (!l.startsWith("data:")) {
                        if (!sawSse && nonSse.length < 1_000_000) nonSse.append(l)
                        continue
                    }
                    sawSse = true
                    val data = l.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    if (data.isEmpty()) continue

                    var piece: String? = null
                    var apiError: String? = null
                    try {
                        val json = gson.fromJson(data, JsonObject::class.java)
                        val err = json.get("error")
                        if (err != null && !err.isJsonNull) {
                            apiError = err.toString()
                        } else {
                            val choices = json.getAsJsonArray("choices")
                            if (choices != null && choices.size() > 0) {
                                val delta = choices[0].asJsonObject.getAsJsonObject("delta")
                                val c = delta?.get("content")
                                // 推理模型会先吐 reasoning_content、content 为 null，这里只取正文
                                if (c != null && !c.isJsonNull) piece = c.asString
                            }
                        }
                    } catch (ignored: Exception) {
                    }
                    if (apiError != null) throw IOException("API 返回错误: $apiError")
                    if (!piece.isNullOrEmpty()) emit(piece)
                }

                if (!sawSse && nonSse.isNotEmpty()) {
                    var whole: String? = null
                    var apiError: String? = null
                    try {
                        val json = gson.fromJson(nonSse.toString(), JsonObject::class.java)
                        val err = json.get("error")
                        if (err != null && !err.isJsonNull) {
                            apiError = err.toString()
                        } else {
                            val msg = json.getAsJsonArray("choices")?.get(0)?.asJsonObject?.getAsJsonObject("message")
                            val c = msg?.get("content")
                            if (c != null && !c.isJsonNull) whole = c.asString
                        }
                    } catch (ignored: Exception) {
                    }
                    if (apiError != null) throw IOException("API 返回错误: $apiError")
                    if (!whole.isNullOrEmpty()) emit(whole)
                }
            }
        } finally {
            activeCall = null
            handle?.dispose()
        }
    }.flowOn(Dispatchers.IO)
}
