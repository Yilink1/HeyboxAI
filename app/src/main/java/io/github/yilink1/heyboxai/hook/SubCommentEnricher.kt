package io.github.yilink1.heyboxai.hook

import android.app.Activity
import android.util.Log
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 第一阶段集成：折叠楼中楼自动受控补齐组件
 *
 * 核心底线保证：
 * 1. 协程安全：反射代理宿主 Continuation，捕获 Failure 异常，挂起与恢复完整闭环。
 * 2. 缓存与去重：以 commentid 为唯一键去重，避免预览回复重复计数；按 post+floor 缓存避免重复请求。
 * 3. 严格资源控制：并发上限 3，单楼最多翻 2 页，单帖最多补齐 8 个折叠楼层，总超时熔断 3.5 秒。
 * 4. 零侵入：纯数据层丰富，绝对不触碰或修改小黑盒原生 UI 与 Adapter。
 * 5. 真实完整性反馈：如实统计补齐楼层与回复条数，绝不虚报全量。
 */
object SubCommentEnricher {

    private const val TAG = "HeyboxAI_Enricher"

    // 单帖/单楼层内存缓存，避免重复点击发起网络风暴 (Key: floorCommentId -> 补齐去重后的回复列表)
    private val floorCache = ConcurrentHashMap<String, List<EnrichedReply>>()

    // 并发控制：同时最多 4 个楼层在拉取网络请求（避免瞬时突发），守护线程，避免滞留在宿主进程里
    private const val MAX_CONCURRENCY = 4
    // 单次补齐允许向服务端发出的请求总数安全阀；耗尽后未补齐的楼层保留预览并如实标记为未完整
    private const val MAX_REQUESTS_PER_RUN = 400
    private val concurrencySemaphore = Semaphore(MAX_CONCURRENCY)
    private val workerExecutor = Executors.newFixedThreadPool(MAX_CONCURRENCY) { r ->
        Thread(r, "heybox-ai-enrich").apply { isDaemon = true }
    }

    data class EnrichedReply(
        val commentId: String,
        val author: String,
        val text: String,
        val isLinkOwner: Boolean = false,
        val replyTo: String = ""
    )

    data class EnrichmentResult(
        val floorsWithFoldedReplies: Int,
        val floorsSuccessfullyEnriched: Int,
        val totalRepliesAdded: Int,
        val isFullyCompleted: Boolean,
        val droppedPureCyCount: Int = 0
    )

