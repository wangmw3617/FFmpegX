package com.zhiwei.ffmpegx.native

/**
 * 一次 FFmpeg 统计回调的结构化数据。
 *
 * 只有提供结构化统计的后端才会回调这个（见 [FfmpegBackend.providesStructuredStats]）；
 * 不提供的后端，进度由上层从日志里解析。
 */
data class RawStats(
    val frame: Long,
    val fps: Double,
    val quality: Double,
    val sizeBytes: Long,
    /** 毫秒。注意 ffmpeg-kit 的 Statistics.getTime() 单位是毫秒 */
    val timeMs: Double,
    val bitrateKbps: Double,
    val speed: Double,
)

/**
 * FFmpeg 执行后端。
 *
 * 目前有两个实现，编译期二选一（见 app/build.gradle.kts 的 ffmpegx.backend）：
 *
 *  - **kit**：Maven Central 上的预编译 ffmpeg-kit AAR（FFmpeg 8.1.1 Full）。
 *    零编译依赖，任何平台都能直接构建 APK。提供结构化进度统计与结构化 ffprobe。
 *
 *  - **native**：app/src/main/cpp 下的自研 JNI 层，直接编译 FFmpeg 的 fftools。
 *    可以精确控制编译选项，但需要先在 Linux / macOS / WSL 上跑交叉编译脚本。
 *
 * 两者都执行**同一条 ffmpeg 命令行**，所以上层的命令生成（Commands）与
 * 硬件加速规划（HardwarePlanner）完全不需要区分后端。
 */
interface FfmpegBackend {

    /** 稳定标识，用于日志与 UI 展示 */
    val id: String

    /** 面向用户的名称 */
    val displayName: String

    /** 是否提供结构化统计。true 时上层不再从日志里正则解析进度 */
    val providesStructuredStats: Boolean

    /** 幂等加载，可在任意线程调用 */
    fun ensureLoaded(): Boolean

    fun version(): String

    fun loadError(): String

    /**
     * 执行一次 ffmpeg 会话。**必须在后台线程调用**，会挂起直到结束。
     *
     * @param args 不含 "ffmpeg" 本身的完整参数列表
     * @param onLog 日志回调（level 取值见 [AvLog]）
     * @param onStats 结构化统计回调；后端不支持时传 null
     * @return FFmpeg 退出码，0 表示成功
     */
    suspend fun runFfmpeg(
        args: List<String>,
        onLog: (level: Int, message: String) -> Unit,
        onStats: ((RawStats) -> Unit)?,
    ): Int

    /**
     * 对文件跑一次 ffprobe，返回**原始 JSON 文本**。
     *
     * 统一返回 JSON 而不是结构化对象，是为了让两个后端共用同一套解析逻辑，
     * 也让「媒体信息」页能原样展示 ffprobe 的完整输出。
     */
    suspend fun probeJson(path: String): Result<String>

    /** 请求取消当前会话 */
    fun cancel()
}
