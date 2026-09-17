package com.zhiwei.ffmpegx.native

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.AbiDetect
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.Level
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 基于 FFmpegKitNext 的唯一 FFmpeg 执行后端。
 *
 * 执行的就是**原样的 ffmpeg 命令行**，所以 Commands.kt 生成的参数可以一字不改地传进来
 * —— 上层的命令生成与硬件加速规划完全不需要感知这里换过库。
 *
 * 相比原先自研 JNI + fftools 的方案，它额外给了三件好事：
 *  - `StatisticsCallback`：结构化进度，不用再从日志里正则抠 `time=`；
 *  - `FFprobeKit.getMediaInformation()`：结构化 ffprobe，直接给 JSON；
 *  - `ffkitsaf:` 协议：直接读写 SAF Uri，不必再复制到应用缓存绕开 seek 限制。
 *
 * 同时它**不存在**「用裸 fftools 会 exit() 掉宿主进程」的问题：FFmpegKitNext 自己
 * 重写了执行入口，出错走的是会话失败而不是进程终止。这就是原先 C 层那套
 * `-Bsymbolic` + `override exit()` + `setjmp/longjmp` 可以整层删掉的原因。
 *
 * 依赖：com.arthenica:ffmpeg-kit-next（自行构建的 AAR，见 docs/ffmpeg-kit-next-build.md）
 */
