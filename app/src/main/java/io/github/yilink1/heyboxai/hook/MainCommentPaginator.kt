package io.github.yilink1.heyboxai.hook

import android.app.Activity
import android.util.Log
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 第二阶段正式集成：主评论静默串行分页加载器
 *
 * 核心保证：
 * 1. 严格串行分页：每一页游标必须来源于上一页真实返回值，严防并发翻页竞争。
 * 2. 截止时间总预算控制：以 deadline = now + totalTimeoutMs 为全局基准，每次网络请求前严格核算剩余预算。
 * 3. 熔断与安全终止：
 *    - hasMoreFloors == false: 正常全量结束 (NO_MORE_PAGES)
 *    - 空白游标 / 重复游标: 触发安全熔断 (CURSOR_BLANK / CURSOR_DUPLICATE)
 *    - 空列表 / 网络异常 / 超时: 触发安全熔断 (EMPTY_RESPONSE / NETWORK_ERROR / BUDGET_TIMEOUT)
 * 4. 严格保序去重：使用 LinkedHashMap 按 commentid 去重，保留自然顺序，处理服务端分页边界重复。
 * 5. 纯数据层调用：复用宿主 LinkPostRepository.h()，绝对零 UI 副作用，不修改 ViewModel 与 Adapter。
 */
object MainCommentPaginator {

    private const val TAG = "HeyboxAI_MainPaginator"

    enum class TerminationReason(val desc: String) {
        CLEAN_NO_MORE("服务端明确无更多主评论"),
        INITIAL_ALL_LOADED("初始数据即已包含全量主评论"),
        MAX_PAGES_REACHED("已达到预设最大分页深度"),
        TARGET_REACHED("已达到设定的抓取楼层上限"),
        BUDGET_TIMEOUT("分页总时间预算耗尽"),
        EMPTY_RESPONSE("服务端返回评论列表为空"),
        CURSOR_BLANK("服务端未返回有效后续游标"),
        CURSOR_DUPLICATE("检测到重复游标，触发安全熔断"),
        NETWORK_ERROR("网络请求或反序列化异常"),
        REPO_NOT_FOUND("未找到宿主数据仓库或分页方法")
    }

    data class PaginationResult(
        val mergedComments: List<Any>,
        val pagesFetched: Int,
        val newFloorsAdded: Int,
        val isCleanCompleted: Boolean,
        val terminationReason: TerminationReason,
        val totalCostMs: Long
    )

