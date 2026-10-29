package io.github.kingslou.memorycontrol

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.Random

/**
 * 宿主调试包的进程级内存加压器，保留 Native 像素与 Java 数组。
 * 不绑定 Activity 生命周期，测试内存保持到手动释放或进程退出。
 */
object MemoryPressureSimulator {
    /** 固定日志标签，便于对齐进程退出及系统回收记录。 */
    const val TAG = "MemoryControl"
    /** 通过本进程 Native 像素增加设备压力，同时增加应用 PSS。 */
    const val MODE_SYSTEM = 0
    /** 增加本进程 Java 堆，便于观察 GC 和业务剩余空间。 */
    const val MODE_APP = 1
    /** 同时推进 Native 和 Java 堆分配。 */
    const val MODE_BOTH = 2
    /** Java 堆默认目标比例。 */
    private const val DEFAULT_TARGET_JAVA_PERCENT = 80
    /** 每块 Java 数组至多 2 MiB。 */
    private const val JAVA_BLOCK_MB = 2
    /** 分配时至少保留 16 MiB 的 Java 堆余量。 */
    private const val JAVA_RESERVE_BYTES = 16L * 1024 * 1024
    /** 对外 Mb 字段和内部分配参数统一使用 MiB。 */
    private const val MIB = 1024L * 1024
    /** 默认整机目标按总量减可用估计计算。 */
    private const val DEFAULT_TARGET_USED_PERCENT = 95
    /** 使用两倍 RAM 限制压缩和交换导致的持续分配。 */
    private const val MAX_RAM_MULTIPLIER = 2
    /** 32 位进程额外受地址空间上限约束。 */
    private const val MAX_32_BIT_ALLOCATED_MB = 1536
    /** 单张像素块为 8 MiB，取消只等待当前小块完成。 */
    private const val BLOCK_MB = 8
    /** 每轮至多增加 32 MiB Native 和 8 MiB Java。 */
    private const val BLOCKS_PER_STEP = 4
    /** 每轮后为业务操作和系统回收留出时间。 */
    private const val STEP_INTERVAL_MS = 1000L
    /** ARGB_8888 像素每行宽度。 */
    private const val PIXEL_WIDTH = 1024
    /** 复用 64 KiB 的 Java 像素填充缓冲。 */
    private const val TILE_HEIGHT = 16
    /** 每行默认一半随机像素、一半固定像素。 */
    private const val DEFAULT_NOISY_PIXELS = 512
    /** 串行排队开始、暂停、释放和采样命令。 */
    private val controlLock = Any()
    /** 仅工作线程读写的像素块强引用。 */
    private val blocks = ArrayList<Bitmap>()
    /** 仅工作线程读写的 Java 数组强引用。 */
    private val javaBlocks = ArrayList<ByteArray>()
    /** 测试器持有的 Java 数组字节数，不包括业务对象。 */
    private var javaAllocatedBytes = 0L
    /** Native 达到边界的原因，仅工作线程更新。 */
    private var nativeBoundary = ""
    /** 合并采样请求，防止后台队列积压。 */
    private var samplePending = false
    /** 最近收到的系统回收等级，-1 表示未收到。 */
    @Volatile private var lastTrimLevel = -1
    /** 只保存 Application Context，不保存业务页面。 */
    private lateinit var applicationContext: Context
    /** 按需建立、进程内复用的后台执行器。 */
    private var worker: Handler? = null
    /** 取消代号，使旧轮次和旧监控立即失效。 */
    @Volatile private var generation = 0
    /** 开始命令提交时立即更新，防止重复启动循环。 */
    @Volatile private var running = false
    /** 最近完成的只读快照，不在主线程读取 PSS。 */
    @Volatile private var latestSnapshot = Snapshot("未开始", -1, 0, 0, 0, -1f, -1, 0, 0, 0, 0, false)
    /** 测试器持有的像素字节数，仅工作线程更新。 */
    private var allocatedBytes = 0L
    /** 固定种子让分配数据可复现。 */
    private val random = Random(20260922L)
    /** Native 模式按需分配并循环复用的填充缓冲。 */
    private var pixelTile: IntArray? = null
    /** 工作线程复用的整机采样对象。 */
    private val systemMemory = ActivityManager.MemoryInfo()
    /** 工作线程复用的进程采样对象。 */
    private val processMemory = Debug.MemoryInfo()
    /** 仅在持有测试内存期间注册系统内存回调。 */
    private var callbacksRegistered = false
    /** 回调只记录事件，不改变宿主页面或任务栈。 */
    private val memoryCallbacks = object : ComponentCallbacks2 {
        /** 保留原始等级，UI_HIDDEN 不能直接解释为低内存。 */
        override fun onTrimMemory(level: Int) {
            lastTrimLevel = level
            recordCheckpoint("ON_TRIM_MEMORY level=$level")
        }

        /** 记录系统向本进程发送的低内存通知。 */
        override fun onLowMemory() = recordCheckpoint("ON_LOW_MEMORY")

        /** 配置变化不参与加压，不保存资源或 Activity。 */
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
    }

