package com.zhiwei.ffmpegx.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进度解析的单元测试。
 *
 * FFmpeg 有两种进度输出形态，区分判据是「行内是否含 `time=`」——
 * 不能用 `startsWith("frame=")`，因为统计行也以 `frame=` 开头，
 * 会被误判成 key=value 形态从而丢掉 time/fps（这是实际踩过的坑）。
 */
class ProgressParserTest {

    private val empty = TranscodeProgress()

    // ------------------------------------------------------------ 统计行形态 ----

    @Test
    fun `解析标准统计行`() {
        val line = "frame=  120 fps= 30 q=28.0 size=    1024kB " +
            "time=00:00:04.00 bitrate=2097.2kbits/s speed=1.00x"

        val p = ProgressParser.parse(line, empty)!!

        assertEquals(120L, p.frame)
        assertEquals(30.0, p.fps, 0.001)
        assertEquals(28.0, p.quality, 0.001)
        assertEquals(1024L * 1024, p.outputBytes)
        assertEquals(4_000_000L, p.timeUs)
        assertEquals(2097.2, p.bitrateKbps, 0.001)
        assertEquals(1.0, p.speed, 0.001)
    }

    @Test
    fun `统计行不会被误判成 key value 形态`() {
        // 这是回归测试：曾经用 startsWith("frame=") 做判据，导致 time/fps 全部丢失
        val line = "frame=  120 fps= 30 q=28.0 size=    1024kB " +
            "time=00:00:04.00 bitrate=2097.2kbits/s speed=1.00x"
        val p = ProgressParser.parse(line, empty)!!

        assertTrue("time 必须被解析出来", p.timeUs > 0)
        assertTrue("fps 必须被解析出来", p.fps > 0)
    }

    @Test
    fun `解析带小时的时间戳`() {
        val line = "frame=100 fps= 25 q=20.0 size=  50000kB " +
            "time=01:02:03.50 bitrate=1000.0kbits/s speed=2.00x"
        val p = ProgressParser.parse(line, empty)!!
        assertEquals(((1 * 3600 + 2 * 60 + 3) * 1_000_000L) + 500_000L, p.timeUs)
    }

    @Test
    fun `解析 dup 与 drop 计数`() {
        val line = "frame=  900 fps= 30 q=28.0 Lsize=  10000kB time=00:00:30.00 " +
            "bitrate=2000.0kbits/s dup=3 drop=7 speed=1.00x"
        val p = ProgressParser.parse(line, empty)!!
        assertEquals(3L, p.dupFrames)
        assertEquals(7L, p.dropFrames)
        assertEquals(10_000L * 1024, p.outputBytes)
    }

    // ------------------------------------------------------- key=value 形态 ----

    @Test
    fun `解析 out_time_us`() {
        val p = ProgressParser.parse("out_time_us=4000000", empty)!!
        assertEquals(4_000_000L, p.timeUs)
    }

    @Test
    fun `out_time_ms 的单位其实是微秒`() {
        // FFmpeg 的历史遗留命名：out_time_ms 实际给的是微秒，不能除以 1000
        val p = ProgressParser.parse("out_time_ms=4000000", empty)!!
        assertEquals(4_000_000L, p.timeUs)
    }

    @Test
    fun `解析 total_size 与 frame 等键值`() {
        assertEquals(
            1_048_576L,
            ProgressParser.parse("total_size=1048576", empty)!!.outputBytes,
        )
        assertEquals(500L, ProgressParser.parse("frame=500", empty)!!.frame)
        assertEquals(29.97, ProgressParser.parse("fps=29.97", empty)!!.fps, 0.001)
        assertEquals(1.5, ProgressParser.parse("speed=1.5x", empty)!!.speed, 0.001)
    }

    @Test
    fun `无法识别的行返回 null 而不是清零`() {
        val previous = TranscodeProgress(frame = 100, timeUs = 5_000_000)
        assertNull(ProgressParser.parse("Stream mapping:", previous))
        assertNull(ProgressParser.parse("Press [q] to stop, [?] for help", previous))
        assertNull(ProgressParser.parse("", previous))
    }

    @Test
    fun `解析失败时保持上一次的值`() {
        // 传进去的行不含任何可解析字段时，调用方应保留旧进度（返回 null）
        val previous = TranscodeProgress(frame = 100, timeUs = 5_000_000)
        assertNull(ProgressParser.parse("Output #0, mp4, to 'out.mp4':", previous))
    }

    // ------------------------------------------------------------ 判据本身 ----

    @Test
    fun `looksLikeProgress 正确区分进度行与普通日志`() {
        assertTrue(
            ProgressParser.looksLikeProgress(
                "frame=  120 fps= 30 q=28.0 size=1024kB time=00:00:04.00 speed=1.00x",
            ),
        )
        assertTrue(ProgressParser.looksLikeProgress("out_time_us=4000000"))
        assertTrue(ProgressParser.looksLikeProgress("total_size=1048576"))
        assertTrue(ProgressParser.looksLikeProgress("progress=continue"))

        assertFalse(ProgressParser.looksLikeProgress("Stream mapping:"))
        assertFalse(ProgressParser.looksLikeProgress("[libx264 @ 0x7f] using cpu capabilities: NEON"))
        assertFalse(ProgressParser.looksLikeProgress("frame=  120 fps= 30 q=28.0"))
    }

    // ------------------------------------------------------------ 派生指标 ----

    @Test
    fun `百分比按总时长计算并夹在 0 到 100 之间`() {
        assertEquals(
            40.0,
            TranscodeProgress(timeUs = 4_000_000, totalDurationUs = 10_000_000).percent,
            0.01,
        )
        assertEquals(
            "超出总时长时应夹到 100",
            100.0,
            TranscodeProgress(timeUs = 12_000_000, totalDurationUs = 10_000_000).percent,
            0.01,
        )
    }

    @Test
    fun `总时长未知时百分比返回负值表示不确定进度`() {
        assertEquals(-1.0, TranscodeProgress(timeUs = 4_000_000).percent, 0.01)
    }

    @Test
    fun `剩余时间按 speed 推算`() {
        val p = TranscodeProgress(timeUs = 4_000_000, totalDurationUs = 10_000_000, speed = 2.0)
        // 剩余 6 秒素材，2 倍速 -> 3 秒
        assertEquals(3_000L, p.remainingMs)
    }

    @Test
    fun `速度为 0 时无法估算剩余时间`() {
        val p = TranscodeProgress(timeUs = 4_000_000, totalDurationUs = 10_000_000, speed = 0.0)
        assertEquals(-1L, p.remainingMs)
    }

    @Test
    fun `展示用的格式化字符串`() {
        assertEquals("—", TranscodeProgress().speedLabel)
        assertEquals("1.25x", TranscodeProgress(speed = 1.25).speedLabel)
        assertEquals("—", TranscodeProgress().fpsLabel)
        assertEquals("29.9 fps", TranscodeProgress(fps = 29.94).fpsLabel)
    }
}
