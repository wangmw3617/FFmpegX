package com.zhiwei.ffmpegx.core.cmd

import com.zhiwei.ffmpegx.Fixtures
import com.zhiwei.ffmpegx.containsSequence
import com.zhiwei.ffmpegx.core.hw.AudioCodec
import com.zhiwei.ffmpegx.core.hw.VideoCodec
import com.zhiwei.ffmpegx.core.model.OutputContainer
import com.zhiwei.ffmpegx.valueOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命令生成的单元测试。
 *
 * argv 是**顺序敏感**的：FFmpeg 的选项位置决定作用域，
 * 全局选项必须在第一个 `-i` 之前，输出选项必须在最后一个 `-i` 之后。
 * 所以这里的断言都用 `containsSequence`（连续子序列）而不是 `contains`。
 */
class CommandsTest {

    private val settings = Fixtures.settings()
    private val plan = Fixtures.softPlan()

    // ============================================================ 通用渲染 ====

    @Test
    fun `基础参数在最前面且输入输出顺序正确`() {
        val args = renderBasic()

        assertEquals(
            listOf("-hide_banner", "-nostdin", "-y", "-loglevel", "info"),
            args.take(5),
        )
        assertTrue(args.containsSequence("-i", "/in.mp4"))
        assertTrue(args.containsSequence("-map", "0:v:0?"))
        assertTrue(args.containsSequence("-map", "0:a:0?"))
        assertTrue(args.containsSequence("-c:v", "libx264"))
        assertTrue(args.containsSequence("-c:a", "aac"))
        assertTrue(args.containsSequence("-f", "mp4"))
        assertEquals("输出路径必须是最后一个参数", "/out.mp4", args.last())
        assertTrue(
            "-i 必须排在 -c:v 之前",
            args.indexOf("-i") < args.indexOf("-c:v"),
        )
    }

    @Test
    fun `CRF 模式生成 crf 与 preset`() {
        val args = renderBasic()
        assertEquals("20", args.valueOf("-crf"))
        assertEquals("veryfast", args.valueOf("-preset"))
        assertEquals(null, args.valueOf("-b:v"))
    }

    @Test
    fun `码率模式生成 b v 与 maxrate bufsize`() {
        val args = Commands.render(
            settings = settings,
            plan = plan,
            inputs = listOf(InputSpec("/in.mp4")),
            output = OutputSpec(
                path = "/out.mp4",
                container = OutputContainer.MP4,
                video = VideoEncodeSpec(VideoCodec.H264, RateMode.BITRATE, bitrate = 4_000_000),
                audio = AudioEncodeSpec(AudioCodec.AAC, 128_000),
            ),
        )
        assertEquals("4000000", args.valueOf("-b:v"))
        assertEquals("6000000", args.valueOf("-maxrate"))
        assertEquals("8000000", args.valueOf("-bufsize"))
        assertEquals(null, args.valueOf("-crf"))
    }

    @Test
    fun `丢弃视频流时不能生成 -vf`() {
        // 这条守的是一个真实踩过的坑：
        // 带 -vf 又带 -vn 时 FFmpeg 会报 "no output video stream"
        val args = Commands.render(
            settings = settings,
            plan = plan,
            inputs = listOf(InputSpec("/in.mp4")),
            output = OutputSpec(
                path = "/out.m4a",
                container = OutputContainer.M4A,
                video = null,
                audio = AudioEncodeSpec(AudioCodec.AAC, 128_000),
                width = 1280,
                height = 720,
            ),
        )
        assertFalse("丢弃视频流时不应出现 -vf", args.contains("-vf"))
        assertTrue(args.containsSequence("-vn"))
        assertFalse("丢弃视频流时不应出现 -c:v", args.contains("-c:v"))
    }

    @Test
    fun `丢弃音频流时不能生成 -af`() {
        val args = Commands.render(
            settings = settings,
            plan = plan,
            inputs = listOf(InputSpec("/in.mp4")),
            output = OutputSpec(
                path = "/out.mp4",
                container = OutputContainer.MP4,
                video = VideoEncodeSpec(VideoCodec.H264, RateMode.CRF, crf = 23),
                audio = null,
                audioFilters = listOf("volume=2.0"),
            ),
        )
        assertFalse("丢弃音频流时不应出现 -af", args.contains("-af"))
        assertTrue(args.containsSequence("-an"))
    }