    /** 返回是否仍在持续增加内存；暂停后保留已有测试占用。 */
    @JvmStatic
    fun isRunning(): Boolean = running

    /** 返回最近完成的不可变快照，不触发同步采样。 */
    @JvmStatic
    fun getSnapshot(): Snapshot = latestSnapshot

    /** 使用 Native 模式、95% 整机目标及默认像素随机度开始或继续。 */
    @JvmStatic
    fun start(context: Context) = start(context, DEFAULT_TARGET_USED_PERCENT, DEFAULT_NOISY_PIXELS)

    /**
     * 在后台增加 Native 像素占用，只保留 [context] 的 Application Context。
     * @param targetUsedPercent 整机目标，范围 1～99
     * @param noisyPixelsPerRow 每行随机像素数量，范围 0～1024
     */
    @JvmStatic
    fun start(context: Context, targetUsedPercent: Int, noisyPixelsPerRow: Int) =
        start(context, MODE_SYSTEM, targetUsedPercent, DEFAULT_TARGET_JAVA_PERCENT, noisyPixelsPerRow)

    /**
     * 在当前进程按指定模式增压，运行中重复调用不改变现有任务。
     * @param context 只保留 Application Context
     * @param mode [MODE_SYSTEM]、[MODE_APP] 或 [MODE_BOTH]
     * @param targetUsedPercent 整机目标，范围 1～99
     * @param targetJavaPercent Java 已用堆目标，范围 1～95，另保留至少 16 MiB
     * @param noisyPixelsPerRow Native 每行随机像素数量，范围 0～1024
     */
    @JvmStatic
    fun start(context: Context, mode: Int, targetUsedPercent: Int, targetJavaPercent: Int,
              noisyPixelsPerRow: Int) {
        if (!DebugEnvironment.isDebuggable(context)) return
        if (mode != MODE_APP && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.w(TAG, "需要Android 8.0以上，避免Bitmap像素落入Java堆")
            return
        }
        require(mode in MODE_SYSTEM..MODE_BOTH && targetJavaPercent in 1..95 &&
            targetUsedPercent in 1..99 && noisyPixelsPerRow in 0..PIXEL_WIDTH) { "内存测试参数越界" }
        synchronized(controlLock) {
            if (running) return
            applicationContext = context.applicationContext
            running = true
            val token = ++generation
            getWorkerLocked().post {
                if (token != generation) return@post
                try {
                    if (mode != MODE_APP && pixelTile == null) pixelTile = IntArray(PIXEL_WIDTH * TILE_HEIGHT)
                    if (!callbacksRegistered) {
                        applicationContext.registerComponentCallbacks(memoryCallbacks)
                        callbacksRegistered = true
                    }
                    Log.i(TAG, "START pid=${Process.myPid()} mode=$mode targetSystemUsedPercent=$targetUsedPercent" +
                        " targetJavaPercent=$targetJavaPercent noisyPixelsPerRow=$noisyPixelsPerRow")
                    runStep(token, mode, targetUsedPercent, targetJavaPercent, noisyPixelsPerRow)
                } catch (error: OutOfMemoryError) {
                    handleAllocationFailure(token, error)
                } catch (error: RuntimeException) {
                    handleAllocationFailure(token, error)
                }
            }
        }
    }

    /** 请求一次合并后的后台采样；读取 [getSnapshot] 不等待本次任务完成。 */
    @JvmStatic
    fun requestSample(context: Context) {
        if (!DebugEnvironment.isDebuggable(context)) return
        synchronized(controlLock) {
            if (samplePending) return
            applicationContext = context.applicationContext
            samplePending = true
            getWorkerLocked().post {
                try {
                    sampleAndLog(latestSnapshot.state)
                } finally {
                    synchronized(controlLock) { samplePending = false }
                }
            }
        }
    }