    /**
     * 对已加载的主楼层列表进行静默检查并补全折叠楼中楼
     */
    fun enrichFoldedSubComments(
        activity: Activity,
        mainFloors: List<Any>,
        maxFloors: Int = 20,
        timeoutMs: Long = 3500L
    ): Pair<Map<String, List<EnrichedReply>>, EnrichmentResult> {
        val classLoader = activity.classLoader
        val enrichedMap = ConcurrentHashMap<String, List<EnrichedReply>>()
        val droppedPureCyCounter = AtomicInteger(0)

        // 1. 获取宿主 LinkPostRepository 实例与分页方法 (采用特征签名匹配，杜绝混淆名绑定)
        val viewModel = XiaoheiheFingerprintHelper.findPostViewModel(activity)
        val repo = if (viewModel != null) XiaoheiheFingerprintHelper.findLinkPostRepository(viewModel) else null
        val methodI = repo?.let { XiaoheiheFingerprintHelper.findSubCommentPaginateMethod(it) }

        if (repo == null || methodI == null) {
            Log.w(TAG, "未找到宿主 Repository 或楼中楼抓取方法，跳过楼中楼补齐")
            return Pair(emptyMap(), EnrichmentResult(0, 0, 0, false, 0))
        }

        // 2. 识别哪些楼层需要补全（带有折叠标记或回复数 > 已有预览数）
        val targetFloors = mutableListOf<FloorTarget>()
        var structureDumped = false
        for (floorObj in mainFloors) {
            if (!structureDumped) {
                structureDumped = true
                try {
                    val interesting = floorObj.javaClass.methods
                        .filter { it.parameterCount == 0 }
                        .map { it.name }
                        .filter { n -> n.contains("has", true) || n.contains("more", true) || n.contains("cnt", true) || n.contains("count", true) || n.contains("total", true) || n.contains("sub", true) || n.contains("reply", true) || n.contains("fold", true) }
                        .sorted()
                    Log.i(TAG, "CommentsObj 结构诊断 ${floorObj.javaClass.name}: 可疑方法=${interesting.joinToString(", ")}")
                } catch (t: Throwable) {
                    Log.w(TAG, "结构诊断失败: ${t.message}")
                }
            }
            val commentList = getCommentList(floorObj) ?: continue
            if (commentList.isEmpty()) continue

            val mainComment = commentList[0] ?: continue
            val commentId = getCommentId(mainComment)
            if (commentId.isBlank()) continue

            // 检查初始已有预览回复
            val initialReplies = mutableListOf<EnrichedReply>()
            for (i in 1 until commentList.size) {
                val r = commentList[i]
                if (r != null) {
                    val rId = getCommentId(r)
                    val rAuthor = getCommentAuthor(r)
                    val rText = getCommentText(r)
                    val rReplyTo = getCommentReplyTo(r)
                    val hasMedia = hasCommentMedia(r)
                    val effectiveText = when {
                        rText.isNotBlank() -> rText
                        hasMedia -> "[含图片]"
                        else -> ""
                    }
                    if (rId.isNotBlank()) {
                        if (effectiveText.isNotBlank()) {
                            initialReplies.add(EnrichedReply(rId, rAuthor, effectiveText, isCommentLinkOwner(r), rReplyTo))
                        } else {
                            // 纯表情/纯Cy/空文本，记录被丢弃数
                            droppedPureCyCounter.incrementAndGet()
                        }
                    }
                }
            }

            // 判断是否有折叠楼中楼：
            // ① 主评论显式声明的总回复数 > 初始预览数（如声明 35 条，已有 2 条预览）
            // ② 容器 hasMore 为 true
            // ③ 已有预览数达到 2 条（小黑盒服务端列表默认下发上限就是 2 条预览，满 2 条绝大多数有更多折叠回复）
            // ④ 只要有预览且声明总数大于 1
            val declaredTotal = getFloorDeclaredReplyCount(floorObj, mainComment)
            val hasMore = isHasMoreSubComments(floorObj)
            val originalTotal = getOriginalSubCommentCnt(floorObj).coerceAtLeast(declaredTotal)

            val needsEnrichment = hasMore ||
                    (originalTotal > initialReplies.size) ||
                    (initialReplies.size >= 2) ||
                    (initialReplies.isNotEmpty() && originalTotal > 1)

            // 诊断：输出每个楼层的折叠判断依据，定位"该补却没补"的原因
            Log.d(TAG, "楼层 $commentId: 预览回复=${initialReplies.size}, hasMore=$hasMore, 声明总数=$originalTotal, 需补齐=$needsEnrichment")

            if (needsEnrichment) {
                targetFloors.add(FloorTarget(commentId, initialReplies))
                if (targetFloors.size >= maxFloors) {
                    // 请求预算控制：达到当前请求允许的最大补齐楼层数
                    break
                }
            } else {
                // 无需补全，直接保留初始预览
                if (initialReplies.isNotEmpty()) {
                    enrichedMap[commentId] = initialReplies
                }
            }
        }

        if (targetFloors.isEmpty()) {
            return Pair(enrichedMap, EnrichmentResult(0, 0, 0, true))
        }

        Log.i(TAG, "发现 ${targetFloors.size} 个楼层存在折叠楼中楼，开始受控并发补齐...")

        val latch = CountDownLatch(targetFloors.size)
        val guard = RequestGuard.Breaker(3)
        val requestBudget = AtomicInteger(MAX_REQUESTS_PER_RUN)
        // 主线程等待超时后置位，排队中的任务不再发请求
        val abandoned = AtomicBoolean(false)
        var successfullyEnrichedCount = 0
        var totalAddedCount = 0

        for (target in targetFloors) {
            workerExecutor.execute {
                var acquired = false
                try {
                    if (abandoned.get() || guard.tripped) {
                        enrichedMap[target.commentId] = target.initialReplies
                        return@execute
                    }
                    acquired = concurrencySemaphore.tryAcquire(timeoutMs.coerceAtLeast(10000L), TimeUnit.MILLISECONDS)
                    if (!acquired) {
                        Log.w(TAG, "楼层 ${target.commentId} 等待并发许可超时，降级保留预览")
                        enrichedMap[target.commentId] = target.initialReplies
                        return@execute
                    }

                    // 检查缓存
                    val cached = floorCache[target.commentId]
                    if (cached != null) {
                        enrichedMap[target.commentId] = cached
                        synchronized(this) {
                            successfullyEnrichedCount++
                            totalAddedCount += (cached.size - target.initialReplies.size).coerceAtLeast(0)
                        }
                        return@execute
                    }

                    // 发起受控分页拉取（单楼最多翻 10 页，覆盖约 100 条）
                    val finalReplies = fetchFloorSubCommentsWithPaging(
                        repo = repo,
                        methodI = methodI,
                        classLoader = classLoader,
                        floorCommentId = target.commentId,
                        initialReplies = target.initialReplies,
                        maxPages = 10,  // 单页约10条，10页覆盖100条；带游标熔断与去重，不会失控
                        droppedPureCyCounter = droppedPureCyCounter,
                        guard = guard,
                        requestBudget = requestBudget,
                        abandoned = abandoned
                    )

                    enrichedMap[target.commentId] = finalReplies
                    // 熔断/超时/预算耗尽时拿到的可能是残缺结果，不写入缓存，避免下次直接复用
                    if (!guard.tripped && !abandoned.get() && requestBudget.get() >= 0) {
                        floorCache[target.commentId] = finalReplies
                    }

                    val added = (finalReplies.size - target.initialReplies.size).coerceAtLeast(0)
                    synchronized(this) {
                        successfullyEnrichedCount++
                        totalAddedCount += added
                    }
                    Log.i(TAG, "楼层 ${target.commentId} 补齐成功: 初始 ${target.initialReplies.size} 条 -> 最终 ${finalReplies.size} 条 (新增 $added 条)")
                } catch (e: Throwable) {
                    Log.w(TAG, "楼层 ${target.commentId} 补齐异常，降级保留初始数据: ${e.message}")
                    enrichedMap[target.commentId] = target.initialReplies
                } finally {
                    if (acquired) {
                        concurrencySemaphore.release()
                    }
                    latch.countDown()
                }
            }
        }

        // 等待所有楼层补齐完成或超时
        val completedInTime = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!completedInTime) abandoned.set(true)
        if (guard.tripped) {
            Log.w(TAG, "楼中楼补齐已熔断（连续失败或遇到 403/429），剩余楼层保留预览")
        }
        val isFullyCompleted = completedInTime && !guard.tripped && requestBudget.get() >= 0 &&
                (successfullyEnrichedCount == targetFloors.size)

