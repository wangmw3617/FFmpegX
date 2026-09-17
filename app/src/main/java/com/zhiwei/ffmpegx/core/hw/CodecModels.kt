package com.zhiwei.ffmpegx.core.hw

/**
 * 目标视频编码格式。
 *
 * `mcName` 是 FFmpeg 里对应的 MediaCodec 硬件编码器名；
 * `swName` 是软件编码器名，为 null 表示当前构建的 FFmpeg 没有包含它
 * （需要在 scripts/build-ffmpeg-kit-next.sh 里加对应 --enable-lib*）。
 */
enum class VideoCodec(
    val label: String,
    val shortLabel: String,
    /** ffprobe 报告的名字（codec_name），注意与 FFmpeg 的编码器名不是一回事 */
    val ffprobeName: String,
    val mime: String,
    val mcName: String,
    val swName: String?,
    val containerHint: String,
) {
    H264("H.264 / AVC", "H.264", "h264", "video/avc", "h264_mediacodec", "libx264", "mp4"),
    HEVC("H.265 / HEVC", "HEVC", "hevc", "video/hevc", "hevc_mediacodec", null, "mp4"),
    AV1("AV1", "AV1", "av1", "video/av01", "av1_mediacodec", null, "mp4"),
    VP9("VP9", "VP9", "vp9", "video/x-vnd.on2.vp9", "vp9_mediacodec", null, "webm"),
    MPEG4("MPEG-4 Part 2", "MPEG4", "mpeg4", "video/mp4v-es", "mpeg4_mediacodec", "mpeg4", "mp4");

    /** 是否具备可用的软件编码器（决定「兼容优先」策略下是否可选） */
    val hasSoftwareEncoder: Boolean get() = swName != null

    companion object {
        /**
         * 从各种名字反查枚举。
         * 同时接受 ffprobe 的 codec_name（h264）、FFmpeg 编码器名（libx264 / h264_mediacodec）。
         */
        fun fromFfmpegName(name: String): VideoCodec? {
            val n = name.lowercase()
            return entries.firstOrNull {
                it.ffprobeName == n || it.swName == n || it.mcName == n
            }
        }
    }
}

enum class AudioCodec(
    val label: String,
    val ffmpegName: String,
    val mime: String?,
    val bitratePresets: List<Int>,
) {
    AAC("AAC", "aac", "audio/mp4a-latm", listOf(64_000, 96_000, 128_000, 192_000, 256_000, 320_000)),
    OPUS("Opus", "libopus", "audio/opus", listOf(32_000, 48_000, 64_000, 96_000, 128_000, 192_000)),
    MP3("MP3", "libmp3lame", "audio/mpeg", listOf(96_000, 128_000, 192_000, 256_000, 320_000)),
    FLAC("FLAC（无损）", "flac", "audio/flac", listOf(0)),
    VORBIS("Vorbis", "libvorbis", "audio/vorbis", listOf(96_000, 128_000, 192_000, 256_000)),
    PCM("PCM 16bit（无损）", "pcm_s16le", null, listOf(0)),
    COPY("不重新编码", "copy", null, listOf(0));

    val isLossless: Boolean get() = this == FLAC || this == PCM
}

/** 硬解/软解在 FFmpeg 侧的解码器策略 */
enum class DecoderKind(val label: String) {
    MEDIACODEC("MediaCodec 硬解"),
    MEDIACODEC_SURFACE("MediaCodec 硬解（Surface 零拷贝）"),
    SOFTWARE("软件解码"),
    STREAM_COPY("不重新编码（直通）"),
}

/** 编码器策略 */
enum class EncoderKind(val label: String) {
    MEDIACODEC("MediaCodec 硬编"),
    SOFTWARE("软件编码"),
    STREAM_COPY("不重新编码（直通）"),
}

/**
 * 单个 MediaCodec 编解码器的能力快照。
 * 所有字段都来自 `MediaCodecInfo`，不做任何猜测。
 */
