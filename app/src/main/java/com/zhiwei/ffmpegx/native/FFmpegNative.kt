package com.zhiwei.ffmpegx.native

import android.content.Context
import android.util.Log

/**
 * 原生层门面。上层只跟它打交道，具体走哪个后端由构建配置决定。
 *
 * 设计要点：
 *  - **可降级**：后端加载失败（.so 缺失 / ABI 不匹配）时不会崩，只把状态记下来，
 *    UI 顶部会显示横幅，其它界面仍可用。
 *  - **串行执行**：FFmpeg CLI 有大量进程级全局状态，同一时刻只允许一个会话。
 *    队列层（TaskRepository）已经保证串行，这里不再重复加锁。
 */
object FFmpegNative {

    private const val TAG = "FFmpegNative"

    enum class State { NOT_TRIED, READY, UNAVAILABLE }

    @Volatile
    private var backend: FfmpegBackend? = null

    @Volatile
    var state: State = State.NOT_TRIED
        private set

    @Volatile
    private var errorDetail: String = ""

    @Volatile
    private var versionString: String = "unknown"

    /**
     * 必须在 Application.onCreate 里第一时间调用。
     * 需要 Context 是因为 kit 后端要走 FFmpegKitConfig，native 后端要用 cacheDir 落 ffprobe 输出。
     */
    fun initialize(context: Context) {
        if (backend != null) return
        synchronized(this) {
            if (backend != null) return
            backend = createBackend(context.applicationContext)
            Log.i(TAG, "后端已选定：${backend?.displayName}")
        }
    }

    private fun current(): FfmpegBackend? = backend

    // ------------------------------------------------------------------ 状态查询 ----

    /** 当前后端标识：kit / native / unknown */
    val backendId: String get() = current()?.id ?: "unknown"

    /** 当前后端展示名 */
    val backendName: String get() = current()?.displayName ?: "未初始化"

    /** 是否提供结构化进度统计 */
    val providesStructuredStats: Boolean get() = current()?.providesStructuredStats == true

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (state == State.READY) return true
        if (state == State.UNAVAILABLE) return false

        val b = current()
        if (b == null) {
            errorDetail = "FFmpegNative.initialize() 未被调用（应在 Application.onCreate 中初始化）"
            Log.e(TAG, errorDetail)
            state = State.UNAVAILABLE
            return false
        }

        return if (b.ensureLoaded()) {
            versionString = b.version()
            state = State.READY
            Log.i(TAG, "${b.displayName} 就绪，FFmpeg $versionString")
            true
        } else {
            errorDetail = b.loadError().ifBlank { "后端加载失败" }
            state = State.UNAVAILABLE
            Log.e(TAG, "后端加载失败：$errorDetail")
            false
        }
    }

    val isAvailable: Boolean get() = state == State.READY

    fun version(): String = versionString

    fun loadError(): String = errorDetail

    // ------------------------------------------------------------------ 执行 ----

    /**
     * 执行一次 ffmpeg 会话。**必须在后台线程调用**，会挂起直到结束。
     */
    suspend fun runFfmpeg(
        args: List<String>,
        onLog: (level: Int, message: String) -> Unit,
        onStats: ((RawStats) -> Unit)? = null,
    ): Int {
        val b = current() ?: return EXIT_NATIVE_MISSING
        if (!ensureLoaded()) return EXIT_NATIVE_MISSING
        return try {
            b.runFfmpeg(args, onLog, if (b.providesStructuredStats) onStats else null)
        } catch (t: Throwable) {
            Log.e(TAG, "runFfmpeg 异常", t)
            onLog(AvLog.ERROR, "执行异常：${t.message}")
            EXIT_INTERNAL
        }
    }

    /** 对文件跑一次 ffprobe，返回原始 JSON 文本 */
    suspend fun probeJson(path: String): Result<String> {
        val b = current() ?: return Result.failure(IllegalStateException("后端未初始化"))
        if (!ensureLoaded()) {
            return Result.failure(IllegalStateException(loadError()))
        }
        return b.probeJson(path)
    }

    /** 请求取消当前会话 */
    fun cancel() {
        runCatching { current()?.cancel() }
            .onFailure { Log.w(TAG, "cancel 失败：${it.message}") }
    }

    // ------------------------------------------------------------------ 常量 ----

    /** 后端不可用（.so 缺失 / ABI 不匹配） */
    const val EXIT_NATIVE_MISSING = -1000

    /** 调用过程中抛异常 */
    const val EXIT_INTERNAL = -1001
}
