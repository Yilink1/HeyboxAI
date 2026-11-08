package io.github.yilink1.heyboxai.hook

import android.app.Activity
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object XiaoheihePostExtractor {

    private const val TAG = "HeyboxAI_Extractor"

    // 单条评论 / 全部楼层的字符预算，防止长帖撑爆模型上下文（小上下文模型会直接 400）
    private const val MAX_COMMENT_CHARS = 500
    private const val MAX_FLOOR_CHARS = 40000

    private fun clip(text: String): String =
        if (text.length > MAX_COMMENT_CHARS) text.substring(0, MAX_COMMENT_CHARS) + "…(已截断)" else text

    enum class SummaryScope(val label: String, val floorLimit: Int) {
        MAIN_ONLY("仅看主楼", 0),
        TOP_5("前 5 楼", 5),
        TOP_10("前 10 楼", 10),
        TOP_20("前 20 楼", 20),
        TOP_50("前 50 楼", 50),
        TOP_100("前 100 楼", 100),
        ALL_LOADED("全帖讨论", -1)
    }

    data class CompletenessReport(
        val isMainCommentsFull: Boolean,
        val mainPagesFetched: Int,
        val mainFloorsCount: Int,
        val mainTerminationReason: MainCommentPaginator.TerminationReason,
        val isSubCommentsFull: Boolean,
        val subFloorsEnriched: Int,
        val subFloorsTargeted: Int,
        val subRepliesAdded: Int,
        val isOverallComplete: Boolean,
        val statusBadgeText: String,
        val summaryDescription: String
    )

    data class ProcessedSubReply(
        val subIndex: Int,          // 1, 2, 3... (该主楼下的第几条回复)
        val commentId: String,
        val author: String,
        val text: String,
        val isOp: Boolean = false,
        val replyTo: String = ""    // 被回复人的昵称（若有），如 "李四" -> "张三 回复 @李四: ..."
    )

    data class ProcessedFloor(
        val originalFloorNo: Int,   // 绝对原始楼层号（1, 2, 3... 在帖子里的真实物理位置，永不重排！）
        val commentId: String,
        val author: String,
        val text: String,
        val likeCount: Int = 0,
        val isOp: Boolean = false,
        val hasMedia: Boolean = false,
        val subReplies: List<ProcessedSubReply> = emptyList()
    ) {
        fun toPromptString(): String {
            val sb = StringBuilder()
            val likeStr = if (likeCount > 0) " (赞 $likeCount)" else ""
            val opStr = if (isOp) " [楼主]" else ""
            val mediaStr = if (hasMedia) " [含图片]" else ""
            sb.append("#$originalFloorNo @$author$opStr$likeStr$mediaStr:\n${clip(text)}\n\n")
            for (sub in subReplies) {
                val subOpStr = if (sub.isOp) " [楼主]" else ""
                val replyToStr = if (sub.replyTo.isNotBlank()) " 回复 @${sub.replyTo}" else ""
                sb.append("#$originalFloorNo-${sub.subIndex} @${sub.author}$subOpStr$replyToStr:\n${clip(sub.text)}\n\n")
            }
            return sb.toString()
        }
    }

    data class ExtractedData(
        val linkId: String = "",
        val title: String,
        val mainPostBody: String,
        val formattedFloors: List<String>,
        val totalMainCommentCount: Int,
        val totalSubCommentCount: Int,
        val postTotalComments: Int = 0,
        val processedFloors: List<ProcessedFloor> = emptyList(),
        val paginationResult: MainCommentPaginator.PaginationResult? = null,
        val enrichmentResult: SubCommentEnricher.EnrichmentResult? = null,
        val completenessReport: CompletenessReport? = null,
        val extractedScope: SummaryScope = SummaryScope.ALL_LOADED,
        val baseComments: List<Any> = emptyList(),
        val linkPostModel: Any? = null,
        val pageUrl: String = "",
        val filteredSpamCount: Int = 0
    ) {
        val hasCompleteBody: Boolean
            get() = mainPostBody.contains("【完整正文攻略】") && mainPostBody.length > 50

        fun buildPrompt(scope: SummaryScope): String {
            val sb = StringBuilder()
            if (mainPostBody.isNotBlank()) {
                sb.append(mainPostBody).append("\n\n")
            }
            val bodyLen = sb.length

            when (scope) {
                SummaryScope.MAIN_ONLY -> {}
                SummaryScope.TOP_5, SummaryScope.TOP_10, SummaryScope.TOP_20,
                SummaryScope.TOP_50, SummaryScope.TOP_100 -> {
                    val count = formattedFloors.size.coerceAtMost(scope.floorLimit)
                    for (i in 0 until count) {
                        if (sb.length - bodyLen > MAX_FLOOR_CHARS) break
                        sb.append(formattedFloors[i])
                    }
                }
                SummaryScope.ALL_LOADED -> {
                    for (floorStr in formattedFloors) {
                        if (sb.length - bodyLen > MAX_FLOOR_CHARS) break
                        sb.append(floorStr)
                    }
                }
            }
            return sb.toString().trim()
        }

        val promptText: String get() = buildPrompt(SummaryScope.ALL_LOADED)
        val fullContent: String get() = promptText
        val mainCommentCount: Int get() = totalMainCommentCount
        val subCommentCount: Int get() = totalSubCommentCount
    }

    data class WebViewContent(
        val url: String = "",
        val title: String = "",
        val text: String = ""
    )

    fun formatPostMainBody(title: String, body: String, pageUrl: String = ""): String {
        val sb = StringBuilder()
        val cleanTitle = title.trim()
        val cleanUrl = pageUrl.trim()
        val cleanBody = body.trim()

        if (cleanTitle.isNotBlank()) {
            sb.append("【帖子标题】: ").append(cleanTitle).append("\n\n")
        }
        if (cleanUrl.isNotBlank()) {
            sb.append("【文章网页来源】: ").append(cleanUrl).append("\n\n")
        }
        if (cleanBody.isNotBlank()) {
            val truncatedBody = if (cleanBody.length > 18000) {
                cleanBody.substring(0, 18000) + "\n\n(网页正文过长，后续内容已截断)"
            } else {
                cleanBody
            }
            sb.append("【完整正文攻略】:\n").append(truncatedBody)
        } else if (cleanUrl.isNotBlank()) {
            sb.append("【说明】: 本帖为外部网页外链转帖，未能抓取到网页正文，请主要参考标题与评论区讨论。")
        }
        return sb.toString().trim()
    }

    /**
     * 在指定 View 树中递归寻找 WebView 控件
     */
    fun findWebView(root: View?): WebView? {
        if (root == null) return null
        val list = mutableListOf<WebView>()
        fun scan(v: View) {
            if (v is WebView) {
                list.add(v)
                return
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    scan(v.getChildAt(i))
                }
            }
        }
        scan(root)
        return list.firstOrNull { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }
            ?: list.firstOrNull { it.visibility == View.VISIBLE }
            ?: list.firstOrNull()
    }

    /**
     * 从当前界面的 WebView 中提取已渲染的网页文本（安全协程调用，带超时保护）
     */
    suspend fun extractWebViewContent(activity: Activity): WebViewContent? = withContext(Dispatchers.Main) {
        if (activity.isFinishing || (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed)) {
            return@withContext null
        }
        try {
            val decorView = activity.window?.decorView ?: return@withContext null
            val webView = findWebView(decorView) ?: return@withContext null

            val curUrl = webView.url ?: ""
            val curTitle = webView.title ?: ""

            val extractedText = withTimeoutOrNull(2500L) {
                suspendCancellableCoroutine<String> { cont ->
                    val js = """
                        (function() {
                            try {
                                var body = document.body;
                                if (!body) return "";
                                var clone = body.cloneNode(true);
                                var toRemove = ['script', 'style', 'noscript', 'iframe', 'header', 'footer', 'nav', 'svg', 'button'];
                                for (var i = 0; i < toRemove.length; i++) {
                                    var els = clone.getElementsByTagName(toRemove[i]);
                                    for (var j = els.length - 1; j >= 0; j--) {
                                        if (els[j] && els[j].parentNode) {
                                            els[j].parentNode.removeChild(els[j]);
                                        }
                                    }
                                }
                                var t = clone.innerText || clone.textContent || "";
                                return t.replace(/\n{3,}/g, '\n\n').trim();
                            } catch(e) {
                                return (document.body && (document.body.innerText || document.body.textContent) || "").trim();
                            }
                        })();
                    """.trimIndent()

                    webView.evaluateJavascript(js) { raw ->
                        if (cont.isActive) {
                            val parsed = parseJsString(raw)
                            cont.resume(parsed)
                        }
                    }
                }
            } ?: ""

            Log.i(TAG, "extractWebViewContent完成: url=${curUrl.substringBefore('?')}, title=$curTitle, textLen=${extractedText.length}")
            WebViewContent(
                url = curUrl,
                title = curTitle,
                text = extractedText
            )
        } catch (t: Throwable) {
            Log.w(TAG, "从 WebView 提取正文异常: ${t.message}")
            null
        }
    }

    fun parseJsString(raw: String?): String {
        if (raw == null || raw == "null" || raw.isBlank()) return ""
        val trimmed = raw.trim()
        if (trimmed == "\"\"" || trimmed == "''") return ""
        return try {
            val tokener = org.json.JSONTokener(trimmed)
            val obj = tokener.nextValue()
            if (obj is String) {
                obj.trim()
            } else {
                cleanEscapedString(trimmed)
            }
        } catch (t: Throwable) {
            cleanEscapedString(trimmed)
        }
    }

    private fun cleanEscapedString(s: String): String {
        var str = s.removeSurrounding("\"").removeSurrounding("'")
        str = str.replace("\\n", "\n")
            .replace("\\r", "")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
        return str.trim()
    }

    /**
     * 极速内存提取：优先从网络层旁路缓存提取，若未命中则从内存反射兜底，绝不遍历 View 树以避免崩溃
     */
    fun extractBasePost(activity: Activity): ExtractedData {
        // 1. 尝试解析 linkId
        var extractedLinkId = XiaoheiheFingerprintHelper.extractLinkId(activity, null, null)
        if (extractedLinkId.isBlank()) {
            extractedLinkId = HeyboxDataRepository.lastActiveLinkId
        }

        // 探测界面中是否有 WebView（外链新闻帖专用）
        val webView = findWebView(activity.window?.decorView)
        val webUrl = webView?.url?.takeIf { it.isNotBlank() } ?: ""
        val webTitle = webView?.title?.takeIf { it.isNotBlank() } ?: ""

        // 2. 优先从跨版本网络层数据仓库获取（100% 稳定纯 JSON 数据）
        if (extractedLinkId.isNotBlank()) {
            val netPost = HeyboxDataRepository.getPost(extractedLinkId)
            val netComments = HeyboxDataRepository.getComments(extractedLinkId)

            if (netPost != null || netComments.isNotEmpty()) {
                var title = netPost?.title ?: ""
                var body = netPost?.content ?: ""
                var pageUrl = netPost?.pageUrl?.takeIf { it.isNotBlank() } ?: webUrl
                if (title.isBlank()) title = webTitle

                val processedFloors = mutableListOf<ProcessedFloor>()
                val formattedFloors = mutableListOf<String>()
                var totalSubCount = 0
                var netSpamCount = 0

                for (c in netComments) {
                    val subReplies = c.subReplies.map { s ->
                        ProcessedSubReply(
                            subIndex = s.subIndex,
                            commentId = s.replyId,
                            author = s.author,
                            text = s.content,
                            isOp = s.isOp,
                            replyTo = s.replyTo
                        )
                    }
                    totalSubCount += subReplies.size

                    val pf = ProcessedFloor(
                        originalFloorNo = c.floorNo,
                        commentId = c.commentId,
                        author = c.author,
                        text = c.content,
                        likeCount = c.likeCount,
                        isOp = c.isOp,
                        hasMedia = c.hasMedia,
                        subReplies = subReplies
                    )
                    if (isSpamFloor(pf)) {
                        netSpamCount++
                    } else {
                        processedFloors.add(pf)
                        formattedFloors.add(pf.toPromptString())
                    }
                }

                Log.i(TAG, "成功从网络层缓存中提取帖子数据: linkId=$extractedLinkId, 有效楼层数=${processedFloors.size}, 已折叠纯插眼数=$netSpamCount")

                // 即使命中网络层缓存，也同时提取 ViewModel/PostModel 引用以供后续静默深度分页使用
                var hostModel: Any? = null
                val modelComments = mutableListOf<Any>()
                try {
                    val vm = XiaoheiheFingerprintHelper.findPostViewModel(activity)
                    hostModel = XiaoheiheFingerprintHelper.findLinkPostModel(vm)
                    if (hostModel != null) {
                        extractPostCommentsFromModel(hostModel, modelComments)
                        // 若网络层缓存中的正文或标题缺失，立即从内存模型深度兜底提取！
                        if (body.isBlank() || title.isBlank() || pageUrl.isBlank()) {
                            val memSb = StringBuilder()
                            val memTitle = extractPostContent(hostModel, memSb)
                            if (title.isBlank()) title = memTitle
                            val memBody = memSb.toString().trim()
                            if (body.isBlank() && memBody.isNotBlank()) {
                                body = memBody
                            }
                            if (pageUrl.isBlank()) {
                                val modelUrl = findStringProperty(hostModel, listOf("pageUrl", "url", "link", "webUrl", "sourceUrl"))
                                if (!modelUrl.isNullOrBlank()) pageUrl = modelUrl
                            }
                        }
                    }
                } catch (ignored: Throwable) {}

                // 统一规范包装主楼正文（避免裸奔与空标题）
                val formattedMainPostBody = if (body.startsWith("【帖子标题】") || body.startsWith("【完整正文攻略】")) {
                    body
                } else {
                    formatPostMainBody(title, body, pageUrl)
                }

                Log.i(TAG, "extractBasePost(网络层): linkId=$extractedLinkId, title=$title, bodyLen=${formattedMainPostBody.length}, floors=${formattedFloors.size}, pageUrl=$pageUrl")

                return ExtractedData(
                    linkId = extractedLinkId,
                    title = title,
                    mainPostBody = formattedMainPostBody,
                    formattedFloors = formattedFloors,
                    totalMainCommentCount = processedFloors.size,
                    totalSubCommentCount = totalSubCount,
                    postTotalComments = netPost?.totalComments ?: 0,
                    processedFloors = processedFloors,
                    paginationResult = null,
                    enrichmentResult = null,
                    completenessReport = null,
                    extractedScope = SummaryScope.ALL_LOADED,
                    baseComments = modelComments,
                    linkPostModel = hostModel,
                    pageUrl = pageUrl,
                    filteredSpamCount = netSpamCount
                )
            }
        }

        // 3. 次级兜底方案：仅安全反射 ViewModel / Model，严禁在主线程遍历 View 树
        val mainPostSb = StringBuilder()
        var extractedTitle = ""
        var linkPostModel: Any? = null
        val modelComments = mutableListOf<Any>()
        var fallbackPageUrl = webUrl.substringBefore("?")

        try {
            val viewModel = XiaoheiheFingerprintHelper.findPostViewModel(activity)
            if (viewModel != null) {
                linkPostModel = XiaoheiheFingerprintHelper.findLinkPostModel(viewModel)
                if (linkPostModel != null) {
                    extractedTitle = extractPostContent(linkPostModel, mainPostSb)
                    extractPostCommentsFromModel(linkPostModel, modelComments)
                    if (extractedLinkId.isBlank()) {
                        extractedLinkId = XiaoheiheFingerprintHelper.extractLinkId(activity, viewModel, linkPostModel)
                    }
                    if (fallbackPageUrl.isBlank()) {
                        val mUrl = findStringProperty(linkPostModel, listOf("pageUrl", "url", "link", "webUrl", "sourceUrl"))
                        if (!mUrl.isNullOrBlank()) fallbackPageUrl = mUrl.substringBefore("?")
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error extracting via XiaoheiheFingerprintHelper", e)
        }

        if (extractedTitle.isBlank()) extractedTitle = webTitle

        if (extractedLinkId.isBlank()) {
            extractedLinkId = XiaoheiheFingerprintHelper.extractLinkId(activity, null, linkPostModel)
        }

        if (extractedLinkId.isBlank() && extractedTitle.isNotBlank()) {
            extractedLinkId = "post_${Math.abs(extractedTitle.hashCode())}"
        }

        val res = processRawFloors(
            rawComments = modelComments,
            enrichedMap = emptyMap(),
            filterSpam = true
        )

        val totalComments = extractTotalCommentCountFromModel(linkPostModel)

        val formattedMainPostBody = if (mainPostSb.isNotBlank()) {
            mainPostSb.toString()
        } else {
            formatPostMainBody(extractedTitle, "", fallbackPageUrl)
        }

        return ExtractedData(
            linkId = extractedLinkId,
            title = extractedTitle,
            mainPostBody = formattedMainPostBody,
            formattedFloors = res.formatted,
            totalMainCommentCount = res.floors.size,
            totalSubCommentCount = res.totalSub,
            postTotalComments = totalComments,
            processedFloors = res.floors,
            paginationResult = null,
            enrichmentResult = null,
            completenessReport = null,
            extractedScope = SummaryScope.ALL_LOADED,
            baseComments = modelComments,
            linkPostModel = linkPostModel,
            pageUrl = fallbackPageUrl,
            filteredSpamCount = res.spamCount
        )
    }

    /**
     * 异步深度增强拉取：在后台线程受控补充分页与楼中楼数据
     *
     * @param targetMaxFloors 用户配置的目标最大楼层数（由设置面板决定）
     */
    fun enrichPostData(
        activity: Activity,
        baseData: ExtractedData,
        scope: SummaryScope = SummaryScope.ALL_LOADED,
        targetMaxFloors: Int = 100
    ): ExtractedData {
        val baseComments = baseData.baseComments
        val linkPostModel = baseData.linkPostModel ?: run {
            try {
                val viewModel = XiaoheiheFingerprintHelper.findPostViewModel(activity)
                XiaoheiheFingerprintHelper.findLinkPostModel(viewModel)
            } catch (e: Throwable) {
                null
            }
        }

        var finalFloors = baseComments
        var paginationResult: MainCommentPaginator.PaginationResult? = null
        var enrichedSubRepliesMap: Map<String, List<SubCommentEnricher.EnrichedReply>> = emptyMap()
        var enrichmentResult: SubCommentEnricher.EnrichmentResult? = null
        var completenessReport: CompletenessReport? = null

        val configMaxFloors = targetMaxFloors.coerceAtLeast(30)
        val initialCount = baseComments.size
        val neededFloors = (configMaxFloors - initialCount).coerceAtLeast(0)
        // 动态计算翻页数与预算：每页约 30 楼，上限 25 页（覆盖 500+ 楼长帖）
        val calculatedPages = if (neededFloors > 0) ((neededFloors + 29) / 30).coerceIn(1, 25) else 1
        // 每页 2.5s + 基础 4s，上限 60s：长帖抓全比秒回更重要
        val dynamicTimeoutMs = (calculatedPages * 2500L + 4000L).coerceIn(6000L, 60000L)

        when (scope) {
            SummaryScope.ALL_LOADED -> {
                paginationResult = MainCommentPaginator.paginateMainComments(
                    activity = activity,
                    linkPostModel = linkPostModel,
                    initialComments = baseComments,
                    targetFloors = configMaxFloors,
                    maxPages = calculatedPages,
                    totalTimeoutMs = dynamicTimeoutMs
                )
                finalFloors = paginationResult.mergedComments

                val subFloorsLimit = configMaxFloors.coerceIn(30, 200)
                val enrichPair = SubCommentEnricher.enrichFoldedSubComments(
                    activity = activity,
                    mainFloors = finalFloors,
                    maxFloors = subFloorsLimit,
                    timeoutMs = dynamicTimeoutMs.coerceAtLeast(20000L)
                )
                enrichedSubRepliesMap = enrichPair.first
                enrichmentResult = enrichPair.second

                val isMainFull = paginationResult.isCleanCompleted
                val isSubFull = enrichmentResult.isFullyCompleted
                val isOverallFull = isMainFull && isSubFull

                val badge = when {
                    isOverallFull -> "[全帖完整]"
                    paginationResult.terminationReason == MainCommentPaginator.TerminationReason.TARGET_REACHED -> "[已达设定楼层上限，后续讨论未纳入]"
                    paginationResult.terminationReason == MainCommentPaginator.TerminationReason.MAX_PAGES_REACHED -> "[部分覆盖 - 达上限]"
                    paginationResult.terminationReason == MainCommentPaginator.TerminationReason.BUDGET_TIMEOUT -> "[部分覆盖 - 分页超时]"
                    paginationResult.terminationReason == MainCommentPaginator.TerminationReason.CURSOR_BLANK -> "[部分覆盖 - 游标中断]"
                    paginationResult.terminationReason == MainCommentPaginator.TerminationReason.CURSOR_DUPLICATE -> "[部分覆盖 - 游标熔断]"
                    paginationResult.terminationReason == MainCommentPaginator.TerminationReason.NETWORK_ERROR -> "[部分覆盖 - 网络中断]"
                    !isSubFull -> "[部分覆盖 - 楼中楼超时]"
                    else -> "[部分覆盖]"
                }

                val desc = when {
                    isOverallFull -> "已完整覆盖全帖讨论与折叠楼中楼回复"
                    paginationResult.terminationReason == MainCommentPaginator.TerminationReason.TARGET_REACHED ->
                        "已按设定上限获取 ${finalFloors.size} 楼，更多楼层未纳入分析"
                    paginationResult.terminationReason == MainCommentPaginator.TerminationReason.MAX_PAGES_REACHED ->
                        "已补充至 ${finalFloors.size} 楼（单次抓取达深度上限，尚有后续未加载楼层）"
                    paginationResult.terminationReason == MainCommentPaginator.TerminationReason.BUDGET_TIMEOUT ->
                        "已补充至 ${finalFloors.size} 楼（主评论分页时间预算耗尽）"
                    !isSubFull ->
                        "主楼层已全部获取，部分折叠楼中楼因超时未完全展开"
                    else ->
                        "已获取当前可用讨论数据"
                }

                completenessReport = CompletenessReport(
                    isMainCommentsFull = isMainFull,
                    mainPagesFetched = paginationResult.pagesFetched,
                    mainFloorsCount = finalFloors.size,
                    mainTerminationReason = paginationResult.terminationReason,
                    isSubCommentsFull = isSubFull,
                    subFloorsEnriched = enrichmentResult.floorsSuccessfullyEnriched,
                    subFloorsTargeted = enrichmentResult.floorsWithFoldedReplies,
                    subRepliesAdded = enrichmentResult.totalRepliesAdded,
                    isOverallComplete = isOverallFull,
                    statusBadgeText = badge,
                    summaryDescription = desc
                )
            }

            SummaryScope.TOP_5, SummaryScope.TOP_10, SummaryScope.TOP_20,
            SummaryScope.TOP_50, SummaryScope.TOP_100 -> {
                val needed = (scope.floorLimit - baseComments.size).coerceAtLeast(0)
                val pages = if (needed > 0) ((needed + 29) / 30).coerceIn(1, 10) else 0
                val timeout = (pages * 2500L + 3000L).coerceIn(4000L, 20000L)

                val paginated = if (pages > 0 && linkPostModel != null) {
                    val pagResult = MainCommentPaginator.paginateMainComments(
                        activity = activity,
                        linkPostModel = linkPostModel,
                        initialComments = baseComments,
                        targetFloors = scope.floorLimit,
                        maxPages = pages,
                        totalTimeoutMs = timeout
                    )
                    paginationResult = pagResult
                    pagResult.mergedComments
                } else {
                    baseComments
                }

                finalFloors = paginated.take(scope.floorLimit)

                val enrichTimeout = (finalFloors.size * 600L).coerceIn(8000L, 30000L)
                val enrichPair = SubCommentEnricher.enrichFoldedSubComments(
                    activity = activity,
                    mainFloors = finalFloors,
                    maxFloors = finalFloors.size,
                    timeoutMs = enrichTimeout
                )
                enrichedSubRepliesMap = enrichPair.first
                enrichmentResult = enrichPair.second

                completenessReport = CompletenessReport(
                    isMainCommentsFull = (finalFloors.size >= scope.floorLimit) || enrichmentResult.isFullyCompleted,
                    mainPagesFetched = pages,
                    mainFloorsCount = finalFloors.size,
                    mainTerminationReason = MainCommentPaginator.TerminationReason.INITIAL_ALL_LOADED,
                    isSubCommentsFull = enrichmentResult.isFullyCompleted,
                    subFloorsEnriched = enrichmentResult.floorsSuccessfullyEnriched,
                    subFloorsTargeted = enrichmentResult.floorsWithFoldedReplies,
                    subRepliesAdded = enrichmentResult.totalRepliesAdded,
                    isOverallComplete = enrichmentResult.isFullyCompleted,
                    statusBadgeText = "[${scope.label}]",
                    summaryDescription = "已获取${scope.label}（共 ${finalFloors.size} 楼）及折叠楼中楼回复"
                )
            }

            SummaryScope.MAIN_ONLY -> {
                finalFloors = emptyList()
                completenessReport = CompletenessReport(
                    isMainCommentsFull = false,
                    mainPagesFetched = 0,
                    mainFloorsCount = 0,
                    mainTerminationReason = MainCommentPaginator.TerminationReason.INITIAL_ALL_LOADED,
                    isSubCommentsFull = true,
                    subFloorsEnriched = 0,
                    subFloorsTargeted = 0,
                    subRepliesAdded = 0,
                    isOverallComplete = false,
                    statusBadgeText = "[仅看主楼]",
                    summaryDescription = "仅提取主楼图文，不抓取评论"
                )
            }
        }

        val extraSpam = enrichmentResult?.droppedPureCyCount ?: 0
        val res = if (finalFloors.isNotEmpty()) {
            processRawFloors(
                rawComments = finalFloors,
                enrichedMap = enrichedSubRepliesMap,
                filterSpam = true,
                extraSpamCount = extraSpam
            )
        } else {
            ProcessedFloorsResult(emptyList(), emptyList(), 0, 0)
        }

        return baseData.copy(
            formattedFloors = res.formatted,
            totalMainCommentCount = res.floors.size,
            totalSubCommentCount = res.totalSub,
            processedFloors = res.floors,
            paginationResult = paginationResult,
            enrichmentResult = enrichmentResult,
            completenessReport = completenessReport,
            extractedScope = scope,
            filteredSpamCount = res.spamCount
        )
    }

    /**
     * 深度提取小黑盒帖子：支持按 scope 受控拉取
     */
    fun extractFullPost(
        activity: Activity,
        scope: SummaryScope = SummaryScope.ALL_LOADED
    ): ExtractedData {
        val base = extractBasePost(activity)
        return enrichPostData(activity, base, scope)
    }

    private fun extractPostContent(model: Any, sb: StringBuilder): String {
        var postTitle = ""
        try {
            val sectionMethod = model.javaClass.methods.firstOrNull { it.name == "getPostContentSection" }
            val postSection = sectionMethod?.invoke(model) ?: return ""

            val contentMethod = postSection.javaClass.methods.firstOrNull { it.name == "getContent" }
            val contentObj = contentMethod?.invoke(postSection) ?: return ""

            // 提取标题
            val titleMethod = contentObj.javaClass.methods.firstOrNull { it.name == "getTitle" }
            postTitle = titleMethod?.invoke(contentObj) as? String ?: ""
            if (postTitle.isBlank()) {
                postTitle = findStringProperty(contentObj, listOf("title", "postTitle")) ?: ""
            }
            if (postTitle.isNotBlank()) {
                sb.append("【帖子标题】: ").append(postTitle).append("\n\n")
            }

            // 提取完整正文段落（兼容普通帖、图文帖与 WebNews 资讯长文）
            var paragraphs: List<*>? = null
            for (mName in listOf("getContentModelList", "getImageTextContentModels")) {
                val listMethod = contentObj.javaClass.methods.firstOrNull { it.name == mName && it.parameterCount == 0 }
                val list = listMethod?.invoke(contentObj) as? List<*>
                if (!list.isNullOrEmpty()) {
                    paragraphs = list
                    break
                }
            }

            val bodySb = StringBuilder()
            if (!paragraphs.isNullOrEmpty()) {
                for (p in paragraphs) {
                    if (p != null) {
                        val text = extractTextFromParagraph(p)
                        if (!text.isNullOrBlank()) {
                            bodySb.append(text).append("\n\n")
                        }
                    }
                }
            }

            // 兜底直接扫描 contentObj 或 postSection 文本
            if (bodySb.isBlank()) {
                for (target in listOf(contentObj, postSection)) {
                    for (mName in listOf("getHtml", "getContent", "getRawContent", "getArticle", "getBody", "getDesc", "getText")) {
                        try {
                            val m = target.javaClass.methods.firstOrNull { it.name == mName && it.parameterCount == 0 }
                            val res = m?.invoke(target)
                            if (res is String && res.isNotBlank()) {
                                val cleaned = HeyboxDataRepository.cleanHtml(res)
                                if (cleaned.isNotBlank()) {
                                    bodySb.append(cleaned).append("\n\n")
                                    break
                                }
                            }
                        } catch (ignored: Throwable) {}
                    }
                    if (bodySb.isNotBlank()) break
                }
            }

            // 若外部网页帖正文为空，补充抓取 pageUrl 链接信息
            if (bodySb.isBlank()) {
                val pageUrl = findStringProperty(contentObj, listOf("pageUrl", "url", "link", "webUrl"))
                    ?: findStringProperty(model, listOf("pageUrl", "url", "link", "webUrl", "sourceUrl"))
                if (!pageUrl.isNullOrBlank()) {
                    sb.append("【文章网页来源】: ").append(pageUrl).append("\n\n")
                    sb.append("【说明】: 本帖为外部网页外链转帖，未能抓取到网页正文，请主要参考标题与评论区讨论。\n\n")
                }
            } else {
                val finalBody = bodySb.toString().trim()
                Log.i(TAG, "extractPostContent完成: title=$postTitle, bodyLen=${finalBody.length}, paragraphs=${paragraphs?.size ?: 0}")
                if (finalBody.isNotBlank()) {
                    sb.append("【完整正文攻略】:\n").append(finalBody).append("\n\n")
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error in extractPostContent", e)
        }
        return postTitle
    }

    private fun extractTextFromParagraph(p: Any): String? {
        val clazz = p.javaClass
        // 1. 常见 Getter 方法测试 (重点兼容 HtmlTextContent 的 getHtml 与 PureTextContent 的 getText)
        for (mName in listOf("getText", "getHtml", "getContent", "getRawText", "getDesc", "getDescription", "getTitle")) {
            try {
                val m = clazz.methods.firstOrNull { it.name == mName && it.parameterCount == 0 }
                val res = m?.invoke(p)
                if (res is String && res.isNotBlank()) {
                    val cleaned = HeyboxDataRepository.cleanHtml(res)
                    if (cleaned.isNotBlank()) return cleaned
                }
            } catch (ignored: Throwable) {}
        }
        // 2. 字段反射递归扫描
        var cur: Class<*>? = clazz
        while (cur != null && cur != Any::class.java) {
            for (fName in listOf("text", "html", "content", "desc", "description")) {
                try {
                    val f = cur.declaredFields.firstOrNull { it.name == fName }
                    if (f != null) {
                        f.isAccessible = true
                        val res = f.get(p)
                        if (res is String && res.isNotBlank()) {
                            val cleaned = HeyboxDataRepository.cleanHtml(res)
                            if (cleaned.isNotBlank()) return cleaned
                        }
                    }
                } catch (ignored: Throwable) {}
            }
            cur = cur.superclass
        }
        return null
    }

    private fun findStringProperty(target: Any, names: List<String>): String? {
        val clazz = target.javaClass
        for (name in names) {
            val getterName = "get" + name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            val m = clazz.methods.firstOrNull { it.name.equals(getterName, ignoreCase = true) && it.parameterCount == 0 }
            if (m != null) {
                try {
                    val res = m.invoke(target) as? String
                    if (!res.isNullOrBlank()) return res
                } catch (ignored: Throwable) {}
            }
            var cur: Class<*>? = clazz
            while (cur != null && cur != Any::class.java) {
                val f = cur.declaredFields.firstOrNull { it.name.equals(name, ignoreCase = true) }
                if (f != null) {
                    try {
                        f.isAccessible = true
                        val res = f.get(target) as? String
                        if (!res.isNullOrBlank()) return res
                    } catch (ignored: Throwable) {}
                }
                cur = cur.superclass
            }
        }
        return null
    }

    private fun extractTotalCommentCountFromModel(model: Any?): Int {
        if (model == null) return 0
        try {
            val sectionMethod = model.javaClass.methods.firstOrNull { it.name == "getCommentSection" }
            val commentSection = sectionMethod?.invoke(model)
            val targets = listOfNotNull(commentSection, model)
            for (t in targets) {
                for (m in t.javaClass.methods) {
                    val name = m.name.lowercase()
                    if (m.parameterCount == 0 && (name.contains("commentcount") || name.contains("commentnum") || name.contains("replynum") || name.contains("totalcomment"))) {
                        val v = m.invoke(t)
                        if (v is Number && v.toInt() > 0) return v.toInt()
                    }
                }
            }
        } catch (ignored: Throwable) {}
        return 0
    }

    private fun extractPostCommentsFromModel(model: Any, outList: MutableList<Any>) {
        try {
            val sectionMethod = model.javaClass.methods.firstOrNull { it.name == "getCommentSection" }
            val commentSection = sectionMethod?.invoke(model) ?: return

            val getCommentsMethod = commentSection.javaClass.methods.firstOrNull { it.name == "getPostComments" }
            val comments = getCommentsMethod?.invoke(commentSection) as? List<*>

            if (!comments.isNullOrEmpty()) {
                for (c in comments) {
                    if (c != null) outList.add(c)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error in extractPostCommentsFromModel", e)
        }
    }

    private fun mergeComments(list1: List<Any>, list2: List<Any>): List<Any> {
        val map = LinkedHashMap<String, Any>()
        for (item in list1) {
            val id = getCommentId(item)
            if (id.isNotBlank()) {
                map[id] = item
            }
        }
        for (item in list2) {
            val id = getCommentId(item)
            if (id.isNotBlank()) {
                map[id] = item // Adapter 里的数据通常更丰富（包含更多回复），故覆盖更新
            }
        }
        return map.values.toList()
    }

    private fun getCommentId(obj: Any?): String {
        if (obj == null) return ""
        return try {
            val directMethod = obj.javaClass.methods.firstOrNull { it.name == "getCommentid" }
            if (directMethod != null) {
                val id = directMethod.invoke(obj)?.toString()
                if (!id.isNullOrBlank()) return id
            }

            val getCommentMethod = obj.javaClass.methods.firstOrNull { it.name == "getComment" }
            val commentList = getCommentMethod?.invoke(obj) as? List<*>
            if (!commentList.isNullOrEmpty()) {
                val first = commentList[0]
                val idMethod = first?.javaClass?.methods?.firstOrNull { it.name == "getCommentid" }
                val id = idMethod?.invoke(first)?.toString()
                if (!id.isNullOrBlank()) return id
            }

            ""
        } catch (e: Exception) {
            ""
        }
    }

    private val SPAM_WORDS = setOf(
        "cy", "插眼", "mark", "mk", "+1", "1", "滴滴", "打卡", "占坑", "分母", "抽奖",
        "分母来了", "分母+1", "分母加一", "来了", "纯表情", "前排", "第一", "坐等", "蹲", "蹲一个", "蹲蹲",
        "插个眼", "插", "眼睛", "mark一下", "先mark", "先插眼", "dd"
    )

    /**
     * 水楼安全过滤判定：
     * 白名单保护原则：
     * 1. 产生衍生讨论（有楼中楼子回复）绝对保留
     * 2. 有点赞（likeCount > 0，被其他玩家认同）绝对保留
     * 3. 包含媒体附件（图片/视频）绝对保留
     * 4. 楼主本人发言绝对保留
     * 5. 包含 @用户 或 外部超链接 绝对保留
     * 6. 命中纯插眼词表 或 纯标点符号/纯无意义单一字符叠字（如 "..."、"？？"、"啊啊" 长度<=3）才做精简折叠
     */
    fun isSpamFloor(floor: ProcessedFloor): Boolean {
        if (floor.subReplies.isNotEmpty()) return false
        if (floor.likeCount > 0) return false
        if (floor.hasMedia) return false
        if (floor.isOp) return false

        val trimmed = floor.text.trim()
        if (trimmed.contains("@") || trimmed.contains("http://") || trimmed.contains("https://")) return false

        val cleanText = trimmed.replace(Regex("""[^\p{L}\p{N}]+"""), "").lowercase()
        if (SPAM_WORDS.contains(cleanText)) return true

        if (cleanText.isEmpty() || (cleanText.length <= 3 && cleanText.toSet().size == 1)) {
            return true
        }

        return false
    }

    /**
     * 楼中楼子回复打卡/水贴判定：
     * 1. 楼主发言（sub.isOp）绝对保留
     * 2. 含图片（sub.text.contains("[含图片]")）绝对保留
     * 3. 包含超链接（http:// 或 https://）绝对保留
     * 4. 文本长句（> 15字）绝对保留
     * 5. 提问/解答特征（包含 ？? 怎么 如何 为什么 求 请问 什么 哪 谁）绝对保留
     * 6. 命中纯插眼词表（cy、插眼、mark、+1等）折叠
     * 7. 包含 [Cy]、cy、插眼、mark 前缀/标记且短打卡（<= 6字，如 "[Cy] 反代"、"cy ai反代"）折叠
     * 8. 同一栋楼中短词复读（<= 6字且已在当前楼出现过，如连续多个人发 "反代"、"ai反代"）折叠
     */
    fun isSpamSubReply(sub: ProcessedSubReply, seenTexts: MutableSet<String>): Boolean {
        if (sub.isOp) return false
        if (sub.text.contains("[含图片]")) return false
        if (sub.text.contains("http://") || sub.text.contains("https://")) return false
        if (sub.text.length > 15) return false

        val raw = sub.text.trim()
        val questionKeywords = listOf("?", "？", "怎么", "如何", "为什么", "求", "请问", "什么", "哪个", "哪里", "谁")
        if (questionKeywords.any { raw.contains(it) }) return false

        val clean = raw.replace(Regex("""[^\p{L}\p{N}]+"""), "").lowercase()
        if (SPAM_WORDS.contains(clean)) return true

        val isCyFlag = raw.contains("[Cy]", ignoreCase = true) ||
                clean.startsWith("cy") || clean.endsWith("cy") ||
                clean.contains("插眼") || clean.contains("mark") || clean.contains("打卡")

        if (isCyFlag) {
            val stripped = clean.replace("cy", "").replace("插眼", "").replace("mark", "").replace("打卡", "")
            if (stripped.length <= 6) return true
        }

        if (clean.length in 1..6) {
            if (seenTexts.contains(clean)) {
                return true
            }
            seenTexts.add(clean)
        }

        if (clean.isEmpty() || (clean.length <= 3 && clean.toSet().size == 1)) {
            return true
        }

        return false
    }

    data class ProcessedFloorsResult(
        val floors: List<ProcessedFloor>,
        val formatted: List<String>,
        val totalSub: Int,
        val spamCount: Int
    )

    private fun processRawFloors(
        rawComments: List<Any>,
        enrichedMap: Map<String, List<SubCommentEnricher.EnrichedReply>> = emptyMap(),
        filterSpam: Boolean = true,
        extraSpamCount: Int = 0
    ): ProcessedFloorsResult {
        val parsedList = mutableListOf<ProcessedFloor>()
        for ((idx, obj) in rawComments.withIndex()) {
            val floor = parseFloorComment(obj, idx + 1, enrichedMap)
            if (floor != null) {
                parsedList.add(floor)
            }
        }

        val sortedList = parsedList.sortedBy { it.originalFloorNo }
        var spamCount = extraSpamCount
        val finalList = if (filterSpam) {
            val kept = mutableListOf<ProcessedFloor>()
            for (f in sortedList) {
                if (isSpamFloor(f)) {
                    spamCount++
                } else {
                    val keptSubReplies = mutableListOf<ProcessedSubReply>()
                    val seenTexts = mutableSetOf<String>()
                    var subIndex = 1
                    for (sub in f.subReplies) {
                        if (isSpamSubReply(sub, seenTexts)) {
                            spamCount++
                        } else {
                            keptSubReplies.add(sub.copy(subIndex = subIndex++))
                        }
                    }
                    kept.add(f.copy(subReplies = keptSubReplies))
                }
            }
            kept
        } else {
            sortedList
        }

        val formatted = finalList.map { it.toPromptString() }
        val totalSub = finalList.sumOf { it.subReplies.size }
        return ProcessedFloorsResult(finalList, formatted, totalSub, spamCount)
    }

    private fun parseFloorComment(
        commentsObj: Any,
        fallbackFloor: Int,
        enrichedMap: Map<String, List<SubCommentEnricher.EnrichedReply>>
    ): ProcessedFloor? {
        try {
            val getCommentMethod = commentsObj.javaClass.methods.firstOrNull { it.name == "getComment" }
            val commentList = getCommentMethod?.invoke(commentsObj) as? List<*> ?: return null
            if (commentList.isEmpty()) return null

            val main = commentList[0] ?: return null
            val floorCommentId = getCommentId(main)
            val user = getCommentAuthor(main)
            val text = getCommentText(main)
            val originalFloorNo = getCommentFloorNumber(main, fallbackFloor)
            val likeCount = getCommentLikeCount(main)
            val isOp = isCommentLinkOwner(main)
            val hasMedia = hasCommentMedia(main)

            val subReplies = mutableListOf<ProcessedSubReply>()

            // 优先使用补齐去重后的回复列表（包含所有展开与被折叠回复）
            val enrichedList = if (floorCommentId.isNotBlank()) enrichedMap[floorCommentId] else null
            if (enrichedList != null && enrichedList.isNotEmpty()) {
                for ((idx, reply) in enrichedList.withIndex()) {
                    subReplies.add(
                        ProcessedSubReply(
                            subIndex = idx + 1,
                            commentId = reply.commentId,
                            author = reply.author,
                            text = reply.text,
                            isOp = reply.isLinkOwner,
                            replyTo = reply.replyTo
                        )
                    )
                }
            } else {
                // 降级使用初始预览楼中楼
                var subIdx = 1
                for (i in 1 until commentList.size) {
                    val reply = commentList[i]
                    if (reply != null) {
                        val replyUser = getCommentAuthor(reply)
                        val replyText = getCommentText(reply)
                        val replyId = getCommentId(reply)
                        val replyIsOp = isCommentLinkOwner(reply)
                        val replyTo = SubCommentEnricher.getCommentReplyTo(reply)
                        if (replyText.isNotBlank()) {
                            subReplies.add(
                                ProcessedSubReply(
                                    subIndex = subIdx++,
                                    commentId = replyId,
                                    author = replyUser,
                                    text = replyText,
                                    isOp = replyIsOp,
                                    replyTo = replyTo
                                )
                            )
                        }
                    }
                }
            }

            return ProcessedFloor(
                originalFloorNo = originalFloorNo,
                commentId = floorCommentId,
                author = user,
                text = text,
                likeCount = likeCount,
                isOp = isOp,
                hasMedia = hasMedia,
                subReplies = subReplies
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Error parsing comment obj", e)
            return null
        }
    }

    private fun getCommentFloorNumber(comment: Any, fallbackIndex: Int): Int {
        try {
            val floorMethod = comment.javaClass.methods.firstOrNull { it.name == "getFloor_num" }
            val res = floorMethod?.invoke(comment)?.toString() ?: ""
            val digits = Regex("""\d+""").find(res)?.value
            if (digits != null) {
                val parsed = digits.toIntOrNull()
                if (parsed != null && parsed > 0) return parsed
            }
        } catch (ignored: Throwable) {}
        return fallbackIndex
    }

    private fun getCommentLikeCount(comment: Any?): Int {
        if (comment == null) return 0
        val candidates = listOf("getScore", "getLike_num", "getPraise", "getPraise_num", "getLike", "getLikes")
        for (name in candidates) {
            try {
                val m = comment.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                val res = m?.invoke(comment)
                if (res is Number) return res.toInt()
                if (res is String && res.isNotBlank()) {
                    val parsed = res.toIntOrNull()
                    if (parsed != null) return parsed
                }
            } catch (ignored: Throwable) {}
        }
        return 0
    }

    private fun hasCommentMedia(comment: Any?): Boolean {
        if (comment == null) return false
        val candidates = listOf("getImgs", "getImages", "getPic", "getPics", "getMedia", "getImg_list")
        for (name in candidates) {
            try {
                val m = comment.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                val res = m?.invoke(comment)
                if (res is Collection<*> && res.isNotEmpty()) return true
                if (res is Array<*> && res.isNotEmpty()) return true
            } catch (ignored: Throwable) {}
        }
        return false
    }

    private fun isCommentLinkOwner(comment: Any?): Boolean {
        if (comment == null) return false
        val candidates = listOf("is_link_owner", "isLinkOwner", "getIs_link_owner", "isOwner")
        for (name in candidates) {
            try {
                val m = comment.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                val res = m?.invoke(comment)
                if (res == true || res == "1" || res == 1) return true
            } catch (ignored: Throwable) {}
        }
        return false
    }

    private fun getCommentAuthor(comment: Any): String {
        return try {
            val userMethod = comment.javaClass.methods.firstOrNull { it.name == "getUser" }
            val userObj = userMethod?.invoke(comment)
            val nameMethod = userObj?.javaClass?.methods?.firstOrNull { it.name == "getUsername" }
            nameMethod?.invoke(userObj) as? String ?: "用户"
        } catch (e: Exception) {
            "用户"
        }
    }

    private fun getCommentText(comment: Any): String {
        return try {
            val textMethod = comment.javaClass.methods.firstOrNull { it.name == "getText" }
            textMethod?.invoke(comment) as? String ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun isRecyclerView(view: View): Boolean {
        var c: Class<*>? = view.javaClass
        while (c != null) {
            if (c.name == "androidx.recyclerview.widget.RecyclerView") return true
            c = c.superclass
        }
        return false
    }

    private fun findAdapterComments(root: View, outList: MutableList<Any>) {
        walkViews(root) { v ->
            if (!isRecyclerView(v)) return@walkViews
            try {
                val adapter = v.javaClass.methods.firstOrNull { it.name == "getAdapter" && it.parameterCount == 0 }
                    ?.invoke(v) ?: return@walkViews
                val dataListMethod = adapter.javaClass.methods.firstOrNull { it.name == "getDataList" && it.parameterCount == 0 }
                val list = dataListMethod?.invoke(adapter) as? List<*>
                if (list != null) {
                    for (item in list) {
                        if (item != null && item.javaClass.name.contains("BBSCommentsObj")) {
                            outList.add(item)
                        }
                    }
                }
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun walkViews(view: View, action: (View) -> Unit) {
        action(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                walkViews(view.getChildAt(i), action)
            }
        }
    }

    private fun extractTextsFromView(view: View, sb: StringBuilder) {
        if (view is TextView && view.visibility == View.VISIBLE) {
            val t = view.text?.toString()?.trim() ?: ""
            if (t.isNotBlank() && t.length > 2 && !t.contains("AI 总结")) {
                sb.append(t).append("\n")
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                extractTextsFromView(view.getChildAt(i), sb)
            }
        }
    }
}
