package io.github.kingslou.memorycontrol;

import android.app.ActivityManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 宿主调试包的进程级内存加压器：保留Native像素与Java数组，供任意模块复现高占用问题。
 * 内存不绑定Activity生命周期；页面退出后仍保留，直到手动释放或进程退出。
 */
public final class MemoryPressureSimulator {
    /** 固定日志标签，方便与系统任务栈、进程退出记录对齐。 */
    public static final String TAG = "MemoryControl";
    /** 整机模式通过本进程Native像素增加设备压力，也会增加应用PSS。 */
    public static final int MODE_SYSTEM = 0;
    /** 应用模式增加本进程Java堆，便于观察GC及业务剩余堆空间。 */
    public static final int MODE_APP = 1;
    /** 同时逐步增加Native像素和Java堆。 */
    public static final int MODE_BOTH = 2;
    /** 应用堆默认达到上限的80%，保留继续操作业务的空间。 */
    private static final int DEFAULT_TARGET_JAVA_PERCENT = 80;
    /** Java分配以2MiB小块推进，每轮最多8MiB。 */
    private static final int JAVA_BLOCK_MB = 2;
    /** 无论目标比例多高，测试器分配时至少保留16MiB堆余量。 */
    private static final long JAVA_RESERVE_BYTES = 16L * 1024L * 1024L;
    /** MB参数采用MiB，即1024×1024字节。 */
    private static final long MIB = 1024L * 1024L;
    /** 默认按整机总量减可用估计计算，尝试达到95%的整机内存使用率。 */
    private static final int DEFAULT_TARGET_USED_PERCENT = 95;
    /** 允许交换页增大Native占用；按整机RAM的两倍限制无效的持续分配。 */
    private static final int MAX_RAM_MULTIPLIER = 2;
    /** 32位地址空间较小，仅对32位进程保留额外分配限制。 */
    private static final int MAX_32_BIT_ALLOCATED_MB = 1536;
    /** 单张Bitmap固定为8MiB，取消操作只需等待当前小块完成。 */
    private static final int BLOCK_MB = 8;
    /** 每轮最多增加32MiB Native及8MiB Java，避免一次分配数GB造成瞬时崩溃。 */
    private static final int BLOCKS_PER_STEP = 4;
    /** 每轮分配后留出业务操作和内核回收的时间。 */
    private static final long STEP_INTERVAL_MS = 1000L;
    /** Bitmap宽度；ARGB_8888每个像素占4字节。 */
    private static final int PIXEL_WIDTH = 1024;
    /** Native填充仅复用64KiB Java缓冲，让Native与Java堆两种压力可以分别控制。 */
    private static final int TILE_HEIGHT = 16;
    /** 每行一半随机像素、一半固定像素，允许内核压缩冷页面。 */
    private static final int DEFAULT_NOISY_PIXELS = 512;
    /** 控制命令按同一把锁排队，使开始、暂停和释放顺序一致。 */
    private static final Object CONTROL_LOCK = new Object();
    /** 只在工作线程读写的Bitmap强引用，防止GC自动释放测试占用。 */
    private static final List<Bitmap> BLOCKS = new ArrayList<>();
    /** 只在工作线程持有Java测试块；暂停保留，手动释放清空强引用。 */
    private static final List<byte[]> JAVA_BLOCKS = new ArrayList<>();
    /** 实际持有的Java数组字节数，不包含业务堆内存。 */
    private static long javaAllocatedBytes;
    /** Native达到边界时的原因，仅由工作线程更新。 */
    private static String nativeBoundary = "";
    /** 合并悬浮框采样请求，防止分配较慢时排队积压。 */
    private static boolean samplePending;
    /** 最近一次系统回收等级，-1表示未收到通知。 */
    private static volatile int lastTrimLevel = -1;
    /** 只使用Application Context，避免持有任何业务页面。 */
    private static Context applicationContext;
    /** 宿主可调试时按需建立工作线程，发布 AAR 自身的构建类型不影响此判断。 */
    private static Handler worker;
    /** 取消代号；页面线程可以立即使正在分配的旧轮次失效。 */
    private static volatile int generation;
    /** 点击开始后立即更新，避免快速连点建立多个加压循环。 */
    private static volatile boolean running;
    /** 最近一次只读快照，页面刷新不直接扫描内存或执行分配。 */
    private static volatile Snapshot snapshot = new Snapshot("未开始", -1, 0, 0, 0, -1f, -1, 0, 0, 0, 0, false);
    /** 实际持有的像素字节，只在工作线程维护。 */
    private static long allocatedBytes;
    /** 可复现的随机序列，用于填充部分难压缩像素。 */
    private static final Random RANDOM = new Random(20260922L);
    /** 循环复用的Java填充缓冲，总量仅64KiB。 */
    private static int[] pixelTile;
    /** 循环复用的系统内存采样对象。 */
    private static final ActivityManager.MemoryInfo SYSTEM_MEMORY = new ActivityManager.MemoryInfo();
    /** 循环复用的当前主进程内存采样对象。 */
    private static final Debug.MemoryInfo PROCESS_MEMORY = new Debug.MemoryInfo();
    /** 仅在持有测试内存时注册应用级内存回调，避免详情页退出后丢失压力信号。 */
    private static boolean callbacksRegistered;
    /** 回调只记录事件，绝不调用移除任务、启动首页或自动完成业务。 */
    private static final ComponentCallbacks2 MEMORY_CALLBACKS = new ComponentCallbacks2() {
        /** 保留原始level，UI_HIDDEN等状态不能直接解释为低内存。 */
        @Override
        public void onTrimMemory(int level) {
            lastTrimLevel = level;
            recordCheckpoint("ON_TRIM_MEMORY level=" + level);
        }

        /** 记录系统传给本进程的低内存通知。 */
        @Override
        public void onLowMemory() {
            recordCheckpoint("ON_LOW_MEMORY");
        }

        /** 配置变化与本次测试无关，不持有任何Activity或资源对象。 */
        @Override
        public void onConfigurationChanged(Configuration configuration) {
        }
    };

