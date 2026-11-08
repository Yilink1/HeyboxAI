package io.github.yilink1.heyboxai.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.*
import io.github.yilink1.heyboxai.ai.AiClient
import io.github.yilink1.heyboxai.config.AiConfig
import io.github.yilink1.heyboxai.hook.XiaoheihePostExtractor
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.*
import java.io.IOException

class AiSummaryBottomSheet(
    private val activity: Activity,
    private val linkId: String,
    private val data: XiaoheihePostExtractor.ExtractedData
) {
    companion object {
        private const val TAG = "HeyboxAI_Sheet"

        data class SessionData(
            val messages: MutableList<AiClient.Message>,
            var displayText: String,
            var statusText: String
        )
        // 每帖一份会话（不随范围分裂）；限制最大缓存条数防内存泄漏
        private val sessionCache = LruCache<String, SessionData>(20)

        // 跟踪当前活跃弹窗实例与点击节流，杜绝切主题/recreate导致静态标志位死锁
        private var activeDialogRef = java.lang.ref.WeakReference<Dialog>(null)
        private var lastShowTime = 0L

        fun isNightMode(activity: Activity): Boolean {
            val night = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            return night
        }

        fun dismissCurrent() {
            try {
                val old = activeDialogRef.get()
                if (old != null && old.isShowing) {
                    old.dismiss()
                }
            } catch (ignored: Throwable) {}
            activeDialogRef.clear()
        }
    }

    /** 贴合小黑盒原生聊天与设置设计风格的调色板（轻盈、透气、自然） */
    private class Palette(val dark: Boolean) {
        // 弹窗底色：小黑盒原生聊天背景（浅色是纯白 #FFFFFFFF，深色是深空卡片黑 #FF16171A）
        val bg = if (dark) 0xFF16171A.toInt() else 0xFFFFFFFF.toInt()

        // 顶栏与底栏背景
        val barBg = if (dark) 0xFF16171A.toInt() else 0xFFFFFFFF.toInt()
        val barStroke = if (dark) 0xFF24262B.toInt() else 0xFFECEEF2.toInt()

        // AI 消息卡片表面底色：浅色为小黑盒经典浅灰 #F5F6F8，深色为深空暗灰 #202125
        val aiCardBg = if (dark) 0xFF202125.toInt() else 0xFFF5F6F8.toInt()
        val aiCardStroke = if (dark) 0xFF2B2D33.toInt() else 0xFFECEEF2.toInt()

        // 用户提问气泡：小黑盒原生聊天温润天蓝气泡！浅色 #CBE7FF，深色 #233244
        val userBubbleBg = if (dark) 0xFF233244.toInt() else 0xFFCBE7FF.toInt()
        val userBubbleText = if (dark) 0xFFD8E7F8.toInt() else 0xFF1A222C.toInt()

        // 核心文字
        val text = if (dark) 0xFFECECEE.toInt() else 0xFF1F1F1F.toInt()
        val text2 = if (dark) 0xFF8E9096.toInt() else 0xFF666666.toInt()
        // 三级文字：原 #999999 / #5E6066 对背景对比度仅约 2.85:1，提到 4.3~4.7:1
        val text3 = if (dark) 0xFF80838A.toInt() else 0xFF767A82.toInt()

        // 小黑盒回复/提及沉稳蓝
        val linkBlue = if (dark) 0xFF6B9EE8.toInt() else 0xFF2B64B3.toInt()

        // 选项高亮态
        val selectedBg = if (dark) 0xFF253344.toInt() else 0xFFEBF3FC.toInt()
        val selectedStroke = if (dark) 0xFF3D5370.toInt() else 0xFFB8D7FA.toInt()
        val selectedText = if (dark) 0xFF89B9F8.toInt() else 0xFF2B64B3.toInt()

        // 底部输入框与微灰底（小黑盒原生灰底：浅色 #FFF6F7F9，深色 #FF101112）
        val inputBg = if (dark) 0xFF101112.toInt() else 0xFFF6F7F9.toInt()
        val inputStroke = if (dark) 0xFF24262B.toInt() else 0xFFE5E7EB.toInt()

        // 按钮主色：小黑盒规范深炭灰 #2B2D33（深色 #ECECEE）
        val primaryBtnBg = if (dark) 0xFFECECEE.toInt() else 0xFF2B2D33.toInt()
        val primaryBtnText = if (dark) 0xFF16171A.toInt() else 0xFFFFFFFF.toInt()

        // 快捷标签 Chips：小黑盒方圆微卡片
        val chipBg = if (dark) 0xFF101112.toInt() else 0xFFF6F7F9.toInt()
        val chipStroke = if (dark) 0xFF24262B.toInt() else 0xFFE5E7EB.toInt()
        val chipText = if (dark) 0xFFCCCCCC.toInt() else 0xFF333333.toInt()

        // 弹窗与卡片通用表面
        val surface = if (dark) 0xFF16171A.toInt() else 0xFFFFFFFF.toInt()

        // 边框与手柄
        val stroke = if (dark) 0xFF24262B.toInt() else 0xFFECEEF2.toInt()
        val handle = if (dark) 0xFF35373E.toInt() else 0xFFD8D9E0.toInt()
        // 停止按钮红底：优雅沉稳暗红（拒绝高饱和刺眼红）
        val danger = if (dark) 0xFFB83B3B.toInt() else 0xFFC53929.toInt()
        // 成功提示色（原先在测试连接里硬编码，不随明暗主题变化）
        val success = if (dark) 0xFF5FB37C.toInt() else 0xFF2E7D32.toInt()
        // 代码块底色：浅色下 AI 卡片本身就是浅灰，原 chipBg 与卡片几乎同色（对比 1.01:1）
        val codeBg = if (dark) 0xFF14151A.toInt() else 0xFFE9EBF0.toInt()
        // 引用竖线：原浅色 #2B2D30 近黑，过重
        val quoteBar = if (dark) 0xFF4A4C54.toInt() else 0xFFC4C8D0.toInt()
        // 按压水波纹
        val ripple = if (dark) 0x26FFFFFF else 0x14000000
    }

    private val pal = Palette(isNightMode(activity))

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    /** 四角独立圆角：顺序 左上、右上、右下、左下（气泡“尾角”用） */
    private fun roundedCorners(color: Int, tl: Int, tr: Int, br: Int, bl: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadii = floatArrayOf(
                dp(tl).toFloat(), dp(tl).toFloat(), dp(tr).toFloat(), dp(tr).toFloat(),
                dp(br).toFloat(), dp(br).toFloat(), dp(bl).toFloat(), dp(bl).toFloat()
            )
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    /** 给已有背景的控件叠一层按压水波纹，用同形状遮罩裁切 */
    private fun View.withRipple(radiusDp: Int) {
        val content = background
        background = RippleDrawable(ColorStateList.valueOf(pal.ripple), content, rounded(Color.WHITE, radiusDp))
    }

    private fun closeIcon(onClick: () -> Unit): ImageView = ImageView(activity).apply {
        setImageDrawable(GlyphDrawable(GlyphDrawable.Kind.CLOSE, pal.text2))
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        contentDescription = "关闭"
        withRipple(20)
        setOnClickListener { onClick() }
    }

    private val dimOverlayColor: Int
        get() = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            0x55000000.toInt()
        } else {
            0x80000000.toInt()
        }

    /** Android 12+ (API 31+) 原生硬件加速高斯模糊羽化背景，12 以下安全降级兼容 */
    private fun applyBlurBehind(d: Dialog) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            d.window?.apply {
                addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                attributes = attributes?.apply {
                    blurBehindRadius = dp(22)
                }
            }
        }
    }

    // 重写返回键，让返回 / 点遮罩 / 下拉关闭都走同一个退场动画
    private val dialog = object : Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar) {
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onBackPressed() {
            dismissSheet()
        }
    }

    // 子弹窗是独立窗口，宿主 Activity 销毁时必须一并关闭，否则 WindowLeaked
    private val childDialogs = mutableListOf<Dialog>()
    private var dismissing = false

    private fun setDim(alpha: Int) {
        if (::rootView.isInitialized) rootView.background?.alpha = alpha.coerceIn(0, 255)
    }

    private fun animateDim(from: Int, to: Int, duration: Long) {
        ValueAnimator.ofInt(from, to).apply {
            this.duration = duration
            addUpdateListener { setDim(it.animatedValue as Int) }
            start()
        }
    }

    private fun dismissSheet() {
        if (dismissing) return
        if (!::sheetView.isInitialized || !dialog.isShowing) {
            try { dialog.dismiss() } catch (ignored: Throwable) {}
            return
        }
        dismissing = true
        animateDim(rootView.background?.alpha ?: 255, 0, 200)
        sheetView.animate().cancel()
        sheetView.animate()
            .translationY(sheetView.height.toFloat())
            .setDuration(200)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { try { dialog.dismiss() } catch (ignored: Throwable) {} }
            .start()
    }

    /** 子弹窗统一展示：关掉默认横向滑入动画，改为淡入 + 轻微缩放，并登记以便统一关闭 */
    private fun showChildDialog(d: Dialog, outer: View, softInput: Boolean = false) {
        d.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setWindowAnimations(0)
            if (softInput) setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            applyBlurBehind(d)
        }
        childDialogs.add(d)
        d.setOnDismissListener { childDialogs.remove(d) }
        outer.alpha = 0f
        try {
            d.show()
        } catch (e: Throwable) {
            Log.e(TAG, "child dialog show failed", e)
            childDialogs.remove(d)
            return
        }
        outer.animate().alpha(1f).setDuration(160).start()
        (outer as? ViewGroup)?.getChildAt(0)?.let { card ->
            card.scaleX = 0.96f
            card.scaleY = 0.96f
            card.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    // 兜底：宿主进程里任何未捕获的协程异常都会直接崩掉小黑盒，这里统一拦截
    private val errorHandler = CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "coroutine failed", t)
        generating = false
        if (::statusTv.isInitialized) {
            statusTv.text = "出错: ${t.message ?: t.javaClass.simpleName}"
            applyGeneratingState(false)
        }
    }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + errorHandler)

    private var config = AiConfig.loadFromProvider(activity)
    private val markwon by lazy {
        Markwon.builder(activity)
            .usePlugin(TablePlugin.create(activity))
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder.linkColor(pal.linkBlue)
                        .blockQuoteColor(pal.quoteBar)
                        .codeBackgroundColor(pal.codeBg)
                        .codeBlockBackgroundColor(pal.codeBg)
                        .codeTextColor(pal.text)
                        .codeBlockTextColor(pal.text)
                        // 默认 H1 是正文 2 倍且带分割线，放在 14.5sp 的聊天气泡里过于突兀
                        .headingBreakHeight(0)
                        .headingTextSizeMultipliers(floatArrayOf(1.3f, 1.2f, 1.1f, 1.05f, 1f, 1f))
                        .thematicBreakColor(pal.stroke)
                }
            })
            .build()
    }

    // 当前帖子数据对象（支持动态异步补齐后续楼层）
    private var currentData: XiaoheihePostExtractor.ExtractedData = data
    // 默认分析范围：使用提取数据时的范围 (默认全帖讨论)
    private var currentScope: XiaoheihePostExtractor.SummaryScope = data.extractedScope

    private lateinit var messageContainer: LinearLayout
    private lateinit var statusTv: TextView
    private lateinit var inputEt: EditText
    private lateinit var sendBtn: ImageView
    private lateinit var headerDivider: View
    private lateinit var modelSubtitleTv: TextView
    private lateinit var scopeBtn: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var rootView: View
    private lateinit var sheetView: View

    private var generating = false
    private var streamJob: Job? = null
    private var currentClient: AiClient? = null
    private var taskSeq = 0

    // 每帖一份对话：切换上下文范围只是告诉模型"看多少楼"，历史保持连续
    // linkId 取不到时不进缓存，否则所有“未知帖子”会共用同一份会话
    private val ephemeralSession by lazy { SessionData(mutableListOf(), "", "") }
    private val currentSession: SessionData
        get() {
            if (linkId.isBlank()) return ephemeralSession
            val key = linkId
            var session = sessionCache.get(key)
            if (session == null) {
                session = SessionData(
                    messages = mutableListOf(),
                    displayText = "",
                    statusText = ""
                )
                sessionCache.put(key, session)
            }
            return session
        }

    private fun scopeLabel(scope: XiaoheihePostExtractor.SummaryScope): String = scope.label

    private fun scopeFloorCount(): Int = when {
        currentScope == XiaoheihePostExtractor.SummaryScope.MAIN_ONLY -> 0
        currentScope == XiaoheihePostExtractor.SummaryScope.ALL_LOADED -> currentData.formattedFloors.size
        else -> currentData.formattedFloors.size.coerceAtMost(currentScope.floorLimit)
    }

    fun show() {
        val now = System.currentTimeMillis()
        if (now - lastShowTime < 500L) return // 500ms 快速连点保护
        lastShowTime = now

        if (activity.isFinishing || (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed)) return

        // 如果之前有残留的旧弹窗（如切主题前的），先安全关闭
        dismissCurrent()

        val root = createView()
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(root)

        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // 默认的窗口动画是整页平移/缩放，这里关掉，用下面的底部上滑动画
            setWindowAnimations(0)
            // 键盘弹出时整体缩小而不是遮住输入框 / 把标题顶出屏幕
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            applyBlurBehind(dialog)
        }

        // 弹窗关闭时自动取消后台协程并断开网络请求，防止继续消耗 Token 及持有 Activity 导致泄露
        dialog.setOnDismissListener {
            if (activeDialogRef.get() == dialog) {
                activeDialogRef.clear()
            }
            currentClient?.cancelActiveCall()
            thinkingAnim?.cancel()
            thinkingAnim = null
            childDialogs.toList().forEach { try { it.dismiss() } catch (ignored: Throwable) {} }
            childDialogs.clear()
            scope.cancel()
        }

        sheetView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                sheetView.viewTreeObserver.removeOnPreDrawListener(this)
                sheetView.translationY = sheetView.height.toFloat()
                setDim(0)
                sheetView.animate().translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator(1.6f)).start()
                animateDim(0, 255, 260)
                return true
            }
        })

        try {
            dialog.show()
            activeDialogRef = java.lang.ref.WeakReference(dialog)
            loadSessionIntoView()
        } catch (e: Throwable) {
            Log.e(TAG, "dialog.show failed", e)
            activeDialogRef.clear()
        }
    }

    /** 恢复本帖会话：内存 > 本地历史 > 欢迎页 */
    private fun loadSessionIntoView() {
        val session = currentSession
        if (session.messages.isNotEmpty()) {
            // displayText 为空说明历史是真实对话，让消息列表渲染器接管
            rebuildMessageViews(session)
            statusTv.text = session.statusText
            return
        }
        val cached = SummaryHistoryManager.loadHistory(activity, linkId, currentScope)
        if (cached != null) {
            session.messages.clear()
            session.messages.addAll(cached.messages)
            // 若存档是旧版"总结+追问混排"文本，直接整段作为一条 AI 消息展示
            if (cached.messages.isEmpty() && cached.displayText.isNotBlank()) {
                session.messages.add(AiClient.Message("assistant", cached.displayText))
            }
            // 范围随存档恢复，保证顶栏胶囊与上次对话一致
            currentScope = cached.scope
            scopeBtn.text = scopeLabel(currentScope) + " ▾"
            val relativeTime = SummaryHistoryManager.formatRelativeTime(cached.timestamp)
            session.statusText = "已恢复历史 ($relativeTime)"
            rebuildMessageViews(session)
            statusTv.text = session.statusText
        } else {
            showWelcomeState()
        }
    }

    /** 空会话：一条 AI 欢迎气泡，还原清爽纯粹质感 */
    private fun showWelcomeState() {
        val titleText = if (currentData.title.isNotBlank()) currentData.title else "本帖讨论"
        val commentInfo = if (currentData.postTotalComments > 0) " (共 ${currentData.postTotalComments} 条讨论)" else ""
        val welcome = if (config.apiKey.isBlank()) {
            "已载入《$titleText》$commentInfo。\n\n首次使用请点击右上角「设置」配置 API 密钥，即可开启楼层分析与智能问答。"
        } else {
            "已载入《$titleText》$commentInfo。点击下方快捷指令或直接输入问题，AI 将为你分析答疑。"
        }
        val session = currentSession
        session.messages.clear()
        session.displayText = ""
        session.statusText = "就绪 · ${scopeLabel(currentScope)}"
        rebuildMessageViews(session, extraWelcome = welcome)
        if (::statusTv.isInitialized) {
            statusTv.setTextColor(pal.text3)
            statusTv.text = session.statusText
        }
    }

    // 流式期间实时更新的 AI 气泡文本视图
    private var streamingTv: TextView? = null
    private var streamingHolder: LinearLayout? = null
    private var thinkingAnim: ObjectAnimator? = null

    /** 聊天气泡式全量重建（小黑盒风格：用户深黑极客胶囊 / AI 极简卡片） */
    private fun rebuildMessageViews(session: SessionData, extraWelcome: String? = null) {
        if (!::messageContainer.isInitialized) return
        messageContainer.removeAllViews()
        thinkingAnim?.cancel()
        thinkingAnim = null
        streamingTv = null
        streamingHolder = null

        val msgs = session.messages.filter {
            it.role != "system" &&
            !it.content.startsWith("以下是话题内容：") &&
            it.content != "好的，我已经阅读了话题内容。请问你有什么问题？"
        }
        if (msgs.isEmpty() && extraWelcome != null) {
            messageContainer.addView(buildAssistantBubble(extraWelcome, isWelcome = true))
        } else {
            for (m in msgs) {
                if (m.content.isBlank()) continue
                messageContainer.addView(
                    if (m.role == "user") buildUserBubble(m.content) else buildAssistantBubble(m.content, isWelcome = false)
                )
            }
        }
        // 每次重建后滚到底部展示最新内容
        scrollToBottom()
    }

    /**
     * 滚到底部。不用 ScrollView.fullScroll：它会把焦点抢给 ScrollView / 可选中文本，
     * 流式输出时输入框会因此失焦、键盘被收起。
     */
    private fun scrollToBottom() {
        scrollView.post {
            val child = scrollView.getChildAt(0) ?: return@post
            val y = (child.height + scrollView.paddingTop + scrollView.paddingBottom - scrollView.height).coerceAtLeast(0)
            scrollView.scrollTo(0, y)
        }
    }

    private fun buildUserBubble(text: String): View {
        val tv = TextView(activity).apply {
            this.text = text
            textSize = 14.5f
            setTextColor(pal.userBubbleText)
            setLineSpacing(dp(3).toFloat(), 1.1f)
            setTextIsSelectable(true)
            // 右下角收小作为“尾角”，与 AI 气泡左下角呼应
            background = roundedCorners(pal.userBubbleBg, 16, 16, 4, 16)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxWidth = (activity.resources.displayMetrics.widthPixels * 0.75).toInt()
        }
        return FrameLayout(activity).apply {
            // 新一轮提问前留出更大的间距，让“一问一答”成组
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            }
            addView(tv, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END
            ))
        }
    }

    private fun newAssistantTextView(): TextView = TextView(activity).apply {
        textSize = 14.5f
        setTextColor(pal.text)
        setLinkTextColor(pal.linkBlue)
        // 原来 +4dp 且欢迎气泡再乘 1.6，行距接近 2 倍，偏松
        setLineSpacing(dp(3).toFloat(), 1.1f)
        background = roundedCorners(pal.aiCardBg, 16, 16, 16, 4, pal.aiCardStroke)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun newAssistantHolder(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        }
    }

    /** 每条 AI 回复下方的“复制”：长按复制与文字选择手势冲突，且流式气泡原本没有长按 */
    private fun makeCopyFooter(markdown: String): View = TextView(activity).apply {
        text = "复制"
        textSize = 12f
        setTextColor(pal.text3)
        setPadding(dp(8), dp(8), dp(12), dp(8))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(6)
        }
        withRipple(8)
        setOnClickListener {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("AI回复", markdown))
            Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildAssistantBubble(markdown: String, isWelcome: Boolean = false): View {
        val tv = newAssistantTextView().apply { setTextIsSelectable(true) }
        markwon.setMarkdown(tv, markdown)
        return newAssistantHolder().apply {
            addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(48)
            })
            if (!isWelcome) addView(makeCopyFooter(markdown))
        }
    }

    private fun ensureStreamingBubble(): TextView {
        val existing = streamingTv
        if (existing != null) return existing
        val tv = newAssistantTextView()
        val holder = newAssistantHolder()
        holder.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(48)
        })
        messageContainer.addView(holder)
        streamingTv = tv
        streamingHolder = holder
        return tv
    }

    /** 首个 token 到来前的占位：呼吸闪烁的“思考中”，推理模型可能要等几十秒 */
    private fun showThinkingBubble() {
        val tv = ensureStreamingBubble()
        tv.text = "思考中"
        tv.setTextColor(pal.text3)
        thinkingAnim?.cancel()
        thinkingAnim = ObjectAnimator.ofFloat(tv, View.ALPHA, 1f, 0.35f).apply {
            duration = 800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
        scrollToBottom()
    }

    /** 流式输出期间往容器追加/更新一条 AI 气泡 */
    private fun updateStreamingBubble(markdown: String) {
        val tv = ensureStreamingBubble()
        if (thinkingAnim != null) {
            thinkingAnim?.cancel()
            thinkingAnim = null
            tv.alpha = 1f
            tv.setTextColor(pal.text)
        }
        markwon.setMarkdown(tv, markdown)
        // 用户停在底部时才自动跟随，往上翻阅读时不抢滚动位置
        val child = scrollView.getChildAt(0)
        if (child != null) {
            val range = child.height + scrollView.paddingTop + scrollView.paddingBottom - scrollView.height
            if (range - scrollView.scrollY < dp(120)) scrollToBottom()
        }
    }

    /** 生成结束：气泡转为可选中并补上“复制” */
    private fun finalizeStreamingBubble(markdown: String) {
        val tv = streamingTv ?: return
        tv.setTextIsSelectable(true)
        streamingHolder?.addView(makeCopyFooter(markdown))
        streamingTv = null
        streamingHolder = null
    }

    private fun updateSendEnabled() {
        if (!::sendBtn.isInitialized || !::inputEt.isInitialized) return
        val active = generating || !inputEt.text.isNullOrBlank()
        sendBtn.alpha = if (active) 1f else 0.35f
    }

    private fun applyGeneratingState(g: Boolean) {
        generating = g
        if (!g) {
            thinkingAnim?.cancel()
            thinkingAnim = null
        }
        if (!::sendBtn.isInitialized) return
        // 图标改为自绘，避免 ↑ ■ 字形在不同字体下基线偏移；停止态不再用红色大色块，保持克制
        sendBtn.setImageDrawable(
            GlyphDrawable(if (g) GlyphDrawable.Kind.STOP else GlyphDrawable.Kind.ARROW_UP, pal.primaryBtnText)
        )
        sendBtn.contentDescription = if (g) "停止生成" else "发送"
        sendBtn.background = rounded(pal.primaryBtnBg, 18)
        sendBtn.withRipple(18)
        updateSendEnabled()
    }

    /** 同一时间只允许一个生成任务，新任务会先取消旧任务 */
    private fun launchTask(block: suspend CoroutineScope.(taskId: Int) -> Unit) {
        streamJob?.cancel()
        currentClient?.cancelActiveCall()
        val id = ++taskSeq
        applyGeneratingState(true)
        streamJob = scope.launch {
            try {
                block(id)
            } finally {
                if (id == taskSeq) applyGeneratingState(false)
            }
        }
    }

    /** 用户点“停止”：0ms 视觉即刻变回发送按钮，底层立即切断 TCP/HTTP 连接并停止协程 */
    private fun stopTask() {
        applyGeneratingState(false)
        statusTv.setTextColor(pal.text3)
        statusTv.text = "已停止"
        currentClient?.cancelActiveCall()
        streamJob?.cancel()
    }

    /** 切换范围 / 清空时：直接作废当前任务，不再回写界面 */
    private fun invalidateTask() {
        taskSeq++
        currentClient?.cancelActiveCall()
        streamJob?.cancel()
        applyGeneratingState(false)
    }

    /** 构建包含 Fluxdo 式两轮上下文注入的完整 API 请求消息序列 */
    private fun buildApiMessages(
        scope: XiaoheihePostExtractor.SummaryScope,
        conversation: List<AiClient.Message>
    ): List<AiClient.Message> {
        val titleText = currentData.title.ifBlank { "本帖讨论" }
        val systemContent = "${config.systemPrompt}\n话题标题：$titleText\n用户可能会就话题内容向你提问，请基于提供的上下文回答。\n请用 Markdown 格式回复。"
        val contextPrompt = currentData.buildPrompt(scope)
        val readyContent = "好的，我已经阅读了话题内容。请问你有什么问题？"

        val apiMessages = mutableListOf<AiClient.Message>()
        apiMessages.add(AiClient.Message("system", systemContent))
        if (contextPrompt.isNotBlank()) {
            apiMessages.add(AiClient.Message("user", "以下是话题内容：\n\n$contextPrompt"))
            apiMessages.add(AiClient.Message("assistant", readyContent))
        }
        for (m in conversation) {
            if (m.role == "system") continue
            if (m.content.startsWith("以下是话题内容：")) continue
            if (m.content == readyContent) continue
            apiMessages.add(m)
        }
        return apiMessages
    }

    private fun chipPrompt(action: String): String = when (action) {
        "总结这个话题" -> "请简要总结这个话题的主要内容和讨论要点。"
        "核心观点" -> "请列出这个话题中各楼层的主要观点和立场。"
        "有什么值得关注的" -> "这个话题中有哪些值得关注的信息或亮点？"
        "争议焦点" -> "请分析这个话题中各方的主要分歧与争议焦点。"
        "各方立场" -> "请梳理这个话题中各方阵营的立场倾向与主要依据。"
        "极简省流" -> "请用精简的一句话或几个要点概括这个话题的核心内容。"
        else -> "针对本帖讨论内容，请回答：$action"
    }

    private fun onChipClick(action: String) {
        sendFollowup(chipPrompt(action), display = action)
    }

    /** 按需静默补齐楼层与折叠楼中楼；失败时降级使用已加载内容，绝不抛出 */
    private suspend fun ensureData(force: Boolean) {
        // 1. 若主楼正文缺失（例如外部网页新闻贴 WebNews），优先尝试从 WebView 异步抓取已渲染正文
        if (!currentData.hasCompleteBody || force) {
            try {
                val webContent = XiaoheihePostExtractor.extractWebViewContent(activity)
                if (webContent != null && webContent.text.isNotBlank()) {
                    val updatedTitle = currentData.title.ifBlank { webContent.title }
                    val updatedUrl = currentData.pageUrl.ifBlank { webContent.url }
                    val updatedBody = XiaoheihePostExtractor.formatPostMainBody(
                        title = updatedTitle,
                        body = webContent.text,
                        pageUrl = updatedUrl
                    )
                    currentData = currentData.copy(
                        title = updatedTitle,
                        mainPostBody = updatedBody,
                        pageUrl = updatedUrl
                    )
                    Log.i(TAG, "成功从 WebView 提取正文并更新: len=${webContent.text.length}, title=$updatedTitle")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "从 WebView 补齐正文失败: ${t.message}")
            }
        }

        val scopeEnum = currentScope
        if (scopeEnum == XiaoheihePostExtractor.SummaryScope.MAIN_ONLY) return
        // 每次都重新读配置，设置面板里改完抓取深度立即生效
        config = AiConfig.loadFromProvider(activity)
        // 只有当前数据已经针对该 scope 完成过深度补齐时才跳过
        val isAlreadyEnriched = (currentData.enrichmentResult != null && currentData.extractedScope == scopeEnum)
        if (!force && isAlreadyEnriched) return

        statusTv.text = "正在整理帖子讨论..."
        try {
            currentData = withContext(Dispatchers.IO) {
                XiaoheihePostExtractor.enrichPostData(
                    activity, currentData, scopeEnum, config.maxFloors
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "enrichPostData failed", e)
            statusTv.text = "使用当前已有讨论分析"
        }
    }

    private fun restoreInput(text: String) {
        if (::inputEt.isInitialized && inputEt.text.isNullOrEmpty()) {
            inputEt.setText(text)
            inputEt.setSelection(text.length)
        }
    }

    private fun sendFollowup(
        question: String,
        display: String = question,
        forceRefresh: Boolean = false,
        userTyped: Boolean = false
    ) {
        if (question.isBlank()) return

        if (config.apiKey.isBlank()) {
            if (userTyped) restoreInput(question)
            Toast.makeText(activity, "首次使用请先配置 API Key", Toast.LENGTH_SHORT).show()
            showAiSettingsDialog()
            return
        }

        val session = currentSession
        val prevMessages = session.messages.toList()
        val prevStatus = session.statusText

        launchTask { id ->
            // 先放用户气泡与占位：整理楼层最长可能数十秒，界面不能空着
            messageContainer.addView(buildUserBubble(display))
            showThinkingBubble()
            statusTv.setTextColor(pal.text3)
            statusTv.text = "正在整理帖子讨论..."

            val replySb = StringBuilder()
            val newConversation = mutableListOf<AiClient.Message>()
            var scopeNow = currentScope
            var client: AiClient? = null
            var charCount = 0
            var lastRenderTime = 0L
            var renderPending = false

            val renderNow = object : Runnable {
                override fun run() {
                    renderPending = false
                    lastRenderTime = System.currentTimeMillis()
                    updateStreamingBubble(replySb.toString())
                    statusTv.setTextColor(pal.text3)
                    statusTv.text = "正在生成 · 输出 ~${(charCount * 0.65).toInt()} Token"
                }
            }
            fun stopRenderTimer() {
                statusTv.removeCallbacks(renderNow)
                renderPending = false
            }

            fun commit() {
                newConversation.add(AiClient.Message("assistant", replySb.toString()))
                session.messages.clear()
                session.messages.addAll(newConversation)
                session.displayText = ""
            }

            fun persist() {
                SummaryHistoryManager.saveHistoryAsync(
                    context = activity,
                    linkId = linkId,
                    data = currentData,
                    scope = scopeNow,
                    displayText = "",
                    statusText = session.statusText,
                    messages = session.messages,
                    floorCount = scopeFloorCount()
                )
            }

            fun revert() {
                session.messages.clear()
                session.messages.addAll(prevMessages)
                session.statusText = prevStatus
                rebuildMessageViews(session)
            }

            try {
                ensureData(forceRefresh)

                scopeNow = currentScope
                newConversation.addAll(session.messages)
                newConversation.add(AiClient.Message("user", question))

                val apiMsgs = buildApiMessages(scopeNow, newConversation)
                Log.i(TAG, "API 消息序列: 共有 ${apiMsgs.size} 条消息, 用户问题: $question")

                statusTv.setTextColor(pal.text3)
                statusTv.text = "范围: ${scopeNow.label} · 正在连接..."

                val c = AiClient(config.baseUrl, config.apiKey, config.model)
                client = c
                currentClient = c

                c.chatStream(apiMsgs).collect { chunk ->
                    replySb.append(chunk)
                    charCount += chunk.length
                    // 内容越长，整段 Markdown 重排越贵，节流间隔随之放宽
                    val interval = when {
                        replySb.length < 1500 -> 80L
                        replySb.length < 4000 -> 150L
                        else -> 250L
                    }
                    val wait = interval - (System.currentTimeMillis() - lastRenderTime)
                    if (wait <= 0L) {
                        statusTv.removeCallbacks(renderNow)
                        renderNow.run()
                    } else if (!renderPending) {
                        // 尾沿补渲染：流停顿时最后一小段不会一直卡着不显示
                        renderPending = true
                        statusTv.postDelayed(renderNow, wait)
                    }
                }
                stopRenderTimer()
                if (replySb.isEmpty()) {
                    throw IOException("模型未返回任何正文，请检查模型名称或更换模型")
                }
                updateStreamingBubble(replySb.toString())
                finalizeStreamingBubble(replySb.toString())
                commit()

                val isSummaryAction = question.contains("总结") || question.contains("观点") || question.contains("讨论要点")
                session.statusText = if (isSummaryAction) "总结完成 · ${scopeNow.label}" else "回答完成 · ${scopeNow.label}"
                statusTv.setTextColor(pal.text3)
                statusTv.text = session.statusText
                persist()
            } catch (e: CancellationException) {
                stopRenderTimer()
                if (replySb.isNotEmpty()) {
                    updateStreamingBubble(replySb.toString())
                    finalizeStreamingBubble(replySb.toString())
                    commit()
                    session.statusText = "${scopeNow.label} · 已停止"
                    persist()
                    if (id == taskSeq) {
                        statusTv.setTextColor(pal.text3)
                        statusTv.text = session.statusText
                    }
                } else if (id == taskSeq) {
                    revert()
                    if (userTyped) restoreInput(question)
                    statusTv.setTextColor(pal.text3)
                    statusTv.text = "已停止"
                }
                throw e
            } catch (e: Exception) {
                stopRenderTimer()
                val msg = AiClient.friendlyError(e)
                if (replySb.isNotEmpty()) {
                    // 已经生成了一部分：保留下来，而不是整体丢弃
                    updateStreamingBubble(replySb.toString())
                    finalizeStreamingBubble(replySb.toString())
                    commit()
                    session.statusText = "${scopeNow.label} · 已中断"
                    persist()
                    statusTv.setTextColor(pal.danger)
                    statusTv.text = "连接中断: $msg (已保留已生成内容)"
                } else {
                    revert()
                    if (userTyped) restoreInput(question)
                    statusTv.setTextColor(pal.danger)
                    statusTv.text = "请求失败: $msg (原有内容已保留)"
                }
            } finally {
                if (client != null && currentClient === client) {
                    currentClient = null
                }
            }
        }
    }

    /**
     * 自定义范围选择弹窗（纯原生 Dialog，绝不调用 AppCompat 避免宿主缺资源闪退）
     */
    private fun showScopeSelectionDialog() {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val pickDialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        pickDialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val outer = FrameLayout(activity).apply {
            setBackgroundColor(dimOverlayColor)
            setOnClickListener { pickDialog.dismiss() }
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(pal.surface)
                cornerRadius = dp(18).toFloat()
            }
            setPadding(dp(20), dp(18), dp(20), dp(16))
            setOnClickListener { /* 拦截点击 */ }
        }

        val cardLp = FrameLayout.LayoutParams(
            (activity.resources.displayMetrics.widthPixels * 0.86).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            topMargin = dp(24)
            bottomMargin = dp(24)
        }
        // 7 个选项在横屏 / 小屏下会超出屏幕，放进 ScrollView
        val scopeScroll = ScrollView(activity)
        scopeScroll.addView(card)
        outer.addView(scopeScroll, cardLp)

        val titleTv = TextView(activity).apply {
            text = "上下文范围"
            textSize = 17f
            setTextColor(pal.text)
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            }
        }
        card.addView(titleTv)

        val scopes = XiaoheihePostExtractor.SummaryScope.values()
        for (scope in scopes) {
            val isSelected = (scope == currentScope)

            val itemLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    if (isSelected) {
                        setColor(pal.selectedBg)
                        cornerRadius = dp(8).toFloat()
                        setStroke(dp(1), pal.selectedStroke)
                    } else {
                        setColor(if (pal.dark) 0xFF202125.toInt() else 0xFFF5F6F8.toInt())
                        cornerRadius = dp(8).toFloat()
                    }
                }
                setPadding(dp(14), dp(10), dp(14), dp(10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
                setOnClickListener {
                    if (scope != currentScope) {
                        // 切换范围不重建对话：会话保留，仅影响下一条请求的上下文楼层数
                        currentScope = scope
                        scopeBtn.text = scopeLabel(scope) + " ▾"
                        statusTv.setTextColor(pal.text3)
                        statusTv.text = "已切换为 ${scope.label}，可点击下方指令重新分析"
                    }
                    pickDialog.dismiss()
                }
            }

            itemLayout.withRipple(8)
            val textBox = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val mainTitle = TextView(activity).apply {
                text = scope.label
                textSize = 14f
                setTextColor(if (isSelected) pal.selectedText else pal.text)
                paint.isFakeBoldText = isSelected
            }
            textBox.addView(mainTitle)

            if (scope == XiaoheihePostExtractor.SummaryScope.ALL_LOADED && currentData.postTotalComments > 0) {
                val subTitle = TextView(activity).apply {
                    text = "全帖共 ${currentData.postTotalComments} 条讨论"
                    textSize = 11.5f
                    setTextColor(if (isSelected) pal.selectedText else pal.text2)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(2)
                    }
                }
                textBox.addView(subTitle)
            }

            itemLayout.addView(textBox)

            val checkMark = TextView(activity).apply {
                text = if (isSelected) "✓" else ""
                textSize = 16f
                setTextColor(pal.selectedText)
                paint.isFakeBoldText = true
            }
            itemLayout.addView(checkMark)

            card.addView(itemLayout)
        }

        val cancelBtn = TextView(activity).apply {
            text = "取消"
            textSize = 14f
            setTextColor(pal.text2)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(4))
            setOnClickListener { pickDialog.dismiss() }
        }
        card.addView(cancelBtn)

        pickDialog.setContentView(outer)
        showChildDialog(pickDialog, outer)
    }

    data class PresetProvider(val name: String, val baseUrl: String, val defaultModel: String)

    /**
     * 全新一体式原生 AI 设置面板（在小黑盒内即可完成全部配置与连通测试，告别切出 App）
     */
    private fun showAiSettingsDialog() {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val cfgDialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        cfgDialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val outer = FrameLayout(activity).apply {
            setBackgroundColor(dimOverlayColor)
            setOnClickListener { cfgDialog.dismiss() }
        }

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(pal.surface)
                cornerRadius = dp(20).toFloat()
            }
            setPadding(dp(20), dp(18), dp(20), dp(20))
            setOnClickListener { /* 拦截点击 */ }
        }

        val cardLp = FrameLayout.LayoutParams(
            (activity.resources.displayMetrics.widthPixels * 0.92).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            topMargin = dp(24)
            bottomMargin = dp(24)
        }
        scroll.addView(card)
        outer.addView(scroll, cardLp)

        // 1. 顶部标题栏 + 关闭按钮
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }
        val titleBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleTv = TextView(activity).apply {
            text = "AI 模型与服务设置"
            textSize = 17f
            setTextColor(pal.text)
            paint.isFakeBoldText = true
        }
        val subTitleTv = TextView(activity).apply {
            text = "配置 API 密钥后即可直接使用"
            textSize = 11f
            setTextColor(pal.text3)
            setPadding(0, dp(2), 0, 0)
        }
        titleBox.addView(titleTv)
        titleBox.addView(subTitleTv)
        header.addView(titleBox)

        val closeBtn = closeIcon { cfgDialog.dismiss() }
        header.addView(closeBtn)
        card.addView(header)

        // 2. 供应商预设选择 (横向水平滑动)
        val providerTitle = TextView(activity).apply {
            text = "快捷服务商"
            textSize = 13f
            setTextColor(pal.text)
            paint.isFakeBoldText = true
            setPadding(0, dp(4), 0, dp(6))
        }
        card.addView(providerTitle)

        val providers = listOf(
            PresetProvider("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
            PresetProvider("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
            PresetProvider("OpenRouter", "https://openrouter.ai/api/v1", "deepseek/deepseek-chat"),
            PresetProvider("硅基流动", "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V3"),
            PresetProvider("Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-32k"),
            PresetProvider("智谱", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
            PresetProvider("自定义", "", "")
        )

        val providerHScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }
        val providerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        providerHScroll.addView(providerRow)
        card.addView(providerHScroll)

        // 输入框变量
        val baseUrlEt = EditText(activity).apply { setTextColor(pal.text); setHintTextColor(pal.text3) }
        val modelEt = EditText(activity).apply { setTextColor(pal.text); setHintTextColor(pal.text3) }
        val keyEt = EditText(activity).apply {
            hint = "sk-... (必填)"
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            textSize = 13f
            setTextColor(pal.text)
            setHintTextColor(pal.text3)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = GradientDrawable().apply {
                setColor(pal.inputBg)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), pal.stroke)
            }
            setPadding(dp(12), dp(10), dp(44), dp(10))
        }

        // 独立存储映射与当前选中供应商
        val providerKeyMap = mutableMapOf<String, String>()
        val providerUrlMap = mutableMapOf<String, String>()
        val providerModelMap = mutableMapOf<String, String>()

        for (p in providers) {
            val savedKey = AiConfig.getProviderApiKey(activity, p.name)
            if (savedKey.isNotBlank()) providerKeyMap[p.name] = savedKey

            val savedUrl = AiConfig.getProviderBaseUrl(activity, p.name, p.baseUrl)
            providerUrlMap[p.name] = savedUrl

            val savedModel = AiConfig.getProviderModel(activity, p.name, p.defaultModel)
            providerModelMap[p.name] = savedModel
        }

        // 以“已保存的当前服务商 + 地址吻合”为准，其次才按默认地址猜；
        // 原先只按默认地址匹配，改过地址的服务商会被误判成“自定义”并串用 Key
        val savedProviderName = AiConfig.getCurrentProvider(activity)
        fun effectiveUrl(p: PresetProvider) = providerUrlMap[p.name] ?: p.baseUrl
        val matchedPreset = providers.firstOrNull { it.name == savedProviderName && effectiveUrl(it) == config.baseUrl }
            ?: providers.firstOrNull { it.baseUrl.isNotBlank() && effectiveUrl(it) == config.baseUrl }
            ?: providers.firstOrNull { it.baseUrl.isNotBlank() && it.baseUrl == config.baseUrl }
            ?: providers.last()
        var currentProvider = matchedPreset

        if (config.apiKey.isNotBlank() && providerKeyMap[currentProvider.name].isNullOrBlank()) {
            providerKeyMap[currentProvider.name] = config.apiKey
        }
        keyEt.setText(providerKeyMap[currentProvider.name] ?: "")
        baseUrlEt.setText(providerUrlMap[currentProvider.name] ?: config.baseUrl)
        modelEt.setText(providerModelMap[currentProvider.name] ?: config.model)

        val chipViews = mutableListOf<TextView>()
        fun selectProvider(p: PresetProvider, isInitial: Boolean = false) {
            if (!isInitial) {
                providerKeyMap[currentProvider.name] = keyEt.text.toString().trim()
                providerUrlMap[currentProvider.name] = baseUrlEt.text.toString().trim()
                providerModelMap[currentProvider.name] = modelEt.text.toString().trim()
            }
            currentProvider = p

            for (tv in chipViews) {
                val isCurrent = (tv.text == p.name)
                tv.background = GradientDrawable().apply {
                    if (isCurrent) {
                        setColor(pal.selectedBg)
                        cornerRadius = dp(8).toFloat()
                        setStroke(dp(1), pal.selectedStroke)
                    } else {
                        setColor(pal.chipBg)
                        cornerRadius = dp(8).toFloat()
                    }
                }
                tv.setTextColor(if (isCurrent) pal.selectedText else pal.chipText)
            }

            if (!isInitial) {
                val targetUrl = providerUrlMap[p.name] ?: p.baseUrl
                val targetModel = providerModelMap[p.name] ?: p.defaultModel
                val targetKey = providerKeyMap[p.name] ?: ""

                baseUrlEt.setText(targetUrl)
                modelEt.setText(targetModel)
                keyEt.setText(targetKey)
                keyEt.setSelection(keyEt.text.length)
            }
        }

        for (p in providers) {
            val chip = TextView(activity).apply {
                text = p.name
                textSize = 12f
                paint.isFakeBoldText = true
                setPadding(dp(12), dp(6), dp(12), dp(6))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(8)
                }
                setOnClickListener { selectProvider(p) }
            }
            chipViews.add(chip)
            providerRow.addView(chip)
        }

        // 3. API Key 输入区域（带一键粘贴与明暗文切换）
        val keyLabelRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val keyLabel = TextView(activity).apply {
            text = "API Key"
            textSize = 13f
            setTextColor(pal.text)
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        keyLabelRow.addView(keyLabel)

        val pasteBtn = TextView(activity).apply {
            text = "粘贴"
            textSize = 12f
            setTextColor(pal.linkBlue)
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        keyLabelRow.addView(pasteBtn)
        card.addView(keyLabelRow)

        val keyBox = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }
        keyBox.addView(keyEt, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        var isPasswordVisible = false
        val eyeBtn = TextView(activity).apply {
            text = "显示"
            textSize = 12f
            setTextColor(pal.text2)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(10), dp(8))
            setOnClickListener {
                isPasswordVisible = !isPasswordVisible
                text = if (isPasswordVisible) "隐藏" else "显示"
                if (isPasswordVisible) {
                    keyEt.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                } else {
                    keyEt.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                keyEt.setSelection(keyEt.text.length)
            }
        }
        val eyeLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            rightMargin = dp(4)
        }
        keyBox.addView(eyeBtn, eyeLp)
        card.addView(keyBox)

        pasteBtn.setOnClickListener {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString()?.trim() ?: ""
                if (text.isNotBlank()) {
                    keyEt.setText(text)
                    keyEt.setSelection(text.length)
                    Toast.makeText(activity, "已粘贴剪贴板内容", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 4. API Base URL
        val urlLabel = TextView(activity).apply {
            text = "接口地址 (Base URL)"
            textSize = 13f
            setTextColor(pal.text)
            paint.isFakeBoldText = true
            setPadding(0, dp(2), 0, dp(4))
        }
        card.addView(urlLabel)

        baseUrlEt.apply {
            hint = "https://api.deepseek.com/v1"
            textSize = 13f
            setText(config.baseUrl)
            background = GradientDrawable().apply {
                setColor(pal.inputBg)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), pal.stroke)
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }
        card.addView(baseUrlEt)

        // 5. 模型名称 (Model ID) + 在线拉取
        val modelLabelRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(4))
        }
        val modelLabel = TextView(activity).apply {
            text = "模型名称 (Model)"
            textSize = 13f
            setTextColor(pal.text)
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        modelLabelRow.addView(modelLabel)

        val fetchModelsBtn = TextView(activity).apply {
            text = "拉取模型"
            textSize = 12f
            setTextColor(pal.linkBlue)
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        modelLabelRow.addView(fetchModelsBtn)
        card.addView(modelLabelRow)

        modelEt.apply {
            hint = "deepseek-chat"
            textSize = 13f
            setText(config.model)
            background = GradientDrawable().apply {
                setColor(pal.inputBg)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), pal.stroke)
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }
        card.addView(modelEt)

        // 6. 全帖最大抓取楼层数（药丸单选）
        val floorLabel = TextView(activity).apply {
            text = "全帖最多分析楼层 (主评论)"
            textSize = 13f
            setTextColor(pal.text)
            paint.isFakeBoldText = true
            setPadding(0, dp(2), 0, dp(6))
        }
        card.addView(floorLabel)

        var selectedMaxFloors = config.maxFloors
        val floorRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            }
        }
        val floorOptions = listOf(100, 200, 300, 500)
        val floorChipViews = mutableListOf<TextView>()

        fun updateFloorChips() {
            for ((idx, fVal) in floorOptions.withIndex()) {
                val tv = floorChipViews[idx]
                val isSelected = (selectedMaxFloors == fVal)
                tv.background = GradientDrawable().apply {
                    if (isSelected) {
                        setColor(pal.selectedBg)
                        cornerRadius = dp(8).toFloat()
                        setStroke(dp(1), pal.selectedStroke)
                    } else {
                        setColor(if (pal.dark) 0xFF202125.toInt() else 0xFFF5F6F8.toInt())
                        cornerRadius = dp(8).toFloat()
                    }
                }
                tv.setTextColor(if (isSelected) pal.selectedText else pal.chipText)
            }
        }

        for (fVal in floorOptions) {
            val fChip = TextView(activity).apply {
                text = "$fVal 楼"
                textSize = 12f
                gravity = Gravity.CENTER
                paint.isFakeBoldText = true
                setPadding(dp(10), dp(6), dp(10), dp(6))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (fVal != floorOptions.last()) rightMargin = dp(6)
                }
                setOnClickListener {
                    selectedMaxFloors = fVal
                    updateFloorChips()
                }
            }
            floorChipViews.add(fChip)
            floorRow.addView(fChip)
        }
        updateFloorChips()
        card.addView(floorRow)

        // 7. 测试状态栏
        val testStatusTv = TextView(activity).apply {
            text = ""
            textSize = 11f
            setTextColor(pal.text2)
            setPadding(0, 0, 0, dp(8))
            visibility = View.GONE
        }
        card.addView(testStatusTv)

        // 8. 底部操作按钮栏
        val bottomRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val testBtn = TextView(activity).apply {
            text = "测试连接"
            textSize = 13f
            setTextColor(pal.text)
            paint.isFakeBoldText = true
            background = GradientDrawable().apply {
                setColor(if (pal.dark) 0xFF202125.toInt() else 0xFFF5F6F8.toInt())
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), pal.stroke)
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        bottomRow.addView(testBtn)

        val spacer = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }
        bottomRow.addView(spacer)

        val saveBtn = TextView(activity).apply {
            text = "保存并生效"
            textSize = 13.5f
            setTextColor(pal.primaryBtnText)
            paint.isFakeBoldText = true
            background = GradientDrawable().apply {
                setColor(pal.primaryBtnBg)
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(18), dp(9), dp(18), dp(9))
        }
        bottomRow.addView(saveBtn)
        card.addView(bottomRow)

        // 初始化预设高亮
        selectProvider(currentProvider, isInitial = true)
        baseUrlEt.setText(config.baseUrl)
        modelEt.setText(config.model)

        // 事件逻辑：拉取模型
        fetchModelsBtn.setOnClickListener {
            if (fetchModelsBtn.text == "拉取中...") return@setOnClickListener
            val key = keyEt.text.toString().trim()
            val url = baseUrlEt.text.toString().trim()
            if (key.isBlank() || url.isBlank()) {
                Toast.makeText(activity, "请先填写 API Key 和接口地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            fetchModelsBtn.text = "拉取中..."
            scope.launch {
                val client = AiClient(url, key, "")
                val res = withContext(Dispatchers.IO) { client.fetchModels() }
                fetchModelsBtn.text = "拉取模型"
                res.onSuccess { list ->
                    if (list.isEmpty()) {
                        Toast.makeText(activity, "未获取到模型", Toast.LENGTH_SHORT).show()
                        return@onSuccess
                    }
                    showModelPickerDialog(currentProvider.name, list) { picked ->
                        modelEt.setText(picked)
                    }
                }.onFailure { e ->
                    Toast.makeText(activity, "获取失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 事件逻辑：测试连接
        testBtn.setOnClickListener {
            val key = keyEt.text.toString().trim()
            val url = baseUrlEt.text.toString().trim()
            val model = modelEt.text.toString().trim()
            if (key.isBlank() || url.isBlank()) {
                Toast.makeText(activity, "请先填写 API Key 和接口地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            testStatusTv.visibility = View.VISIBLE
            testStatusTv.text = "正在连通性测试..."
            testStatusTv.setTextColor(pal.text3)

            testBtn.isEnabled = false
            testBtn.alpha = 0.5f
            scope.launch {
                val client = AiClient(url, key, model)
                val result = try {
                    withContext(Dispatchers.IO) { client.testConnection() }
                } finally {
                    testBtn.isEnabled = true
                    testBtn.alpha = 1f
                }
                if (result.success) {
                    testStatusTv.text = "连接成功 (响应延迟: ${result.latencyMs}ms)"
                    testStatusTv.setTextColor(pal.success)
                } else {
                    testStatusTv.text = "测试失败: ${result.message}"
                    testStatusTv.setTextColor(pal.danger)
                }
            }
        }

        // 事件逻辑：保存
        saveBtn.setOnClickListener {
            val key = keyEt.text.toString().trim()
            var url = baseUrlEt.text.toString().trim()
            val model = modelEt.text.toString().trim()

            if (url.isNotBlank() && !url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://$url"
            }

            // 1. 同步当前选中服务商的 Key、URL、Model，并独立持久化所有供应商配置
            providerKeyMap[currentProvider.name] = key
            providerUrlMap[currentProvider.name] = url
            providerModelMap[currentProvider.name] = model

            for ((pName, pKey) in providerKeyMap) {
                AiConfig.saveProviderApiKey(activity, pName, pKey)
            }
            for ((pName, pUrl) in providerUrlMap) {
                AiConfig.saveProviderBaseUrl(activity, pName, pUrl)
            }
            for ((pName, pModel) in providerModelMap) {
                AiConfig.saveProviderModel(activity, pName, pModel)
            }
            AiConfig.saveCurrentProvider(activity, currentProvider.name)

            if (model.isNotBlank()) {
                AiConfig.addFavoriteModel(activity, model)
            }

            // 2. 保存全局配置
            AiConfig.saveApiKeyLocally(activity, key, url, model, selectedMaxFloors)
            // 更新当前上下文 config
            config = config.copy(
                apiKey = key,
                maxFloors = selectedMaxFloors,
                baseUrl = if (url.isNotBlank()) url else config.baseUrl,
                model = if (model.isNotBlank()) model else config.model
            )
            scopeBtn.text = scopeLabel(currentScope) + " ▾"

            modelSubtitleTv.text = (if (config.model.isNotBlank()) config.model else "选择模型") + " ▾"
            Toast.makeText(activity, "设置已保存", Toast.LENGTH_SHORT).show()
            cfgDialog.dismiss()
            // 不再清空当前已生成的总结/对话；仅在还没有内容时刷新就绪页
            if (currentSession.messages.isEmpty()) showWelcomeState()
        }

        cfgDialog.setContentView(outer)
        showChildDialog(cfgDialog, outer, softInput = true)
    }

    /**
     * 复选框打勾多选启用模型弹窗（对标专业中转站助手：拉取后打勾保存启用）
     */
    private fun showModelPickerDialog(providerName: String, models: List<String>, onPick: (String) -> Unit) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val outer = FrameLayout(activity).apply {
            setBackgroundColor(dimOverlayColor)
            setOnClickListener { dialog.dismiss() }
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(pal.surface)
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(18), dp(16), dp(18), dp(16))
            setOnClickListener { /* 拦截点击 */ }
        }

        val cardLp = FrameLayout.LayoutParams(
            (activity.resources.displayMetrics.widthPixels * 0.90).toInt(),
            (activity.resources.displayMetrics.heightPixels * 0.72).toInt()
        ).apply {
            gravity = Gravity.CENTER
        }
        outer.addView(card, cardLp)

        // 顶栏：标题 + 关闭
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            }
        }
        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleTv = TextView(activity).apply {
            text = "选择启用模型 (${models.size} 个)"
            textSize = 15.5f
            setTextColor(pal.text)
            paint.isFakeBoldText = true
        }
        val subtitleTv = TextView(activity).apply {
            textSize = 11.5f
            setTextColor(pal.text3)
            setPadding(0, dp(2), 0, 0)
        }
        titleCol.addView(titleTv)
        titleCol.addView(subtitleTv)
        headerRow.addView(titleCol)

        val closeBtn = closeIcon { dialog.dismiss() }
        headerRow.addView(closeBtn)
        card.addView(headerRow)

        // 搜索框（快速筛选模型）
        val searchEt = EditText(activity).apply {
            hint = "搜索模型 (如 claude, 4o, deepseek)..."
            setSingleLine(true)
            textSize = 12.5f
            setTextColor(pal.text)
            setHintTextColor(pal.text3)
            background = GradientDrawable().apply {
                setColor(pal.inputBg)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), pal.stroke)
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
        }
        card.addView(searchEt)

        // 读取当前服务商已勾选启用的模型集合
        val savedEnabled = AiConfig.getEnabledModelsForProvider(activity, providerName)
        val enabledSet = savedEnabled.toMutableSet()
        // 若此前从未保存过启用列表，默认将当前输入框的模型（若在列表中）打勾
        if (enabledSet.isEmpty() && config.model.isNotBlank() && models.contains(config.model)) {
            enabledSet.add(config.model)
        }

        fun updateSubtitle() {
            subtitleTv.text = "已启用 ${enabledSet.size} 个常用模型 (主界面可快速切换)"
        }
        updateSubtitle()

        // 快捷操作工具条：全选 / 清空勾选
        val toolBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }
        val selectAllBtn = TextView(activity).apply {
            text = "全选"
            textSize = 11.5f
            setTextColor(pal.linkBlue)
            setPadding(0, dp(2), dp(12), dp(2))
        }
        val clearAllBtn = TextView(activity).apply {
            text = "清空勾选"
            textSize = 11.5f
            setTextColor(pal.text3)
            setPadding(0, dp(2), dp(12), dp(2))
        }
        toolBar.addView(selectAllBtn)
        toolBar.addView(clearAllBtn)
        card.addView(toolBar)

        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                bottomMargin = dp(8)
            }
        }
        val listLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(listLayout)
        card.addView(scroll)

        fun renderModels(filterText: String = "") {
            listLayout.removeAllViews()
            val filtered = if (filterText.isBlank()) {
                models
            } else {
                models.filter { it.contains(filterText.trim(), ignoreCase = true) }
            }

            titleTv.text = if (filterText.isBlank()) {
                "选择启用模型 (${models.size} 个)"
            } else {
                "搜索结果 (${filtered.size} / ${models.size} 个)"
            }

            if (filtered.isEmpty()) {
                listLayout.addView(TextView(activity).apply {
                    text = "无匹配模型"
                    textSize = 12f
                    setTextColor(pal.text3)
                    setPadding(0, dp(16), 0, 0)
                })
                return
            }

            // OpenRouter 等有几百个模型，每次按键全量建视图会卡；只渲染前 80 项
            for (m in filtered.take(80)) {
                val isChecked = enabledSet.contains(m)
                val itemRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(if (isChecked) pal.selectedBg else pal.chipBg, 8, if (isChecked) pal.selectedStroke else null)
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(5)
                    }
                }

                // ☑ ☐ 在部分机型字体里会被渲染成彩色 Emoji，改为自绘
                fun boxDrawable(on: Boolean) = GlyphDrawable(
                    if (on) GlyphDrawable.Kind.CHECK_BOX_ON else GlyphDrawable.Kind.CHECK_BOX_OFF,
                    if (on) pal.selectedText else pal.text3,
                    Color.WHITE
                )
                val checkIcon = ImageView(activity).apply {
                    setImageDrawable(boxDrawable(isChecked))
                    layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(10) }
                }
                itemRow.addView(checkIcon)

                val itemTv = TextView(activity).apply {
                    text = m
                    textSize = 12.5f
                    setTextColor(if (isChecked) pal.selectedText else pal.text)
                    paint.isFakeBoldText = isChecked
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                itemRow.addView(itemTv)

                itemRow.setOnClickListener {
                    if (enabledSet.contains(m)) {
                        enabledSet.remove(m)
                        checkIcon.setImageDrawable(boxDrawable(false))
                        itemTv.setTextColor(pal.text)
                        itemTv.paint.isFakeBoldText = false
                        itemRow.background = rounded(pal.chipBg, 8)
                    } else {
                        enabledSet.add(m)
                        checkIcon.setImageDrawable(boxDrawable(true))
                        itemTv.setTextColor(pal.selectedText)
                        itemTv.paint.isFakeBoldText = true
                        itemRow.background = rounded(pal.selectedBg, 8, pal.selectedStroke)
                    }
                    updateSubtitle()
                }

                listLayout.addView(itemRow)
            }
            if (filtered.size > 80) {
                listLayout.addView(TextView(activity).apply {
                    text = "仅显示前 80 项，输入关键字缩小范围"
                    textSize = 11.5f
                    setTextColor(pal.text3)
                    setPadding(0, dp(8), 0, dp(4))
                })
            }
        }

        selectAllBtn.setOnClickListener {
            val filterText = searchEt.text.toString().trim()
            val targets = if (filterText.isBlank()) models else models.filter { it.contains(filterText, ignoreCase = true) }
            enabledSet.addAll(targets)
            updateSubtitle()
            renderModels(filterText)
        }

        clearAllBtn.setOnClickListener {
            val filterText = searchEt.text.toString().trim()
            if (filterText.isBlank()) {
                enabledSet.clear()
            } else {
                enabledSet.removeAll(models.filter { it.contains(filterText, ignoreCase = true) }.toSet())
            }
            updateSubtitle()
            renderModels(filterText)
        }

        renderModels()

        searchEt.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderModels(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // 底部保存与取消按钮
        val bottomBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            }
        }
        val cancelBtn = TextView(activity).apply {
            text = "取消"
            textSize = 13f
            setTextColor(pal.text2)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { dialog.dismiss() }
        }
        bottomBar.addView(cancelBtn)

        bottomBar.addView(View(activity).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) })

        val saveBtn = TextView(activity).apply {
            text = "保存启用模型"
            textSize = 13f
            setTextColor(pal.primaryBtnText)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            background = rounded(pal.primaryBtnBg, 8)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener {
                AiConfig.saveEnabledModelsForProvider(activity, providerName, enabledSet.toList())
                if (enabledSet.isNotEmpty()) {
                    val first = if (enabledSet.contains(config.model)) config.model else enabledSet.first()
                    onPick(first)
                }
                Toast.makeText(activity, "已为 $providerName 保存 ${enabledSet.size} 个常用模型", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        bottomBar.addView(saveBtn)
        card.addView(bottomBar)

        dialog.setContentView(outer)
        showChildDialog(dialog, outer)
    }

    /**
     * 左上角胶囊快速切换模型浮层：
     * 1. 优先展示已勾选启用的模型（中转站玩家秒切常用模型）
     * 2. 快捷服务商切换（DeepSeek, OpenAI, OpenRouter, 硅基流动, Kimi, 智谱, 自定义）
     * 3. 底部详细设置入口
     */
    private fun showQuickModelSwitchDialog() {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val switchDialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        switchDialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val outer = FrameLayout(activity).apply {
            setBackgroundColor(dimOverlayColor)
            setOnClickListener { switchDialog.dismiss() }
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(pal.surface)
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(18), dp(16), dp(18), dp(14))
            setOnClickListener { /* 拦截点击 */ }
        }

        val cardLp = FrameLayout.LayoutParams(
            (activity.resources.displayMetrics.widthPixels * 0.88).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        outer.addView(card, cardLp)

        var activeProvider = AiConfig.getCurrentProvider(activity)

        // 顶栏：标题与当前供应商提示
        val topTitleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        val topTitleTv = TextView(activity).apply {
            text = "切换模型"
            textSize = 16f
            setTextColor(pal.text)
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val providerBadge = TextView(activity).apply {
            text = activeProvider
            textSize = 11.5f
            setTextColor(pal.linkBlue)
            background = rounded(pal.chipBg, 6, pal.chipStroke)
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        topTitleRow.addView(topTitleTv)
        topTitleRow.addView(providerBadge)
        card.addView(topTitleRow)

        // 1. 中部动态模型容器（随供应商切换即时重绘，弹窗保持开启）
        val modelSectionContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        card.addView(modelSectionContainer)

        // 2. 快捷服务商切换
        val providerLabel = TextView(activity).apply {
            text = "切换服务商"
            textSize = 12f
            setTextColor(pal.text2)
            paint.isFakeBoldText = true
            setPadding(0, dp(2), 0, dp(6))
        }
        card.addView(providerLabel)

        val presetList = listOf(
            PresetProvider("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
            PresetProvider("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
            PresetProvider("OpenRouter", "https://openrouter.ai/api/v1", "deepseek/deepseek-chat"),
            PresetProvider("硅基流动", "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V3"),
            PresetProvider("Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-32k"),
            PresetProvider("智谱", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
            PresetProvider("自定义", "", "")
        )

        val providerScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }
        val providerRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        providerScroll.addView(providerRow)
        card.addView(providerScroll)

        val providerChipViews = mutableMapOf<String, TextView>()

        fun renderModelSection() {
            providerBadge.text = activeProvider
            modelSectionContainer.removeAllViews()

            val enabledModels = AiConfig.getEnabledModelsForProvider(activity, activeProvider).toMutableList()
            if (enabledModels.isNotEmpty()) {
                val listLabel = TextView(activity).apply {
                    text = "已启用模型 (${enabledModels.size} 个)"
                    textSize = 12f
                    setTextColor(pal.text2)
                    paint.isFakeBoldText = true
                    setPadding(0, dp(2), 0, dp(6))
                }
                modelSectionContainer.addView(listLabel)

                val scroll = ScrollView(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)).apply {
                        bottomMargin = dp(10)
                    }
                }
                val listLayout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                scroll.addView(listLayout)
                modelSectionContainer.addView(scroll)

                for (m in enabledModels) {
                    val isCurrent = (m == config.model)
                    val row = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = rounded(if (isCurrent) pal.selectedBg else pal.chipBg, 8, if (isCurrent) pal.selectedStroke else null)
                        setPadding(dp(12), dp(8), dp(10), dp(8))
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            bottomMargin = dp(6)
                        }
                        setOnClickListener {
                            config = config.copy(model = m)
                            AiConfig.saveApiKeyLocally(activity, apiKey = config.apiKey, model = m)
                            AiConfig.saveProviderModel(activity, activeProvider, m)
                            modelSubtitleTv.text = "$m ▾"
                            Toast.makeText(activity, "已切换模型: $m", Toast.LENGTH_SHORT).show()
                            switchDialog.dismiss()
                        }
                    }
                    val tv = TextView(activity).apply {
                        text = if (isCurrent) "✓ $m" else m
                        textSize = 13f
                        setTextColor(if (isCurrent) pal.selectedText else pal.text)
                        paint.isFakeBoldText = isCurrent
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    row.addView(tv)
                    listLayout.addView(row)
                }
            } else {
                val currentUsedModel = if (activeProvider == AiConfig.getCurrentProvider(activity) && config.model.isNotBlank()) config.model else AiConfig.getProviderModel(activity, activeProvider, "")
                val emptyBox = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = rounded(pal.chipBg, 8)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(12)
                    }
                }
                emptyBox.addView(TextView(activity).apply {
                    text = "当前使用: ${if (currentUsedModel.isNotBlank()) currentUsedModel else "未配置"}"
                    textSize = 13f
                    setTextColor(pal.text)
                    paint.isFakeBoldText = true
                })
                emptyBox.addView(TextView(activity).apply {
                    text = "尚未为 $activeProvider 勾选常用模型。可在设置中点击【拉取模型】添加常用模型，即可在此快速切换。"
                    textSize = 11.5f
                    setTextColor(pal.text2)
                    setPadding(0, dp(4), 0, 0)
                })
                modelSectionContainer.addView(emptyBox)
            }

            // 更新服务商药丸的高亮状态
            for ((pName, chipView) in providerChipViews) {
                val isSelected = (pName == activeProvider)
                chipView.background = rounded(if (isSelected) pal.selectedBg else pal.chipBg, 8, if (isSelected) pal.selectedStroke else null)
                chipView.setTextColor(if (isSelected) pal.selectedText else pal.chipText)
            }
        }

        for (p in presetList) {
            val pChip = TextView(activity).apply {
                text = p.name
                textSize = 12f
                paint.isFakeBoldText = true
                setPadding(dp(12), dp(6), dp(12), dp(6))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(6)
                }
                setOnClickListener {
                    if (activeProvider != p.name) {
                        // 切走之前把当前 Key 记到原服务商名下，切回来不会丢
                        if (config.apiKey.isNotBlank()) {
                            AiConfig.saveProviderApiKey(activity, activeProvider, config.apiKey)
                        }
                        activeProvider = p.name
                        val pUrl = AiConfig.getProviderBaseUrl(activity, p.name, p.baseUrl)
                        val pModel = AiConfig.getProviderModel(activity, p.name, p.defaultModel)
                        val pKey = AiConfig.getProviderApiKey(activity, p.name)
                        // 绝不回退到别家的 Key：否则会把 A 的密钥作为 Authorization 发给 B 的服务器
                        val targetKey = pKey
                        val targetUrl = if (pUrl.isNotBlank()) pUrl else config.baseUrl
                        val targetModel = if (pModel.isNotBlank()) pModel else config.model

                        config = config.copy(
                            apiKey = targetKey,
                            baseUrl = targetUrl,
                            model = targetModel
                        )
                        AiConfig.saveCurrentProvider(activity, p.name)
                        AiConfig.saveApiKeyLocally(activity, apiKey = targetKey, baseUrl = targetUrl, model = targetModel)
                        modelSubtitleTv.text = (if (targetModel.isNotBlank()) targetModel else "选择模型") + " ▾"

                        // 原地刷新弹窗内容，不关闭弹窗，用户可直接选择该服务商下的模型！
                        renderModelSection()
                        if (targetKey.isBlank()) {
                            Toast.makeText(activity, "${p.name} 尚未配置 API Key，请在详细设置中填写", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            providerChipViews[p.name] = pChip
            providerRow.addView(pChip)
        }

        renderModelSection()

        // 3. 详细设置入口
        val detailSettingBtn = TextView(activity).apply {
            text = "服务商与 API 详细设置"
            textSize = 13f
            setTextColor(pal.linkBlue)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(4))
            setOnClickListener {
                switchDialog.dismiss()
                showAiSettingsDialog()
            }
        }
        card.addView(detailSettingBtn)

        switchDialog.setContentView(outer)
        showChildDialog(switchDialog, outer)
    }

    private fun clearSession() {
        invalidateTask()
        sessionCache.remove(linkId)
        SummaryHistoryManager.deleteHistory(activity, linkId)
        showWelcomeState()
        Toast.makeText(activity, "已清空本帖会话与本地记录", Toast.LENGTH_SHORT).show()
    }

    private fun showClearConfirmDialog() {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val d = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val outer = FrameLayout(activity).apply {
            setBackgroundColor(dimOverlayColor)
            setOnClickListener { d.dismiss() }
        }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(pal.surface, 16)
            setPadding(dp(20), dp(18), dp(20), dp(16))
            setOnClickListener { }
        }
        outer.addView(card, FrameLayout.LayoutParams(
            (activity.resources.displayMetrics.widthPixels * 0.82).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })

        card.addView(TextView(activity).apply {
            text = "清空对话记录"
            textSize = 16f
            setTextColor(pal.text)
            paint.isFakeBoldText = true
            setPadding(0, 0, 0, dp(8))
        })
        card.addView(TextView(activity).apply {
            text = "确定要清空本帖的所有对话记录和历史缓存吗？"
            textSize = 13.5f
            setTextColor(pal.text2)
            setPadding(0, 0, 0, dp(16))
        })

        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val cancelTv = TextView(activity).apply {
            text = "取消"
            textSize = 13.5f
            setTextColor(pal.text2)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { d.dismiss() }
        }
        val confirmTv = TextView(activity).apply {
            text = "清空"
            textSize = 13.5f
            setTextColor(pal.danger)
            paint.isFakeBoldText = true
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                d.dismiss()
                clearSession()
            }
        }
        btnRow.addView(cancelTv)
        btnRow.addView(confirmTv)
        card.addView(btnRow)

        d.setContentView(outer)
        showChildDialog(d, outer)
    }

    private fun handleSend() {
        if (generating) {
            stopTask()
            return
        }
        val q = inputEt.text.toString().trim()
        if (q.isBlank()) return
        inputEt.setText("")
        sendFollowup(q, userTyped = true)
    }

    /** 顶栏文字按钮：保证 ≥44dp 宽、36dp 高的可点击区域 */
    private fun textButton(label: String, color: Int, onClick: () -> Unit): TextView =
        TextView(activity).apply {
            text = label
            textSize = 13f
            setTextColor(color)
            gravity = Gravity.CENTER
            minWidth = dp(44)
            minHeight = dp(36)
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { onClick() }
            withRipple(8)
        }

    private fun createView(): View {
        val outerContainer = FrameLayout(activity).apply {
            setBackgroundColor(dimOverlayColor)
            setOnClickListener { dismissSheet() }
        }
        rootView = outerContainer

        val sheetLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(pal.bg)
                cornerRadii = floatArrayOf(
                    dp(20).toFloat(), dp(20).toFloat(),
                    dp(20).toFloat(), dp(20).toFloat(),
                    0f, 0f, 0f, 0f
                )
            }
            setPadding(0, dp(10), 0, dp(12))
            setOnClickListener { /* 拦截点击 */ }
        }
        sheetView = sheetLayout

        // 边到边（宿主 targetSdk 35 / Android 15）下面板底部会被导航条或键盘盖住，按系统栏 + 键盘高度补底部内边距。
        // 非边到边时系统已消费 inset，这里读到 0，不会重复留白。
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            outerContainer.setOnApplyWindowInsetsListener { _, insets ->
                val bottom = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime()
                ).bottom
                sheetLayout.setPadding(0, dp(10), 0, dp(12) + bottom)
                insets
            }
        }

        // 顶部留出约 10% 屏高，面板覆盖近全屏（类 FluxDo 的阅读体验）；键盘弹出时窗口整体收缩
        val sheetLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply {
            gravity = Gravity.BOTTOM
            topMargin = (activity.resources.displayMetrics.heightPixels * 0.10).toInt()
        }
        outerContainer.addView(sheetLayout, sheetLp)

        // 顶部手柄（带手指下拉拖动顺滑拉走手势）
        var startY = 0f
        var isDragging = false
        val dragThreshold = dp(80)

        val handleContainer = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)).apply {
                bottomMargin = dp(4)
            }
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startY = event.rawY
                        isDragging = true
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isDragging) {
                            val dy = event.rawY - startY
                            sheetLayout.translationY = if (dy > 0) dy else 0f
                            // 遮罩随下拉同步变淡
                            val p = (dy / sheetLayout.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                            setDim((255 * (1f - p)).toInt())
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isDragging) {
                            isDragging = false
                            val dy = event.rawY - startY
                            if (dy > dragThreshold) {
                                dismissSheet()
                            } else {
                                sheetLayout.animate().translationY(0f).setDuration(150).start()
                                animateDim(rootView.background?.alpha ?: 255, 255, 150)
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        val handle = View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER
            }
            background = rounded(pal.handle, 2)
        }
        handleContainer.addView(handle)
        sheetLayout.addView(handleContainer)

        // 顶部主栏（单行左右两端对齐：左标题 + 范围选择胶囊，右操作项）
        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(16)
                rightMargin = dp(8)
                topMargin = dp(2)
                bottomMargin = dp(8)
            }
        }
        // 左侧占满剩余宽度：窄屏（360dp）下模型胶囊会被省略号截断，而不是把右侧“清空/设置”挤出屏幕
        val leftBox = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleTv = TextView(activity).apply {
            text = "AI 总结"
            setSingleLine(true)
            textSize = 17f
            setTextColor(pal.text)
            paint.isFakeBoldText = true
        }
        leftBox.addView(titleTv)

        scopeBtn = TextView(activity).apply {
            text = scopeLabel(currentScope) + " ▾"
            textSize = 12f
            setTextColor(pal.text)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            background = rounded(pal.chipBg, 8, pal.chipStroke)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(8)
            }
            setSingleLine(true)
            setOnClickListener { showScopeSelectionDialog() }
            withRipple(8)
        }
        leftBox.addView(scopeBtn)

        modelSubtitleTv = TextView(activity).apply {
            text = (if (config.model.isNotBlank()) config.model else "选择模型") + " ▾"
            textSize = 12f
            setTextColor(pal.text2)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            background = rounded(pal.chipBg, 8, pal.chipStroke)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(6)
            }
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(120)
            setOnClickListener { showQuickModelSwitchDialog() }
            withRipple(8)
        }
        leftBox.addView(modelSubtitleTv)
        titleRow.addView(leftBox)

        // 右侧操作项：沿用 textButton，保证 44x36dp 的点击区域（原先是无内边距的裸文字）
        val actionGroup = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actionGroup.addView(textButton("清空", pal.text2) { showClearConfirmDialog() })
        actionGroup.addView(textButton("设置", pal.text2) { showAiSettingsDialog() })
        titleRow.addView(actionGroup)
        sheetLayout.addView(titleRow)

        // 内容滚动后才出现的分隔线，让顶栏与内容有层次而不增加常驻线条
        headerDivider = View(activity).apply {
            setBackgroundColor(pal.stroke)
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        }
        sheetLayout.addView(headerDivider)

        // 中部消息展示区（小黑盒极简风格）
        scrollView = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                bottomMargin = dp(8)
            }
        }
        messageContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        scrollView.addView(messageContainer)
        scrollView.setOnScrollChangeListener { _, _, y, _, _ ->
            val target = if (y > 0) 1f else 0f
            if (headerDivider.alpha != target) headerDivider.animate().alpha(target).setDuration(120).start()
        }
        sheetLayout.addView(scrollView)

        // 状态信息（轻量优雅，提供实时加载与异常反馈）
        statusTv = TextView(activity).apply {
            text = "就绪 · ${scopeLabel(currentScope)}"
            textSize = 11.5f
            setTextColor(pal.text3)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(16)
                rightMargin = dp(16)
                bottomMargin = dp(6)
            }
        }
        sheetLayout.addView(statusTv)

        // 快捷指令横滑栏（占满弹窗全宽，左右内边距 16px，胶囊高度 32px，隐藏滚动条）
        val hScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(dp(16), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }
        val chipContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val shortcuts = listOf("总结这个话题", "极简省流", "核心观点", "争议焦点", "有什么值得关注的", "各方立场")
        for (sc in shortcuts) {
            val chip = TextView(activity).apply {
                text = sc
                textSize = 12f
                setTextColor(pal.chipText)
                gravity = Gravity.CENTER
                setSingleLine(true)
                background = rounded(pal.chipBg, 16, pal.chipStroke)
                setPadding(dp(12), 0, dp(12), 0)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply {
                    rightMargin = dp(8)
                }
                setOnClickListener { onChipClick(sc) }
                withRipple(16)
            }
            chipContainer.addView(chip)
        }
        hScroll.addView(chipContainer)
        sheetLayout.addView(hScroll)

        // 底部追问输入栏
        val inputBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            // 输入框长到多行时，发送按钮贴底而不是悬在中间
            gravity = Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(16)
                rightMargin = dp(16)
                bottomMargin = dp(6)
            }
        }

        inputEt = EditText(activity).apply {
            hint = "针对本帖内容提问..."
            textSize = 13.5f
            setTextColor(pal.text)
            setHintTextColor(pal.text3)
            background = rounded(pal.inputBg, 19, pal.inputStroke)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            gravity = Gravity.CENTER_VERTICAL
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION
            maxLines = 4
            maxHeight = dp(96)
            minimumHeight = dp(38)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        inputBar.addView(inputEt)

        // 右侧：36dp 圆形发送 / 停止按钮，图标自绘；输入为空时变淡
        sendBtn = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                leftMargin = dp(8)
                bottomMargin = dp(1)
            }
            setOnClickListener { handleSend() }
        }
        applyGeneratingState(false)
        inputEt.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { updateSendEnabled() }
        })
        inputBar.addView(sendBtn)
        sheetLayout.addView(inputBar)

        return outerContainer
    }
}