    /** 停止新分配，保留 Native 和 Java 测试块，并异步更新快照。 */
    @JvmStatic
    fun pause() {
        synchronized(controlLock) {
            running = false
            val token = ++generation
            worker?.post {
                if (token == generation) {
                    sampleAndLog("已暂停，保留占用")
                    monitorHeldMemory(token)
                }
            }
        }
    }

    /** 取消增压并异步释放测试块；不应随业务页面销毁自动调用。 */
    @JvmStatic
    fun release() {
        synchronized(controlLock) {
            running = false
            ++generation
            worker?.post {
                releaseBlocks()
                // 仅手动释放时请求回收测试数组，归还时间由运行时决定。
                System.gc()
                sampleAndLog("已释放测试占用")
            }
        }
    }

    /**
     * 为业务事件记录发生时间及后续后台采样，二者不是同步快照。
     * @param event 事件描述；尚未建立工作线程时忽略
     */
    @JvmStatic
    fun recordCheckpoint(event: String) {
        synchronized(controlLock) {
            val handler = worker ?: return
            Log.i(TAG, "EVENT elapsedMs=${SystemClock.elapsedRealtime()} pid=${Process.myPid()} " +
                "$event allocatedMb=${latestSnapshot.allocatedMb}")
            handler.post { sampleAndLog(latestSnapshot.state) }
        }
    }

    /** 必须持有控制锁；分配、回收和完整采样均在此执行器串行运行。 */
    private fun getWorkerLocked(): Handler = worker ?: run {
        val thread = HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        Handler(thread.looper).also { worker = it }
    }

    /** 先增加 Java 堆，再增加 Native 占用，两类目标分别判断。 */
    private fun runStep(token: Int, mode: Int, targetUsedPercent: Int, targetJavaPercent: Int,
                        noisyPixelsPerRow: Int) {
        if (token != generation || !running) return
        try {
            val javaDone = mode == MODE_SYSTEM || allocateJavaStep(token, targetJavaPercent)
            val nativeDone = mode == MODE_APP || allocateNativeStep(token, targetUsedPercent, noisyPixelsPerRow)
            if (token != generation || !running) return
            if (javaDone && nativeDone) {
                hold(token, when (mode) {
                    MODE_APP -> "达到应用堆目标或余量边界，保留占用"
                    MODE_SYSTEM -> nativeBoundary
                    else -> "$nativeBoundary；应用堆到达目标或余量边界"
                })
                return
            }
            sampleAndLog("正在增压")
            worker?.postDelayed({ runStep(token, mode, targetUsedPercent, targetJavaPercent, noisyPixelsPerRow) },
                STEP_INTERVAL_MS)
        } catch (error: OutOfMemoryError) {
            handleAllocationFailure(token, error)
        } catch (error: RuntimeException) {
            handleAllocationFailure(token, error)
        }
    }

    /** 写满 Java 数组并逐块检查堆余量；返回是否达到目标或边界。 */
    private fun allocateJavaStep(token: Int, targetJavaPercent: Int): Boolean {
        val runtime = Runtime.getRuntime()
        val targetBytes = minOf(runtime.maxMemory() * targetJavaPercent / 100L,
            runtime.maxMemory() - JAVA_RESERVE_BYTES)
        for (index in 0 until BLOCKS_PER_STEP) {
            if (token != generation || !running) return false
            val remainingBytes = targetBytes - (runtime.totalMemory() - runtime.freeMemory())
            if (remainingBytes <= 0) return true
            val bytes = minOf(JAVA_BLOCK_MB * MIB, remainingBytes).toInt()
            val block = ByteArray(bytes)
            random.nextBytes(block)
            if (token != generation || !running) return false
            javaBlocks.add(block)
            javaAllocatedBytes += bytes
        }
        return runtime.totalMemory() - runtime.freeMemory() >= targetBytes
    }

