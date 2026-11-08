package io.github.yilink1.heyboxai.hook

import android.util.Log
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject

/**
 * 小黑盒帖子与评论的内存数据仓库（跨版本免疫）
 *
 * 数据完全来源于 OkHttp 底层网络响应的原始 JSON，零反射、零混淆依赖、零 UI 侵入。
 */
object HeyboxDataRepository {

    private const val TAG = "HeyboxAI_Repo"

    data class CachedPost(
        val linkId: String,
        val title: String,
        val content: String,
        val author: String,
        val totalComments: Int = 0,
        val pageUrl: String = "",
        val timestamp: Long = System.currentTimeMillis()
    )

    data class CachedComment(
        val commentId: String,
        val floorNo: Int,
        val author: String,
        val content: String,
        val likeCount: Int,
        val isOp: Boolean,
        val hasMedia: Boolean,
        // 网络线程写入、主线程遍历：用写时复制列表避免 ConcurrentModificationException
        val subReplies: MutableList<CachedSubReply> = java.util.concurrent.CopyOnWriteArrayList()
    )

    data class CachedSubReply(
        val subIndex: Int,
        val replyId: String,
        val author: String,
        val content: String,
        val isOp: Boolean,
        val replyTo: String = ""
    )

    // 帖子正文缓存：link_id -> CachedPost
    private val postCache = LruCache<String, CachedPost>(50)

    // 主评论缓存：link_id -> Map<commentId, CachedComment>
    private val commentsCache = LruCache<String, LinkedHashMap<String, CachedComment>>(50)

    // 最近活跃的 link_id
    @Volatile
    var lastActiveLinkId: String = ""