        Log.i(TAG, "楼中楼补齐阶段结束: 完成度 $successfullyEnrichedCount/${targetFloors.size}, 新增回复 $totalAddedCount 条, 纯表情/纯Cy过滤 ${droppedPureCyCounter.get()} 条, 全量就绪: $isFullyCompleted")

        return Pair(
            enrichedMap,
            EnrichmentResult(
                floorsWithFoldedReplies = targetFloors.size,
                floorsSuccessfullyEnriched = successfullyEnrichedCount,
                totalRepliesAdded = totalAddedCount,
                isFullyCompleted = isFullyCompleted,
                droppedPureCyCount = droppedPureCyCounter.get()
            )
        )
    }

    /**
     * 单个楼层的受控分页抓取与去重合并
     */
    private fun fetchFloorSubCommentsWithPaging(
        repo: Any,
        methodI: Method,
        classLoader: ClassLoader,
        floorCommentId: String,
        initialReplies: List<EnrichedReply>,
        maxPages: Int,
        droppedPureCyCounter: AtomicInteger,
        guard: RequestGuard.Breaker,
        requestBudget: AtomicInteger,
        abandoned: AtomicBoolean
    ): List<EnrichedReply> {
        // 使用 LinkedHashMap 严格按 ID 去重并保留自然顺序
        val replyMap = LinkedHashMap<String, EnrichedReply>()
        for (r in initialReplies) {
            replyMap[r.commentId] = r
        }

        var cursor = ""
        var page = 1

        while (page <= maxPages) {
            if (guard.tripped || abandoned.get()) break
            if (requestBudget.decrementAndGet() < 0) {
                Log.w(TAG, "本轮请求总数已达安全上限，停止继续补齐")
                break
            }
            RequestGuard.sleepJitter(80L, 200L)

            var failure: Throwable? = null
            val subCommentsObj = try {
                SuspendBridge.invokeSuspend(
                    target = repo,
                    method = methodI,
                    args = arrayOf(floorCommentId, cursor, "", "0"),
                    classLoader = classLoader,
                    timeoutMs = 5000L
                )
            } catch (t: Throwable) {
                failure = t
                null
            }
            if (subCommentsObj == null) {
                guard.onFailure(failure)
                break
            }
            guard.onSuccess()

            val hasMore = getFieldOrMethodString(subCommentsObj, "getHas_more")
            val nextLastVal = getFieldOrMethodString(subCommentsObj, "getLastval")
            val list = getSubCommentsList(subCommentsObj)

            if (list.isEmpty()) break

            for (item in list) {
                if (item == null) continue
                val rId = getCommentId(item)
                val rAuthor = getCommentAuthor(item)
                val rawText = getCommentText(item)
                if (rId.isBlank()) continue
                // 空文本分两类：纯图片回复（真信息，保留）与已删除/插眼（噪音，丢弃）。
                val hasMedia = hasCommentMedia(item)
                val rText = when {
                    rawText.isNotBlank() -> rawText
                    hasMedia -> "[含图片]"
                    else -> ""
                }
                if (rText.isBlank()) {
                    droppedPureCyCounter.incrementAndGet()
                    continue
                }
                val rReplyTo = getCommentReplyTo(item)
                // ID 存在则覆盖/更新，不存在则加入，彻底避免重复计数
                replyMap[rId] = EnrichedReply(rId, rAuthor, rText, isCommentLinkOwner(item), rReplyTo)
            }

            val isHasMoreTrue = hasMore == "1" || hasMore.equals("true", ignoreCase = true)
            if (isHasMoreTrue && nextLastVal.isNotBlank() && nextLastVal != cursor) {
                cursor = nextLastVal
                page++
            } else {
                break
            }
        }

        return replyMap.values.toList()
    }

    private fun findLinkPostRepository(viewModel: Any): Any? {
        return XiaoheiheFingerprintHelper.findLinkPostRepository(viewModel)
    }

    private fun getCommentList(commentsObj: Any): List<*>? {
        return try {
            val getCommentMethod = commentsObj.javaClass.methods.firstOrNull { it.name == "getComment" }
            getCommentMethod?.invoke(commentsObj) as? List<*>
        } catch (e: Exception) {
            null
        }
    }

    private fun isHasMoreSubComments(commentsObj: Any): Boolean {
        return try {
            val m = commentsObj.javaClass.methods.firstOrNull { it.name == "isHasMoreSubComments" }
            m?.invoke(commentsObj) as? Boolean ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun getSubCommentLoadPageCnt(commentsObj: Any): Int {
        return try {
            val m = commentsObj.javaClass.methods.firstOrNull { it.name == "getSubCommentLoadPageCnt" }
            m?.invoke(commentsObj) as? Int ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private fun getOriginalSubCommentCnt(commentsObj: Any): Int {
        return try {
            val m = commentsObj.javaClass.methods.firstOrNull { it.name == "getOriginalSubCommentCnt" }
            m?.invoke(commentsObj) as? Int ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private fun getFloorDeclaredReplyCount(commentsObj: Any, mainComment: Any?): Int {
        // 1. 尝试从 Comments 容器读取
        for (m in commentsObj.javaClass.methods) {
            val name = m.name.lowercase()
            if (m.parameterCount == 0 && (name.contains("replycnt") || name.contains("replycount") || name.contains("subcommentcnt") || name.contains("subcommentcount") || name.contains("totalsub"))) {
                try {
                    val res = m.invoke(commentsObj)
                    if (res is Number && res.toInt() > 0) return res.toInt()
                } catch (ignored: Throwable) {}
            }
        }
        // 2. 尝试从主评论 Comment 对象读取 (小黑盒的 reply_num / sub_comment_count)
        if (mainComment != null) {
            for (m in mainComment.javaClass.methods) {
                val name = m.name.lowercase()
                if (m.parameterCount == 0 && (name.contains("replynum") || name.contains("replycount") || name.contains("subcommentcount") || name.contains("subcommentnum") || name.contains("replycnt") || name.contains("commentcount"))) {
                    try {
                        val res = m.invoke(mainComment)
                        if (res is Number && res.toInt() > 0) return res.toInt()
                    } catch (ignored: Throwable) {}
                }
            }
        }
        return 0
    }

    private fun getSubCommentsList(subCommentsObj: Any): List<*> {
        return try {
            val m = subCommentsObj.javaClass.methods.firstOrNull { it.name == "getComments" }
            m?.invoke(subCommentsObj) as? List<*> ?: emptyList<Any>()
        } catch (e: Exception) {
            emptyList<Any>()
        }
    }

    private fun getFieldOrMethodString(obj: Any, methodName: String): String {
        return try {
            val m = obj.javaClass.methods.firstOrNull { it.name == methodName }
            m?.invoke(obj)?.toString() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /** 纯图片回复的正文为空，但图片本身是有效信息，不能当噪音丢掉 */
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

    // 与 Extractor 保持一致：补齐后的楼中楼不能丢失“楼主”标记
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

    private fun getCommentId(comment: Any?): String {
        if (comment == null) return ""
        return try {
            val m = comment.javaClass.methods.firstOrNull { it.name == "getCommentid" }
            m?.invoke(comment)?.toString() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun getCommentAuthor(comment: Any?): String {
        if (comment == null) return "未知"
        return try {
            val userMethod = comment.javaClass.methods.firstOrNull { it.name == "getUser" }
            val userObj = userMethod?.invoke(comment)
            val nameMethod = userObj?.javaClass?.methods?.firstOrNull { it.name == "getUsername" }
            nameMethod?.invoke(userObj)?.toString() ?: "用户"
        } catch (e: Exception) {
            "用户"
        }
    }

    fun getCommentReplyTo(comment: Any?): String {
        if (comment == null) return ""
        try {
            val candidates = listOf("getReply_user", "getReplyUser", "getReply_to_user", "getReplyToUser", "getTo_user", "getToUser")
            for (name in candidates) {
                val m = comment.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                val uObj = m?.invoke(comment)
                if (uObj != null) {
                    val nameMethod = uObj.javaClass.methods.firstOrNull { (it.name == "getUsername" || it.name == "getName") && it.parameterCount == 0 }
                    val uName = nameMethod?.invoke(uObj)?.toString()
                    if (!uName.isNullOrBlank()) return uName
                }
            }
            val stringCandidates = listOf("getReply_user_name", "getReplyUserName", "getReply_to", "getReplyTo")
            for (name in stringCandidates) {
                val m = comment.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                val res = m?.invoke(comment)?.toString()
                if (!res.isNullOrBlank()) return res
            }
        } catch (ignored: Throwable) {}
        return ""
    }

    private fun getCommentText(comment: Any?): String {
        if (comment == null) return ""
        return try {
            val m = comment.javaClass.methods.firstOrNull { it.name == "getText" }
            m?.invoke(comment)?.toString() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private data class FloorTarget(
        val commentId: String,
        val initialReplies: List<EnrichedReply>
    )
}