    /** 检查整机余量及地址空间上限；Native 到达边界不阻止 Java 堆增压。 */
    private fun allocateNativeStep(token: Int, targetUsedPercent: Int, noisyPixelsPerRow: Int): Boolean {
        val manager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val blockBytes = BLOCK_MB * MIB
        for (index in 0 until BLOCKS_PER_STEP) {
            if (token != generation || !running) return false
            manager.getMemoryInfo(systemMemory)
            if (systemMemory.totalMem <= 0 || systemMemory.availMem < 0) {
                nativeBoundary = "无法读取整机内存，保留占用"
                return true
            }
            val targetAvailableBytes = systemMemory.totalMem * (100L - targetUsedPercent) / 100L
            if (systemMemory.availMem <= targetAvailableBytes) {
                nativeBoundary = "达到整机${targetUsedPercent}%目标，保留占用"
                return true
            }
            var maxAllocatedBytes = systemMemory.totalMem * MAX_RAM_MULTIPLIER
            if (!Process.is64Bit()) maxAllocatedBytes = minOf(maxAllocatedBytes, MAX_32_BIT_ALLOCATED_MB * MIB)
            if (allocatedBytes + javaAllocatedBytes + blockBytes > maxAllocatedBytes) {
                nativeBoundary = "达到动态分配上限，保留占用"
                return true
            }
            allocateBlock(token, noisyPixelsPerRow)
        }
        return false
    }

    /** 写入每页像素提交真实内存，未保留的局部块在取消或失败时立即回收。 */
    private fun allocateBlock(token: Int, noisyPixelsPerRow: Int) {
        val bitmap = Bitmap.createBitmap(PIXEL_WIDTH, 2048, Bitmap.Config.ARGB_8888)
        var retained = false
        try {
            val tile = checkNotNull(pixelTile)
            for (y in 0 until bitmap.height step TILE_HEIGHT) {
                if (token != generation || !running) return
                for (row in 0 until TILE_HEIGHT) {
                    val offset = row * PIXEL_WIDTH
                    for (x in 0 until PIXEL_WIDTH) {
                        tile[offset + x] = if (x < noisyPixelsPerRow)
                            0xff000000.toInt() or (random.nextInt() and 0x00ffffff) else 0xff202020.toInt()
                    }
                }
                bitmap.setPixels(tile, 0, PIXEL_WIDTH, 0, y, PIXEL_WIDTH, TILE_HEIGHT)
            }
            blocks.add(bitmap)
            allocatedBytes += bitmap.allocationByteCount
            retained = true
        } finally {
            if (!retained) bitmap.recycle()
        }
    }

    /** 到达边界后保留强引用，只暂停新增分配。 */
    private fun hold(token: Int, reason: String) {
        synchronized(controlLock) {
            if (token != generation) return
            running = false
        }
        sampleAndLog(reason)
        monitorHeldMemory(token)
    }

    /** 暂停后持续采样保留量，取消代号阻止旧监控与下一轮并存。 */
    private fun monitorHeldMemory(token: Int) {
        worker?.postDelayed({
            if (token == generation && !running && (allocatedBytes > 0 || javaAllocatedBytes > 0)) {
                sampleAndLog(latestSnapshot.state)
                monitorHeldMemory(token)
            }
        }, 2000L)
    }

    /** 只回收测试器自己的像素和数组，不触碰宿主业务缓存。 */
    private fun releaseBlocks() {
        for (bitmap in blocks) bitmap.recycle()
        blocks.clear()
        allocatedBytes = 0
        javaBlocks.clear()
        javaAllocatedBytes = 0
        pixelTile = null
        if (callbacksRegistered) {
            applicationContext.unregisterComponentCallbacks(memoryCallbacks)
            callbacksRegistered = false
        }
    }

    /** 分配失败先释放测试内存，此保护无法捕获系统杀进程或 Native 崩溃。 */
    private fun handleAllocationFailure(token: Int, error: Throwable) {
        synchronized(controlLock) {
            // 旧轮次失败仍释放块，但不能撤销刚排队的新开始命令。
            if (token == generation) {
                running = false
                ++generation
            }
        }
        releaseBlocks()
        Log.e(TAG, "ALLOCATION_FAILED，已释放测试内存", error)
        sampleAndLog("分配失败，已释放测试占用")
    }