    @Test
    fun `缩放会同时生成 scale 与 setsar`() {
        val args = Commands.render(
            settings = settings,
            plan = plan,
            inputs = listOf(InputSpec("/in.mp4")),
            output = OutputSpec(
                path = "/out.mp4",
                container = OutputContainer.MP4,
                video = VideoEncodeSpec(VideoCodec.H264, RateMode.CRF),
                audio = null,
                width = 1280,
                height = 720,
                fps = 30.0,
            ),
        )
        val vf = args.valueOf("-vf")
        assertEquals("scale=1280:720:flags=lanczos,setsar=1,fps=30", vf)
    }

    @Test
    fun `GOP 按帧率的两秒计算`() {
        val args = Commands.render(
            settings = settings,
            plan = plan,
            inputs = listOf(InputSpec("/in.mp4")),
            output = OutputSpec(
                path = "/out.mp4",
                container = OutputContainer.MP4,
                video = VideoEncodeSpec(VideoCodec.H264, RateMode.CRF),
                audio = null,
                fps = 25.0,
            ),
        )
        assertEquals("50", args.valueOf("-g"))
    }

    @Test
    fun `faststart 只加在 mp4 与 mov 上`() {
        val mp4 = renderBasic()
        assertTrue(mp4.containsSequence("-movflags", "+faststart"))

        val mkv = Commands.render(
            settings = settings,
            plan = plan,
            inputs = listOf(InputSpec("/in.mp4")),
            output = OutputSpec(
                path = "/out.mkv",
                container = OutputContainer.MKV,
                video = VideoEncodeSpec(VideoCodec.H264, RateMode.CRF),
                audio = null,
            ),
        )
        assertFalse("MKV 不需要 faststart", mkv.contains("-movflags"))
    }

    @Test
    fun `不保留元数据时加 map_metadata -1`() {
        val args = Commands.render(
            settings = settings,
            plan = plan,
            inputs = listOf(InputSpec("/in.mp4")),
            output = OutputSpec(
                path = "/out.mp4",
                container = OutputContainer.MP4,
                video = VideoEncodeSpec(VideoCodec.H264, RateMode.CRF),
                audio = null,
                keepMetadata = false,
            ),
        )
        assertTrue(args.containsSequence("-map_metadata", "-1"))
    }

    @Test
    fun `覆盖开关为 false 时用 -n`() {
        val args = Commands.render(
            settings = Fixtures.settings(overwrite = false),
            plan = plan,
            inputs = listOf(InputSpec("/in.mp4")),
            output = OutputSpec(
                path = "/out.mp4",
                container = OutputContainer.MP4,
                video = VideoEncodeSpec(VideoCodec.H264, RateMode.CRF),
                audio = null,
            ),
        )
        assertTrue(args.contains("-n"))
        assertFalse(args.contains("-y"))
    }

    // ================================================================ 剪辑 ====

    @Test
    fun `无损剪切的 ss 与 t 必须在 i 之前`() {
        val args = Commands.trim(
            settings = settings,
            plan = plan,
            inputPath = "/in.mp4",
            spec = TrimSpec(
                output = "/out.mp4",
                container = OutputContainer.MP4,
                startUs = 5_000_000,
                endUs = 15_000_000,
                streamCopy = true,
                video = null,
                audio = null,
            ),
        )

        assertEquals("00:00:05.000", args.valueOf("-ss"))
        assertEquals("00:00:10.000", args.valueOf("-t"))
        assertTrue("-ss 必须在 -i 之前才是快速定位", args.indexOf("-ss") < args.indexOf("-i"))
        assertTrue("-t 也必须在 -i 之前", args.indexOf("-t") < args.indexOf("-i"))
        assertTrue(args.containsSequence("-c", "copy"))
        assertTrue(args.containsSequence("-map", "0"))
        assertTrue(args.containsSequence("-avoid_negative_ts", "make_zero"))
    }

