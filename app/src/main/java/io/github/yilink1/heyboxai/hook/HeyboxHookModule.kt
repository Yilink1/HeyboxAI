package io.github.yilink1.heyboxai.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import io.github.yilink1.heyboxai.ui.AiSummaryBottomSheet
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class HeyboxHookModule : XposedModule() {

    companion object {
        const val TAG = "HeyboxAI"
        const val TARGET_PKG = "com.max.xiaoheihe"
        val TARGET_ACTIVITIES = listOf(
            "com.max.xiaoheihe.module.bbs.post.ui.activitys.v2.BasePostPageActivityV2",
            "com.max.xiaoheihe.module.bbs.post.ui.activitys.v2.WebNewsPostPageActivityV2",
            "com.max.xiaoheihe.module.bbs.post.ui.activitys.v2.PicturePostPageActivityV2",
            "com.max.xiaoheihe.module.bbs.post.ui.activitys.NormalPostPageActivity"
        )
        const val BTN_TAG = "heybox_ai_floating_btn"
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)

        if (param.packageName != TARGET_PKG) {
            return
        }

        Log.i(TAG, "Xiaoheihe package ready, injecting hooks...")

        val classLoader = param.classLoader

        // 0. 安装跨版本稳定的 OkHttp 网络层数据嗅探器
        HeyboxNetworkSniffer.install(this, classLoader)

        // 1. 安装 ViewPager 测量异常防护盾（彻底阻断小黑盒自身 PagerAdapter 数量不同步导致的闪退）
        protectViewPager(classLoader)

        for (activityName in TARGET_ACTIVITIES) {
            try {
                val activityClass = classLoader.loadClass(activityName)
                val onResumeMethod = activityClass.getMethod("onResume")

                hook(onResumeMethod)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .intercept { chain ->
                        val result = chain.proceed()
                        try {
                            val activity = chain.thisObject as? Activity
                            activity?.let { act ->
                                Handler(Looper.getMainLooper()).post {
                                    injectAiButton(act)
                                }
                            }
                        } catch (e: Throwable) {
                            Log.e(TAG, "Error in onResume post-processing", e)
                        }
                        result
                    }

                // 2. 监听日夜间模式等配置改变，动态重新渲染 AI 按钮与配色
                try {
                    val onConfigMethod = activityClass.getMethod("onConfigurationChanged", Configuration::class.java)
                    hook(onConfigMethod)
                        .setPriority(XposedInterface.PRIORITY_DEFAULT)
                        .intercept { chain ->
                            val result = chain.proceed()
                            try {
                                val activity = chain.thisObject as? Activity
                                activity?.let { act ->
                                    Handler(Looper.getMainLooper()).post {
                                        val decorView = act.window.decorView as? ViewGroup
                                        val oldBtn = decorView?.findViewWithTag<View>(BTN_TAG)
                                        if (oldBtn != null) {
                                            decorView.removeView(oldBtn)
                                        }
                                        injectAiButton(act)
                                    }
                                }
                            } catch (ignored: Throwable) {}
                            result
                        }
                } catch (ignored: Throwable) {}

                // 3. 监听 Activity 销毁，安全关闭任何残留的 Dialog，彻底杜绝 WindowLeaked
                try {
                    val onDestroyMethod = activityClass.getMethod("onDestroy")
                    hook(onDestroyMethod)
                        .setPriority(XposedInterface.PRIORITY_DEFAULT)
                        .intercept { chain ->
                            try {
                                AiSummaryBottomSheet.dismissCurrent()
                            } catch (ignored: Throwable) {}
                            chain.proceed()
                        }
                } catch (ignored: Throwable) {}

                Log.i(TAG, "Successfully hooked $activityName.onResume")
            } catch (e: Throwable) {
                Log.w(TAG, "Failed or skipped hook for $activityName: ${e.message}")
            }
        }
    }

    /**
     * 针对小黑盒自身 SlideViewPager 在焦点改变重新测量时抛出 IllegalStateException 的全局保护盾
     */
    private fun protectViewPager(classLoader: ClassLoader) {
        try {
            val viewPagerClass = classLoader.loadClass("androidx.viewpager.widget.ViewPager")
            val onMeasureMethod = viewPagerClass.getDeclaredMethod("onMeasure", Integer.TYPE, Integer.TYPE)
            hook(onMeasureMethod)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .intercept { chain ->
                    try {
                        chain.proceed()
                    } catch (e: Throwable) {
                        if (e is IllegalStateException && e.message?.contains("PagerAdapter changed the adapter's contents") == true) {
                            Log.w(TAG, "捕获并拦截 ViewPager 测量崩溃: ${e.message}")
                            val pager = chain.thisObject as? View
                            if (pager != null) {
                                try {
                                    val adapterField = viewPagerClass.getDeclaredField("mAdapter").apply { isAccessible = true }
                                    val adapter = adapterField.get(pager)
                                    adapter?.javaClass?.getMethod("notifyDataSetChanged")?.invoke(adapter)
                                    chain.proceed()
                                } catch (t: Throwable) {
                                    try {
                                        val setDim = View::class.java.getDeclaredMethod("setMeasuredDimension", Integer.TYPE, Integer.TYPE).apply { isAccessible = true }
                                        val wSpec = chain.args[0] as Int
                                        val hSpec = chain.args[1] as Int
                                        setDim.invoke(pager, View.getDefaultSize(0, wSpec), View.getDefaultSize(0, hSpec))
                                    } catch (ignored: Throwable) {}
                                    null
                                }
                            } else {
                                null
                            }
                        } else {
                            throw e
                        }
                    }
                }
            Log.i(TAG, "成功安装 ViewPager 崩溃防护盾")
        } catch (e: Throwable) {
            Log.w(TAG, "安装 ViewPager 防护盾跳过: ${e.message}")
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun injectAiButton(activity: Activity) {
        val decorView = activity.window.decorView as? ViewGroup ?: return

        // 防止重复注入
        if (decorView.findViewWithTag<View>(BTN_TAG) != null) {
            return
        }

        decorView.post {
            if (activity.isFinishing || (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed)) return@post
            if (decorView.findViewWithTag<View>(BTN_TAG) != null) return@post

            val density = activity.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()

            val isDark = AiSummaryBottomSheet.isNightMode(activity)
            val btnW = dp(38)
            val btnH = dp(28)

            // 原生扁平胶囊风格：贴合小黑盒顶栏图标与设计语言（26dp 高度精致微胶囊）
            val btnBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                if (isDark) {
                    setColor(Color.parseColor("#25272B"))
                } else {
                    setColor(Color.parseColor("#F2F3F5"))
                }
            }

            val aiBtn = TextView(activity).apply {
                tag = BTN_TAG
                text = "AI"
                textSize = 13f
                setTextColor(if (isDark) Color.parseColor("#ECECEE") else Color.parseColor("#1F1F1F"))
                gravity = Gravity.CENTER
                paint.isFakeBoldText = true
                background = btnBg
                isClickable = true
            }

            aiBtn.setOnClickListener {
                onAiButtonClicked(activity)
            }

            val lp = FrameLayout.LayoutParams(
                btnW,
                btnH
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

            decorView.addView(aiBtn, lp)

            // 精准计算并贴合在分享按钮左侧
            positionAiButtonNextToShare(activity, decorView, aiBtn, btnW, btnH)

            // 150ms 延迟校准，确保异步渲染与动画完成后位置绝对精准
            decorView.postDelayed({
                if (aiBtn.parent != null && !activity.isFinishing) {
                    positionAiButtonNextToShare(activity, decorView, aiBtn, btnW, btnH)
                }
            }, 150)

            // 单击打开 AI 面板；长按弹出位置菜单（收起）
            aiBtn.setOnLongClickListener {
                showButtonMenu(activity, decorView)
                true
            }
        }
    }

    /**
     * 动态寻找顶栏右上角的分享按钮，并将 AI 按钮像素级紧贴在分享按钮左侧一个身位
     */
    private fun positionAiButtonNextToShare(activity: Activity, decorView: ViewGroup, aiBtn: TextView, btnW: Int, btnH: Int) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val screenW = decorView.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val statusBarHeight = getStatusBarHeight(activity)

        val shareBtn = findTopRightActionButton(decorView, screenW, statusBarHeight)
        if (shareBtn != null) {
            val loc = IntArray(2)
            shareBtn.getLocationOnScreen(loc)
            val shareX = loc[0]
            val shareY = loc[1]
            val shareH = shareBtn.height.takeIf { it > 0 } ?: dp(40)

            // 严格贴在可见分享按钮左侧，保持 8dp 间距，垂直完全居中对齐
            aiBtn.x = (shareX - btnW - dp(8)).toFloat().coerceAtLeast(dp(8).toFloat())
            aiBtn.y = (shareY + (shareH - btnH) / 2).toFloat()
            Log.i(TAG, "AI button locked next to shareBtn: x=${aiBtn.x}, y=${aiBtn.y} (shareX=$shareX, shareY=$shareY)")
        } else {
            // 精确几何保底：顶栏通常高度 48dp，分享按钮右边距 12dp，宽度约 40dp
            val fallbackShareLeft = screenW - dp(12) - dp(40)
            aiBtn.x = (fallbackShareLeft - btnW - dp(8)).toFloat().coerceAtLeast(dp(8).toFloat())
            aiBtn.y = (statusBarHeight + (dp(48) - btnH) / 2).toFloat()
            Log.i(TAG, "AI button fallback positioned: x=${aiBtn.x}, y=${aiBtn.y}")
        }
    }

    /**
     * 严格在屏幕可见区域的顶栏右上角寻找操作按钮（分享图标）
     */
    private fun findTopRightActionButton(decorView: ViewGroup, screenW: Int, statusBarH: Int): View? {
        val density = decorView.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val topBarTop = statusBarH
        val topBarBottom = statusBarH + dp(54)
        val rightThreshold = screenW - dp(64)
        var bestView: View? = null
        var maxRight = -1

        fun scan(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v.tag == BTN_TAG) return

            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val vx = loc[0]
            val vy = loc[1]
            val vw = v.width
            val vh = v.height
            val vRight = vx + vw
            val vBottom = vy + vh

            // 必须严格在屏幕可见顶栏 54dp 垂直范围内，且严格在屏幕最右侧 64dp 可见区域内
            if (vx in rightThreshold..screenW && vRight <= screenW &&
                vy in topBarTop..topBarBottom && vBottom <= topBarBottom + dp(6) &&
                vw in dp(18)..dp(56) && vh in dp(18)..dp(56)
            ) {
                if (vRight > maxRight) {
                    maxRight = vRight
                    bestView = v
                }
            }

            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    scan(v.getChildAt(i))
                }
            }
        }

        scan(decorView)
        return bestView
    }

    /** 长按按钮弹出菜单：临时收起按钮 */
    private fun showButtonMenu(activity: Activity, decorView: ViewGroup) {
        val density = activity.resources.displayMetrics.density
        fun bdp(v: Int) = (v * density).toInt()

        val isDark = AiSummaryBottomSheet.isNightMode(activity)
        val surfaceColor = if (isDark) 0xFF1E2024.toInt() else Color.WHITE
        val textColor = if (isDark) 0xFFECECEE.toInt() else 0xFF1A1A1A.toInt()
        val text2Color = if (isDark) 0xFF8E9096.toInt() else 0xFF8E8E93.toInt()

        val d = android.app.Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val outer = FrameLayout(activity).apply {
            setBackgroundColor(Color.parseColor("#80000000"))
            setOnClickListener { d.dismiss() }
        }
        val card = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(surfaceColor)
                cornerRadius = bdp(16).toFloat()
            }
            setPadding(bdp(16), bdp(12), bdp(16), bdp(8))
            setOnClickListener { }
        }
        outer.addView(card, FrameLayout.LayoutParams(
            (activity.resources.displayMetrics.widthPixels * 0.72).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })

        fun item(title: String, action: () -> Unit) {
            card.addView(TextView(activity).apply {
                text = title
                textSize = 15f
                setTextColor(textColor)
                setPadding(bdp(8), bdp(12), bdp(8), bdp(12))
                setOnClickListener { d.dismiss(); action() }
            })
        }

        item("临时隐藏悬浮按钮 (重新进帖恢复)") {
            decorView.findViewWithTag<View>(BTN_TAG)?.let { decorView.removeView(it) }
        }

        card.addView(TextView(activity).apply {
            text = "取消"
            textSize = 13f
            setTextColor(text2Color)
            gravity = Gravity.CENTER
            setPadding(0, bdp(8), 0, bdp(4))
            setOnClickListener { d.dismiss() }
        })

        d.setContentView(outer)
        d.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
        d.show()
    }

    private fun getStatusBarHeight(activity: Activity): Int {
        val resourceId = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            activity.resources.getDimensionPixelSize(resourceId)
        } else {
            (activity.resources.displayMetrics.density * 32).toInt()
        }
    }

    private fun onAiButtonClicked(activity: Activity) {
        try {
            // 1. 纯内存极速提取基础数据（优先网络层缓存，耗时 < 1ms，零卡顿）
            val baseData = XiaoheihePostExtractor.extractBasePost(activity)
            val linkId = if (baseData.linkId.isNotBlank()) {
                baseData.linkId
            } else {
                val extracted = XiaoheiheFingerprintHelper.extractLinkId(activity, null, null)
                if (extracted.isNotBlank()) {
                    extracted
                } else if (baseData.title.isNotBlank()) {
                    "post_${Math.abs(baseData.title.hashCode())}"
                } else {
                    HeyboxDataRepository.lastActiveLinkId
                }
            }

            // 2. 立即在主线程弹出 BottomSheet 弹窗（0 毫秒即刻响应，彻底消除点击延迟）
            AiSummaryBottomSheet(activity, linkId, baseData).show()

        } catch (e: Throwable) {
            Log.e(TAG, "Failed to launch AI summary dialog", e)
            android.widget.Toast.makeText(activity, "AI 总结启动失败，请稍后重试", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
