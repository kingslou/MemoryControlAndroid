package io.github.kingslou.memorycontrol;

import android.app.Activity;
import android.app.Application;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Locale;

/**
 * 宿主调试包的内存测试悬浮框：随前台Activity切换，暂停或退后台不释放测试占用。
 * 使用Activity窗口令牌，无需系统悬浮窗权限；窗口外的触摸交给当前业务页面。
 */
public final class MemoryPressureFloatingPanel implements Application.ActivityLifecycleCallbacks {
    /** 每个进程只保留一个实例；仅在主线程安装、卸载和访问。 */
    private static MemoryPressureFloatingPanel instance;
    /** 只持有 Application，用于卸载生命周期回调。 */
    private final Application application;
    /** 卸载后使已投递到 DecorView 的挂载任务失效。 */
    private boolean active = true;
    /** 所有窗口操作及UI刷新只在主线程执行。 */
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    /** 待挂载的前台页面使用弱引用，页面切换后旧挂载任务自动失效。 */
    private WeakReference<Activity> resumedActivity = new WeakReference<>(null);
    /** 当前窗口所属页面，在卸载时立即清空。 */
    private Activity attachedActivity;
    /** 只在窗口存在期间持有该页面的窗口管理器。 */
    private WindowManager windowManager;
    /** 当前悬浮框视图，切换页面时重建以避免持有旧页面。 */
    private LinearLayout panel;
    /** 标题兼作拖动区域，点击可展开或收起。 */
    private TextView titleView;
    /** 收起时隐藏的操作与采样区域。 */
    private ScrollView bodyView;
    /** 展开时显示拖动提示，收起时复用为短状态徽标。 */
    private TextView headerHintView;
    /** 标题右侧使用矢量箭头标识展开和收起。 */
    private ImageView headerArrowView;
    /** 收起后独立显示摘要卡片，不再把全部信息塞入标题。 */
    private LinearLayout summaryView;
    /** 收起摘要的两类占用比例，和展开状态共享同一次快照。 */
    private TextView summaryMetricsView;
    /** 收起摘要显示测试持有量及展开提示，不误标为应用PSS。 */
    private TextView summaryAllocationView;
    /** 仅在展开状态改变时更新徽标背景和箭头，减少刷新时分配。 */
    private Boolean renderedCollapsed;
    /** 仅在运行状态改变时切换合并按钮的样式与图标。 */
    private Boolean renderedRunning;
    /** 仅在模式改变时切换目标按钮样式，目标预设始终可点击。 */
    private int renderedMode = -1;
    /** 应用PSS使用主色突出，与测试分配量区分。 */
    private TextView pssValueView;
    /** 系统未提供Swap时显示未知，不将其当成零。 */
    private TextView swapView;
    /** Java堆当前值与上限来自工作线程快照。 */
    private TextView javaMetricsView;
    /** Java已用堆比例与同一快照中的数字保持一致。 */
    private TextView javaPercentView;
    /** 仅可视化Java堆比例，不参与加压目标计算。 */
    private ProgressBar javaProgressView;
    /** 整机可用量与总量保留原有采样含义。 */
    private TextView systemMetricsView;
    /** 整机使用率与可用内存放在同一指标卡片中。 */
    private TextView systemPercentView;
    /** 仅可视化整机占用比例，不改变采样及分配行为。 */
    private ProgressBar systemProgressView;
    /** 单独显示两类测试持有量，避免误认为进程总占用。 */
    private TextView allocationView;
    /** 低内存状态与最近Trim等级保留为独立诊断信息。 */
    private TextView lowMemoryView;
    /** 展示测试器原始状态，长状态允许换行。 */
    private TextView stateView;
    /** 提示两个目标可以预设，但仅所选模式对应的目标会参与加压。 */
    private TextView targetHintView;
    /** 分别控制整机、应用Java堆及两者同时增加的模式按钮。 */
    private Button modeButton;
    /** 每次点击循环调整整机目标比例。 */
    private Button systemTargetButton;
    /** 每次点击循环调整应用Java堆目标比例。 */
    private Button javaTargetButton;
    /** 开始、继续和暂停共用一个按钮，点击时查询真实运行状态。 */
    private Button controlButton;
    /** 当前Activity的应用内子窗口参数。 */
    private WindowManager.LayoutParams windowParams;
    /** 默认收起，进入任意模块后点击标题即可测试。 */
    private boolean collapsed = true;
    /** 默认同时增加整机Native压力和应用Java堆占用。 */
    private int mode = MemoryPressureSimulator.MODE_BOTH;
    /** 整机目标沿用已有测试器的95%。 */
    private int systemTarget = 95;
    /** 应用堆默认80%，每次开始前可调整。 */
    private int javaTarget = 80;
    /** 整机目标按5%递增循环，范围限制为80%至95%。 */
    private static final int[] SYSTEM_TARGETS = {80, 85, 90, 95};
    /** 浅色面板的文字颜色，避免随业务页面主题变化。 */
    private static final int COLOR_TEXT = 0xff17212b;
    /** 次级指标和说明采用灰色，保持信息层级。 */
    private static final int COLOR_MUTED = 0xff667381;
    /** 浅色青方案的主色，用于占用比例和主要操作。 */
    private static final int COLOR_ACCENT = 0xff009e8f;
    /** 指标卡片采用浅灰底，和白色面板区分。 */
    private static final int COLOR_SURFACE = 0xfff1f6f8;
    /** 可点击的目标参数使用浅青底。 */
    private static final int COLOR_ACCENT_SURFACE = 0xffe0f4f1;
    /** 可选应用堆等级，仍受测试器16MiB余量边界约束。 */
    private static final int[] JAVA_TARGETS = {60, 70, 80, 85, 90, 95};
    /** 保存窗口位置，跨模块保持上次拖动结果。 */
    private int positionX;
    /** 保存相对Activity窗口顶部的位置。 */
    private int positionY;
    /** 首次建立窗口后才初始化位置，后续只按页面尺寸约束。 */
    private boolean positionInitialized;
    /** 周期读取轻量快照，并请求合并后的后台采样；退后台立即移除刷新。 */
    private final Runnable refresh = new Runnable() {
        /** 只为已挂载的前台窗口刷新，避免回调持有离开的业务页面。 */
        @Override
        public void run() {
            if (panel == null || attachedActivity == null) {
                return;
            }
            MemoryPressureSimulator.requestSample(attachedActivity.getApplicationContext());
            renderSnapshot();
            uiHandler.postDelayed(this, 1500L);
        }
    };

