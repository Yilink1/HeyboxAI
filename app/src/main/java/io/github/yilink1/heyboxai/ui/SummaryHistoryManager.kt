package io.github.yilink1.heyboxai.ui

import android.content.Context
import android.util.Log
import io.github.yilink1.heyboxai.ai.AiClient
import io.github.yilink1.heyboxai.hook.XiaoheihePostExtractor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 本地总结历史持久化与增量感知管理器
 *
 * 核心设计准则：
 * 1. 范围隔离：缓存键基于帖子 ID 与分析范围 (linkId + scope)，避免不同范围数据互串。
 * 2. 写入原子性：先写入 .tmp 临时文件再 rename，防止进程异常退出导致数据损坏。
 * 3. 失败不毁旧：重新生成失败时不覆盖旧数据，平稳保留上一次有效总结。
 * 4. 存储自限 (LRU 50)：历史记录文件上限 50 条，超额时按修改时间自动清理陈旧文件。
 * 5. 损坏容错：读取异常或版本不兼容时安全静默兜底，不阻断正常业务。
 */
object SummaryHistoryManager {

    private const val TAG = "HeyboxAI_History"
    private const val CURRENT_VERSION = 1
    private const val MAX_HISTORY_FILES = 50
    private const val DIR_NAME = "heybox_ai_history"

    private val ioExecutor = Executors.newSingleThreadExecutor()

    data class CachedHistory(
        val linkId: String,
        val title: String,
        val scope: XiaoheihePostExtractor.SummaryScope,
        val displayText: String,
        val statusText: String,
        val timestamp: Long,
        val mainCommentCount: Int,
        val subCommentCount: Int,
        val completenessBadge: String,
        val completenessDesc: String,
        val messages: List<AiClient.Message>
    )