    /**
     * HTML 标签剥离清洗器，保留排版换行
     */
    fun cleanHtml(html: String): String {
        if (!html.contains("<") || !html.contains(">")) return html.trim()
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim()
            } else {
                @Suppress("DEPRECATION")
                android.text.Html.fromHtml(html).toString().trim()
            }
        } catch (t: Throwable) {
            html.replace(Regex("(?i)<br\\s*/?>"), "\n")
                .replace(Regex("(?i)</p>"), "\n")
                .replace(Regex("(?i)</div>"), "\n")
                .replace(Regex("<[^>]+>"), "")
                .replace(Regex("&nbsp;"), " ")
                .replace(Regex("&amp;"), "&")
                .replace(Regex("&lt;"), "<")
                .replace(Regex("&gt;"), ">")
                .replace(Regex("&quot;"), "\"")
                .trim()
        }
    }

    /**
     * 收到 OkHttp 响应数据时的统一分发处理
     */
    fun onNetworkResponse(url: String, jsonStr: String) {
        try {
            if (url.contains("comment/sub")) {
                parseSubComments(jsonStr)
            } else if (url.contains("link/post") ||
                       url.contains("link/tree") ||
                       url.contains("api/news") ||
                       url.contains("web_news") ||
                       url.contains("news") ||
                       url.contains("link/detail")) {
                parsePostAndComments(url, jsonStr)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "解析网络数据异常: ${t.message}")
        }
    }

    private fun extractContentFromJson(linkObj: JSONObject): String {
        val sb = StringBuilder()
        val rawContent = linkObj.opt("content")

        when (rawContent) {
            is JSONArray -> {
                appendContentArray(rawContent, sb)
            }
            is String -> {
                val str = rawContent.trim()
                if (str.startsWith("[") && str.endsWith("]")) {
                    try {
                        val arr = JSONArray(str)
                        appendContentArray(arr, sb)
                    } catch (e: Throwable) {
                        sb.append(cleanHtml(str))
                    }
                } else if (str.isNotBlank()) {
                    sb.append(cleanHtml(str))
                }
            }
            is JSONObject -> {
                val text = rawContent.optString("text")
                    .ifBlank { rawContent.optString("html") }
                    .ifBlank { rawContent.optString("content") }
                    .ifBlank { rawContent.optString("desc") }
                if (text.isNotBlank()) {
                    sb.append(cleanHtml(text))
                }
            }
        }

        // 兜底检查其他常见字段
        if (sb.isBlank()) {
            for (key in listOf("text", "feed_content", "description", "summary", "rich_text", "article")) {
                val fallbackVal = linkObj.optString(key)
                if (fallbackVal.isNotBlank()) {
                    sb.append(cleanHtml(fallbackVal))
                    break
                }
            }
        }

        return sb.toString().trim()
    }

    private fun appendContentArray(arr: JSONArray, sb: StringBuilder) {
        for (i in 0 until arr.length()) {
            val item = arr.opt(i) ?: continue
            when (item) {
                is JSONObject -> {
                    val text = item.optString("text")
                        .ifBlank { item.optString("html") }
                        .ifBlank { item.optString("content") }
                        .ifBlank { item.optString("desc") }
                        .ifBlank { item.optString("description") }
                    if (text.isNotBlank()) {
                        sb.append(cleanHtml(text)).append("\n\n")
                    }
                }
                is String -> {
                    if (item.isNotBlank()) {
                        sb.append(cleanHtml(item)).append("\n\n")
                    }
                }
            }
        }
    }

    private fun parsePostAndComments(url: String, jsonStr: String) {
        val root = JSONObject(jsonStr)
        val result = root.optJSONObject("result") ?: root.optJSONObject("data") ?: root

        // 1. 尝试提取 link_id
        var linkId = result.optString("link_id")
        if (linkId.isBlank()) {
            val linkObj = result.optJSONObject("link")
            linkId = linkObj?.optString("link_id") ?: ""
        }
        if (linkId.isBlank()) {
            // 从 URL query 中匹配 link_id
            linkId = Regex("""link_id=(\d+)""").find(url)?.groupValues?.getOrNull(1) ?: ""
        }
        if (linkId.isNotBlank()) {
            lastActiveLinkId = linkId
        }

        // 2. 提取正文内容
        val linkObj = result.optJSONObject("link") ?: result
        val title = linkObj.optString("title").ifBlank { result.optString("title") }
        val content = extractContentFromJson(linkObj).ifBlank { extractContentFromJson(result) }
        val userObj = linkObj.optJSONObject("user") ?: result.optJSONObject("user")
        val author = userObj?.optString("username") ?: ""
        val totalComments = linkObj.optInt("comments_num",
            linkObj.optInt("reply_num",
            linkObj.optInt("comment_num",
            linkObj.optInt("comment_count",
            result.optInt("comments_num",
            result.optInt("reply_num", 0))))))

        val pageUrl = linkObj.optString("web_url")
            .ifBlank { linkObj.optString("page_url") }
            .ifBlank { linkObj.optString("source_url") }
            .ifBlank { linkObj.optString("url") }
            .ifBlank { linkObj.optString("link_url") }
            .ifBlank { result.optString("web_url") }
            .ifBlank { result.optString("page_url") }
            .ifBlank { result.optString("url") }

        if (linkId.isNotBlank() && (title.isNotBlank() || content.isNotBlank() || pageUrl.isNotBlank())) {
            val cachedPost = CachedPost(
                linkId = linkId,
                title = title,
                content = content,
                author = author,
                totalComments = totalComments,
                pageUrl = pageUrl
            )
            postCache.put(linkId, cachedPost)
            Log.i(TAG, "已成功缓存帖子正文: linkId=$linkId, title=$title, totalComments=$totalComments, len=${content.length}, pageUrl=$pageUrl")
        }

        // 3. 提取主评论列表
        val commentsArray = result.optJSONArray("comments")
            ?: result.optJSONArray("comment_list")
            ?: result.optJSONArray("tree")
        if (commentsArray != null && linkId.isNotBlank()) {
            // 多个 OkHttp 线程可能同时解析同一帖子的分页响应，创建 map 必须原子
            val map = synchronized(commentsCache) {
                commentsCache.get(linkId) ?: LinkedHashMap<String, CachedComment>().also {
                    commentsCache.put(linkId, it)
                }
            }

            for (i in 0 until commentsArray.length()) {
                val cObj = commentsArray.optJSONObject(i) ?: continue
                val cId = cObj.optString("comment_id").ifBlank { cObj.optString("id") }
                if (cId.isBlank()) continue

                val floorNo = cObj.optInt("floor_num", cObj.optInt("floor", map.size + 1))
                val cText = cObj.optString("text").ifBlank { cObj.optString("content") }
                val cUser = cObj.optJSONObject("user")
                val cAuthor = cUser?.optString("username") ?: ""
                val isOp = cObj.optBoolean("is_link_owner") || cObj.optInt("is_link_owner") == 1
                val likeCount = cObj.optInt("praise_num", cObj.optInt("likes", 0))
                val hasMedia = cObj.has("media") || cObj.has("images")

                val comment = CachedComment(
                    commentId = cId,
                    floorNo = floorNo,
                    author = cAuthor,
                    content = cText,
                    likeCount = likeCount,
                    isOp = isOp,
                    hasMedia = hasMedia
                )

                // 检查内嵌的楼中楼
                val subArray = cObj.optJSONArray("sub_comments") ?: cObj.optJSONArray("reply_list")
                if (subArray != null) {
                    for (j in 0 until subArray.length()) {
                        val sObj = subArray.optJSONObject(j) ?: continue
                        val sId = sObj.optString("comment_id").ifBlank { sObj.optString("id") }
                        val sText = sObj.optString("text").ifBlank { sObj.optString("content") }
                        val sUser = sObj.optJSONObject("user")
                        val sAuthor = sUser?.optString("username") ?: ""
                        val sOp = sObj.optBoolean("is_link_owner") || sObj.optInt("is_link_owner") == 1
                        val rUser = sObj.optJSONObject("reply_user") ?: sObj.optJSONObject("reply_to_user") ?: sObj.optJSONObject("to_user")
                        val replyTo = rUser?.optString("username")?.ifBlank { rUser.optString("name") }
                            ?: sObj.optString("reply_user_name").ifBlank { sObj.optString("reply_to") }

                        comment.subReplies.add(
                            CachedSubReply(
                                subIndex = j + 1,
                                replyId = sId,
                                author = sAuthor,
                                content = sText,
                                isOp = sOp,
                                replyTo = replyTo
                            )
                        )
                    }
                }

                synchronized(map) { map[cId] = comment }
            }
            Log.i(TAG, "已成功缓存评论列表: linkId=$linkId, 当前总楼层=${synchronized(map) { map.size }}")
        }
    }

    private fun parseSubComments(jsonStr: String) {
        val root = JSONObject(jsonStr)
        val result = root.optJSONObject("result") ?: root.optJSONObject("data") ?: root
        val subArray = result.optJSONArray("sub_comments") ?: result.optJSONArray("comments") ?: return

        val rootCommentId = result.optString("comment_id").ifBlank { result.optString("root_comment_id") }
        if (rootCommentId.isBlank()) return

        // 寻找包含此 rootCommentId 的主评论
        val linkId = lastActiveLinkId
        val map = if (linkId.isNotBlank()) commentsCache.get(linkId) else null
        val targetComment = map?.let { synchronized(it) { it[rootCommentId] } }

        if (targetComment != null) {
            targetComment.subReplies.clear()
            for (j in 0 until subArray.length()) {
                val sObj = subArray.optJSONObject(j) ?: continue
                val sId = sObj.optString("comment_id").ifBlank { sObj.optString("id") }
                val sText = sObj.optString("text").ifBlank { sObj.optString("content") }
                val sUser = sObj.optJSONObject("user")
                val sAuthor = sUser?.optString("username") ?: ""
                val sOp = sObj.optBoolean("is_link_owner") || sObj.optInt("is_link_owner") == 1
                val rUser = sObj.optJSONObject("reply_user") ?: sObj.optJSONObject("reply_to_user") ?: sObj.optJSONObject("to_user")
                val replyTo = rUser?.optString("username")?.ifBlank { rUser.optString("name") }
                    ?: sObj.optString("reply_user_name").ifBlank { sObj.optString("reply_to") }

                targetComment.subReplies.add(
                    CachedSubReply(
                        subIndex = j + 1,
                        replyId = sId,
                        author = sAuthor,
                        content = sText,
                        isOp = sOp,
                        replyTo = replyTo
                    )
                )
            }
            Log.i(TAG, "已成功补齐楼中楼: rootId=$rootCommentId, 楼中楼数=${targetComment.subReplies.size}")
        }
    }

    /**
     * 获取指定 linkId 的帖子与评论缓存
     */
    fun getPost(linkId: String): CachedPost? = postCache.get(linkId)

    fun getComments(linkId: String): List<CachedComment> {
        val map = commentsCache.get(linkId) ?: return emptyList()
        return synchronized(map) { map.values.toList() }
    }
}
