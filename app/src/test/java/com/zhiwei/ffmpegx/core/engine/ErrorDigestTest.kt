package com.zhiwei.ffmpegx.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 报错行选取的单元测试。
 *
 * 用例里的报错文本都是从真实 FFmpeg 输出里抄下来的（见
 * `.workbuddy-ai/skills/ffmpegx-cmd-verify/SKILL.md` 的实测表），不是编的 ——
 * 这个函数的全部价值就在于「面对真实报错时挑对那一行」。
 */
class ErrorDigestTest {

    @Test
    fun `源无字幕轨时挑出根因而不是级联后果`() {
        // 实测输出：真正的根因在第一行，最后一行只是它引发的解析失败
        val tail = listOf(
            "Stream map '0:s:0' matches no streams.",
            "To ignore this, add a trailing '?' to the map.",
            "Failed to set value '0:s:0' for option 'map': Invalid argument",
            "Error parsing options for output file /out.srt.",
        )
        assertEquals("Stream map '0:s:0' matches no streams.", pickErrorLine(tail))
    }

    @Test
    fun `拼接时某一路缺音频轨时挑出 matches no streams`() {
        val tail = listOf(
            "[fc#0 @ 0x1] Stream specifier ':a:0' in filtergraph description " +
                "[0:v:0]scale=640:480[v0];[v0][0:a:0]concat=n=2:v=1:a=1[v][a] matches no streams.",
            "Error initializing complex filters: Invalid argument",
        )
        assertEquals(
            "[fc#0 @ 0x1] Stream specifier ':a:0' in filtergraph description " +
                "[0:v:0]scale=640:480[v0];[v0][0:a:0]concat=n=2:v=1:a=1[v][a] matches no streams.",
            pickErrorLine(tail),
        )
    }

    @Test
    fun `滤镜缺失时挑出 No such filter`() {
        val tail = listOf(
            "[AVFilterGraph @ 0x1] No such filter: 'o.mp4'",
            "Error initializing filters",
            "Error opening output file out.mp4.",
            "Error opening output files: Invalid argument",
        )
        assertEquals("[AVFilterGraph @ 0x1] No such filter: 'o.mp4'", pickErrorLine(tail))
    }

    @Test
    fun `没有命中任何特征时退回最后一行`() {
        // 例如 vstack 尺寸不一致，FFmpeg 只打了这两行，第一行并非「根因特征」
        val tail = listOf(
            "[fc#0 @ 0x1] Error reinitializing filters!",
            "Failed to inject frame into filter network: Invalid argument",
        )
        assertEquals("Failed to inject frame into filter network: Invalid argument", pickErrorLine(tail))
    }

    @Test
    fun `忽略空行与纯空白行`() {
        val tail = listOf("", "   ", "Encoder not found", "")
        assertEquals("Encoder not found", pickErrorLine(tail))
    }

    @Test
    fun `空尾巴返回 null`() {
        assertNull(pickErrorLine(emptyList()))
        assertNull(pickErrorLine(listOf("", "  ")))
    }

    @Test
    fun `多个特征命中时取最靠前的那条`() {
        // 越靠前越接近根因，这是整个策略的前提
        val tail = listOf(
            "Unable to open input.srt",
            "No such filter: 'subtitles'",
        )
        assertEquals("Unable to open input.srt", pickErrorLine(tail))
    }
}