data class VideoCodecCapability(
    val codecName: String,
    val mime: String,
    val isEncoder: Boolean,
    val isHardware: Boolean,
    val isSoftwareOnly: Boolean,
    val isVendor: Boolean,
    val minWidth: Int,
    val maxWidth: Int,
    val minHeight: Int,
    val maxHeight: Int,
    val minBitrate: Int,
    val maxBitrate: Int,
    val minFps: Double,
    val maxFps: Double,
    val widthAlignment: Int,
    val heightAlignment: Int,
    /** 是否支持 Surface 输入/输出（决定能否做零拷贝硬解硬编直连） */
    val supportsSurface: Boolean,
    /** 是否支持 10bit / HDR 配置 */
    val supports10Bit: Boolean,
    val profiles: List<String>,
) {
    val shortName: String get() = codecName.substringAfterLast('.')

    fun supportsSize(width: Int, height: Int): Boolean =
        width in minWidth..maxWidth && height in minHeight..maxHeight

    fun supportsFps(fps: Double): Boolean = fps in minFps..maxFps

    fun supportsBitrate(bps: Int): Boolean = bps in minBitrate..maxBitrate

    /** 综合判断：尺寸、帧率、码率是否都在能力范围内 */
    fun canHandle(width: Int, height: Int, fps: Double, bitrate: Int): Boolean =
        supportsSize(width, height) &&
            (fps <= 0.0 || supportsFps(fps)) &&
            (bitrate <= 0 || supportsBitrate(bitrate))

    val maxResolutionLabel: String get() = "${maxWidth}×${maxHeight}"
}

data class AudioCodecCapability(
    val codecName: String,
    val mime: String,
    val isEncoder: Boolean,
    val isHardware: Boolean,
    val maxChannels: Int,
    val maxBitrate: Int,
)

/**
 * 一次完整的设备编解码能力快照。
 */
data class DeviceCodecReport(
    val soc: SocProfile,
    val videoEncoders: List<VideoCodecCapability>,
    val videoDecoders: List<VideoCodecCapability>,
    val audioEncoders: List<AudioCodecCapability>,
    val scannedAtMillis: Long,
) {
    /** 某个目标格式下，所有「硬件」编码器，按最大分辨率降序 */
    fun hardwareEncoders(codec: VideoCodec): List<VideoCodecCapability> =
        videoEncoders
            .filter { it.mime.equals(codec.mime, ignoreCase = true) && it.isHardware }
            .sortedByDescending { it.maxWidth.toLong() * it.maxHeight }

    /** 某个源格式下，所有「硬件」解码器 */
    fun hardwareDecoders(mime: String): List<VideoCodecCapability> =
        videoDecoders
            .filter { it.mime.equals(mime, ignoreCase = true) && it.isHardware }
            .sortedByDescending { it.maxWidth.toLong() * it.maxHeight }

    /** 挑一个能处理指定尺寸/帧率的硬件编码器，没有则返回 null */
    fun pickHardwareEncoder(
        codec: VideoCodec,
        width: Int,
        height: Int,
        fps: Double,
        bitrate: Int = 0,
    ): VideoCodecCapability? =
        hardwareEncoders(codec).firstOrNull { it.canHandle(width, height, fps, bitrate) }

    fun pickHardwareDecoder(mime: String, width: Int, height: Int): VideoCodecCapability? =
        hardwareDecoders(mime).firstOrNull { it.supportsSize(width, height) }

    /** 该 MIME 是否存在任何硬件解码器 */
    fun hasHardwareDecoder(mime: String): Boolean = hardwareDecoders(mime).isNotEmpty()

    val supportedVideoEncodeTargets: List<VideoCodec>
        get() = VideoCodec.entries.filter { hardwareEncoders(it).isNotEmpty() || it.hasSoftwareEncoder }

    /** 首页展示用的一句话总结 */
    val summary: String
        get() {
            val hwEnc = VideoCodec.entries.filter { hardwareEncoders(it).isNotEmpty() }
            val hwDec = videoDecoders.count { it.isHardware }
            return buildString {
                append("硬件编码器 ").append(hwEnc.size).append(" 类")
                if (hwEnc.isNotEmpty()) {
                    append("（").append(hwEnc.joinToString("/") { it.shortLabel }).append("）")
                }
                append("；硬件解码器 ").append(hwDec).append(" 个")
            }
        }
}