    @Test
    fun `精确重编码走渲染路径并保留字幕轨`() {
        val args = Commands.trim(
            settings = settings,
            plan = plan,
            inputPath = "/in.mp4",
            spec = TrimSpec(
                output = "/out.mp4",
                container = OutputContainer.MP4,
                startUs = 1_000_000,
                endUs = 2_000_000,
                streamCopy = false,
                video = VideoEncodeSpec(VideoCodec.H264, RateMode.CRF),
                audio = AudioEncodeSpec(AudioCodec.AAC, 128_000),
            ),
        )
        assertTrue(args.containsSequence("-c:v", "libx264"))
        assertFalse("重编码时不应出现 -c copy", args.containsSequence("-c", "copy"))
    }

    // ================================================================= GIF ====

    @Test
    fun `GIF 两遍生成 palettegen 与 paletteuse`() {
        val cmds = Commands.gif(
            settings = settings,
            inputPath = "/in.mp4",
            spec = GifSpec(
                output = "/out.gif",
                startUs = 1_000_000,
                durationUs = 5_000_000,
                fps = 12,
                width = 480,
                twoPass = true,
                palettePath = "/tmp/palette.png",
            ),
        )

        assertEquals(2, cmds.size)
        assertTrue(cmds[0].any { it.contains("palettegen") })
        assertTrue(cmds[0].containsSequence("-f", "image2"))
        assertEquals("/tmp/palette.png", cmds[0].last())

        assertTrue(cmds[1].containsSequence("-i", "/tmp/palette.png"))
        assertTrue(cmds[1].any { it.contains("paletteuse") })
        assertTrue(cmds[1].containsSequence("-loop", "0"))
        assertTrue(cmds[1].containsSequence("-f", "gif"))
        assertEquals("/out.gif", cmds[1].last())

        // 两遍的滤镜链都要先做帧率与缩放
        val vf = cmds[0].valueOf("-vf")
        assertTrue(vf!!.startsWith("fps=12,scale=480:-1:flags=lanczos,setsar=1"))
    }

    @Test
    fun `GIF 单遍用 split 在一条命令里完成`() {
        val cmds = Commands.gif(
            settings = settings,
            inputPath = "/in.mp4",
            spec = GifSpec(
                output = "/out.gif",
                startUs = 0,
                durationUs = 3_000_000,
                fps = 10,
                width = 0,
                twoPass = false,
            ),
        )
        assertEquals(1, cmds.size)
        val vf = cmds[0].valueOf("-vf")!!
        assertTrue(vf.contains("split[a][b]"))
        assertTrue(vf.contains("palettegen"))
        assertTrue(vf.contains("paletteuse"))
        // 用 flags=lanczos 判断而不是 "scale="：
        // paletteuse 的参数里有 bayer_scale=5，用 "scale=" 会误判
        assertFalse("width=0 时不应生成缩放滤镜", vf.contains("flags=lanczos"))
    }

    // ================================================================ 拼接 ====

    @Test
    fun `concat demuxer 模式用 -f concat 加 -c copy`() {
        val args = Commands.concat(
            settings = settings,
            plan = plan,
            inputs = listOf("/a.mp4", "/b.mp4"),
            spec = ConcatSpec(
                output = "/out.mp4",
                container = OutputContainer.MP4,
                useDemuxer = true,
                listFilePath = "/tmp/list.txt",
            ),
        )
        assertTrue(args.containsSequence("-f", "concat", "-safe", "0"))
        assertTrue(args.containsSequence("-i", "/tmp/list.txt"))
        assertTrue(args.containsSequence("-c", "copy"))
        assertEquals("/out.mp4", args.last())
    }

    @Test
    fun `concat 滤镜模式生成 n 路 concat 图`() {
        val args = Commands.concat(
            settings = settings,
            plan = plan,
            inputs = listOf("/a.mp4", "/b.mp4", "/c.mp4"),
            spec = ConcatSpec(
                output = "/out.mp4",
                container = OutputContainer.MP4,
                useDemuxer = false,
                video = VideoEncodeSpec(VideoCodec.H264, RateMode.CRF),
                audio = AudioEncodeSpec(AudioCodec.AAC, 128_000),
                width = 1280,
                height = 720,
                fps = 30.0,
            ),
        )

        val fc = args.valueOf("-filter_complex")!!
        assertTrue(fc.contains("concat=n=3:v=1:a=1"))
        assertTrue("每路都要先归一化尺寸", fc.contains("scale=1280:720:flags=lanczos"))
        assertTrue(fc.contains("[v0][0:a:0][v1][1:a:0][v2][2:a:0]"))
        assertTrue(args.containsSequence("-map", "[v]"))
        assertTrue(args.containsSequence("-map", "[a]"))
        assertTrue(args.containsSequence("-c:v", "libx264"))
    }

