package io.github.kingslou.memorycontrol

import android.app.Activity
import android.app.Application
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * 跟随前台 Activity 的调试内存面板，暂停、切页或退后台均保留测试占用。
 * 使用 Activity 窗口令牌，无需系统悬浮窗权限，窗口外触摸交给业务页面。
 */
class MemoryPressureFloatingPanel private constructor(
    /** 只持有 Application，用于注册和卸载生命周期回调。 */
    private val application: Application
) : Application.ActivityLifecycleCallbacks {
    /** 进程内唯一的面板入口及不随页面变化的显示常量。 */
    companion object {
        /** 仅主线程读写，每个进程至多保留一个已安装实例。 */
        private var instance: MemoryPressureFloatingPanel? = null
        /** 整机目标按 5% 递增循环。 */
        private val SYSTEM_TARGETS = intArrayOf(80, 85, 90, 95)
        /** Java 堆目标仍受测试器 16 MiB 余量限制。 */
        private val JAVA_TARGETS = intArrayOf(60, 70, 80, 85, 90, 95)
        /** 固定文字颜色，避免随业务主题变化。 */
        private val COLOR_TEXT = 0xff17212b.toInt()
        /** 次级指标和说明颜色。 */
        private val COLOR_MUTED = 0xff667381.toInt()
        /** 指标和主要操作使用的主色。 */
        private val COLOR_ACCENT = 0xff009e8f.toInt()
        /** 指标卡片的浅灰背景。 */
        private val COLOR_SURFACE = 0xfff1f6f8.toInt()
        /** 可操作参数使用的浅青背景。 */
        private val COLOR_ACCENT_SURFACE = 0xffe0f4f1.toInt()

        /**
         * 在 Application.onCreate 的主线程安装；非 debuggable 宿主直接跳过。
         * 重复安装无副作用，应在目标进程首个 Activity 恢复前调用。
         * @param application 当前进程的 Application
         */
        @JvmStatic
        fun install(application: Application) {
            if (!DebugEnvironment.isDebuggable(application)) return
            requireMainThread()
            if (instance == null) {
                val current = MemoryPressureFloatingPanel(application)
                instance = current
                application.registerActivityLifecycleCallbacks(current)
            }
        }

        /** 主线程卸载窗口和回调，并异步释放测试占用；后台线程保留供复用。 */
        @JvmStatic
        fun uninstall() {
            requireMainThread()
            instance?.let { current ->
                current.active = false
                current.application.unregisterActivityLifecycleCallbacks(current)
                current.resumedActivity.clear()
                current.detach()
                instance = null
            }
            MemoryPressureSimulator.release()
        }

        /** 在主线程返回当前进程是否已安装面板。 */
        @JvmStatic
        fun isInstalled(): Boolean {
            requireMainThread()
            return instance != null
        }

        /** 窗口和生命周期状态只允许主线程读写。 */
        private fun requireMainThread() {
            check(Looper.myLooper() == Looper.getMainLooper()) { "面板安装、卸载和状态读取必须在主线程调用" }
        }
    }

    /** 卸载后使已投递给 DecorView 的旧挂载任务失效。 */
    private var active = true
    /** 窗口和界面刷新均在主线程执行。 */
    private val uiHandler = Handler(Looper.getMainLooper())
    /** 弱引用等待挂载的页面，旧页面不会被排队任务保留。 */
    private var resumedActivity = WeakReference<Activity>(null)
    /** 当前窗口所属页面，卸载时立即清空。 */
    private var attachedActivity: Activity? = null
    /** 仅在窗口挂载期间持有的页面窗口管理器。 */
    private var windowManager: WindowManager? = null
    /** 统一持有当前页面视图，卸载时整体清空。 */
    private var views: PanelViews? = null
    /** 当前 Activity 子窗口的布局参数。 */
    private var windowParams: WindowManager.LayoutParams? = null
    /** 默认收起，仅点击标题或摘要后展开。 */
    private var collapsed = true
    /** 旧系统默认 Java 模式，API 26 起默认混合模式。 */
    private var mode = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
        MemoryPressureSimulator.MODE_APP else MemoryPressureSimulator.MODE_BOTH
    /** 整机目标沿用原实现的 95%。 */
    private var systemTarget = 95
    /** Java 堆默认目标为 80%。 */
    private var javaTarget = 80
    /** 仅折叠状态变化时重建标题装饰。 */
    private var renderedCollapsed: Boolean? = null
    /** 仅运行状态变化时更新主按钮样式。 */
    private var renderedRunning: Boolean? = null
    /** 仅模式变化时更新目标按钮样式。 */
    private var renderedMode = -1
    /** 跨页面保留的窗口横坐标。 */
    private var positionX = 0
    /** 相对 Activity 窗口顶部的纵坐标。 */
    private var positionY = 0
    /** 首次挂载才初始化坐标，后续只约束边界。 */
    private var positionInitialized = false
    /** 定期展示最近快照并合并请求后台采样，退后台立即移除。 */
    private val refresh = object : Runnable {
        /** 只为当前已挂载页面刷新，不在主线程读取 PSS。 */
        override fun run() {
            val activity = attachedActivity ?: return
            if (views == null) return
            MemoryPressureSimulator.requestSample(activity.applicationContext)
            renderSnapshot()
            uiHandler.postDelayed(this, 1500L)
        }
    }

    /** 等待 Decor 附着后挂载，避免窗口令牌尚未建立。 */
    override fun onActivityResumed(activity: Activity) {
        resumedActivity = WeakReference(activity)
        val candidate = WeakReference(activity)
        activity.window.decorView.post {
            val current = candidate.get()
            if (active && current != null && resumedActivity.get() === current &&
                !current.isFinishing && !current.isDestroyed) attach(current)
        }
    }

    /** 页面离开即移除窗口和定时器，保留进程级测试内存。 */
    override fun onActivityPaused(activity: Activity) {
        if (resumedActivity.get() === activity) resumedActivity.clear()
        if (attachedActivity === activity) detach()
    }

    /** 兜底清除异常生命周期顺序留下的页面引用。 */
    override fun onActivityDestroyed(activity: Activity) = onActivityPaused(activity)

    /** 创建时不挂载，等待页面恢复及令牌可用。 */
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    /** 开始事件不显示窗口，避免后台页面出现操作框。 */
    override fun onActivityStarted(activity: Activity) = Unit

    /** 停止时再次兜底卸载，不修改加压状态。 */
    override fun onActivityStopped(activity: Activity) = onActivityPaused(activity)

    /** 加压参数保留在进程内，不写入业务页面保存状态。 */
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    /** 为前台 Activity 创建应用内子窗口，位置和参数跨页面保留。 */
    private fun attach(activity: Activity) {
        if (!active || attachedActivity === activity) return
        detach()
        attachedActivity = activity
        val manager = activity.windowManager
        windowManager = manager
        try {
            val current = createPanel(activity)
            views = current
            if (!positionInitialized) {
                positionX = dp(12)
                positionY = dp(80)
                positionInitialized = true
            }
            val params = WindowManager.LayoutParams(
                minOf(dp(240), maxOf(dp(160), activity.window.decorView.width - dp(24))),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                token = activity.window.decorView.windowToken
                gravity = Gravity.TOP or Gravity.LEFT
                x = positionX
                y = positionY
            }
            windowParams = params
            manager.addView(current.root, params)
            current.root.post { constrainPosition() }
            uiHandler.post(refresh)
        } catch (error: RuntimeException) {
            Log.w(MemoryPressureSimulator.TAG, "内存悬浮框挂载失败", error)
            detach()
        }
    }

    /** 注销窗口并整体清空视图引用，不释放测试器持有的内存。 */
    private fun detach() {
        uiHandler.removeCallbacks(refresh)
        val root = views?.root
        val manager = windowManager
        if (root != null && manager != null && root.isAttachedToWindow) {
            try {
                manager.removeViewImmediate(root)
            } catch (error: RuntimeException) {
                Log.w(MemoryPressureSimulator.TAG, "内存悬浮框卸载失败", error)
            }
        }
        attachedActivity = null
        windowManager = null
        windowParams = null
        views = null
        renderedCollapsed = null
        renderedRunning = null
        renderedMode = -1
    }

    /** 创建原有 240dp 面板，操作区按页面高度滚动，摘要独立于详情显示。 */
    private fun createPanel(activity: Activity): PanelViews {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), 0xffe2e9ed.toInt())
            }
            elevation = dp(8).toFloat()
        }
        val header = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(8))
        }
        header.addView(image(activity, R.drawable.mc_memory_test_drag, 14),
            LinearLayout.LayoutParams(dp(14), dp(14)))
        val title = text(activity, 13f).apply {
            text = "内存测试"
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        header.addView(title, LinearLayout.LayoutParams(-2, -2).apply {
            setMargins(dp(5), 0, dp(5), 0)
        })
        header.addView(View(activity).apply { setBackgroundColor(0xffd8e1e7.toInt()) },
            LinearLayout.LayoutParams(dp(1), dp(11)).apply { setMargins(0, 0, dp(5), 0) })
        val headerHint = text(activity, 8.5f).apply { setSingleLine(true) }
        header.addView(headerHint)
        header.addView(View(activity), LinearLayout.LayoutParams(0, dp(1), 1f))
        val headerArrow = image(activity, R.drawable.mc_memory_test_expand, 14)
        header.addView(headerArrow, LinearLayout.LayoutParams(dp(14), dp(14)).apply {
            setMargins(dp(4), 0, 0, 0)
        })
        header.setOnClickListener { setCollapsed(!collapsed) }
        header.setOnTouchListener(DragListener())
        root.addView(header)

        val summary = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val summaryMetrics = text(activity, 11f)
        card(activity, summary, LinearLayout.VERTICAL).addView(summaryMetrics)
        val summaryAllocation = text(activity, 10.5f).apply { setTextColor(COLOR_MUTED) }
        card(activity, summary, LinearLayout.VERTICAL).addView(summaryAllocation)
        summary.setOnClickListener { setCollapsed(false) }
        root.addView(summary)
        val bodyHeight = minOf(dp(490), maxOf(dp(90), activity.window.decorView.height - dp(96)))
        val body = CappedScrollView(activity, bodyHeight).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        root.addView(body, LinearLayout.LayoutParams(-1, -2))
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        body.addView(content)
        body.visibility = if (collapsed) View.GONE else View.VISIBLE
        summary.visibility = if (collapsed) View.VISIBLE else View.GONE

        val pssCard = card(activity, content, LinearLayout.HORIZONTAL).apply { gravity = Gravity.CENTER_VERTICAL }
        pssCard.addView(text(activity, 10.5f).apply {
            text = "应用PSS"
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        val pssValue = text(activity, 11f).apply { setTextColor(COLOR_MUTED) }
        pssCard.addView(pssValue, LinearLayout.LayoutParams(0, -2, 1f).apply {
            setMargins(dp(5), 0, dp(5), 0)
        })
        val swap = text(activity, 9.5f)
        pssCard.addView(swap)
        val javaCard = card(activity, content, LinearLayout.VERTICAL)
        val javaRow = metricRow(activity, javaCard)
        val javaMetrics = text(activity, 10.5f)
        javaRow.addView(javaMetrics, LinearLayout.LayoutParams(0, -2, 1f))
        val javaPercent = percentage(activity, javaRow)
        val javaProgress = progress(activity, javaCard)
        val systemCard = card(activity, content, LinearLayout.VERTICAL)
        val systemRow = metricRow(activity, systemCard)
        systemRow.addView(text(activity, 10f).apply {
            text = "整机内存"
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        val systemPercent = percentage(activity, systemRow)
        val systemMetrics = text(activity, 8.5f).apply { gravity = Gravity.RIGHT }
        systemRow.addView(systemMetrics, LinearLayout.LayoutParams(0, -2, 1f))
        val systemProgress = progress(activity, systemCard)
        val detailCard = card(activity, content, LinearLayout.HORIZONTAL).apply { gravity = Gravity.CENTER_VERTICAL }
        val allocation = text(activity, 10f)
        detailCard.addView(allocation, LinearLayout.LayoutParams(0, -2, 3f))
        detailCard.addView(View(activity).apply { setBackgroundColor(0xffd8e1e7.toInt()) },
            LinearLayout.LayoutParams(dp(1), dp(30)).apply { setMargins(0, 0, dp(8), 0) })
        val lowMemory = text(activity, 10f)
        detailCard.addView(lowMemory, LinearLayout.LayoutParams(0, -2, 2f))
        val state = text(activity, 10.5f).apply { setTextColor(COLOR_MUTED) }
        card(activity, content, LinearLayout.VERTICAL).addView(state)

        val modeButton = button(activity, content).apply {
            styleButton(this, Color.WHITE, COLOR_ACCENT, COLOR_ACCENT)
            addCycleIcon(activity, this)
            gravity = Gravity.CENTER_VERTICAL or Gravity.LEFT
            setOnClickListener {
                mode = (mode + 1) % 3
                renderSnapshot()
            }
        }
        val systemTargetButton = button(activity, content).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.LEFT
            addCycleIcon(activity, this)
            setOnClickListener {
                systemTarget = nextTarget(SYSTEM_TARGETS, systemTarget)
                renderSnapshot()
            }
        }
        val javaTargetButton = button(activity, content).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.LEFT
            addCycleIcon(activity, this)
            setOnClickListener {
                javaTarget = nextTarget(JAVA_TARGETS, javaTarget)
                renderSnapshot()
            }
        }
        val hintRow = metricRow(activity, content).apply { setPadding(dp(1), dp(4), dp(1), dp(3)) }
        hintRow.addView(image(activity, R.drawable.mc_memory_test_info, 11), LinearLayout.LayoutParams(dp(11), dp(11)))
        val targetHint = text(activity, 8.5f).apply { setTextColor(COLOR_MUTED) }
        hintRow.addView(targetHint, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(5), 0, 0, 0) })
        val controls = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        content.addView(controls)
        val controlButton = button(activity, controls).apply {
            setOnClickListener {
                if (MemoryPressureSimulator.isRunning()) MemoryPressureSimulator.pause()
                else MemoryPressureSimulator.start(activity.applicationContext, mode, systemTarget, javaTarget, 512)
                renderSnapshot()
            }
        }
        button(activity, controls).apply {
            styleButton(this, Color.WHITE, COLOR_ACCENT, COLOR_ACCENT)
            setCompoundDrawablesRelative(icon(activity, R.drawable.mc_memory_test_release, 14), null, null, null)
            text = "释放"
            setOnClickListener {
                MemoryPressureSimulator.release()
                renderSnapshot()
            }
        }
        val helpCard = card(activity, content, LinearLayout.HORIZONTAL)
        (helpCard.layoutParams as LinearLayout.LayoutParams).apply {
            topMargin = dp(5)
            bottomMargin = 0
            helpCard.layoutParams = this
        }
        helpCard.addView(image(activity, R.drawable.mc_memory_test_info, 12), LinearLayout.LayoutParams(dp(12), dp(12)))
        helpCard.addView(text(activity, 8.5f).apply {
            setTextColor(COLOR_MUTED)
            text = "暂停/收起/切页均保留占用；释放清除测试内存。\n整机模式也增加应用PSS。单位MiB。\nJava堆至少留16MiB，实际目标可能受限。"
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(5), 0, 0, 0) })
        return PanelViews(root, title, body, headerHint, headerArrow, summary, summaryMetrics,
            summaryAllocation, pssValue, swap, javaMetrics, javaPercent, javaProgress, systemMetrics,
            systemPercent, systemProgress, allocation, lowMemory, state, targetHint, modeButton,
            systemTargetButton, javaTargetButton, controlButton)
    }

    /** 仅切换可见区域，不改变加压状态或测试内存。 */
    private fun setCollapsed(value: Boolean) {
        val current = views ?: return
        collapsed = value
        current.body.visibility = if (collapsed) View.GONE else View.VISIBLE
        current.summary.visibility = if (collapsed) View.VISIBLE else View.GONE
        renderSnapshot()
        current.root.post { constrainPosition() }
    }

    /** 使用系统原始密度，避免业务 AutoSize 修改页面密度影响面板。 */
    private fun dp(value: Int): Int = Math.round(value * Resources.getSystem().displayMetrics.density)

    /** 以像素设置文本大小，避免宿主主题和字体缩放改变指标层级。 */
    private fun text(activity: Activity, size: Float): TextView = TextView(activity).apply {
        setTextColor(COLOR_TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * Resources.getSystem().displayMetrics.density)
        includeFontPadding = false
        setLineSpacing(dp(1).toFloat(), 1f)
    }

    /** 指标卡片按内容自适应高度，长文本允许换行。 */
    private fun card(activity: Activity, parent: LinearLayout, orientation: Int): LinearLayout =
        LinearLayout(activity).apply {
            this.orientation = orientation
            setPadding(dp(7), dp(6), dp(7), dp(6))
            background = roundedBackground(COLOR_SURFACE, 0)
            parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(4)) })
        }

    /** 将指标文字与右侧比例垂直居中对齐。 */
    private fun metricRow(activity: Activity, parent: LinearLayout): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        parent.addView(this)
    }

    /** 用主色突出比例，同时保留当前值和容量上限。 */
    private fun percentage(activity: Activity, parent: LinearLayout): TextView = text(activity, 12f).apply {
        setTextColor(COLOR_ACCENT)
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        parent.addView(this, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(4), 0, dp(4), 0) })
    }

    /** 细进度条展示最近快照，不在高内存压力下执行动画。 */
    private fun progress(activity: Activity, parent: LinearLayout): ProgressBar =
        ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 1000
            val fill = ClipDrawable(roundedBackground(COLOR_ACCENT, 0), Gravity.LEFT, ClipDrawable.HORIZONTAL)
            progressDrawable = LayerDrawable(arrayOf<Drawable>(roundedBackground(0xffd8e5e9.toInt(), 0), fill)).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }
            progressTintList = ColorStateList.valueOf(COLOR_ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(0xffd8e5e9.toInt())
            parent.addView(this, LinearLayout.LayoutParams(-1, dp(4)).apply { setMargins(0, dp(5), 0, 0) })
        }

    /** 只在创建或状态变化时分配圆角背景。 */
    private fun roundedBackground(fillColor: Int, strokeColor: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fillColor)
        cornerRadius = dp(6).toFloat()
        if (strokeColor != 0) setStroke(dp(1), strokeColor)
    }

    /** 明确设置按下、禁用和普通状态，避免宿主主题叠加默认染色。 */
    private fun styleButton(view: Button, fillColor: Int, strokeColor: Int, textColor: Int) {
        view.backgroundTintList = null
        view.background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), roundedBackground(0xffedf1f4.toInt(), 0))
            addState(intArrayOf(android.R.attr.state_pressed), roundedBackground(
                if (fillColor == COLOR_ACCENT) 0xff008477.toInt() else 0xffcdebe6.toInt(), strokeColor))
            addState(intArrayOf(), roundedBackground(fillColor, strokeColor))
        }
        view.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(0xffa0adb8.toInt(), textColor)))
        view.stateListAnimator = null
        view.compoundDrawableTintList = view.textColors
    }

    /** 参数按钮独占一行，操作按钮等宽并保留触摸范围。 */
    private fun button(activity: Activity, parent: LinearLayout): Button = Button(activity).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(11).toFloat())
        isAllCaps = false
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        includeFontPadding = false
        setPadding(dp(8), 0, dp(8), 0)
        compoundDrawablePadding = dp(5)
        styleButton(this, COLOR_ACCENT_SURFACE, 0, COLOR_ACCENT)
        val horizontal = parent.orientation == LinearLayout.HORIZONTAL
        parent.addView(this, LinearLayout.LayoutParams(if (horizontal) 0 else -1, dp(32),
            if (horizontal) 1f else 0f).apply {
            setMargins(if (horizontal && parent.childCount > 0) dp(6) else 0, dp(4), 0, 0)
        })
    }

    /** 暂停或未开始时按预置等级循环调整目标。 */
    private fun nextTarget(targets: IntArray, current: Int): Int {
        val index = targets.indexOf(current)
        return targets[if (index < 0) 0 else (index + 1) % targets.size]
    }

    /** 同次快照更新摘要与详情，主线程不进行内存扫描。 */
    private fun renderSnapshot() {
        val current = views ?: return
        val activity = attachedActivity ?: return
        val memory = MemoryPressureSimulator.getSnapshot()
        val running = MemoryPressureSimulator.isRunning()
        val systemPercent = if (memory.systemUsedPercent >= 0)
            String.format(Locale.ROOT, "%.1f%%", memory.systemUsedPercent) else "--"
        val javaPercent = if (memory.javaMaxMb > 0)
            String.format(Locale.ROOT, "%.1f%%", 100f * memory.javaUsedMb / memory.javaMaxMb) else "--"
        current.title.text = "内存测试"
        if (renderedCollapsed != collapsed) {
            current.headerHint.background = if (collapsed) roundedBackground(COLOR_ACCENT_SURFACE, 0) else null
            current.headerHint.setPadding(if (collapsed) dp(5) else 0, if (collapsed) dp(2) else 0,
                if (collapsed) dp(5) else 0, if (collapsed) dp(2) else 0)
            current.headerArrow.setImageResource(if (collapsed) R.drawable.mc_memory_test_expand else R.drawable.mc_memory_test_collapse)
            renderedCollapsed = collapsed
        }
        current.headerHint.text = if (collapsed) compactState(memory, running) else "拖动移动 · 点击收起"
        current.headerHint.setTextColor(if (collapsed) COLOR_ACCENT else COLOR_MUTED)
        val summary = "整机 $systemPercent · Java $javaPercent"
        current.summaryMetrics.text = emphasize(emphasize(summary, systemPercent, 1.2f),
            javaPercent, 1.2f, summary.indexOf("Java"))
        current.summaryAllocation.text = "测试占用 ${memory.allocatedMb} MiB · 点击展开"
        val pss = if (memory.pssMb >= 0) memory.pssMb.toString() else "--"
        current.pssValue.text = emphasize(pss + if (memory.pssMb >= 0) " MiB" else "", pss, 1.6f)
        current.swap.text = "Swap " + if (memory.swapPssMb >= 0) "${memory.swapPssMb} MiB" else "--"
        current.javaMetrics.text = SpannableString("Java堆  ${memory.javaUsedMb} / ${memory.javaMaxMb} MiB").apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        current.javaPercent.text = javaPercent
        current.javaProgress.progress = if (memory.javaMaxMb > 0)
            (memory.javaUsedMb * 1000 / memory.javaMaxMb).coerceIn(0, 1000).toInt() else 0
        current.systemMetrics.text = "可用 ${memory.availableMb}/${memory.totalMb} MiB"
        current.systemPercent.text = systemPercent
        current.systemProgress.progress = if (memory.systemUsedPercent >= 0)
            Math.round(memory.systemUsedPercent * 10).coerceIn(0, 1000) else 0
        current.allocation.text = SpannableString("测试占用\nNative ${memory.nativeAllocatedMb} MiB\nJava ${memory.javaAllocatedMb} MiB").apply {
            setSpan(ForegroundColorSpan(COLOR_MUTED), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val lowMemory = if (memory.lowMemory) "是" else "否"
        current.lowMemory.text = emphasize("低内存 $lowMemory\nTrim ${memory.trimLevel}", lowMemory, 1f)
        current.state.text = emphasize("状态   ${memory.state}", memory.state, 1f)
        current.modeButton.text = "模式：" + when (mode) {
            MemoryPressureSimulator.MODE_SYSTEM -> "整机 (Native)"
            MemoryPressureSimulator.MODE_APP -> "应用 (Java堆)"
            else -> "同时 (Native + Java)"
        }
        current.systemTargetButton.text = emphasize("整机目标 $systemTarget%", "$systemTarget%", 1f)
        current.javaTargetButton.text = emphasize("应用堆目标 $javaTarget%", "$javaTarget%", 1f)
        current.modeButton.isEnabled = !running
        current.systemTargetButton.isEnabled = !running
        current.javaTargetButton.isEnabled = !running
        if (renderedMode != mode) {
            styleButton(current.systemTargetButton,
                if (mode == MemoryPressureSimulator.MODE_APP) COLOR_SURFACE else COLOR_ACCENT_SURFACE,
                0, if (mode == MemoryPressureSimulator.MODE_APP) COLOR_MUTED else COLOR_ACCENT)
            styleButton(current.javaTargetButton,
                if (mode == MemoryPressureSimulator.MODE_SYSTEM) COLOR_SURFACE else COLOR_ACCENT_SURFACE,
                0, if (mode == MemoryPressureSimulator.MODE_SYSTEM) COLOR_MUTED else COLOR_ACCENT)
            renderedMode = mode
        }
        current.targetHint.text = when {
            running -> "增压中不可切换参数，请先暂停。"
            mode == MemoryPressureSimulator.MODE_APP -> "整机目标仅在整机或同时加压时生效。"
            mode == MemoryPressureSimulator.MODE_SYSTEM -> "应用堆目标仅在应用或同时加压时生效。"
            else -> "同时按整机目标和应用堆目标加压。"
        }
        if (renderedRunning != running) {
            styleButton(current.controlButton, if (running) Color.WHITE else COLOR_ACCENT,
                if (running) 0xffa0adb8.toInt() else 0, if (running) COLOR_TEXT else Color.WHITE)
            current.controlButton.setCompoundDrawablesRelative(icon(activity,
                if (running) R.drawable.mc_memory_test_pause else R.drawable.mc_memory_test_play, 14), null, null, null)
            renderedRunning = running
        }
        current.controlButton.isEnabled = true
        current.controlButton.text = when {
            running -> "暂停"
            memory.allocatedMb > 0 -> "继续"
            else -> "开始"
        }
        if (!running && Build.VERSION.SDK_INT < Build.VERSION_CODES.O && mode != MemoryPressureSimulator.MODE_APP) {
            current.controlButton.isEnabled = false
            current.controlButton.text = "需Android 8"
        }
    }

    /** 用真实密度设置矢量图标尺寸，避免系统字体缺少字符。 */
    private fun icon(activity: Activity, resource: Int, size: Int): Drawable =
        activity.resources.getDrawable(resource, activity.theme).mutate().apply { setBounds(0, 0, dp(size), dp(size)) }

    /** 装饰图标不单独接收触摸或重复播报。 */
    private fun image(activity: Activity, resource: Int, size: Int): ImageView = ImageView(activity).apply {
        setImageDrawable(icon(activity, resource, size))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** 参数循环图标与按钮一起响应禁用染色。 */
    private fun addCycleIcon(activity: Activity, view: Button) {
        view.setCompoundDrawablesRelative(null, null, icon(activity, R.drawable.mc_memory_test_cycle, 14), null)
        view.contentDescription = "点击循环切换参数"
    }

    /** 从指定起点强调文字，避免相同比例重复出现时强调错列。 */
    private fun emphasize(value: CharSequence, token: String, scale: Float, fromIndex: Int = 0): CharSequence {
        val text = SpannableString(value)
        val start = value.toString().indexOf(token, fromIndex)
        if (start >= 0 && token.isNotEmpty()) {
            val end = start + token.length
            text.setSpan(ForegroundColorSpan(COLOR_ACCENT), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (scale != 1f) text.setSpan(RelativeSizeSpan(scale), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text
    }

    /** 从操作状态区分未开始、暂停、释放和失败，不混淆零占用。 */
    private fun compactState(memory: MemoryPressureSimulator.Snapshot, running: Boolean): String = when {
        running -> "增压中"
        memory.state.contains("失败") -> "分配失败"
        memory.state.startsWith("已暂停") -> "已暂停"
        memory.state.startsWith("已释放") -> "已释放"
        memory.state.contains("目标") || memory.state.contains("边界") -> "已达目标"
        memory.allocatedMb > 0 -> "保留中"
        else -> "未开始"
    }

    /** 页面尺寸和内容变化后约束坐标，始终保留可操作的标题。 */
    private fun constrainPosition() {
        val root = views?.root ?: return
        val activity = attachedActivity ?: return
        val params = windowParams ?: return
        val decor = activity.window.decorView
        positionX = positionX.coerceIn(0, maxOf(0, decor.width - root.width))
        positionY = positionY.coerceIn(0, maxOf(0, decor.height - root.height))
        params.x = positionX
        params.y = positionY
        try {
            windowManager?.updateViewLayout(root, params)
        } catch (error: RuntimeException) {
            Log.w(MemoryPressureSimulator.TAG, "内存悬浮框位置更新失败", error)
        }
    }

    /** 内容少时自适应，内容多时限制到当前页面可用高度。 */
    private class CappedScrollView(activity: Activity,
        /** 当前页面允许的最大内容高度，单位为像素。 */
        private val maximumHeight: Int
    ) : ScrollView(activity) {
        /** 同时考虑内容高度和父布局传来的可用高度。 */
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val available = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED)
                maximumHeight else minOf(maximumHeight, View.MeasureSpec.getSize(heightMeasureSpec))
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST))
        }
    }

    /** 通过系统触摸阈值区分标题点击和拖动。 */
    private inner class DragListener : View.OnTouchListener {
        /** 手势起点的屏幕横坐标。 */
        private var downX = 0f
        /** 手势起点的屏幕纵坐标。 */
        private var downY = 0f
        /** 手势开始时的窗口横坐标。 */
        private var originX = 0
        /** 手势开始时的窗口纵坐标。 */
        private var originY = 0
        /** 超出阈值后不再把抬手解释为点击。 */
        private var dragging = false

        /** 只消费标题区域手势，不拦截面板之外的业务操作。 */
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    originX = positionX
                    originY = positionY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    val slop = ViewConfiguration.get(view.context).scaledTouchSlop
                    dragging = dragging || kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop
                    if (dragging) {
                        positionX = originX + Math.round(dx)
                        positionY = originY + Math.round(dy)
                        constrainPosition()
                    }
                }
                MotionEvent.ACTION_UP -> if (!dragging) view.performClick()
                MotionEvent.ACTION_CANCEL -> dragging = false
                else -> return false
            }
            return true
        }
    }

    /** 当前页面的完整视图引用，整体置空即可解除对旧 Activity 的持有。 */
    private class PanelViews(
        /** 窗口根布局。 */ val root: LinearLayout,
        /** 标题文本。 */ val title: TextView,
        /** 展开操作区。 */ val body: ScrollView,
        /** 标题提示或折叠状态。 */ val headerHint: TextView,
        /** 展开和收起箭头。 */ val headerArrow: ImageView,
        /** 折叠摘要区域。 */ val summary: LinearLayout,
        /** 折叠时的内存比例。 */ val summaryMetrics: TextView,
        /** 折叠时的测试持有量。 */ val summaryAllocation: TextView,
        /** 应用 PSS。 */ val pssValue: TextView,
        /** 可选的 Swap PSS。 */ val swap: TextView,
        /** Java 堆用量和上限。 */ val javaMetrics: TextView,
        /** Java 堆比例。 */ val javaPercent: TextView,
        /** Java 堆进度条。 */ val javaProgress: ProgressBar,
        /** 整机可用和总内存。 */ val systemMetrics: TextView,
        /** 整机内存比例。 */ val systemPercent: TextView,
        /** 整机比例进度条。 */ val systemProgress: ProgressBar,
        /** 测试器 Native 和 Java 持有量。 */ val allocation: TextView,
        /** 低内存状态及 Trim 等级。 */ val lowMemory: TextView,
        /** 原始操作状态。 */ val state: TextView,
        /** 当前模式的参数说明。 */ val targetHint: TextView,
        /** 加压模式切换按钮。 */ val modeButton: Button,
        /** 整机目标按钮。 */ val systemTargetButton: Button,
        /** Java 堆目标按钮。 */ val javaTargetButton: Button,
        /** 开始、暂停和继续按钮。 */ val controlButton: Button
    )
}