/**
 * 自绘极简线性图标。不依赖字体里的 ↑ ■ ✕ ☑ 等字形：
 * 部分机型会把它们渲染成彩色 Emoji，或因字体度量导致视觉不居中。
 */
private class GlyphDrawable(
    private val kind: Kind,
    private val color: Int,
    private val innerColor: Int = Color.WHITE
) : Drawable() {
    enum class Kind { ARROW_UP, STOP, CLOSE, CHECK_BOX_ON, CHECK_BOX_OFF }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val size = minOf(b.width(), b.height()).toFloat()
        if (size <= 0f) return
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val stroke = size * 0.11f
        paint.color = color
        paint.strokeWidth = stroke
        when (kind) {
            Kind.ARROW_UP -> {
                paint.style = Paint.Style.STROKE
                val h = size * 0.32f
                canvas.drawLine(cx, cy + h, cx, cy - h, paint)
                path.reset()
                path.moveTo(cx - h * 0.85f, cy - h + h * 0.85f)
                path.lineTo(cx, cy - h)
                path.lineTo(cx + h * 0.85f, cy - h + h * 0.85f)
                canvas.drawPath(path, paint)
            }
            Kind.STOP -> {
                paint.style = Paint.Style.FILL
                val q = size * 0.22f
                rect.set(cx - q, cy - q, cx + q, cy + q)
                canvas.drawRoundRect(rect, size * 0.05f, size * 0.05f, paint)
            }
            Kind.CLOSE -> {
                paint.style = Paint.Style.STROKE
                val q = size * 0.22f
                canvas.drawLine(cx - q, cy - q, cx + q, cy + q, paint)
                canvas.drawLine(cx - q, cy + q, cx + q, cy - q, paint)
            }
            Kind.CHECK_BOX_OFF, Kind.CHECK_BOX_ON -> {
                val half = size * 0.5f - stroke
                rect.set(cx - half, cy - half, cx + half, cy + half)
                val radius = size * 0.2f
                if (kind == Kind.CHECK_BOX_ON) {
                    paint.style = Paint.Style.FILL
                    canvas.drawRoundRect(rect, radius, radius, paint)
                    paint.style = Paint.Style.STROKE
                    paint.color = innerColor
                    paint.strokeWidth = stroke * 1.1f
                    path.reset()
                    path.moveTo(cx - size * 0.2f, cy + size * 0.01f)
                    path.lineTo(cx - size * 0.05f, cy + size * 0.15f)
                    path.lineTo(cx + size * 0.22f, cy - size * 0.14f)
                    canvas.drawPath(path, paint)
                } else {
                    paint.style = Paint.Style.STROKE
                    canvas.drawRoundRect(rect, radius, radius, paint)
                }
            }
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