    // ================================================================ 字幕 ====

    @Test
    fun `字幕烧录生成 subtitles 滤镜与样式覆盖`() {
        val args = Commands.subtitle(
            settings = settings,
            plan = plan,
            inputPath = "/in.mp4",
            spec = SubtitleSpec(
                mode = SubtitleMode.BURN,
                output = "/out.mp4",
                subtitlePath = "/tmp/a.srt",
                forceStyle = "FontSize=28",
                video = VideoEncodeSpec(VideoCodec.H264, RateMode.CRF),
                audio = AudioEncodeSpec(AudioCodec.AAC, 128_000),
            ),
        )
        val vf = args.valueOf("-vf")!!
        assertTrue(vf.contains("subtitles=/tmp/a.srt"))
        assertTrue(vf.contains("force_style='FontSize=28'"))
    }

    @Test
    fun `字幕提取直接映射字幕轨并转 srt`() {
        val args = Commands.subtitle(
            settings = settings,
            plan = plan,
            inputPath = "/in.mp4",
            spec = SubtitleSpec(
                mode = SubtitleMode.EXTRACT,
                output = "/out.srt",
                subtitlePath = "",
                subtitleStreamIndex = 2,
            ),
        )
        assertTrue(args.containsSequence("-map", "0:s:2"))
        assertTrue(args.containsSequence("-c:s", "srt"))
        assertTrue(args.containsSequence("-f", "srt"))
    }

    @Test
    fun `字幕封装在 mp4 用 mov_text 在 mkv 用 srt`() {
        val mp4 = Commands.subtitle(
            settings, plan, "/in.mp4",
            SubtitleSpec(SubtitleMode.MUX, "/out.mp4", "/tmp/a.srt", container = OutputContainer.MP4),
        )
        assertTrue(mp4.containsSequence("-c:s", "mov_text"))

        val mkv = Commands.subtitle(
            settings, plan, "/in.mp4",
            SubtitleSpec(SubtitleMode.MUX, "/out.mkv", "/tmp/a.srt", container = OutputContainer.MKV),
        )
        assertTrue(mkv.containsSequence("-c:s", "srt"))
    }

    // ========================================================== 水印 / 分屏 ====

    @Test
    fun `水印生成透明度缩放与位置参数`() {
        val args = Commands.overlay(
            settings = settings,
            plan = plan,
            mainPath = "/in.mp4",
            spec = OverlaySpec(
                mode = OverlayMode.WATERMARK,
                output = "/out.mp4",
                overlayPath = "/logo.png",
                position = OverlayPosition.BOTTOM_RIGHT,
                overlayScale = 0.15,
                opacity = 0.8,
                showFrom = 0.0,
                showTo = 0.0,
                video = VideoEncodeSpec(VideoCodec.H264, RateMode.CRF),
                audio = AudioEncodeSpec(AudioCodec.AAC, 128_000),
            ),
        )

        val fc = args.valueOf("-filter_complex")!!
        assertTrue(fc.contains("[1:v]format=rgba"))
        assertTrue(fc.contains("colorchannelmixer=aa=0.8"))
        assertTrue(fc.contains("scale=iw*0.15:-1[wm]"))
        assertTrue(fc.contains("overlay=W-w-16:H-h-16"))
        assertTrue("未设时间范围时不应生成 enable", !fc.contains("enable="))
        assertTrue(args.containsSequence("-map", "[v]"))
        assertTrue(args.containsSequence("-i", "/logo.png"))
    }