    /** 保存应用引用，旧系统默认使用其支持的 Java 堆模式。 */
    private MemoryPressureFloatingPanel(Application application) {
        this.application = application;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            mode = MemoryPressureSimulator.MODE_APP;
        }
    }

    /**
     * 在 Application.onCreate 的主线程安装面板；宿主非 debuggable 时不注册。
     * 重复安装无副作用；应在需要显示面板的进程中、首个 Activity 恢复前调用。
     * @param application 当前进程的 Application
     */
    public static void install(Application application) {
        if (!DebugEnvironment.isDebuggable(application)) {
            return;
        }
        requireMainThread();
        if (instance == null) {
            instance = new MemoryPressureFloatingPanel(application);
            application.registerActivityLifecycleCallbacks(instance);
        }
    }

    /**
     * 在主线程卸载面板、取消生命周期监听，并异步释放测试占用。
     * 反复调用安全；后台采样线程保留供后续安装复用。
     */
    public static void uninstall() {
        requireMainThread();
        if (instance != null) {
            instance.active = false;
            instance.application.unregisterActivityLifecycleCallbacks(instance);
            instance.resumedActivity.clear();
            instance.detach();
            instance = null;
        }
        MemoryPressureSimulator.release();
    }

    /** 返回当前进程是否已安装面板，必须在主线程读取。 */
    public static boolean isInstalled() {
        requireMainThread();
        return instance != null;
    }

    /** 窗口和生命周期状态仅在主线程读写，错误调用立即给出明确异常。 */
    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("面板安装、卸载和状态读取必须在主线程调用");
        }
    }

    /** 页面恢复后等待Decor附着，防止窗口令牌尚未建立导致BadToken。 */
    @Override
    public void onActivityResumed(Activity activity) {
        resumedActivity = new WeakReference<>(activity);
        WeakReference<Activity> candidate = new WeakReference<>(activity);
        activity.getWindow().getDecorView().post(() -> {
            Activity current = candidate.get();
            if (active && current != null && resumedActivity.get() == current
                    && !current.isFinishing() && !current.isDestroyed()) {
                attach(current);
            }
        });
    }

    /** 离开当前页面即移除窗口和UI定时器，测试器持有的压力内存保持不变。 */
    @Override
    public void onActivityPaused(Activity activity) {
        if (resumedActivity.get() == activity) {
            resumedActivity.clear();
        }
        if (attachedActivity == activity) {
            detach();
        }
    }

    /** 防止异常生命周期顺序使销毁的Activity被窗口引用。 */
    @Override
    public void onActivityDestroyed(Activity activity) {
        onActivityPaused(activity);
    }

    /** 创建事件不挂载窗口，等待页面恢复及窗口令牌可用。 */
    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    /** 开始事件不挂载窗口，避免后台页面出现操作框。 */
    @Override
    public void onActivityStarted(Activity activity) {
    }

    /** 停止时再次兜底卸载，不修改加压状态。 */
    @Override
    public void onActivityStopped(Activity activity) {
        onActivityPaused(activity);
    }

    /** 加压参数由进程级对象保留，不写入业务页面保存状态。 */
    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    /** 仅绑定当前Activity的应用内子窗口，切换页面后保留位置和参数。 */
    private void attach(Activity activity) {
        if (!active || attachedActivity == activity) {
            return;
        }
        detach();
        attachedActivity = activity;
        windowManager = activity.getWindowManager();
        try {
            createPanel(activity);
            if (!positionInitialized) {
                positionX = dp(12);
                positionY = dp(80);
                positionInitialized = true;
            }
            windowParams = new WindowManager.LayoutParams(
                    Math.min(dp(240), Math.max(dp(160),
                            activity.getWindow().getDecorView().getWidth() - dp(24))),
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            windowParams.token = activity.getWindow().getDecorView().getWindowToken();
            windowParams.gravity = Gravity.TOP | Gravity.LEFT;
            windowParams.x = positionX;
            windowParams.y = positionY;
            windowManager.addView(panel, windowParams);
            panel.post(this::constrainPosition);
            uiHandler.post(refresh);
        } catch (RuntimeException error) {
            Log.w(MemoryPressureSimulator.TAG, "内存悬浮框挂载失败", error);
            detach();
        }
    }

    /** 注销窗口并清空全部页面对象；不会调用测试器release。 */
    private void detach() {
        uiHandler.removeCallbacks(refresh);
        if (panel != null && windowManager != null && panel.isAttachedToWindow()) {
            try {
                windowManager.removeViewImmediate(panel);
            } catch (RuntimeException error) {
                Log.w(MemoryPressureSimulator.TAG, "内存悬浮框卸载失败", error);
            }
        }
        attachedActivity = null;
        windowManager = null;
        windowParams = null;
        panel = null;
        titleView = null;
        bodyView = null;
        headerHintView = null;
        headerArrowView = null;
        summaryView = null;
        summaryMetricsView = null;
        summaryAllocationView = null;
        renderedCollapsed = null;
        renderedRunning = null;
        renderedMode = -1;
        pssValueView = null;
        swapView = null;
        javaMetricsView = null;
        javaPercentView = null;
        javaProgressView = null;
        systemMetricsView = null;
        systemPercentView = null;
        systemProgressView = null;
        allocationView = null;
        lowMemoryView = null;
        stateView = null;
        targetHintView = null;
        modeButton = null;
        systemTargetButton = null;
        javaTargetButton = null;
        controlButton = null;
    }

    /** 按确认的240dp窄版设计创建展开和收起视图，保留小窗口滚动能力。 */
    private void createPanel(Activity activity) {
        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(12));
        background.setStroke(dp(1), 0xffe2e9ed);
        panel.setBackground(background);
        panel.setElevation(dp(8));

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(2), dp(2), dp(2), dp(8));
        ImageView grip = image(activity, R.drawable.mc_memory_test_drag, 14);
        header.addView(grip, new LinearLayout.LayoutParams(dp(14), dp(14)));
        titleView = text(activity, 13);
        titleView.setText("内存测试");
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(dp(5), 0, dp(5), 0);
        header.addView(titleView, titleParams);
        View separator = new View(activity);
        separator.setBackgroundColor(0xffd8e1e7);
        LinearLayout.LayoutParams separatorParams = new LinearLayout.LayoutParams(dp(1), dp(11));
        separatorParams.setMargins(0, 0, dp(5), 0);
        header.addView(separator, separatorParams);
        headerHintView = text(activity, 8.5f);
        headerHintView.setSingleLine(true);
        header.addView(headerHintView);
        View spacer = new View(activity);
        header.addView(spacer, new LinearLayout.LayoutParams(0, dp(1), 1f));
        headerArrowView = image(activity, R.drawable.mc_memory_test_expand, 14);
        LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(dp(14), dp(14));
        arrowParams.setMargins(dp(4), 0, 0, 0);
        header.addView(headerArrowView, arrowParams);
        // 标题整行处理点击和拖动，图标不另设按钮。
        header.setOnClickListener(view -> setCollapsed(!collapsed));
        header.setOnTouchListener(new DragListener());
        panel.addView(header);

        summaryView = new LinearLayout(activity);
        summaryView.setOrientation(LinearLayout.VERTICAL);
        LinearLayout summaryCard = card(activity, summaryView, LinearLayout.VERTICAL);
        summaryMetricsView = text(activity, 11);
        summaryCard.addView(summaryMetricsView);
        LinearLayout allocationCard = card(activity, summaryView, LinearLayout.VERTICAL);
        summaryAllocationView = text(activity, 10.5f);
        summaryAllocationView.setTextColor(COLOR_MUTED);
        allocationCard.addView(summaryAllocationView);
        // 摘要中的“点击展开”整块可点击，拖动仍通过标题进行。
        summaryView.setOnClickListener(view -> setCollapsed(false));
        panel.addView(summaryView);

        int bodyHeight = Math.min(dp(490), Math.max(dp(90),
                activity.getWindow().getDecorView().getHeight() - dp(96)));
        bodyView = new CappedScrollView(activity, bodyHeight);
        bodyView.setVerticalScrollBarEnabled(false);
        bodyView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        panel.addView(bodyView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        bodyView.addView(content);
        bodyView.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        summaryView.setVisibility(collapsed ? View.VISIBLE : View.GONE);

        LinearLayout pssCard = card(activity, content, LinearLayout.HORIZONTAL);
        pssCard.setGravity(Gravity.CENTER_VERTICAL);
        TextView pssLabel = text(activity, 10.5f);
        pssLabel.setText("应用PSS");
        pssLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        pssCard.addView(pssLabel);
        pssValueView = text(activity, 11);
        pssValueView.setTextColor(COLOR_MUTED);
        LinearLayout.LayoutParams pssParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        pssParams.setMargins(dp(5), 0, dp(5), 0);
        pssCard.addView(pssValueView, pssParams);
        swapView = text(activity, 9.5f);
        swapView.setTextColor(COLOR_MUTED);
        pssCard.addView(swapView);

        LinearLayout javaCard = card(activity, content, LinearLayout.VERTICAL);
        LinearLayout javaRow = metricRow(activity, javaCard);
        javaMetricsView = text(activity, 10.5f);
        javaRow.addView(javaMetricsView, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        javaPercentView = percentage(activity, javaRow);
        javaProgressView = progress(activity, javaCard);

        LinearLayout systemCard = card(activity, content, LinearLayout.VERTICAL);
        LinearLayout systemRow = metricRow(activity, systemCard);
        TextView systemLabel = text(activity, 10);
        systemLabel.setText("整机内存");
        systemLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        systemRow.addView(systemLabel);
        systemPercentView = percentage(activity, systemRow);
        systemMetricsView = text(activity, 8.5f);
        systemMetricsView.setGravity(Gravity.RIGHT);
        systemRow.addView(systemMetricsView, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        systemProgressView = progress(activity, systemCard);

        LinearLayout detailCard = card(activity, content, LinearLayout.HORIZONTAL);
        detailCard.setGravity(Gravity.CENTER_VERTICAL);
        allocationView = text(activity, 10);
        detailCard.addView(allocationView, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f));
        View detailDivider = new View(activity);
        detailDivider.setBackgroundColor(0xffd8e1e7);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(dp(1), dp(30));
        dividerParams.setMargins(dp(5), 0, dp(8), 0);
        detailCard.addView(detailDivider, dividerParams);
        lowMemoryView = text(activity, 10);
        detailCard.addView(lowMemoryView, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f));

        LinearLayout stateCard = card(activity, content, LinearLayout.VERTICAL);
        stateView = text(activity, 10.5f);
        stateView.setTextColor(COLOR_MUTED);
        stateCard.addView(stateView);

        modeButton = button(activity, content);
        styleButton(modeButton, Color.WHITE, COLOR_ACCENT, COLOR_ACCENT);
        addCycleIcon(activity, modeButton);
        modeButton.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        // 模式切换只作用于下一次开始，已有测试占用继续保留。
        modeButton.setOnClickListener(view -> {
            mode = (mode + 1) % 3;
            renderSnapshot();
        });
        systemTargetButton = button(activity, content);
        systemTargetButton.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        addCycleIcon(activity, systemTargetButton);
        // 应用模式也可预设整机目标，但不因此启动Native分配。
        systemTargetButton.setOnClickListener(view -> {
            systemTarget = nextTarget(SYSTEM_TARGETS, systemTarget);
            renderSnapshot();
        });
        javaTargetButton = button(activity, content);
        javaTargetButton.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        addCycleIcon(activity, javaTargetButton);
        javaTargetButton.setOnClickListener(view -> {
            javaTarget = nextTarget(JAVA_TARGETS, javaTarget);
            renderSnapshot();
        });
        LinearLayout hintRow = metricRow(activity, content);
        hintRow.setPadding(dp(1), dp(4), dp(1), dp(3));
        ImageView targetInfo = image(activity, R.drawable.mc_memory_test_info, 11);
        hintRow.addView(targetInfo, new LinearLayout.LayoutParams(dp(11), dp(11)));
        targetHintView = text(activity, 8.5f);
        targetHintView.setTextColor(COLOR_MUTED);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        hintParams.setMargins(dp(5), 0, 0, 0);
        hintRow.addView(targetHintView, hintParams);

        LinearLayout controls = new LinearLayout(activity);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        content.addView(controls);
        controlButton = button(activity, controls);
        // 每次点击查询测试器的真实状态，避免图标或旧快照决定开始/暂停。
        controlButton.setOnClickListener(view -> {
            if (MemoryPressureSimulator.isRunning()) {
                MemoryPressureSimulator.pause();
            } else {
                MemoryPressureSimulator.start(activity.getApplicationContext(),
                        mode, systemTarget, javaTarget, 512);
            }
            renderSnapshot();
        });
        Button releaseButton = button(activity, controls);
        styleButton(releaseButton, Color.WHITE, COLOR_ACCENT, COLOR_ACCENT);
        releaseButton.setCompoundDrawablesRelative(
                icon(activity, R.drawable.mc_memory_test_release, 14), null, null, null);
        releaseButton.setText("释放");
        // 释放只覆盖两类测试块，业务缓存仍由业务页面管理。
        releaseButton.setOnClickListener(view -> {
            MemoryPressureSimulator.release();
            renderSnapshot();
        });

        LinearLayout helpCard = card(activity, content, LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams helpParams = (LinearLayout.LayoutParams) helpCard.getLayoutParams();
        helpParams.topMargin = dp(5);
        helpParams.bottomMargin = 0;
        helpCard.setLayoutParams(helpParams);
        ImageView helpInfo = image(activity, R.drawable.mc_memory_test_info, 12);
        helpCard.addView(helpInfo, new LinearLayout.LayoutParams(dp(12), dp(12)));
        TextView help = text(activity, 8.5f);
        help.setTextColor(COLOR_MUTED);
        help.setText("暂停/收起/切页均保留占用；释放清除测试内存。\n整机模式也增加应用PSS。单位MiB。\nJava堆至少留16MiB，实际目标可能受限。");
        LinearLayout.LayoutParams helpTextParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        helpTextParams.setMargins(dp(5), 0, 0, 0);
        helpCard.addView(help, helpTextParams);
    }

    /** 仅切换可见区域，展开或收起都不改变测试状态和已分配内存。 */
    private void setCollapsed(boolean value) {
        collapsed = value;
        bodyView.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        summaryView.setVisibility(collapsed ? View.VISIBLE : View.GONE);
        renderSnapshot();
        panel.post(this::constrainPosition);
    }

    /** 采用设备原始密度，避免各业务模块AutoSize修改密度导致悬浮框大小跳变。 */
    private int dp(int value) {
        return Math.round(value * android.content.res.Resources.getSystem()
                .getDisplayMetrics().density);
    }

    /** 为浅色面板创建文本，以像素设置字号避免页面AutoSize影响。 */
    private TextView text(Activity activity, float size) {
        TextView view = new TextView(activity);
        view.setTextColor(COLOR_TEXT);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * android.content.res.Resources.getSystem()
                .getDisplayMetrics().density);
        view.setIncludeFontPadding(false);
        view.setLineSpacing(dp(1), 1f);
        return view;
    }

    /** 为相关指标创建浅灰卡片，长文本自动换行而不固定卡片高度。 */
    private LinearLayout card(Activity activity, LinearLayout parent, int orientation) {
        LinearLayout view = new LinearLayout(activity);
        view.setOrientation(orientation);
        view.setPadding(dp(7), dp(6), dp(7), dp(6));
        view.setBackground(roundedBackground(COLOR_SURFACE, 0));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(4));
        parent.addView(view, params);
        return view;
    }

    /** 将指标文字与右侧比例对齐，允许左侧文字在窄窗口中换行。 */
    private LinearLayout metricRow(Activity activity, LinearLayout parent) {
        LinearLayout view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(view);
        return view;
    }

    /** 比例采用主色和稍大字号，不隐藏当前值及上限。 */
    private TextView percentage(Activity activity, LinearLayout parent) {
        TextView view = text(activity, 12);
        view.setTextColor(COLOR_ACCENT);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(4), 0, dp(4), 0);
        parent.addView(view, params);
        return view;
    }

    /** 细进度条只展示当前快照比例，禁用动画以减少高堆压力下的UI分配。 */
    private ProgressBar progress(Activity activity, LinearLayout parent) {
        ProgressBar view = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        view.setIndeterminate(false);
        view.setMax(1000);
        ClipDrawable fill = new ClipDrawable(roundedBackground(COLOR_ACCENT, 0),
                Gravity.LEFT, ClipDrawable.HORIZONTAL);
        LayerDrawable drawable = new LayerDrawable(new android.graphics.drawable.Drawable[]{
                roundedBackground(0xffd8e5e9, 0), fill});
        drawable.setId(0, android.R.id.background);
        drawable.setId(1, android.R.id.progress);
        view.setProgressDrawable(drawable);
        view.setProgressTintList(ColorStateList.valueOf(COLOR_ACCENT));
        view.setProgressBackgroundTintList(ColorStateList.valueOf(0xffd8e5e9));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(4));
        params.setMargins(0, dp(5), 0, 0);
        parent.addView(view, params);
        return view;
    }

    /** 创建圆角背景，仅在建立视图时分配，不随快照刷新重建。 */
    private GradientDrawable roundedBackground(int fillColor, int strokeColor) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fillColor);
        background.setCornerRadius(dp(6));
        if (strokeColor != 0) {
            background.setStroke(dp(1), strokeColor);
        }
        return background;
    }

    /** 用状态背景区分按下和禁用，避免业务主题给按钮叠加默认染色。 */
    private void styleButton(Button view, int fillColor, int strokeColor, int textColor) {
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{-android.R.attr.state_enabled},
                roundedBackground(0xffedf1f4, 0));
        background.addState(new int[]{android.R.attr.state_pressed},
                roundedBackground(fillColor == COLOR_ACCENT ? 0xff008477 : 0xffcdebe6, strokeColor));
        background.addState(new int[]{}, roundedBackground(fillColor, strokeColor));
        view.setBackgroundTintList(null);
        view.setBackground(background);
        view.setTextColor(new ColorStateList(new int[][]{
                new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{0xffa0adb8, textColor}));
        view.setStateListAnimator(null);
        view.setCompoundDrawableTintList(view.getTextColors());
    }

    /** 参数按钮独占一行，操作按钮等宽并保留清楚的触摸范围。 */
    private Button button(Activity activity, LinearLayout parent) {
        Button view = new Button(activity);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(11));
        view.setAllCaps(false);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setMinWidth(0);
        view.setMinimumWidth(0);
        view.setMinHeight(0);
        view.setMinimumHeight(0);
        view.setIncludeFontPadding(false);
        view.setPadding(dp(8), 0, dp(8), 0);
        view.setCompoundDrawablePadding(dp(5));
        styleButton(view, COLOR_ACCENT_SURFACE, 0, COLOR_ACCENT);
        boolean horizontal = parent.getOrientation() == LinearLayout.HORIZONTAL;
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                horizontal ? 0 : LinearLayout.LayoutParams.MATCH_PARENT, dp(32),
                horizontal ? 1f : 0f);
        params.setMargins(horizontal && parent.getChildCount() > 0 ? dp(6) : 0, dp(4), 0, 0);
        parent.addView(view, params);
        return view;
    }

    /** 按预置等级循环调整目标，仅在暂停或未开始时允许操作。 */
    private int nextTarget(int[] targets, int current) {
        for (int index = 0; index < targets.length; index++) {
            if (targets[index] == current) {
                return targets[(index + 1) % targets.length];
            }
        }
        return targets[0];
    }

    /** 快照展示应用PSS、Java堆、测试占用和整机余量，不在UI线程扫描内存。 */
    private void renderSnapshot() {
        if (panel == null) {
            return;
        }
        MemoryPressureSimulator.Snapshot memory = MemoryPressureSimulator.getSnapshot();
        boolean running = MemoryPressureSimulator.isRunning();
        String systemPercent = memory.systemUsedPercent >= 0
                ? String.format(Locale.ROOT, "%.1f%%", memory.systemUsedPercent) : "--";
        String javaPercent = memory.javaMaxMb > 0
                ? String.format(Locale.ROOT, "%.1f%%", 100f * memory.javaUsedMb / memory.javaMaxMb) : "--";
        String compactState = compactState(memory, running);
        titleView.setText("内存测试");
        if (renderedCollapsed == null || renderedCollapsed != collapsed) {
            headerHintView.setBackground(collapsed ? roundedBackground(COLOR_ACCENT_SURFACE, 0) : null);
            headerHintView.setPadding(collapsed ? dp(5) : 0, collapsed ? dp(2) : 0,
                    collapsed ? dp(5) : 0, collapsed ? dp(2) : 0);
            headerArrowView.setImageResource(collapsed
                    ? R.drawable.mc_memory_test_expand : R.drawable.mc_memory_test_collapse);
            renderedCollapsed = collapsed;
        }
        headerHintView.setText(collapsed ? compactState : "拖动移动 · 点击收起");
        headerHintView.setTextColor(collapsed ? COLOR_ACCENT : COLOR_MUTED);
        String summary = "整机 " + systemPercent + " · Java " + javaPercent;
        summaryMetricsView.setText(emphasize(emphasize(summary, systemPercent, 1.2f),
                javaPercent, 1.2f, summary.indexOf("Java")));
        summaryAllocationView.setText("测试占用 " + memory.allocatedMb + " MiB · 点击展开");
        String pss = memory.pssMb >= 0 ? String.valueOf(memory.pssMb) : "--";
        pssValueView.setText(emphasize(pss + (memory.pssMb >= 0 ? " MiB" : ""), pss, 1.6f));
        swapView.setText("Swap " + (memory.swapPssMb >= 0 ? memory.swapPssMb + " MiB" : "--"));
        SpannableString javaMetrics = new SpannableString(
                "Java堆  " + memory.javaUsedMb + " / " + memory.javaMaxMb + " MiB");
        javaMetrics.setSpan(new StyleSpan(Typeface.BOLD), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        javaMetricsView.setText(javaMetrics);
        javaPercentView.setText(javaPercent);
        javaProgressView.setProgress(memory.javaMaxMb > 0
                ? (int) Math.max(0L, Math.min(1000L, memory.javaUsedMb * 1000L / memory.javaMaxMb)) : 0);
        systemMetricsView.setText("可用 " + memory.availableMb + "/" + memory.totalMb + " MiB");
        systemPercentView.setText(systemPercent);
        systemProgressView.setProgress(memory.systemUsedPercent >= 0
                ? Math.max(0, Math.min(1000, Math.round(memory.systemUsedPercent * 10f))) : 0);
        SpannableString allocations = new SpannableString("测试占用\nNative " + memory.nativeAllocatedMb
                + " MiB\nJava " + memory.javaAllocatedMb + " MiB");
        allocations.setSpan(new ForegroundColorSpan(COLOR_MUTED), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        allocationView.setText(allocations);
        String lowMemory = memory.lowMemory ? "是" : "否";
        lowMemoryView.setText(emphasize("低内存 " + lowMemory + "\nTrim " + memory.trimLevel, lowMemory, 1f));
        stateView.setText(emphasize("状态   " + memory.state, memory.state, 1f));
        modeButton.setText("模式：" + (mode == MemoryPressureSimulator.MODE_SYSTEM ? "整机 (Native)"
                : mode == MemoryPressureSimulator.MODE_APP ? "应用 (Java堆)" : "同时 (Native + Java)"));
        systemTargetButton.setText(emphasize("整机目标 " + systemTarget + "%", systemTarget + "%", 1f));
        javaTargetButton.setText(emphasize("应用堆目标 " + javaTarget + "%", javaTarget + "%", 1f));
        modeButton.setEnabled(!running);
        systemTargetButton.setEnabled(!running);
        javaTargetButton.setEnabled(!running);
        if (renderedMode != mode) {
            styleButton(systemTargetButton,
                    mode == MemoryPressureSimulator.MODE_APP ? COLOR_SURFACE : COLOR_ACCENT_SURFACE,
                    0, mode == MemoryPressureSimulator.MODE_APP ? COLOR_MUTED : COLOR_ACCENT);
            styleButton(javaTargetButton,
                    mode == MemoryPressureSimulator.MODE_SYSTEM ? COLOR_SURFACE : COLOR_ACCENT_SURFACE,
                    0, mode == MemoryPressureSimulator.MODE_SYSTEM ? COLOR_MUTED : COLOR_ACCENT);
            renderedMode = mode;
        }
        targetHintView.setText(running ? "增压中不可切换参数，请先暂停。"
                : mode == MemoryPressureSimulator.MODE_APP ? "整机目标仅在整机或同时加压时生效。"
                : mode == MemoryPressureSimulator.MODE_SYSTEM ? "应用堆目标仅在应用或同时加压时生效。"
                : "同时按整机目标和应用堆目标加压。");
        if (renderedRunning == null || renderedRunning != running) {
            styleButton(controlButton, running ? Color.WHITE : COLOR_ACCENT,
                    running ? 0xffa0adb8 : 0, running ? COLOR_TEXT : Color.WHITE);
            controlButton.setCompoundDrawablesRelative(icon(attachedActivity, running
                    ? R.drawable.mc_memory_test_pause : R.drawable.mc_memory_test_play, 14),
                    null, null, null);
            renderedRunning = running;
        }
        controlButton.setEnabled(true);
        controlButton.setText(running ? "暂停" : memory.allocatedMb > 0 ? "继续" : "开始");
        // 旧系统仍可暂停正在运行的测试，只限制重新开始不支持的Native模式。
        if (!running && Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                && mode != MemoryPressureSimulator.MODE_APP) {
            controlButton.setEnabled(false);
            controlButton.setText("需Android 8");
        }
    }

    /** 为矢量图标设置实际密度尺寸，避免字体图标在不同系统中缺字。 */
    private Drawable icon(Activity activity, int resource, int size) {
        Drawable drawable = activity.getResources().getDrawable(resource, activity.getTheme()).mutate();
        drawable.setBounds(0, 0, dp(size), dp(size));
        return drawable;
    }

    /** 标题和说明的装饰图标不单独接收触摸或重复播报。 */
    private ImageView image(Activity activity, int resource, int size) {
        ImageView view = new ImageView(activity);
        view.setImageDrawable(icon(activity, resource, size));
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    /** 参数按钮右侧保留循环图标，按钮禁用时同步变灰。 */
    private void addCycleIcon(Activity activity, Button view) {
        view.setCompoundDrawablesRelative(null, null,
                icon(activity, R.drawable.mc_memory_test_cycle, 14), null);
        view.setContentDescription("点击循环切换参数");
    }

    /** 通过颜色和字号强调关键数值，不为一次刷新新建额外视图。 */
    private CharSequence emphasize(CharSequence value, String token, float scale) {
        return emphasize(value, token, scale, 0);
    }

    /** 相同百分比或未知值重复出现时，从指定区域查找，避免强调错列或叠加字号。 */
    private CharSequence emphasize(CharSequence value, String token, float scale, int fromIndex) {
        SpannableString text = new SpannableString(value);
        int start = value.toString().indexOf(token, fromIndex);
        if (start >= 0 && !token.isEmpty()) {
            int end = start + token.length();
            text.setSpan(new ForegroundColorSpan(COLOR_ACCENT), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (scale != 1f) {
                text.setSpan(new RelativeSizeSpan(scale), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return text;
    }

    /** 用原始采样状态区分未开始、已暂停、已释放和失败，避免统称保留零占用。 */
    private String compactState(MemoryPressureSimulator.Snapshot memory, boolean running) {
        if (running) {
            return "增压中";
        }
        if (memory.state.contains("失败")) {
            return "分配失败";
        }
        if (memory.state.startsWith("已暂停")) {
            return "已暂停";
        }
        if (memory.state.startsWith("已释放")) {
            return "已释放";
        }
        if (memory.state.contains("目标") || memory.state.contains("边界")) {
            return "已达目标";
        }
        return memory.allocatedMb > 0 ? "保留中" : "未开始";
    }

    /** 内容较少时按实际高度收缩，较小时滚动，避免展开面板保留大块空白。 */
    private static final class CappedScrollView extends ScrollView {
        /** 本次页面可用的内容高度上限，单位为像素。 */
        private final int maximumHeight;

        /** 按当前页面创建视图，页面退出时由悬浮框整体卸载。 */
        private CappedScrollView(Activity activity, int maximumHeight) {
            super(activity);
            this.maximumHeight = maximumHeight;
        }

        /** 使用AT_MOST使视图高度同时受内容和页面剩余空间约束。 */
        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int available = View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED
                    ? maximumHeight : Math.min(maximumHeight, View.MeasureSpec.getSize(heightMeasureSpec));
            super.onMeasure(widthMeasureSpec,
                    View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST));
        }
    }

    /** 页面或面板尺寸改变后限制坐标，保证展开、横竖屏切换后仍能操作标题。 */
    private void constrainPosition() {
        if (panel == null || attachedActivity == null || windowParams == null) {
            return;
        }
        View decor = attachedActivity.getWindow().getDecorView();
        positionX = Math.max(0, Math.min(positionX, decor.getWidth() - panel.getWidth()));
        positionY = Math.max(0, Math.min(positionY, decor.getHeight() - panel.getHeight()));
        windowParams.x = positionX;
        windowParams.y = positionY;
        try {
            windowManager.updateViewLayout(panel, windowParams);
        } catch (RuntimeException error) {
            Log.w(MemoryPressureSimulator.TAG, "内存悬浮框位置更新失败", error);
        }
    }

    /** 标题触摸区区分拖动和点击，拖动不触发收起。 */
    private final class DragListener implements View.OnTouchListener {
        /** 手势起点的屏幕横坐标。 */
        private float downX;
        /** 手势起点的屏幕纵坐标。 */
        private float downY;
        /** 手势开始时的窗口横坐标。 */
        private int originX;
        /** 手势开始时的窗口纵坐标。 */
        private int originY;
        /** 移动超过触摸阈值后不再触发标题点击。 */
        private boolean dragging;

        /** 只消费标题触摸，不拦截悬浮框外的业务操作。 */
        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    originX = positionX;
                    originY = positionY;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
                    dragging |= Math.abs(dx) > slop || Math.abs(dy) > slop;
                    if (dragging) {
                        positionX = originX + Math.round(dx);
                        positionY = originY + Math.round(dy);
                        constrainPosition();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragging) {
                        view.performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    return true;
                default:
                    return false;
            }
        }
    }
}