    /** 工具类只通过静态方法使用。 */
    private MemoryPressureSimulator() {
    }

    /** 返回是否正在持续增加内存；暂停或达到边界后已有Native与Java块仍被保留。 */
    public static boolean isRunning() {
        return running;
    }

    /** 返回最近一次工作线程快照，不触发主线程内存扫描。 */
    public static Snapshot getSnapshot() {
        return snapshot;
    }

    /** 按默认95%的整机使用率开始或继续加压，调用方可以是Activity。 */
    public static void start(Context context) {
        start(context, DEFAULT_TARGET_USED_PERCENT, DEFAULT_NOISY_PIXELS);
    }

    /**
     * 在当前进程后台逐步增加Native像素内存。
     * @param context 只保留其Application Context
     * @param targetUsedPercent 整机使用率目标，范围1～99，按(totalMem-availMem)/totalMem计算
     * @param noisyPixelsPerRow 每行随机像素数量，0易压缩、1024较难压缩，不保证具体压缩比
     */
    public static void start(Context context, int targetUsedPercent, int noisyPixelsPerRow) {
        start(context, MODE_SYSTEM, targetUsedPercent, DEFAULT_TARGET_JAVA_PERCENT, noisyPixelsPerRow);
    }

    /**
     * 以指定模式在当前应用进程增压，暂停后可修改目标并继续，已有占用不会自动释放。
     * @param context 只持有其Application Context，不保留业务Activity
     * @param mode 整机Native、应用Java堆或同时加压
     * @param targetUsedPercent 整机占用目标，范围1～99
     * @param targetJavaPercent Java已用堆占最大堆比例，范围1～95，另保留16MiB余量
     * @param noisyPixelsPerRow Native像素每行随机数量，范围0～1024
     */
    public static void start(Context context, int mode, int targetUsedPercent,
                             int targetJavaPercent, int noisyPixelsPerRow) {
        if (!DebugEnvironment.isDebuggable(context)) {
            return;
        }
        if (mode != MODE_APP && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.w(TAG, "需要Android 8.0以上，避免Bitmap像素落入Java堆");
            return;
        }
        if (mode < MODE_SYSTEM || mode > MODE_BOTH
                || targetJavaPercent < 1 || targetJavaPercent > 95
                || targetUsedPercent < 1 || targetUsedPercent > 99
                || noisyPixelsPerRow < 0 || noisyPixelsPerRow > PIXEL_WIDTH) {
            throw new IllegalArgumentException("内存测试参数越界");
        }
        synchronized (CONTROL_LOCK) {
            if (running) {
                return;
            }
            applicationContext = context.getApplicationContext();
            running = true;
            int token = ++generation;
            getWorkerLocked().post(() -> {
                if (token != generation) {
                    return;
                }
                try {
                    if (mode != MODE_APP && pixelTile == null) {
                        pixelTile = new int[PIXEL_WIDTH * TILE_HEIGHT];
                    }
                    if (!callbacksRegistered) {
                        applicationContext.registerComponentCallbacks(MEMORY_CALLBACKS);
                        callbacksRegistered = true;
                    }
                    Log.i(TAG, "START pid=" + Process.myPid()
                            + " mode=" + mode + " targetSystemUsedPercent=" + targetUsedPercent
                            + " targetJavaPercent=" + targetJavaPercent
                            + " noisyPixelsPerRow=" + noisyPixelsPerRow);
                    runStep(token, mode, targetUsedPercent, targetJavaPercent, noisyPixelsPerRow);
                } catch (OutOfMemoryError | RuntimeException error) {
                    handleAllocationFailure(token, error);
                }
            });
        }
    }

