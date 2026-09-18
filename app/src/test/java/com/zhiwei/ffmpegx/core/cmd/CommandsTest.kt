package com.zhiwei.ffmpegx.core.cmd

import com.zhiwei.ffmpegx.Fixtures
import com.zhiwei.ffmpegx.containsSequence
import com.zhiwei.ffmpegx.core.hw.AudioCodec
import com.zhiwei.ffmpegx.core.hw.VideoCodec
import com.zhiwei.ffmpegx.core.model.OutputContainer
import com.zhiwei.ffmpegx.valueOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    // ============================================================ 旋转 / 翻转 ====

    private fun rotateArgs(
        degrees: Int = 90,
        flipH: Boolean = false,
        flipV: Boolean = false,
        videoMode: RateMode = RateMode.CRF,
        audio: AudioEncodeSpec? = AudioEncodeSpec(AudioCodec.AAC, 128_000),
    ) = Commands.rotate(
        settings = settings,
        plan = plan,
        inputPath = "/in.mp4",
        spec = RotateSpec(
            output = "/out.mp4",
            degrees = degrees,
            flipHorizontal = flipH,
            flipVertical = flipV,
            video = VideoEncodeSpec(VideoCodec.H264, videoMode),
            audio = audio,
        ),
    )

    @Test
    fun `顺时针 90 度用 transpose 1`() {
        assertEquals("transpose=1", rotateArgs(degrees = 90).valueOf("-vf"))
    }

    @Test
    fun `180 度串联两次 transpose 2`() {
        // transpose 只认 0/1/2/3，180° 没有单值可表达，只能两次 90°
        assertEquals("transpose=2,transpose=2", rotateArgs(degrees = 180).valueOf("-vf"))
    }

    @Test
    fun `顺时针 270 度等价于逆时针 90 度`() {
        assertEquals("transpose=2", rotateArgs(degrees = 270).valueOf("-vf"))
    }

    @Test
    fun `角度为 0 且不翻转时不生成 vf`() {
        assertFalse("没有滤镜就不该生成 -vf", rotateArgs(degrees = 0).contains("-vf"))
    }

    @Test
    fun `旋转与翻转叠加时顺序稳定`() {
        assertEquals(
            "transpose=1,hflip,vflip",
            rotateArgs(degrees = 90, flipH = true, flipV = true).valueOf("-vf"),
        )
    }

    @Test
    fun `旋转带滤镜时 copy 会被强制改成重编码`() {
        // -vf 与 -c:v copy 互斥，FFmpeg 直接报
        // "Filtering and streamcopy cannot be used together"
        val args = rotateArgs(degrees = 90, videoMode = RateMode.COPY)
        assertTrue(args.containsSequence("-c:v", "libx264"))
        assertFalse(args.containsSequence("-c:v", "copy"))
    }

    @Test
    fun `角度为 0 的旋转仍然允许直通`() {
        // 没有滤镜就没有冲突，此时 copy 应该保留 —— 降级只在真的需要时发生
        val args = rotateArgs(degrees = 0, videoMode = RateMode.COPY)
        assertTrue(args.containsSequence("-c:v", "copy"))
    }

    // ================================================================ 画面裁剪 ====

    private fun cropArgs(w: Int, h: Int, x: Int = 0, y: Int = 0) = Commands.crop(
        settings = settings,
        plan = plan,
        inputPath = "/in.mp4",
        spec = CropSpec(
            output = "/out.mp4",
            x = x,
            y = y,
            width = w,
            height = h,
            video = VideoEncodeSpec(VideoCodec.H264, RateMode.CRF),
            audio = AudioEncodeSpec(AudioCodec.AAC, 128_000),
        ),
    )

    @Test
    fun `裁剪宽高会取偶数`() {
        // yuv420p 的色度是 2×2 采样，奇数尺寸会错位甚至直接报错
        assertEquals("crop=100:50:10:20", cropArgs(w = 101, h = 51, x = 10, y = 20).valueOf("-vf"))
    }

    @Test
    fun `裁剪尺寸至少留 2px`() {
        // 尺寸为 0 时 crop 会因「必须为正」直接失败
        assertEquals("crop=2:2:0:0", cropArgs(w = 1, h = 0).valueOf("-vf"))
    }

    @Test
    fun `裁剪带滤镜时 copy 会被强制改成重编码`() {
        val args = Commands.crop(
            settings = settings,
            plan = plan,
            inputPath = "/in.mp4",
            spec = CropSpec(
                output = "/out.mp4", x = 0, y = 0, width = 100, height = 100,
                video = VideoEncodeSpec(VideoCodec.H264, RateMode.COPY),
                audio = AudioEncodeSpec(AudioCodec.AAC, 128_000),
            ),
        )
        assertTrue(args.containsSequence("-c:v", "libx264"))
        assertFalse(args.containsSequence("-c:v", "copy"))
    }

    // ================================================================ 提取画面 ====

    private fun thumbnailArgs(format: String = "jpg", width: Int = 0) = Commands.thumbnail(
        settings = settings,
        inputPath = "/in.mp4",
        spec = ThumbnailSpec(
            output = "/out.$format",
            atUs = 1_500_000,
            width = width,
            format = format,
            quality = 3,
        ),
    )

    @Test
    fun `缩略图定位在输入之前且只取一帧`() {
        // -ss 放在 -i 之前才是快速定位（否则要解码到该时间点）
        val args = thumbnailArgs()
        assertTrue(args.containsSequence("-ss", "00:00:01.500", "-i", "/in.mp4"))
        assertTrue(args.containsSequence("-frames:v", "1"))
    }

    @Test
    fun `缩略图指定宽度时走 lanczos 缩放`() {
        assertEquals("scale=640:-1:flags=lanczos", thumbnailArgs(width = 640).valueOf("-vf"))
    }

    @Test
    fun `jpg 缩略图生成 q v 与 image2 封装`() {
        val args = thumbnailArgs(format = "jpg")
        assertTrue(args.containsSequence("-q:v", "3"))
        assertTrue(args.containsSequence("-f", "image2"))
    }

    @Test
    fun `png 缩略图不生成 q v 且仍走 image2 封装`() {
        // -q:v 是 JPEG 的量表，对 png 没有意义。
        // 封装器必须仍是 image2 —— png 不是 muxer 名，`-f png` 会被 FFmpeg 拒绝
        // （Requested output format 'png' is not known），且退出码为 0，不易察觉。
        val args = thumbnailArgs(format = "png")
        assertFalse(args.contains("-q:v"))
        assertTrue(args.containsSequence("-f", "image2"))
    }

    // ================================================================ 视频变速 ====

    private fun speedArgs(
        factor: Double,
        videoMode: RateMode = RateMode.CRF,
        audio: AudioEncodeSpec? = AudioEncodeSpec(AudioCodec.AAC, 128_000),
    ) = Commands.speed(
        settings = settings,
        plan = plan,
        inputPath = "/in.mp4",
        spec = SpeedSpec(
            output = "/out.mp4",
            factor = factor,
            video = VideoEncodeSpec(VideoCodec.H264, videoMode),
            audio = audio,
        ),
    )

    @Test
    fun `二倍速用 setpts 减半并配 atempo 二倍`() {
        val args = speedArgs(2.0)
        assertEquals("setpts=0.5*PTS", args.valueOf("-vf"))
        assertEquals("atempo=2", args.valueOf("-af"))
    }

    @Test
    fun `减速用大于一的 setpts 系数`() {
        assertEquals("setpts=2*PTS", speedArgs(0.5).valueOf("-vf"))
    }

    @Test
    fun `变速时视频 copy 会被强制改成重编码`() {
        val args = speedArgs(2.0, videoMode = RateMode.COPY)
        assertTrue(args.containsSequence("-c:v", "libx264"))
        assertFalse(args.containsSequence("-c:v", "copy"))
    }

    @Test
    fun `变速时音频 copy 会被强制改成重编码`() {
        // 有 -af 就不能 -c:a copy，否则 FFmpeg 报
        // "Filtering and streamcopy cannot be used together"
        val args = speedArgs(2.0, audio = AudioEncodeSpec(AudioCodec.COPY, 0))
        assertFalse(args.containsSequence("-c:a", "copy"))
        assertTrue(args.containsSequence("-c:a", "aac"))
    }

    // ============================================================ 去水印 / 遮挡 ====

    private fun delogoArgs(
        mode: String,
        w: Int = 101,
        h: Int = 51,
        x: Int = 10,
        y: Int = 20,
    ) = Commands.delogo(
        settings = settings,
        plan = plan,
        inputPath = "/in.mp4",
        spec = DelogoSpec(
            output = "/out.mp4",
            x = x,
            y = y,
            width = w,
            height = h,
            mode = mode,
            video = VideoEncodeSpec(VideoCodec.H264, RateMode.CRF),
            audio = AudioEncodeSpec(AudioCodec.AAC, 128_000),
        ),
    )

    @Test
    fun `智能填补用 delogo 滤镜且不走 filter_complex`() {
        val args = delogoArgs(mode = "delogo")
        assertEquals("delogo=x=10:y=20:w=100:h=50", args.valueOf("-vf"))
        assertFalse("单路滤镜不该生成 filter_complex", args.contains("-filter_complex"))
        assertTrue(args.containsSequence("-map", "0:v:0?"))
    }

    @Test
    fun `模糊模式把区域抠出来模糊再盖回并带 v 标签`() {
        // 三个要点：显式 split、输出打 [v] 标签、用 boxblur
        val args = delogoArgs(mode = "blur")
        val graph = args.valueOf("-filter_complex")
        assertEquals(
            "[0:v]split=2[base][src];[src]crop=100:50:10:20,boxblur=20:3[m];[base][m]overlay=10:20[v]",
            graph,
        )
        assertTrue("filter_complex 的输出必须打 [v] 标签，否则 -map 找不到", args.containsSequence("-map", "[v]"))
    }

    @Test
    fun `马赛克模式用 pixelize 而不是 boxblur`() {
        // 「马赛克」的语义是块状像素化，用 boxblur 只是模糊
        val args = delogoArgs(mode = "mosaic")
        val graph = args.valueOf("-filter_complex")
        assertTrue("马赛克应当用 pixelize", graph!!.contains("pixelize=w=16:h=16"))
        assertFalse("马赛克不该退化成模糊", graph.contains("boxblur"))
        assertTrue(args.containsSequence("-map", "[v]"))
    }

    @Test
    fun `模糊与马赛克生成的是两种不同处理`() {
        assertNotEquals(delogoArgs(mode = "blur").valueOf("-filter_complex"), delogoArgs(mode = "mosaic").valueOf("-filter_complex"))
    }

    @Test
    fun `去遮挡带滤镜时 copy 会被强制改成重编码`() {
        val args = Commands.delogo(
            settings = settings,
            plan = plan,
            inputPath = "/in.mp4",
            spec = DelogoSpec(
                output = "/out.mp4", x = 10, y = 20, width = 100, height = 50, mode = "blur",
                video = VideoEncodeSpec(VideoCodec.H264, RateMode.COPY),
                audio = AudioEncodeSpec(AudioCodec.AAC, 128_000),
            ),
        )
        assertTrue(args.containsSequence("-c:v", "libx264"))
        assertFalse(args.containsSequence("-c:v", "copy"))
    }

    // ============================================================ 图片转视频 ====

    private fun slideshowArgs(images: List<String>, secondsEach: Double = 3.0, fps: Int = 30) =
        Commands.slideshow(
            settings = settings,
            plan = plan,
            spec = SlideshowSpec(
                output = "/out.mp4",
                inputs = images,
                secondsEach = secondsEach,
                fps = fps,
                video = VideoEncodeSpec(VideoCodec.H264, RateMode.CRF),
            ),
        )

    @Test
    fun `每张图片带 loop 帧率与停留时长`() {
        val args = slideshowArgs(listOf("/a.jpg", "/b.jpg"))
        assertTrue(args.containsSequence("-loop", "1", "-framerate", "30", "-t", "3", "-i", "/a.jpg"))
        assertTrue(args.containsSequence("-loop", "1", "-framerate", "30", "-t", "3", "-i", "/b.jpg"))
    }

    @Test
    fun `图片转视频统一尺寸后再 concat`() {
        // concat 遇到分辨率不一致会直接报错，所以每路都要先 scale+pad 到同一尺寸
        val graph = slideshowArgs(listOf("/a.jpg", "/b.jpg")).valueOf("-filter_complex")
        assertTrue(graph!!.contains("[0:v]scale=1920:1080"))
        assertTrue(graph.contains("[1:v]scale=1920:1080"))
        assertTrue(graph.contains("setsar=1[v0]"))
        assertTrue(graph.contains("setsar=1[v1]"))
        assertTrue(graph.contains("[v0][v1] concat=n=2:v=1:a=0[v]"))
    }

    @Test
    fun `图片转视频映射到 concat 的输出标签`() {
        val args = slideshowArgs(listOf("/a.jpg"))
        assertTrue(args.containsSequence("-map", "[v]"))
    }

    // ================================================================ 音频轨 ====

    @Test
    fun `audio 为 null 时丢弃音频轨而不是留下未指定的编码`() {
        // 约定与 render 一致：null 表示丢弃。早先无条件 -map 0:a:0?，
        // 于是「丢弃音频」开关点了等于没点
        val args = rotateArgs(degrees = 90, audio = null)
        assertTrue(args.contains("-an"))
        assertFalse(args.containsSequence("-map", "0:a:0?"))
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