    @Test
    fun `水印设定时段时生成 enable 表达式`() {
        val args = Commands.overlay(
            settings = settings,
            plan = plan,
            mainPath = "/in.mp4",
            spec = OverlaySpec(
                mode = OverlayMode.WATERMARK,
                output = "/out.mp4",
                overlayPath = "/logo.png",
                overlayScale = 0.2,
                opacity = 1.0,
                showFrom = 5.0,
                showTo = 20.0,
                video = VideoEncodeSpec(VideoCodec.H264, RateMode.CRF),
                audio = null,
            ),
        )
        val fc = args.valueOf("-filter_complex")!!
        assertTrue(fc.contains("enable='between(t,5,20)'"))
        assertFalse("不透明时不应生成 colorchannelmixer", fc.contains("colorchannelmixer"))
    }

    @Test
    fun `左右分屏生成 hstack 并统一高度`() {
        val args = Commands.overlay(
            settings = settings,
            plan = plan,
            mainPath = "/a.mp4",
            spec = OverlaySpec(
                mode = OverlayMode.SIDE_BY_SIDE,
                output = "/out.mp4",
                overlayPath = "/b.mp4",
                stackEdge = 720,
                video = VideoEncodeSpec(VideoCodec.H264, RateMode.CRF),
                audio = AudioEncodeSpec(AudioCodec.AAC, 128_000),
            ),
        )
        val fc = args.valueOf("-filter_complex")!!
        assertTrue(fc.contains("[0:v]scale=-2:720,setsar=1[a]"))
        assertTrue(fc.contains("[1:v]scale=-2:720,setsar=1[b]"))
        assertTrue(fc.contains("hstack=inputs=2[v]"))
    }

    @Test
    fun `上下分屏生成 vstack`() {
        val args = Commands.overlay(
            settings, plan, "/a.mp4",
            OverlaySpec(
                mode = OverlayMode.TOP_BOTTOM,
                output = "/out.mp4",
                overlayPath = "/b.mp4",
                stackEdge = 1080,
                video = VideoEncodeSpec(VideoCodec.H264, RateMode.CRF),
                audio = null,
            ),
        )
        assertTrue(args.valueOf("-filter_complex")!!.contains("vstack=inputs=2[v]"))
    }

    // ================================================================ 音频 ====

    @Test
    fun `音量与淡入淡出生成对应滤镜`() {
        val args = Commands.audio(
            settings = settings,
            plan = plan,
            inputPath = "/in.mp4",
            spec = AudioSpec(
                output = "/out.m4a",
                container = OutputContainer.M4A,
                codec = AudioCodec.AAC,
                bitrate = 128_000,
                sampleRate = 44_100,
                channels = 2,
                volume = 1.5,
                fadeInSeconds = 2.0,
                fadeOutSeconds = 3.0,
            ),
        )
        val af = args.valueOf("-af")!!
        assertTrue(af.contains("volume=1.5"))
        assertTrue(af.contains("afade=t=in:st=0:d=2"))
        assertTrue("淡出要先把音频倒过来再倒回去", af.contains("areverse,afade=t=in:st=0:d=3,areverse"))
        assertEquals("44100", args.valueOf("-ar"))
        assertEquals("2", args.valueOf("-ac"))
    }

    @Test
    fun `超过两倍的变速会串联多级 atempo`() {
        val args = Commands.audio(
            settings = settings,
            plan = plan,
            inputPath = "/in.mp4",
            spec = AudioSpec(
                output = "/out.m4a",
                container = OutputContainer.M4A,
                codec = AudioCodec.AAC,
                bitrate = 128_000,
                sampleRate = 0,
                channels = 0,
                tempo = 3.0,
            ),
        )
        // atempo 单级上限 2.0，3.0 必须拆成 2.0 * 1.5
        assertEquals("atempo=2.0,atempo=1.5", args.valueOf("-af"))
    }

    @Test
    fun `低于半速的变速同样会串联`() {
        val args = Commands.audio(
            settings = settings,
            plan = plan,
            inputPath = "/in.mp4",
            spec = AudioSpec(
                output = "/out.m4a",
                container = OutputContainer.M4A,
                codec = AudioCodec.AAC,
                bitrate = 128_000,
                sampleRate = 0,
                channels = 0,
                tempo = 0.25,
            ),
        )
        assertEquals("atempo=0.5,atempo=0.5", args.valueOf("-af"))
    }

