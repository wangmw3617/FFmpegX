package com.zhiwei.ffmpegx.native

import android.net.Uri

/**
 * 一次 FFmpeg 统计回调的结构化数据。
 *
 * 由 FFmpegKitNext 的 `StatisticsCallback` 直接提供，不需要从日志里正则解析。
 */
data class RawStats(
    val frame: Long,
    val fps: Double,
    val quality: Double,
    val sizeBytes: Long,
    /** 毫秒。注意 FFmpegKitNext 的 Statistics.time 单位是毫秒（Double） */
    val timeMs: Double,
    val bitrateKbps: Double,
    val speed: Double,
)

/**
 * FFmpeg 执行后端。
 *
 * 目前只有一个实现：[KitBackend]（基于 FFmpegKitNext）。保留这层接口是因为：
 *  - 它把「第三方 FFmpeg 封装库」与「上层业务」隔开，换库时改动被限制在这一层；
 *  - 单测可以用假实现替换它，不必真的加载 .so；
 *  - 将来若要接别的 FFmpeg 发行版，仍有明确的位置可落。
 *
 * 所有实现都执行**同一条 ffmpeg 命令行**，所以上层的命令生成（Commands）与
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

    /** FFmpeg 版本号，如 "9.0.1" */
    fun version(): String

    /** 更详细的构建信息（库版本 / ABI / minSdk），用于诊断；不支持时返回空串 */
    fun buildInfo(): String = ""

    /** 加载失败的原因，成功时为空串 */
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
     * 统一返回 JSON 而不是结构化对象，是为了让上层的解析逻辑只写一份，
     * 也让「媒体信息」页能原样展示 ffprobe 的完整输出。
     */
    suspend fun probeJson(path: String): Result<String>

    /**
     * 列出当前 FFmpeg 构建**实际包含**的编码器名（如 `libx264`、`h264_mediacodec`）。
     *
     * 这是硬件加速规划的前提：设备支持 MediaCodec 不代表 FFmpeg 里编了这个编码器，
     * AAR 构建时少一个 `--enable-lib-*`，生成的命令就会 "Encoder not found"。
     *
     * 默认返回空集，表示该后端不支持自省 —— 上层会退化成「不做可用性过滤」。
     */
    suspend fun listEncoders(): Set<String> = emptySet()

    /** 请求取消当前会话 */
    fun cancel()

    // ---------------------------------------------------------------- SAF 支持 ----

    /**
     * 把 SAF Uri 转成可直接传给 ffmpeg 的参数（FFmpegKitNext 的 `ffkitsaf:` 协议）。
     *
     * 默认返回 null，表示该后端不支持直读 SAF —— 调用方应回退到「复制到缓存」的路径。
     */
    fun safParameterForRead(uri: Uri, reusable: Boolean): String? = null

    /**
     * 把 SAF Uri 转成可直接作为输出写入的参数。
     *
     * 默认返回 null，表示不支持直写 —— 调用方应回退到「写到缓存再复制回去」的路径。
     */
    fun safParameterForWrite(uri: Uri): String? = null

    /** 释放 [safParameterForRead]（reusable=true 时）申请的资源 */
    fun releaseSafUrl(url: String) {}

    /** 该后端是否支持直接读写 SAF Uri，省掉缓存中转 */
    val supportsSaf: Boolean get() = false
}