    /** 同次读取整机总量和可用量，异常时保留上次指标并更新持有量。 */
    private fun sampleAndLog(state: String) {
        try {
            val manager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.getMemoryInfo(systemMemory)
            Debug.getMemoryInfo(processMemory)
            val runtime = Runtime.getRuntime()
            latestSnapshot = Snapshot(state, processMemory.totalPss / 1024,
                ((allocatedBytes + javaAllocatedBytes) / MIB).toInt(), systemMemory.availMem / MIB,
                systemMemory.totalMem / MIB, calculateSystemUsedPercent(), readSwapPssMb(),
                (allocatedBytes / MIB).toInt(), (javaAllocatedBytes / MIB).toInt(),
                (runtime.totalMemory() - runtime.freeMemory()) / MIB, runtime.maxMemory() / MIB, systemMemory.lowMemory)
            val memory = latestSnapshot
            Log.i(TAG, "SAMPLE elapsedMs=${SystemClock.elapsedRealtime()} pid=${Process.myPid()} state=$state" +
                " pssMb=${memory.pssMb} syntheticMb=${memory.allocatedMb} systemAvailMb=${memory.availableMb}" +
                " systemTotalMb=${memory.totalMb} systemUsedPercent=${memory.systemUsedPercent}" +
                " swapPssMb=${memory.swapPssMb} javaSyntheticMb=${memory.javaAllocatedMb}" +
                " javaUsedMb=${memory.javaUsedMb} javaMaxMb=${memory.javaMaxMb} trimLevel=${memory.trimLevel}" +
                " nativeAllocatedMb=${Debug.getNativeHeapAllocatedSize() / MIB} lowMemory=${systemMemory.lowMemory}")
        } catch (error: RuntimeException) {
            preserveSample(state, error)
        } catch (error: OutOfMemoryError) {
            preserveSample(state, error)
        }
    }

    /** 采样失败时只替换操作状态和测试持有量，保持其他指标的上次结果。 */
    private fun preserveSample(state: String, error: Throwable) {
        Log.w(TAG, "内存采样失败，保留上一次PSS并更新测试占用", error)
        val memory = latestSnapshot
        latestSnapshot = Snapshot(state, memory.pssMb, ((allocatedBytes + javaAllocatedBytes) / MIB).toInt(),
            memory.availableMb, memory.totalMb, memory.systemUsedPercent, memory.swapPssMb,
            (allocatedBytes / MIB).toInt(), (javaAllocatedBytes / MIB).toInt(),
            memory.javaUsedMb, memory.javaMaxMb, memory.lowMemory)
    }

    /** 整机使用率为总量减可用估计，未知时返回 -1。 */
    private fun calculateSystemUsedPercent(): Float {
        if (systemMemory.totalMem <= 0 || systemMemory.availMem < 0) return -1f
        return (100f * (1f - systemMemory.availMem.toFloat() / systemMemory.totalMem)).coerceIn(0f, 100f)
    }

    /** 公开交换字段缺失或无法解析时返回 -1，不影响加压边界判断。 */
    private fun readSwapPssMb(): Int = try {
        processMemory.getMemoryStat("summary.total-swap-pss")?.toInt()?.div(1024) ?: -1
    } catch (error: RuntimeException) {
        -1
    }

    /** 不可变快照；保留公开字段以兼容已有 Java 调用方，单位均为 MiB。 */
    class Snapshot internal constructor(
        /** 当前操作状态，检查点采样不覆盖状态含义。 */
        @JvmField val state: String,
        /** 当前进程 PSS，未采样时为 -1。 */
        @JvmField val pssMb: Int,
        /** 测试器持有的 Native 与 Java 总量，不等于 PSS。 */
        @JvmField val allocatedMb: Int,
        /** ActivityManager 提供的整机可用估计。 */
        @JvmField val availableMb: Long,
        /** 内核可访问的 RAM 总量。 */
        @JvmField val totalMb: Long,
        /** 整机估算使用率，未知为 -1。 */
        @JvmField val systemUsedPercent: Float,
        /** Swap PSS，不可用为 -1。 */
        @JvmField val swapPssMb: Int,
        /** 测试器持有的 Native 像素量。 */
        @JvmField val nativeAllocatedMb: Int,
        /** 测试器持有的 Java 数组量。 */
        @JvmField val javaAllocatedMb: Int,
        /** 包含业务对象和测试数组的 Java 已用堆。 */
        @JvmField val javaUsedMb: Long,
        /** 运行时允许的最大 Java 堆容量。 */
        @JvmField val javaMaxMb: Long,
        /** 系统当前是否报告低内存。 */
        @JvmField val lowMemory: Boolean
    ) {
        /** 最近收到的 Trim 等级，-1 表示尚未收到。 */
        @JvmField val trimLevel: Int = lastTrimLevel
    }
}