    @Test
    fun `有音频滤镜时 copy 会被强制改成重编码`() {
        // -af 与 -c:a copy 互斥，否则 FFmpeg 直接报错
        val args = Commands.audio(
            settings = settings,
            plan = plan,
            inputPath = "/in.mp4",
            spec = AudioSpec(
                output = "/out.m4a",
                container = OutputContainer.M4A,
                codec = AudioCodec.COPY,
                bitrate = 0,
                sampleRate = 0,
                channels = 0,
                volume = 2.0,
            ),
        )
        assertTrue(args.containsSequence("-c:a", "aac"))
        assertFalse(args.containsSequence("-c:a", "copy"))
    }

    @Test
    fun `响度标准化生成 loudnorm 滤镜`() {
        val args = Commands.audio(
            settings = settings,
            plan = plan,
            inputPath = "/in.mp4",
            spec = AudioSpec(
                output = "/out.m4a",
                container = OutputContainer.M4A,
                codec = AudioCodec.AAC,
                bitrate = 128_000,
                sampleRate = 0,
                channels = 0,
                loudnessTargetLufs = -16.0,
            ),
        )
        val af = args.valueOf("-af")!!
        assertTrue(af.startsWith("loudnorm=I=-16:TP=-1.5:LRA=11"))
    }

    // ================================================================ 工具 ====

    @Test
    fun `时间格式化保留毫秒`() {
        assertEquals("0", Commands.formatTime(0))
        assertEquals("00:00:05.000", Commands.formatTime(5_000_000))
        assertEquals("01:02:03.456", Commands.formatTime(3_723_456_000))
    }

    @Test
    fun `滤镜路径里的特殊字符会被转义`() {
        // SAF 的卷 ID 里可能带冒号，不转义会把滤镜串切坏
        assertEquals("a\\:b", Commands.escapeFilterPath("a:b"))
        assertEquals("a\\\\b", Commands.escapeFilterPath("a\\b"))
        assertEquals("a\\'b", Commands.escapeFilterPath("a'b"))
        assertEquals("a\\,b", Commands.escapeFilterPath("a,b"))
        assertEquals("a\\[b\\]", Commands.escapeFilterPath("a[b]"))
    }

    @Test
    fun `plannedFilters 在没有视频输出时返回空`() {
        // 这是渲染侧与规划侧共用的判据，必须在源头拦住
        val noVideo = OutputSpec(
            path = "/out.m4a",
            container = OutputContainer.M4A,
            video = null,
            audio = AudioEncodeSpec(AudioCodec.AAC, 128_000),
            width = 1280,
            height = 720,
            fps = 30.0,
            filters = listOf("hflip"),
        )
        assertTrue("没有视频流就不该有任何视频滤镜", Commands.plannedFilters(noVideo).isEmpty())
    }

    @Test
    fun `plannedFilters 与 render 生成的滤镜完全一致`() {
        // 规划与渲染两边必须用同一个来源判断「有没有滤镜」，
        // 否则会出现「规划说可以零拷贝但实际带了 scale」的矛盾
        val output = OutputSpec(
            path = "/out.mp4",
            container = OutputContainer.MP4,
            video = VideoEncodeSpec(VideoCodec.H264, RateMode.CRF),
            audio = null,
            width = 1280,
            height = 720,
            fps = 24.0,
            filters = listOf("hflip"),
        )
        val expected = Commands.plannedFilters(output).joinToString(",")
        val args = Commands.render(settings, plan, listOf(InputSpec("/in.mp4")), output)
        assertEquals(expected, args.valueOf("-vf"))
    }

    // -------------------------------------------------------------- 辅助 ----

    private fun renderBasic() = Commands.render(
        settings = settings,
        plan = plan,
        inputs = listOf(InputSpec("/in.mp4")),
        output = OutputSpec(
            path = "/out.mp4",
            container = OutputContainer.MP4,
            video = VideoEncodeSpec(VideoCodec.H264, RateMode.CRF, crf = 20),
            audio = AudioEncodeSpec(AudioCodec.AAC, 128_000),
        ),
    )
}