    /**
     * 静默串行拉取后续主评论
     *
     * @param activity 宿主 Activity
     * @param linkPostModel 宿主帖子模型
     * @param initialComments 初始提取的已加载主评论
     * @param targetFloors 期望达到的目标总楼层数（由用户设置决定，默认 100 楼）
     * @param maxPages 最大允许额外翻页数（动态自适应计算）
     * @param totalTimeoutMs 总时间预算截止时间（毫秒）
     */
    fun paginateMainComments(
        activity: Activity,
        linkPostModel: Any?,
        initialComments: List<Any>,
        targetFloors: Int = 100,
        maxPages: Int = 6,
        totalTimeoutMs: Long = 6500L
    ): PaginationResult {
        val startTime = System.currentTimeMillis()
        val deadline = startTime + totalTimeoutMs

        // 1. 初始化有序去重映射
        val commentMap = LinkedHashMap<String, Any>()
        for (item in initialComments) {
            val id = getCommentId(item)
            if (id.isNotBlank()) {
                commentMap[id] = item
            }
        }
        val initialCount = commentMap.size

        // 2. 检查初始模型状态 (采用特征指纹定位，杜绝混淆名 O4 绑定)
        val viewModel = XiaoheiheFingerprintHelper.findPostViewModel(activity)
        if (viewModel == null || linkPostModel == null) {
            Log.w(TAG, "ViewModel 或 PostModel 为空，跳过主评论分页")
            return PaginationResult(
                mergedComments = initialComments,
                pagesFetched = 0,
                newFloorsAdded = 0,
                isCleanCompleted = false,
                terminationReason = TerminationReason.REPO_NOT_FOUND,
                totalCostMs = System.currentTimeMillis() - startTime
            )
        }

        val linkId = extractLinkId(activity, viewModel, linkPostModel)
        if (linkId.isBlank()) {
            Log.w(TAG, "未能提取目标帖子 link_id，跳过主评论分页")
            return PaginationResult(
                mergedComments = initialComments,
                pagesFetched = 0,
                newFloorsAdded = 0,
                isCleanCompleted = false,
                terminationReason = TerminationReason.REPO_NOT_FOUND,
                totalCostMs = System.currentTimeMillis() - startTime
            )
        }

        // 3. 检查第 1 页分页状态
        val commentSection = getFieldOrMethod(linkPostModel, "getCommentSection")
        val commentConfig = if (commentSection != null) getFieldOrMethod(commentSection, "getCommentConfig") else null

        val initialHasMore = if (commentConfig != null) {
            getFieldOrMethod(commentConfig, "getHasMoreFloors") as? Boolean ?: false
        } else false

        val initialLastVal = if (commentConfig != null) {
            getFieldOrMethod(commentConfig, "getLastReqPageVal")?.toString() ?: ""
        } else ""

        if (!initialHasMore) {
            Log.i(TAG, "帖子 [$linkId] 初始 hasMoreFloors == false，无后续主评论")
            return PaginationResult(
                mergedComments = initialComments,
                pagesFetched = 0,
                newFloorsAdded = 0,
                isCleanCompleted = true,
                terminationReason = TerminationReason.INITIAL_ALL_LOADED,
                totalCostMs = System.currentTimeMillis() - startTime
            )
        }

        if (initialCount >= targetFloors) {
            return PaginationResult(
                mergedComments = initialComments,
                pagesFetched = 0,
                newFloorsAdded = 0,
                isCleanCompleted = false,
                terminationReason = TerminationReason.TARGET_REACHED,
                totalCostMs = System.currentTimeMillis() - startTime
            )
        }

        if (initialLastVal.isBlank()) {
            Log.w(TAG, "帖子 [$linkId] hasMoreFloors 为 true 但 lastReqPageVal 为空，触发熔断")
            return PaginationResult(
                mergedComments = initialComments,
                pagesFetched = 0,
                newFloorsAdded = 0,
                isCleanCompleted = false,
                terminationReason = TerminationReason.CURSOR_BLANK,
                totalCostMs = System.currentTimeMillis() - startTime
            )
        }

        // 4. 定位 LinkPostRepository 与方法 h
        val repo = findLinkPostRepository(viewModel)
        val methodH = repo?.let { findRepositoryMethodH(it) }

        if (repo == null || methodH == null) {
            if (repo != null) {
                // 输出候选方法签名，便于跨版本适配排障
                val sigs = repo.javaClass.methods
                    .filter { it.parameterCount in 10..14 }
                    .joinToString("; ") { m -> "${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})" }
                Log.w(TAG, "未匹配到主评论分页方法 h，repo=${repo.javaClass.name} 候选: $sigs")
            } else {
                Log.w(TAG, "未能找到 LinkPostRepository")
            }
            return PaginationResult(
                mergedComments = initialComments,
                pagesFetched = 0,
                newFloorsAdded = 0,
                isCleanCompleted = false,
                terminationReason = TerminationReason.REPO_NOT_FOUND,
                totalCostMs = System.currentTimeMillis() - startTime
            )
        }

        // 5. 串行分页循环
        val classLoader = activity.classLoader
        val visitedCursors = HashSet<String>()
        visitedCursors.add(initialLastVal)

        var currentCursor = initialLastVal
        var pagesFetched = 0
        var terminationReason: TerminationReason = TerminationReason.MAX_PAGES_REACHED
        var cleanCompleted = false

        Log.i(TAG, "开始静默拉取帖子 [$linkId] 后续主评论，预算 ${totalTimeoutMs}ms，初始楼层: $initialCount")

        for (pageIndex in 2..(1 + maxPages)) {
            // 严格检查剩余总预算截止时间
            val remainingBudget = deadline - System.currentTimeMillis()
            if (remainingBudget <= 400L) {
                Log.w(TAG, "第 $pageIndex 页拉取前预算不足 (${remainingBudget}ms <= 400ms)，触发预算熔断")
                terminationReason = TerminationReason.BUDGET_TIMEOUT
                break
            }

            // 单次请求超时时间不超过剩余预算且不超过 8000ms（慢网络下避免误判超时）
            val singleRequestTimeout = remainingBudget.coerceAtMost(8000L)
            val requestPageStr = pageIndex.toString()

            val args = arrayOf<Any?>(
                linkId,                     // 0: link_id
                requestPageStr,             // 1: page
                currentCursor,              // 2: last_val
                emptyMap<String, String>(), // 3: extra map
                "",                         // 4: h_src
                "0",                        // 5: sort_filter
                "0",                        // 6: owner_only
                "0",                        // 7: hide_cy
                "0",                        // 8: check_commented
                "0",                        // 9: is_first
                "30"                        // 10: limit
            )

            var resultModel: Any? = null
            var requestError: Throwable? = null
            val pageFetchStart = System.currentTimeMillis()

            try {
                resultModel = SuspendBridge.invokeSuspend(
                    target = repo,
                    method = methodH,
                    args = args,
                    classLoader = classLoader,
                    timeoutMs = singleRequestTimeout
                )
            } catch (t: Throwable) {
                requestError = t
            }

            // 偶发失败重试一次；403/429 说明被限流或拦截，不重试，直接熔断
            if ((requestError != null || resultModel == null) && !RequestGuard.isRateLimitOrBlocked(requestError)) {
                RequestGuard.sleepJitter(400L, 800L)
                val retryBudget = deadline - System.currentTimeMillis()
                if (retryBudget > 1500L) {
                    try {
                        requestError = null
                        resultModel = SuspendBridge.invokeSuspend(
                            target = repo,
                            method = methodH,
                            args = args,
                            classLoader = classLoader,
                            timeoutMs = retryBudget.coerceAtMost(8000L)
                        )
                    } catch (t: Throwable) {
                        requestError = t
                    }
                }
            }

            val pageCost = System.currentTimeMillis() - pageFetchStart

            if (requestError != null || resultModel == null) {
                Log.w(TAG, "第 $pageIndex 页网络请求失败 (耗时 ${pageCost}ms): ${requestError?.message}")
                terminationReason = TerminationReason.NETWORK_ERROR
                break
            }

            pagesFetched++

            // 解析返回数据
            val pSection = getFieldOrMethod(resultModel, "getCommentSection")
            val pConfig = if (pSection != null) getFieldOrMethod(pSection, "getCommentConfig") else null

            val nextHasMore = if (pConfig != null) {
                getFieldOrMethod(pConfig, "getHasMoreFloors") as? Boolean ?: false
            } else false

            val nextCursor = if (pConfig != null) {
                getFieldOrMethod(pConfig, "getLastReqPageVal")?.toString() ?: ""
            } else ""

            val pageComments = if (pSection != null) {
                getFieldOrMethod(pSection, "getPostComments") as? List<*> ?: emptyList<Any>()
            } else emptyList<Any>()

            if (pageComments.isEmpty()) {
                Log.i(TAG, "第 $pageIndex 页返回空列表，停止后续分页")
                terminationReason = TerminationReason.EMPTY_RESPONSE
                break
            }

            // 将新评论写入有序 Map，按 ID 去重
            var newlyAddedThisPage = 0
            for (c in pageComments) {
                if (c != null) {
                    val cId = getCommentId(c)
                    if (cId.isNotBlank() && !commentMap.containsKey(cId)) {
                        commentMap[cId] = c
                        newlyAddedThisPage++
                    }
                }
            }

            Log.i(TAG, "第 $pageIndex 页获取成功: 耗时 ${pageCost}ms, 返回 ${pageComments.size} 条, 净新增 $newlyAddedThisPage 楼层, 累计 ${commentMap.size}/$targetFloors, hasMore=$nextHasMore")

            // 检查是否已达到用户期望的总提取楼层预算
            if (commentMap.size >= targetFloors) {
                Log.i(TAG, "已达到目标提取楼层 (${commentMap.size} >= $targetFloors)，结束分页")
                // 只是达到用户设定的上限，服务端可能还有更多楼层，不能算“全帖完整”
                terminationReason = if (nextHasMore) TerminationReason.TARGET_REACHED else TerminationReason.CLEAN_NO_MORE
                cleanCompleted = !nextHasMore
                break
            }

            // 检查是否正常到达全帖末尾
            if (!nextHasMore) {
                Log.i(TAG, "服务端明确返回 hasMoreFloors == false，已到达全帖末尾")
                terminationReason = TerminationReason.CLEAN_NO_MORE
                cleanCompleted = true
                break
            }

            // 安全熔断检查：空白游标
            if (nextCursor.isBlank()) {
                Log.w(TAG, "第 $pageIndex 页返回 hasMore=true 但 nextCursor 为空，触发安全熔断")
                terminationReason = TerminationReason.CURSOR_BLANK
                break
            }

            // 安全熔断检查：重复游标（死循环防护）
            if (visitedCursors.contains(nextCursor)) {
                Log.w(TAG, "第 $pageIndex 页返回重复游标 [$nextCursor]，触发死循环熔断")
                terminationReason = TerminationReason.CURSOR_DUPLICATE
                break
            }

            visitedCursors.add(nextCursor)
            currentCursor = nextCursor
        }

        val totalCost = System.currentTimeMillis() - startTime
        val totalNewAdded = (commentMap.size - initialCount).coerceAtLeast(0)

        Log.i(TAG, "主评论分页流程结束: 耗时 ${totalCost}ms, 翻页 $pagesFetched 次, 净新增 $totalNewAdded 楼层, 最终主楼层数: ${commentMap.size}, 状态: ${terminationReason.desc}")

        return PaginationResult(
            mergedComments = commentMap.values.toList(),
            pagesFetched = pagesFetched,
            newFloorsAdded = totalNewAdded,
            isCleanCompleted = cleanCompleted,
            terminationReason = terminationReason,
            totalCostMs = totalCost
        )
    }

    fun extractLinkId(activity: Activity, viewModel: Any?, postModel: Any?): String {
        return XiaoheiheFingerprintHelper.extractLinkId(activity, viewModel, postModel)
    }

    private fun findLinkPostRepository(viewModel: Any): Any? {
        return XiaoheiheFingerprintHelper.findLinkPostRepository(viewModel)
    }

    private fun findRepositoryMethodH(repo: Any): Method? {
        return XiaoheiheFingerprintHelper.findMainCommentPaginateMethod(repo)
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

    private fun getFieldOrMethod(target: Any?, name: String): Any? {
        if (target == null) return null
        return try {
            val m = target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
            if (m != null) return m.invoke(target)
            val f = target.javaClass.declaredFields.firstOrNull { it.name == name }
            if (f != null) {
                f.isAccessible = true
                return f.get(target)
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