    /** 请求一次后台采样；仅打开悬浮框即可看到基线，主线程不读取PSS。 */
    public static void requestSample(Context context) {
        if (!DebugEnvironment.isDebuggable(context)) {
            return;
        }
        synchronized (CONTROL_LOCK) {
            if (samplePending) {
                return;
            }
            applicationContext = context.getApplicationContext();
            samplePending = true;
            getWorkerLocked().post(() -> {
                try {
                    sampleAndLog(snapshot.state);
                } finally {
                    synchronized (CONTROL_LOCK) {
                        samplePending = false;
                    }
                }
            });
        }
    }

    /** 暂停继续分配，保留已有Native和Java内存，使任意业务模块仍处于加压状态。 */
    public static void pause() {
        synchronized (CONTROL_LOCK) {
            running = false;
            int token = ++generation;
            if (worker != null) {
                worker.post(() -> {
                    if (token == generation) {
                        sampleAndLog("已暂停，保留占用");
                        monitorHeldMemory(token);
                    }
                });
            }
        }
    }

    /** 取消加压并在工作线程释放两类测试块；不要随业务页面销毁自动调用。 */
    public static void release() {
        synchronized (CONTROL_LOCK) {
            running = false;
            ++generation;
            if (worker != null) {
                worker.post(() -> {
                    releaseBlocks();
                    // 仅手动释放时请求回收Java测试数组，实际归还时间由运行时决定。
                    System.gc();
                    sampleAndLog("已释放测试占用");
                });
            }
        }
    }

    /**
     * 为提交、接口成功及页面销毁留下内存现场；只在Debug采样线程建立后记录。
     * 调用时间先写入日志，后台完整采样另含自己的时间，避免误把二者当同步快照。
     */
    public static void recordCheckpoint(String event) {
        synchronized (CONTROL_LOCK) {
            if (worker == null) {
                return;
            }
            Log.i(TAG, "EVENT elapsedMs=" + SystemClock.elapsedRealtime() + " pid="
                    + Process.myPid() + " " + event + " allocatedMb=" + snapshot.allocatedMb);
            worker.post(() -> sampleAndLog(snapshot.state));
        }
    }

    /** 必须持有控制锁后调用；两类内存分配、回收及采样均落在此线程。 */
    private static Handler getWorkerLocked() {
        if (worker == null) {
            HandlerThread thread = new HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            worker = new Handler(thread.getLooper());
        }
        return worker;
    }

