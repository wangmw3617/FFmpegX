package com.zhiwei.ffmpegx.core.model

import com.zhiwei.ffmpegx.core.hw.VideoCodec

/**
 * 压缩预设。目标是「一键得到能在某个场景下正常使用的文件」。
 *
 * 码率值是按 1080p 给的基准，实际会按输出像素数等比缩放（见 [resolveVideoBitrate]）。
 */
enum class CompressPreset(
    val label: String,
    val description: String,
    val maxLongEdge: Int,
    val baseBitrate1080p: Int,
    val audioCodec: String,
    val audioBitrate: Int,
    val fpsCap: Double,
) {
    ORIGINAL(
        label = "保持原样",
        description = "只重新编码，不缩放、不降帧",
        maxLongEdge = 0,
        baseBitrate1080p = 0,
        audioCodec = "copy",
        audioBitrate = 0,
        fpsCap = 0.0,
    ),
    WECHAT(
        label = "微信发送",
        description = "长边 720，单文件控制在 25MB 内比较稳妥",
        maxLongEdge = 1280,
        baseBitrate1080p = 2_000_000,
        audioCodec = "aac",
        audioBitrate = 96_000,
        fpsCap = 30.0,
    ),
    DOUYIN(
        label = "短视频平台",
        description = "竖屏 1080 宽，高码率保证二次压缩后画质",
        maxLongEdge = 1920,
        baseBitrate1080p = 6_000_000,
        audioCodec = "aac",
        audioBitrate = 128_000,
        fpsCap = 30.0,
    ),
    BILIBILI(
        label = "长视频平台",
        description = "1080p 高码率，适合投稿",
        maxLongEdge = 1920,
        baseBitrate1080p = 8_000_000,
        audioCodec = "aac",
        audioBitrate = 192_000,
        fpsCap = 0.0,
    ),
    WEB(
        label = "网页嵌入",
        description = "720p 中等码率，兼顾加载速度与观感",
        maxLongEdge = 1280,
        baseBitrate1080p = 2_500_000,
        audioCodec = "aac",
        audioBitrate = 128_000,
        fpsCap = 30.0,
    ),
    TINY(
        label = "极速压缩",
        description = "480p 低码率，只求能看清",
        maxLongEdge = 854,
        baseBitrate1080p = 800_000,
        audioCodec = "aac",
        audioBitrate = 64_000,
        fpsCap = 24.0,
    ),
    ARCHIVE(
        label = "归档保真",
        description = "不缩放，CRF 18 高画质，体积偏大",
        maxLongEdge = 0,
        baseBitrate1080p = 12_000_000,
        audioCodec = "copy",
        audioBitrate = 0,
        fpsCap = 0.0,
    ),
    ;

    /**
     * 按输出像素数把 1080p 基准码率等比换算过来。
     * 用 0.75 次方而不是线性：编码效率随分辨率提升而提高，线性换算会偏大。
     */
    fun resolveVideoBitrate(targetWidth: Int, targetHeight: Int): Int {
        if (baseBitrate1080p <= 0) return 0
        val pixels = targetWidth.toLong() * targetHeight
        if (pixels <= 0) return baseBitrate1080p
        val base = 1920L * 1080L
        val ratio = Math.pow(pixels.toDouble() / base, 0.75)
        return (baseBitrate1080p * ratio).toInt().coerceAtLeast(200_000)
    }

    /** 按预设算出输出分辨率（保持宽高比，只缩不放） */
    fun resolveSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        if (maxLongEdge <= 0 || sourceWidth <= 0 || sourceHeight <= 0) {
            return sourceWidth to sourceHeight
        }
        val longEdge = maxOf(sourceWidth, sourceHeight)
        if (longEdge <= maxLongEdge) return sourceWidth to sourceHeight
        val scale = maxLongEdge.toDouble() / longEdge
        return even(sourceWidth * scale) to even(sourceHeight * scale)
    }

    companion object {
        /** H.264/H.265 硬编普遍要求偶数分辨率 */
        fun even(v: Double): Int {
            val i = v.toInt().coerceAtLeast(2)
            return if (i % 2 == 0) i else i - 1
        }
    }
}

/**
 * 输出容器。
 */
enum class OutputContainer(
    val label: String,
    val extension: String,
    val muxer: String,
    val supportedVideo: Set<VideoCodec>,
    val supportedAudio: Set<String>,
) {
    MP4(
        label = "MP4",
        extension = "mp4",
        muxer = "mp4",
        supportedVideo = setOf(VideoCodec.H264, VideoCodec.HEVC, VideoCodec.AV1, VideoCodec.MPEG4),
        supportedAudio = setOf("aac", "libopus", "libmp3lame", "flac", "copy"),
    ),
    MKV(
        label = "MKV",
        extension = "mkv",
        muxer = "matroska",
        supportedVideo = VideoCodec.entries.toSet(),
        supportedAudio = setOf("aac", "libopus", "libmp3lame", "flac", "libvorbis", "pcm_s16le", "copy"),
    ),
    WEBM(
        label = "WebM",
        extension = "webm",
        muxer = "webm",
        supportedVideo = setOf(VideoCodec.VP9, VideoCodec.AV1),
        supportedAudio = setOf("libopus", "libvorbis"),
    ),
    MOV(
        label = "MOV",
        extension = "mov",
        muxer = "mov",
        supportedVideo = setOf(VideoCodec.H264, VideoCodec.HEVC, VideoCodec.MPEG4),
        supportedAudio = setOf("aac", "libmp3lame", "pcm_s16le", "copy"),
    ),
    TS(
        label = "MPEG-TS",
        extension = "ts",
        muxer = "mpegts",
        supportedVideo = setOf(VideoCodec.H264, VideoCodec.HEVC, VideoCodec.MPEG4),
        supportedAudio = setOf("aac", "libmp3lame", "copy"),
    ),
    GIF(
        label = "GIF",
        extension = "gif",
        muxer = "gif",
        supportedVideo = emptySet(),
        supportedAudio = emptySet(),
    ),
    MP3(
        label = "MP3 音频",
        extension = "mp3",
        muxer = "mp3",
        supportedVideo = emptySet(),
        supportedAudio = setOf("libmp3lame"),
    ),
    M4A(
        label = "M4A 音频",
        extension = "m4a",
        muxer = "ipod",
        supportedVideo = emptySet(),
        supportedAudio = setOf("aac"),
    ),
    WAV(
        label = "WAV 音频",
        extension = "wav",
        muxer = "wav",
        supportedVideo = emptySet(),
        supportedAudio = setOf("pcm_s16le"),
    ),
    FLAC(
        label = "FLAC 音频",
        extension = "flac",
        muxer = "flac",
        supportedVideo = emptySet(),
        supportedAudio = setOf("flac"),
    ),
    ;

    val isAudioOnly: Boolean get() = supportedVideo.isEmpty()
}
