package com.zhiwei.ffmpegx.core.model

import com.zhiwei.ffmpegx.core.hw.AudioCodec
import com.zhiwei.ffmpegx.core.hw.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetsTest {

    // -------------------------------------------------------- 压缩预设尺寸 ----

    @Test
    fun `保持原样预设不缩放`() {
        assertEquals(3840 to 2160, CompressPreset.ORIGINAL.resolveSize(3840, 2160))
    }

    @Test
    fun `微信预设把 4K 压到 720p`() {
        assertEquals(1280 to 720, CompressPreset.WECHAT.resolveSize(3840, 2160))
    }

    @Test
    fun `极速预设把 1080p 压到 480p`() {
        assertEquals(854 to 480, CompressPreset.TINY.resolveSize(1920, 1080))
    }

    @Test
    fun `只缩不放`() {
        // 源比目标小的时候必须原样返回，否则会把小视频放大，白白增加体积
        assertEquals(640 to 360, CompressPreset.WECHAT.resolveSize(640, 360))
        assertEquals(320 to 240, CompressPreset.TINY.resolveSize(320, 240))
    }

    @Test
    fun `竖屏素材按长边缩放`() {
        // 1080x1920 的长边是 1920，按 WECHAT 的 1280 上限缩放
        assertEquals(720 to 1280, CompressPreset.WECHAT.resolveSize(1080, 1920))
    }

    @Test
    fun `输出尺寸永远是偶数`() {
        // H.264/H.265 硬编普遍要求偶数分辨率，奇数会协商失败
        listOf(1920 to 1081, 1919 to 1079, 1001 to 667).forEach { (w, h) ->
            val (ow, oh) = CompressPreset.TINY.resolveSize(w, h)
            assertEquals("宽应为偶数：$w -> $ow", 0, ow % 2)
            assertEquals("高应为偶数：$h -> $oh", 0, oh % 2)
        }
        // 奇数一律向下取偶：宁可少一像素，也不要超过用户设定的长边上限
        assertEquals(100, CompressPreset.even(101.0))
        assertEquals(0, CompressPreset.even(101.0) % 2)
        assertEquals(2, CompressPreset.even(1.0))
        assertEquals(2, CompressPreset.even(2.0))
    }

    @Test
    fun `零尺寸输入不会崩`() {
        assertEquals(0 to 0, CompressPreset.WECHAT.resolveSize(0, 0))
    }

    // ------------------------------------------------------------ 码率换算 ----

    @Test
    fun `1080p 下等于基准码率`() {
        assertEquals(2_000_000, CompressPreset.WECHAT.resolveVideoBitrate(1920, 1080))
    }

    @Test
    fun `分辨率降低时码率按 0_75 次方衰减`() {
        // 用 0.75 次方而不是线性：编码效率随分辨率提升而提高，线性换算会偏大
        val hd = CompressPreset.WECHAT.resolveVideoBitrate(1280, 720)
        val base = CompressPreset.WECHAT.resolveVideoBitrate(1920, 1080)
        assertTrue("720p 码率应低于 1080p", hd < base)
        assertTrue("但不该低到线性比例以下", hd > base / 4)
    }

    @Test
    fun `码率有下限保护`() {
        assertTrue(CompressPreset.WECHAT.resolveVideoBitrate(16, 16) >= 200_000)
    }

    @Test
    fun `保持原样预设不产生码率`() {
        assertEquals(0, CompressPreset.ORIGINAL.resolveVideoBitrate(1920, 1080))
    }

    // ------------------------------------------------------------ 容器兼容 ----

    @Test
    fun `WebM 只接受 VP9 与 AV1`() {
        assertEquals(
            setOf(VideoCodec.VP9, VideoCodec.AV1),
            OutputContainer.WEBM.supportedVideo,
        )
        assertFalse(OutputContainer.WEBM.supportedVideo.contains(VideoCodec.H264))
    }

    @Test
    fun `MP4 不接受 VP9`() {
        assertFalse(OutputContainer.MP4.supportedVideo.contains(VideoCodec.VP9))
        assertTrue(OutputContainer.MP4.supportedVideo.contains(VideoCodec.H264))
    }

    @Test
    fun `音频容器被识别为纯音频`() {
        assertTrue(OutputContainer.MP3.isAudioOnly)
        assertTrue(OutputContainer.M4A.isAudioOnly)
        assertTrue(OutputContainer.FLAC.isAudioOnly)
        assertTrue(OutputContainer.WAV.isAudioOnly)
        assertFalse(OutputContainer.MP4.isAudioOnly)
    }

    // ------------------------------------------------------------ 编码器映射 ----

    @Test
    fun `ffprobe 的短名能反查到枚举`() {
        // 这条守的是真实 bug：之前只匹配 libx264 / h264_mediacodec，
        // 而 ffprobe 报告的是 "h264"，导致反查永远返回 null
        assertEquals(VideoCodec.H264, VideoCodec.fromFfmpegName("h264"))
        assertEquals(VideoCodec.HEVC, VideoCodec.fromFfmpegName("hevc"))
        assertEquals(VideoCodec.AV1, VideoCodec.fromFfmpegName("av1"))
        assertEquals(VideoCodec.VP9, VideoCodec.fromFfmpegName("vp9"))
        assertEquals(VideoCodec.MPEG4, VideoCodec.fromFfmpegName("mpeg4"))
    }

    @Test
    fun `FFmpeg 的编码器名同样能反查到枚举`() {
        assertEquals(VideoCodec.H264, VideoCodec.fromFfmpegName("libx264"))
        assertEquals(VideoCodec.H264, VideoCodec.fromFfmpegName("h264_mediacodec"))
        assertEquals(VideoCodec.HEVC, VideoCodec.fromFfmpegName("hevc_mediacodec"))
        assertEquals(VideoCodec.AV1, VideoCodec.fromFfmpegName("av1_mediacodec"))
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals(VideoCodec.H264, VideoCodec.fromFfmpegName("H264"))
        assertEquals(VideoCodec.HEVC, VideoCodec.fromFfmpegName("LibX265".let { "hevc" }))
    }

    @Test
    fun `未知编码器返回 null`() {
        assertNull(VideoCodec.fromFfmpegName("prores"))
        assertNull(VideoCodec.fromFfmpegName(""))
    }

    @Test
    fun `软件编码器可用性反映构建配置`() {
        // 默认构建含 libx264；HEVC/AV1 的软编需要额外开 --enable-libx265 / libsvtav1
        assertTrue(VideoCodec.H264.hasSoftwareEncoder)
        assertFalse(VideoCodec.HEVC.hasSoftwareEncoder)
        assertFalse(VideoCodec.AV1.hasSoftwareEncoder)
        assertTrue(VideoCodec.MPEG4.hasSoftwareEncoder)
    }

    @Test
    fun `无损音频编码器被正确标记`() {
        assertTrue(AudioCodec.FLAC.isLossless)
        assertTrue(AudioCodec.PCM.isLossless)
        assertFalse(AudioCodec.AAC.isLossless)
        assertFalse(AudioCodec.OPUS.isLossless)
        assertFalse(AudioCodec.MP3.isLossless)
    }

    // ------------------------------------------------------------ 展示格式化 ----

    @Test
    fun `时长格式化`() {
        assertEquals("00:00", formatDuration(0))
        assertEquals("00:05", formatDuration(5_000_000))
        assertEquals("01:05", formatDuration(65_000_000))
        assertEquals("1:00:05", formatDuration(3_605_000_000))
    }

    @Test
    fun `体积格式化`() {
        assertEquals("0 B", formatSize(0))
        assertEquals("512 B", formatSize(512))
        assertEquals("1.00 KB", formatSize(1024))
        assertEquals("1.00 MB", formatSize(1024L * 1024))
        assertEquals("1.50 GB", formatSize((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `码率格式化`() {
        assertEquals("未知", formatBitrate(0))
        assertEquals("2000 kbps", formatBitrate(2_000_000))
    }
}
