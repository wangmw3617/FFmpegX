package com.zhiwei.ffmpegx.native

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 自研 JNI 后端。
 *
 * 对应 app/src/main/cpp 下的三层结构：
 *   - `libffmpegx.so`      JNI 桥接，运行时 dlopen 下面两个库
 *   - `libffmpegcmd.so`    FFmpeg CLI（fftools 编成），导出 ffmpegx_run_ffmpeg
 *   - `libffprobecmd.so`   FFprobe CLI，导出 ffmpegx_run_ffprobe
 *
 * 与 kit 后端的差异：本后端不做结构化统计（C 层只接管 av_log），
 * 进度由上层从日志的统计行里解析 —— 见 [ProgressParser]。
 */
internal class JniBackend(
    private val context: Context,
) : FfmpegBackend {

    companion object {
        private const val TAG = "JniBackend"
    }

    override val id: String = "native"

    override val displayName: String = "自研 JNI (FFmpeg fftools)"

    override val providesStructuredStats: Boolean = false

    @Volatile
    private var loaded = false

    @Volatile
    private var errorDetail = ""

    @Volatile
    private var versionString = "unknown"

    override fun ensureLoaded(): Boolean {
        if (loaded) return true
        synchronized(this) {
            if (loaded) return true
            return try {
                System.loadLibrary("ffmpegx")
                if (FfmpegJni.nativeInit()) {
                    versionString = runCatching { FfmpegJni.nativeVersion() }.getOrDefault("unknown")
                    loaded = true
                    Log.i(TAG, "原生库就绪，FFmpeg $versionString")
                    true
                } else {
                    errorDetail = runCatching { FfmpegJni.nativeLoadError() }
                        .getOrDefault("nativeInit 返回 false")
                    Log.e(TAG, "原生库加载失败：$errorDetail")
                    false
                }
            } catch (t: Throwable) {
                // UnsatisfiedLinkError 最常见：说明 .so 没被打进 APK，或者 ABI 不匹配
                errorDetail = buildString {
                    append(t::class.java.simpleName).append(": ").append(t.message)
                    append("\n请确认已执行 scripts/build-ffmpeg-android.sh，")
                    append("且 ffmpegx.abis 覆盖了当前设备的 ABI。")
                }
                Log.e(TAG, "loadLibrary(\"ffmpegx\") 失败", t)
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
    ): Int = withContext(Dispatchers.IO) {
        if (!ensureLoaded()) return@withContext FFmpegNative.EXIT_NATIVE_MISSING

        // onStats 在本后端恒为 null（providesStructuredStats = false）
        val sink = object : NativeLogSink {
            override fun onLog(level: Int, message: String) = onLog(level, message)
        }

        try {
            FfmpegJni.nativeRunFfmpeg(args.toTypedArray(), sink)
        } catch (t: Throwable) {
            Log.e(TAG, "nativeRunFfmpeg 异常", t)
            onLog(AvLog.ERROR, "原生调用异常：${t.message}")
            FFmpegNative.EXIT_INTERNAL
        }
    }

    override suspend fun probeJson(path: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(ensureLoaded()) { errorDetail.ifBlank { "原生库未加载" } }

            // 用 -o 落盘再读，而不是劫持 stdout：在 App 里改进程级 fd 会污染日志与其它库
            val outFile = File.createTempFile("ffprobe_", ".json", context.cacheDir)
            try {
                val code = FfmpegJni.nativeRunFfprobe(
                    arrayOf(
                        "-hide_banner",
                        "-v", "error",
                        "-print_format", "json",
                        "-show_format",
                        "-show_streams",
                        "-o", outFile.absolutePath,
                        path,
                    ),
                    null,
                )
                if (code != 0) {
                    error("ffprobe 退出码 $code（文件可能已损坏或不是媒体文件）")
                }
                outFile.takeIf { it.exists() && it.length() > 0 }?.readText()
                    ?: error("ffprobe 没有产生输出")
            } finally {
                outFile.delete()
            }
        }
    }

    override fun cancel() {
        if (!loaded) return
        runCatching { FfmpegJni.nativeCancel() }
            .onFailure { Log.w(TAG, "nativeCancel 失败：${it.message}") }
    }
}
