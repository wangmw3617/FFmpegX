package com.zhiwei.ffmpegx.core.model

import com.zhiwei.ffmpegx.core.hw.VideoCodec

/**
 * ffprobe 结果的结构化视图。
 * 只保留 App 真正用得到的字段，完整 JSON 原样保留在 [rawJson] 里供「媒体信息」页展示。
 */
data class MediaInfo(
    val path: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val formatName: String?,
    val formatLongName: String?,
    val durationUs: Long,
    val bitRate: Long,
    val videoStreams: List<VideoStreamInfo>,
    val audioStreams: List<AudioStreamInfo>,
    val subtitleStreams: List<SubtitleStreamInfo>,
    val rawJson: String,
) {
    val primaryVideo: VideoStreamInfo? get() = videoStreams.firstOrNull()
    val primaryAudio: AudioStreamInfo? get() = audioStreams.firstOrNull()

    val hasVideo: Boolean get() = videoStreams.isNotEmpty()
    val hasAudio: Boolean get() = audioStreams.isNotEmpty()

    /** 源视频对应的 MediaCodec MIME，用于硬解规划 */
    val primaryVideoMime: String? get() = primaryVideo?.mime

    val durationLabel: String get() = formatDuration(durationUs)
    val sizeLabel: String get() = formatSize(fileSizeBytes)
}

data class VideoStreamInfo(
    val index: Int,
    val codecName: String,
    val codecLongName: String?,
    val width: Int,
    val height: Int,
    val frameRate: Double,
    val avgFrameRate: Double,
    val bitRate: Long,
    val pixelFormat: String?,
    val profile: String?,
    val level: Int?,
    val bitDepth: Int?,
    val rotationDegrees: Int,
    val colorTransfer: String?,
    val colorPrimaries: String?,
    val nbFrames: Long?,
    val durationUs: Long,
) {
    /** ffprobe 的 codec_name 到 Android MIME 的映射，用于查询硬件解码器 */
    val mime: String? get() = ffprobeCodecToMime(codecName)

    val effectiveFps: Double get() = if (frameRate > 0) frameRate else avgFrameRate

    val isHdr: Boolean
        get() = colorTransfer in setOf("smpte2084", "arib-std-b67") ||
            (bitDepth ?: 8) >= 10

    /** 应用旋转后「肉眼看到」的宽高 */
    val displayWidth: Int get() = if (rotationDegrees % 180 != 0) height else width
    val displayHeight: Int get() = if (rotationDegrees % 180 != 0) width else height

    val resolutionLabel: String get() = "${displayWidth}×${displayHeight}"

    val fpsLabel: String
        get() = if (effectiveFps > 0) String.format("%.3f", effectiveFps).trimEnd('0').trimEnd('.') else "未知"

    val codec: VideoCodec? get() = VideoCodec.fromFfmpegName(codecName)

    companion object {
        fun ffprobeCodecToMime(codec: String): String? = when (codec.lowercase()) {
            "h264" -> "video/avc"
            "hevc", "h265" -> "video/hevc"
            "av1" -> "video/av01"
            "vp9" -> "video/x-vnd.on2.vp9"
            "vp8" -> "video/x-vnd.on2.vp8"
            "mpeg4" -> "video/mp4v-es"
            "mpeg2video" -> "video/mpeg2"
            "h263" -> "video/3gpp"
            "mjpeg" -> "video/mjpeg"
            "dolbyvision" -> "video/dolby-vision"
            else -> null
        }
    }
}

data class AudioStreamInfo(
    val index: Int,
    val codecName: String,
    val codecLongName: String?,
    val sampleRate: Int,
    val channels: Int,
    val channelLayout: String?,
    val bitRate: Long,
    val durationUs: Long,
    val language: String?,
) {
    val channelsLabel: String
        get() = when (channels) {
            1 -> "单声道"
            2 -> "立体声"
            6 -> "5.1"
            8 -> "7.1"
            else -> "$channels 声道"
        }
}

data class SubtitleStreamInfo(
    val index: Int,
    val codecName: String,
    val language: String?,
    val title: String?,
) {
    val isBitmap: Boolean get() = codecName.lowercase() in setOf("dvd_subtitle", "hdmv_pgs_subtitle", "dvb_subtitle")
}

// ------------------------------------------------------------------ 展示工具 ----

fun formatDuration(us: Long): String {
    if (us <= 0) return "00:00"
    val totalSeconds = us / 1_000_000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) {
        String.format("%d:%02d:%02d", h, m, s)
    } else {
        String.format("%02d:%02d", m, s)
    }
}

fun formatDurationPrecise(us: Long): String {
    if (us <= 0) return "00:00.000"
    val totalMs = us / 1000
    val h = totalMs / 3_600_000
    val m = (totalMs % 3_600_000) / 60_000
    val s = (totalMs % 60_000) / 1000
    val ms = totalMs % 1000
    return if (h > 0) {
        String.format("%d:%02d:%02d.%03d", h, m, s, ms)
    } else {
        String.format("%02d:%02d.%03d", m, s, ms)
    }
}

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = 1024.0
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var idx = 0
    while (value >= kb && idx < units.lastIndex) {
        value /= kb
        idx++
    }
    return if (idx == 0) {
        "${bytes} B"
    } else {
        String.format("%.2f %s", value, units[idx])
    }
}

fun formatBitrate(bps: Long): String =
    if (bps <= 0) "未知" else String.format("%.0f kbps", bps / 1000.0)