    /** 先增加Java堆，再按整机余量增加Native；两种目标独立到达后保留占用。 */
    private static void runStep(int token, int mode, int targetUsedPercent,
                                int targetJavaPercent, int noisyPixelsPerRow) {
        if (token != generation || !running) {
            return;
        }
        try {
            boolean javaDone = mode == MODE_SYSTEM || allocateJavaStep(token, targetJavaPercent);
            boolean nativeDone = mode == MODE_APP
                    || allocateNativeStep(token, targetUsedPercent, noisyPixelsPerRow);
            if (token != generation || !running) {
                return;
            }
            if (javaDone && nativeDone) {
                hold(token, mode == MODE_APP ? "达到应用堆目标或余量边界，保留占用"
                        : mode == MODE_SYSTEM ? nativeBoundary
                        : nativeBoundary + "；应用堆到达目标或余量边界");
                return;
            }
            sampleAndLog("正在增压");
            worker.postDelayed(() -> runStep(token, mode, targetUsedPercent,
                    targetJavaPercent, noisyPixelsPerRow), STEP_INTERVAL_MS);
        } catch (OutOfMemoryError | RuntimeException error) {
            handleAllocationFailure(token, error);
        }
    }

    /** 每块检查总堆用量并写满数组；返回是否达到目标或必须保留业务堆余量。 */
    private static boolean allocateJavaStep(int token, int targetJavaPercent) {
        Runtime runtime = Runtime.getRuntime();
        long targetBytes = Math.min(runtime.maxMemory() * targetJavaPercent / 100L,
                runtime.maxMemory() - JAVA_RESERVE_BYTES);
        for (int index = 0; index < BLOCKS_PER_STEP; index++) {
            if (token != generation || !running) {
                return false;
            }
            long usedBytes = runtime.totalMemory() - runtime.freeMemory();
            long remainingBytes = targetBytes - usedBytes;
            if (remainingBytes <= 0) {
                return true;
            }
            int bytes = (int) Math.min(JAVA_BLOCK_MB * MIB, remainingBytes);
            byte[] block = new byte[bytes];
            RANDOM.nextBytes(block);
            if (token != generation || !running) {
                return false;
            }
            JAVA_BLOCKS.add(block);
            javaAllocatedBytes += bytes;
        }
        return runtime.totalMemory() - runtime.freeMemory() >= targetBytes;
    }

    /** 每块检查整机余量及地址空间边界，达到边界不阻止混合模式的Java堆增压。 */
    private static boolean allocateNativeStep(int token, int targetUsedPercent, int noisyPixelsPerRow) {
        ActivityManager manager = (ActivityManager) applicationContext
                .getSystemService(Context.ACTIVITY_SERVICE);
        long blockBytes = BLOCK_MB * MIB;
        for (int index = 0; index < BLOCKS_PER_STEP; index++) {
            if (token != generation || !running) {
                return false;
            }
            manager.getMemoryInfo(SYSTEM_MEMORY);
            if (SYSTEM_MEMORY.totalMem <= 0 || SYSTEM_MEMORY.availMem < 0) {
                nativeBoundary = "无法读取整机内存，保留占用";
                return true;
            }
            long targetAvailableBytes = SYSTEM_MEMORY.totalMem * (100L - targetUsedPercent) / 100L;
            if (SYSTEM_MEMORY.availMem <= targetAvailableBytes) {
                nativeBoundary = "达到整机" + targetUsedPercent + "%目标，保留占用";
                return true;
            }
            long maxAllocatedBytes = SYSTEM_MEMORY.totalMem * MAX_RAM_MULTIPLIER;
            if (!Process.is64Bit()) {
                maxAllocatedBytes = Math.min(maxAllocatedBytes, MAX_32_BIT_ALLOCATED_MB * MIB);
            }
            if (allocatedBytes + javaAllocatedBytes + blockBytes > maxAllocatedBytes) {
                nativeBoundary = "达到动态分配上限，保留占用";
                return true;
            }
            allocateBlock(token, noisyPixelsPerRow);
        }
        return false;
    }

    /** 写入每一页像素以提交真实内存；不上传GPU，也不把Bitmap交给任何View绘制。 */
    private static void allocateBlock(int token, int noisyPixelsPerRow) {
        Bitmap bitmap = Bitmap.createBitmap(PIXEL_WIDTH, 2048, Bitmap.Config.ARGB_8888);
        boolean retained = false;
        try {
            for (int y = 0; y < bitmap.getHeight(); y += TILE_HEIGHT) {
                if (token != generation || !running) {
                    return;
                }
                for (int row = 0; row < TILE_HEIGHT; row++) {
                    int offset = row * PIXEL_WIDTH;
                    for (int x = 0; x < PIXEL_WIDTH; x++) {
                        pixelTile[offset + x] = x < noisyPixelsPerRow
                                ? 0xff000000 | (RANDOM.nextInt() & 0x00ffffff) : 0xff202020;
                    }
                }
                bitmap.setPixels(pixelTile, 0, PIXEL_WIDTH, 0, y, PIXEL_WIDTH, TILE_HEIGHT);
            }
            BLOCKS.add(bitmap);
            allocatedBytes += bitmap.getAllocationByteCount();
            retained = true;
        } finally {
            if (!retained) {
                bitmap.recycle();
            }
        }
    }