internal class KitBackend(
    @Suppress("unused") private val context: Context,
) : FfmpegBackend {

    companion object {
        private const val TAG = "KitBackend"
    }

    override val id: String = "kit"

    override val displayName: String =
        "ffmpeg-kit-next ${com.zhiwei.ffmpegx.BuildConfig.FFMPEG_KIT_NEXT_VERSION}"

    override val providesStructuredStats: Boolean = true

    @Volatile
    private var loaded = false

    @Volatile
    private var errorDetail = ""

    @Volatile
    private var versionString = "unknown"

    /** FFmpegKitNext 报告的 ABI / 最低 SDK，用于诊断包是否装对 */
    @Volatile
    private var buildDetail = ""

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
                // 注意：getNativeAbi() / getNativeMinSdk() 挂在 AbiDetect 上，
                // 不在 FFmpegKitConfig 里（FFmpegKitConfig 只有 getVersion/getFFmpegVersion）。
                buildDetail = runCatching {
                    "ffmpeg-kit-next ${FFmpegKitConfig.getVersion()} " +
                        "(abi=${AbiDetect.getAbi()}, minSdk=${AbiDetect.getNativeMinSdk()})"
                }.getOrDefault("")
                loaded = true
                Log.i(TAG, "FFmpegKitNext 加载成功，FFmpeg $versionString / $buildDetail")
                true
            } catch (t: Throwable) {
                errorDetail = buildString {
                    append(t::class.java.name).append(": ").append(t.message)
                    // NativeLoader 会把真正的 UnsatisfiedLinkError 挂在 cause 上
                    // （throw new Error("FFmpegKit failed to start ...", e)）。
                    // 只打外层消息就只能看到 "failed to start"，看不到 dlopen 的真实原因，
                    // 所以把 cause 链也带出来。
                    var cause: Throwable? = t.cause
                    var depth = 0
                    while (cause != null && depth < 5) {
                        append("\n  ← 由 ").append(cause::class.java.name)
                            .append(" 引起：").append(cause.message)
                        cause = cause.cause
                        depth++
                    }
                    // 注意：这里是普通字符串字面量，不能跨行写。
                    // 插值表达式 ${...} 内部可以换行，但字符串本身的换行必须用 \n。
                    append("\n请确认 AAR 已构建且包含当前设备的 ABI（abiFilters=${android.os.Build.SUPPORTED_ABIS.joinToString(",")}）")
                }
                Log.e(TAG, "FFmpegKitNext 加载失败", t)
                false
            }
        }
    }

    override fun version(): String = versionString

    override fun buildInfo(): String = buildDetail

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
            // s.returnCode 是 protected，回调里也必须走 getReturnCode()
            { s -> finished.complete(s?.getReturnCode()?.value ?: -1) },
            { log -> onLog(levelToInt(log?.level), log?.message.orEmpty()) },
            { stats ->
                if (stats != null && onStats != null) {
                    onStats(
                        RawStats(
                            frame = stats.videoFrameNumber.toLong(),
                            fps = stats.videoFps.toDouble(),
                            quality = stats.videoQuality.toDouble(),
                            sizeBytes = stats.size,
                            // FFmpegKitNext 的 Statistics.time 单位是毫秒（Double）
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
            require(ensureLoaded()) { errorDetail.ifBlank { "FFmpegKitNext 未加载" } }

            val session = FFprobeKit.getMediaInformation(path)
            // AbstractSession.returnCode / failStackTrace 是 protected var，
            // 外部只能走公开 getter：getReturnCode() / getFailStackTrace()。
            val code = session.getReturnCode()
            if (code != null && !ReturnCode.isSuccess(code)) {
                error("ffprobe 退出码 ${code.value}：${session.getFailStackTrace() ?: "无详细信息"}")
            }
            // MediaInformationSession.mediaInformation 是 private var，
            // 只能通过 open fun getMediaInformation() 取。
            val info = session.getMediaInformation()
                ?: error("ffprobe 没有返回媒体信息（文件可能已损坏或不是媒体文件）")

            // getAllProperties() 返回的就是 ffprobe -print_format json -show_format -show_streams
            // 的完整 JSON，与「媒体信息」页期望的结构一致。
            // 注意它声明为 open fun，Kotlin 不会为它合成 allProperties 属性语法。
            info.getAllProperties().toString()
        }
    }

    /**
     * 跑一次 `ffmpeg -encoders`，问 FFmpeg 自己有哪些编码器。
     *
     * 不能靠 `FFmpegKitConfig` 的常量判断：那反映的是**构建时的预期**，
     * 而这里要的是「这个 AAR 里实际存在什么」。两者不一致正是
     * "Encoder not found" 的来源，所以只认实际跑出来的结果。
     */
    override suspend fun listEncoders(): Set<String> = withContext(Dispatchers.IO) {
        if (!ensureLoaded()) return@withContext emptySet<String>()
        runCatching {
            val session = FFmpegKit.execute("-hide_banner -encoders")
            parseEncoderTable(session.getOutput().orEmpty())
        }.onFailure { Log.w(TAG, "执行 -encoders 失败", it) }
            .getOrDefault(emptySet())
    }

    /**
     * 解析 `ffmpeg -encoders` 的输出表。
     *
     * 表体每行形如（首列是 6 个标志字符，V/A/S 表示视频/音频/字幕）：
     * ```
     *  V....D libx264          libx264 H.264 / AVC / MPEG-4 AVC (codec h264)
     *  V....D h264_mediacodec  h264_mediacodec (codec h264)
     *  A....D aac              AAC (Advanced Audio Coding)
     * ```
     * 以 `------` 分隔线为界，之后的行才属于表体；第 2 列即编码器名。
     */
    private fun parseEncoderTable(text: String): Set<String> {
        val result = mutableSetOf<String>()
        var inTable = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("------")) {
                inTable = true
                continue
            }
            if (!inTable || line.isEmpty()) continue
            val parts = line.split(' ').filter { it.isNotEmpty() }
            if (parts.size >= 2 && parts[0].length == 6) {
                result += parts[1]
            }
        }
        return result
    }

    override fun cancel() {
        val session = activeSession
        runCatching {
            if (session != null) session.cancel() else FFmpegKit.cancel()
        }.onFailure { Log.w(TAG, "取消失败：${it.message}") }
    }

    /** FFmpegKitNext 内建 ffkitsaf: 协议，可以直接读写 SAF Uri */
    override val supportsSaf: Boolean = true

    /**
     * 把 SAF Uri 转成 FFmpegKitNext 能直接读的 `ffkitsaf:` 参数。
     *
     * 这是 FFmpegKitNext 内建的协议，FFmpeg 侧直接按 fd 读写 Uri 指向的文档，
     * 因此**不再需要先把文件复制到应用缓存**（那条限制来自 SAF 管道不支持 seek）。
     *
     * @param reusable true 表示该 url 会被多条命令复用，用完必须手动
     *        [releaseSafUrl] 释放；false 时执行结束由 FFmpegKitNext 自动释放。
     */
    override fun safParameterForRead(uri: android.net.Uri, reusable: Boolean): String? =
        runCatching { FFmpegKitConfig.getSafParameterForRead(context, uri, reusable) }
            .onFailure { Log.w(TAG, "创建 saf 读参数失败：${it.message}") }
            .getOrNull()

    /** 把 SAF Uri 转成可写的 `ffkitsaf:` 参数，供输出直接写入 SAF 文档 */
    override fun safParameterForWrite(uri: android.net.Uri): String? =
        runCatching { FFmpegKitConfig.getSafParameterForWrite(context, uri) }
            .onFailure { Log.w(TAG, "创建 saf 写参数失败：${it.message}") }
            .getOrNull()

    /** 释放 [safParameterForRead]（reusable=true 时）申请的可复用 url */
    override fun releaseSafUrl(url: String) {
        runCatching { FFmpegKitConfig.unregisterSafProtocolUrl(url) }
            .onFailure { Log.w(TAG, "释放 saf url 失败：${it.message}") }
    }

    /** FFmpegKitNext 的 Level 枚举 -> libavutil 的整数级别 */
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