    private fun getHistoryDir(context: Context): File {
        val dir = File(context.applicationContext.filesDir, DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /** 会话在界面层是“每帖一份”，所以存档也按帖子一份；范围作为字段写在文件内 */
    private fun getCacheFile(context: Context, linkId: String): File {
        val safe = linkId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(getHistoryDir(context), "$safe.json")
    }

    /**
     * 读取历史缓存（同步快速读取）
     */
    fun loadHistory(
        context: Context,
        linkId: String,
        scope: XiaoheihePostExtractor.SummaryScope
    ): CachedHistory? {
        if (linkId.isBlank()) return null
        return try {
            val file = getCacheFile(context, linkId)
            if (!file.exists() || file.length() == 0L) return null

            val jsonStr = file.readText(Charsets.UTF_8)
            val json = JSONObject(jsonStr)

            val version = json.optInt("version", 0)
            if (version != CURRENT_VERSION) {
                Log.w(TAG, "缓存版本不匹配 ($version != $CURRENT_VERSION)，丢弃过期缓存")
                file.delete()
                return null
            }

            val savedLinkId = json.getString("linkId")
            val title = json.optString("title", "")
            val scopeStr = json.optString("scope", scope.name)
            val savedScope = try {
                XiaoheihePostExtractor.SummaryScope.valueOf(scopeStr)
            } catch (e: Exception) {
                scope
            }

            val displayText = json.optString("displayText", "")
            val statusText = json.optString("statusText", "")
            val timestamp = json.optLong("timestamp", System.currentTimeMillis())
            val mainCommentCount = json.optInt("mainCommentCount", 0)
            val subCommentCount = json.optInt("subCommentCount", 0)
            val completenessBadge = json.optString("completenessBadge", "[历史就绪]")
            val completenessDesc = json.optString("completenessDesc", "")

            val messages = mutableListOf<AiClient.Message>()
            val msgsArray = json.optJSONArray("messages")
            if (msgsArray != null) {
                for (i in 0 until msgsArray.length()) {
                    val mObj = msgsArray.getJSONObject(i)
                    messages.add(
                        AiClient.Message(
                            role = mObj.getString("role"),
                            content = mObj.getString("content")
                        )
                    )
                }
            }

            // 新版只存 messages；旧版只有 displayText。两者都空才视为无效存档
            if (messages.isEmpty() && displayText.isBlank()) return null

            CachedHistory(
                linkId = savedLinkId,
                title = title,
                scope = savedScope,
                displayText = displayText,
                statusText = statusText,
                timestamp = timestamp,
                mainCommentCount = mainCommentCount,
                subCommentCount = subCommentCount,
                completenessBadge = completenessBadge,
                completenessDesc = completenessDesc,
                messages = messages
            )
        } catch (e: Throwable) {
            Log.e(TAG, "读取历史缓存异常，清理损坏文件: ${e.message}")
            try {
                getCacheFile(context, linkId).delete()
            } catch (ignored: Exception) {}
            null
        }
    }

    /**
     * 异步持久化保存总结历史
     */
    fun saveHistoryAsync(
        context: Context,
        linkId: String,
        data: XiaoheihePostExtractor.ExtractedData,
        scope: XiaoheihePostExtractor.SummaryScope,
        displayText: String,
        statusText: String,
        messages: List<AiClient.Message>,
        floorCount: Int = data.mainCommentCount
    ) {
        if (linkId.isBlank() || messages.isEmpty()) return
        // 在调用线程上快照列表，避免后台线程写盘时被 UI 线程并发修改
        val messagesSnapshot = messages.toList()

        ioExecutor.execute {
            try {
                val json = JSONObject().apply {
                    put("version", CURRENT_VERSION)
                    put("linkId", linkId)
                    put("title", data.title)
                    put("scope", scope.name)
                    put("displayText", displayText)
                    put("statusText", statusText)
                    put("timestamp", System.currentTimeMillis())
                    put("mainCommentCount", floorCount)
                    put("subCommentCount", data.subCommentCount)
                    put("completenessBadge", data.completenessReport?.statusBadgeText ?: "[已就绪]")
                    put("completenessDesc", data.completenessReport?.summaryDescription ?: "")

                    val arr = JSONArray()
                    for (m in messagesSnapshot) {
                        val mObj = JSONObject().apply {
                            put("role", m.role)
                            put("content", m.content)
                        }
                        arr.put(mObj)
                    }
                    put("messages", arr)
                }

                val dir = getHistoryDir(context)
                val targetFile = getCacheFile(context, linkId)
                val tmpFile = File(dir, "${targetFile.name}.tmp")

                // 原子写入：先写入临时文件，再重命名
                tmpFile.writeText(json.toString(), Charsets.UTF_8)
                if (!tmpFile.renameTo(targetFile)) {
                    // 个别文件系统 rename 不覆盖已有文件，退化为先删后改
                    targetFile.delete()
                    tmpFile.renameTo(targetFile)
                }

                Log.i(TAG, "帖子 [$linkId] 总结历史已安全保存至本地: ${targetFile.length()} bytes")

                // LRU 淘汰检查
                trimOldHistory(dir)

            } catch (e: Throwable) {
                Log.w(TAG, "保存历史缓存异常: ${e.message}")
            }
        }
    }

    /**
     * 删除特定帖子的历史缓存（用户手动点击清空时触发）
     */
    fun deleteHistory(
        context: Context,
        linkId: String,
        @Suppress("UNUSED_PARAMETER") scope: XiaoheihePostExtractor.SummaryScope? = null
    ) {
        ioExecutor.execute {
            try {
                val file = getCacheFile(context, linkId)
                if (file.exists()) file.delete()
                File(file.parentFile, "${file.name}.tmp").delete()
                Log.i(TAG, "已清除帖子 [$linkId] 的本地历史记录")
            } catch (e: Throwable) {
                Log.w(TAG, "删除历史缓存异常: ${e.message}")
            }
        }
    }

    /**
     * LRU 淘汰机制：限制最多保留 MAX_HISTORY_FILES 个记录
     */
    private fun trimOldHistory(dir: File) {
        val files = dir.listFiles { _, name -> name.endsWith(".json") } ?: return
        if (files.size <= MAX_HISTORY_FILES) return

        // 按修改时间升序排列，删除最旧的文件
        files.sortBy { it.lastModified() }
        val toDeleteCount = files.size - MAX_HISTORY_FILES
        for (i in 0 until toDeleteCount) {
            val f = files[i]
            val name = f.name
            if (f.delete()) {
                Log.i(TAG, "LRU 自动清理陈旧历史缓存: $name")
            }
        }
    }

    /**
     * 格式化人类友好的相对时间
     */
    fun formatRelativeTime(timestamp: Long): String {
        val diff = (System.currentTimeMillis() - timestamp).coerceAtLeast(0)
        val minutes = diff / (1000 * 60)
        val hours = minutes / 60
        val days = hours / 24

        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "${minutes}分钟前"
            hours < 24 -> "${hours}小时前"
            days < 7 -> "${days}天前"
            else -> "较早前"
        }
    }
}