    /** 达到边界只暂停新分配，已有强引用保持到手动释放。 */
    private static void hold(int token, String reason) {
        synchronized (CONTROL_LOCK) {
            if (token != generation) {
                return;
            }
            running = false;
        }
        sampleAndLog(reason);
        monitorHeldMemory(token);
    }

    /** 暂停后持续观察保留内存；取消代号阻止旧监控与下一轮加压并存。 */
    private static void monitorHeldMemory(int token) {
        worker.postDelayed(() -> {
            if (token == generation && !running && (allocatedBytes > 0 || javaAllocatedBytes > 0)) {
                sampleAndLog(snapshot.state);
                monitorHeldMemory(token);
            }
        }, 2000L);
    }

    /** 回收测试器自己的Native像素并解除Java数组强引用，不触碰业务缓存。 */
    private static void releaseBlocks() {
        for (Bitmap bitmap : BLOCKS) {
            bitmap.recycle();
        }
        BLOCKS.clear();
        allocatedBytes = 0;
        JAVA_BLOCKS.clear();
        javaAllocatedBytes = 0;
        pixelTile = null;
        if (callbacksRegistered) {
            applicationContext.unregisterComponentCallbacks(MEMORY_CALLBACKS);
            callbacksRegistered = false;
        }
    }

    /** 分配失败先释放测试内存；该保护不能捕获LMK或Native进程崩溃。 */
    private static void handleAllocationFailure(int token, Throwable error) {
        synchronized (CONTROL_LOCK) {
            // 旧轮次失败仍需释放测试块，但不能撤销用户刚排队的新开始命令。
            if (token == generation) {
                running = false;
                ++generation;
            }
        }
        releaseBlocks();
        Log.e(TAG, "ALLOCATION_FAILED，已释放测试内存", error);
        sampleAndLog("分配失败，已释放测试占用");
    }

    /** 记录PSS和整机比例；整机目标使用同一次MemoryInfo的总量和可用估计。 */
    private static void sampleAndLog(String state) {
        try {
            ActivityManager manager = (ActivityManager) applicationContext
                    .getSystemService(Context.ACTIVITY_SERVICE);
            manager.getMemoryInfo(SYSTEM_MEMORY);
            Debug.getMemoryInfo(PROCESS_MEMORY);
            Runtime runtime = Runtime.getRuntime();
            snapshot = new Snapshot(state, PROCESS_MEMORY.getTotalPss() / 1024,
                    (int) ((allocatedBytes + javaAllocatedBytes) / MIB), SYSTEM_MEMORY.availMem / MIB,
                    SYSTEM_MEMORY.totalMem / MIB, calculateSystemUsedPercent(), readSwapPssMb(),
                    (int) (allocatedBytes / MIB), (int) (javaAllocatedBytes / MIB),
                    (runtime.totalMemory() - runtime.freeMemory()) / MIB,
                    runtime.maxMemory() / MIB, SYSTEM_MEMORY.lowMemory);
            Log.i(TAG, "SAMPLE elapsedMs=" + SystemClock.elapsedRealtime() + " pid="
                    + Process.myPid() + " state=" + state + " pssMb=" + snapshot.pssMb
                    + " syntheticMb=" + snapshot.allocatedMb + " systemAvailMb="
                    + snapshot.availableMb + " systemTotalMb=" + snapshot.totalMb
                    + " systemUsedPercent=" + snapshot.systemUsedPercent
                    + " swapPssMb=" + snapshot.swapPssMb
                    + " javaSyntheticMb=" + snapshot.javaAllocatedMb
                    + " javaUsedMb=" + snapshot.javaUsedMb + " javaMaxMb=" + snapshot.javaMaxMb
                    + " trimLevel=" + snapshot.trimLevel
                    + " nativeAllocatedMb=" + Debug.getNativeHeapAllocatedSize() / MIB
                    + " lowMemory=" + SYSTEM_MEMORY.lowMemory);
        } catch (RuntimeException | OutOfMemoryError error) {
            Log.w(TAG, "内存采样失败，保留上一次PSS并更新测试占用", error);
            snapshot = new Snapshot(state, snapshot.pssMb,
                    (int) ((allocatedBytes + javaAllocatedBytes) / MIB),
                    snapshot.availableMb, snapshot.totalMb, snapshot.systemUsedPercent,
                    snapshot.swapPssMb, (int) (allocatedBytes / MIB), (int) (javaAllocatedBytes / MIB),
                    snapshot.javaUsedMb, snapshot.javaMaxMb, snapshot.lowMemory);
        }
    }

