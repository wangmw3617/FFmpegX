package com.zhiwei.ffmpegx.native

import android.content.Context
import android.util.Log
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.FFprobeKit
import com.antonkarpenko.ffmpegkit.Level
import com.antonkarpenko.ffmpegkit.ReturnCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 基于预编译 ffmpeg-kit 的后端。
 *
 * ffmpeg-kit 执行的就是**原样的 ffmpeg 命令行**，所以 Commands.kt 生成的参数可以
 * 一字不改地传进来 —— 这也正是选它的原因：上层的命令生成与硬件加速规划完全不用改。
 *
 * 相比自研 JNI 后端，它还额外提供了两件好事：
 *  - `StatisticsCallback`：结构化进度，不用再从日志里正则抠 `time=`；
 *  - `FFprobeKit.getMediaInformation()`：结构化 ffprobe，直接给 JSON。
 *
 * 包名是 `com.antonkarpenko.ffmpegkit` —— 官方 `com.arthenica` 的制品已于 2025 年初
 * 从 Maven Central 下架，这是社区仍在维护的分支（FFmpeg 8.1.1 Full，LGPL/GPL）。
 */
internal class KitBackend(
    @Suppress("unused") private val context: Context,
) : FfmpegBackend {

    companion object {
        private const val TAG = "KitBackend"
    }

    override val id: String = "kit"

    override val displayName: String = "ffmpeg-kit (预编译 FFmpeg 8.1.1)"

    override val providesStructuredStats: Boolean = true

    @Volatile
    private var loaded = false

    @Volatile
    private var errorDetail = ""

    @Volatile
    private var versionString = "unknown"

    /** 当前正在执行的会话，用于精确取消 */
    @Volatile
    private var activeSession: FFmpegSession? = null

    override fun ensureLoaded(): Boolean {
        if (loaded) return true
        synchronized(this) {
            if (loaded) return true
            return try {
                // 引用 FFmpegKitConfig 会触发 NativeLoader 加载 libffmpegkit.so 与各 libav*.so
                versionString = FFmpegKitConfig.getFFmpegVersion() ?: "unknown"
                loaded = true
                Log.i(TAG, "ffmpeg-kit 加载成功，FFmpeg $versionString")
                true
            } catch (t: Throwable) {
                errorDetail = buildString {
                    append(t::class.java.name).append(": ").append(t.message)
                    // ffmpeg-kit 的 NativeLoader 会把真正的 UnsatisfiedLinkError 挂在 cause 上
                    // （throw new Error("FFmpegKit failed to start ...", e)）。
                    // 只打外层消息就只能看到 "failed to start"，看不到 dlopen 的真实原因，所以把 cause 链也带出来。
                    var cause: Throwable? = t.cause
                    var depth = 0
                    while (cause != null && depth < 5) {
                        append("\n  ← 由 ").append(cause::class.java.name)
                            .append(" 引起：").append(cause.message)
                        cause = cause.cause
                        depth++
                    }
                }
                Log.e(TAG, "ffmpeg-kit 加载失败", t)
                false
            }
        }
    }

    override fun version(): String = versionString

    override fun loadError(): String = errorDetail

    override suspend fun runFfmpeg(
        args: List<String>,
        onLog: (level: Int, message: String) -> Unit,
        onStats: ((RawStats) -> Unit)?,
    ): Int {
        if (!ensureLoaded()) return FFmpegNative.EXIT_NATIVE_MISSING

        val finished = CompletableDeferred<Int>()

        val session = FFmpegKit.executeWithArgumentsAsync(
            args.toTypedArray(),
            { s -> finished.complete(s?.returnCode?.value ?: -1) },
            { log -> onLog(levelToInt(log?.level), log?.message.orEmpty()) },
            { stats ->
                if (stats != null && onStats != null) {
                    onStats(
                        RawStats(
                            frame = stats.videoFrameNumber.toLong(),
                            fps = stats.videoFps.toDouble(),
                            quality = stats.videoQuality.toDouble(),
                            sizeBytes = stats.size,
                            // ffmpeg-kit 的 Statistics.getTime() 单位是毫秒
                            timeMs = stats.time,
                            bitrateKbps = stats.bitrate,
                            speed = stats.speed,
                        ),
                    )
                }
            },
        )

        activeSession = session

        return try {
            withContext(Dispatchers.IO) { finished.await() }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // 上层取消了收集：中断 ffmpeg，并等它走完自己的清理流程
            runCatching { session.cancel() }
            throw ce
        } finally {
            activeSession = null
        }
    }

    override suspend fun probeJson(path: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(ensureLoaded()) { errorDetail.ifBlank { "ffmpeg-kit 未加载" } }

            val session = FFprobeKit.getMediaInformation(path)
            val code = session.returnCode
            if (code != null && !ReturnCode.isSuccess(code)) {
                error("ffprobe 退出码 ${code.value}：${session.failStackTrace ?: "无详细信息"}")
            }
            val info = session.mediaInformation
                ?: error("ffprobe 没有返回媒体信息（文件可能已损坏或不是媒体文件）")

            // getAllProperties() 返回的就是 ffprobe -print_format json -show_format -show_streams
            // 的完整 JSON，与 native 后端的输出结构一致，上层解析逻辑可以共用
            info.allProperties.toString()
        }
    }

    override fun cancel() {
        val session = activeSession
        runCatching {
            if (session != null) session.cancel() else FFmpegKit.cancel()
        }.onFailure { Log.w(TAG, "取消失败：${it.message}") }
    }

    /** ffmpeg-kit 的 Level 枚举 -> libavutil 的整数级别 */
    private fun levelToInt(level: Level?): Int = when (level) {
        Level.AV_LOG_QUIET -> AvLog.QUIET
        Level.AV_LOG_PANIC -> AvLog.PANIC
        Level.AV_LOG_FATAL -> AvLog.FATAL
        Level.AV_LOG_ERROR -> AvLog.ERROR
        Level.AV_LOG_WARNING -> AvLog.WARNING
        Level.AV_LOG_VERBOSE -> AvLog.VERBOSE
        Level.AV_LOG_DEBUG -> AvLog.DEBUG
        Level.AV_LOG_TRACE -> AvLog.TRACE
        // AV_LOG_STDERR 与 AV_LOG_INFO 都归到 INFO
        else -> AvLog.INFO
    }
}