    /** 用整机总量减可用估计计算百分比；总量未提供时返回-1。 */
    private static float calculateSystemUsedPercent() {
        if (SYSTEM_MEMORY.totalMem <= 0 || SYSTEM_MEMORY.availMem < 0) {
            return -1f;
        }
        return Math.max(0f, Math.min(100f,
                100f * (1f - (float) SYSTEM_MEMORY.availMem / SYSTEM_MEMORY.totalMem)));
    }

    /** 可选的交换字段缺失时返回-1，不影响PSS采样和分配边界判断。 */
    private static int readSwapPssMb() {
        try {
            String value = PROCESS_MEMORY.getMemoryStat("summary.total-swap-pss");
            return value == null ? -1 : Integer.parseInt(value) / 1024;
        } catch (RuntimeException error) {
            return -1;
        }
    }

    /** 不可变快照，可以从主线程读取而不持有Activity或Bitmap引用。 */
    public static final class Snapshot {
        /** 当前加压或释放状态；检查点采样不会覆盖操作状态。 */
        public final String state;
        /** 本进程的总PSS估计，单位MiB。 */
        public final int pssMb;
        /** 测试器持有的Native及Java总量，单位MiB，不等于总PSS。 */
        public final int allocatedMb;
        /** ActivityManager提供的系统可用估计，不直接标成/proc的MemAvailable。 */
        public final long availableMb;
        /** 内核可访问的整机RAM总量，单位MiB，不使用营销标称容量。 */
        public final long totalMb;
        /** 按(totalMem-availMem)/totalMem估算的整机使用率；-1表示未采样。 */
        public final float systemUsedPercent;
        /** 公开API可读的SwapPss，单位MiB；-1表示未提供。 */
        public final int swapPssMb;

        /** 测试器持有的Native像素量，单位MiB。 */
        public final int nativeAllocatedMb;
        /** 测试器持有的Java数组量，单位MiB。 */
        public final int javaAllocatedMb;
        /** 应用全部已用Java堆，包含业务对象和测试数组，单位MiB。 */
        public final long javaUsedMb;
        /** 应用最大Java堆容量，单位MiB，不等于设备RAM。 */
        public final long javaMaxMb;
        /** 系统当前是否处于低内存状态。 */
        public final boolean lowMemory;
        /** 最近收到的回收等级；UI_HIDDEN只表示切到后台。 */
        public final int trimLevel;
        /** 构造只包含数值和状态字符串的快照。 */
        private Snapshot(String state, int pssMb, int allocatedMb, long availableMb,
                         long totalMb, float systemUsedPercent, int swapPssMb,
                         int nativeAllocatedMb, int javaAllocatedMb,
                         long javaUsedMb, long javaMaxMb, boolean lowMemory) {
            this.state = state;
            this.pssMb = pssMb;
            this.allocatedMb = allocatedMb;
            this.availableMb = availableMb;
            this.totalMb = totalMb;
            this.systemUsedPercent = systemUsedPercent;
            this.swapPssMb = swapPssMb;
            this.nativeAllocatedMb = nativeAllocatedMb;
            this.javaAllocatedMb = javaAllocatedMb;
            this.javaUsedMb = javaUsedMb;
            this.javaMaxMb = javaMaxMb;
            this.lowMemory = lowMemory;
            this.trimLevel = lastTrimLevel;
        }
    }
}
