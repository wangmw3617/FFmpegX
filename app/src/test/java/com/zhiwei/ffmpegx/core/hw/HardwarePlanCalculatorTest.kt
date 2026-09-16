package com.zhiwei.ffmpegx.core.hw

import com.zhiwei.ffmpegx.Fixtures
import com.zhiwei.ffmpegx.valueOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 硬件加速规划的单元测试。
 *
 * 这里守的是 [HardwarePlanner] 文档里那三条硬规则 ——
 * 它们是整个项目最容易出错、出错后最难查的部分（现象往往只是 FFmpeg 报一句
 * "Impossible to convert between the formats"，看不出是规划的锅）。
 */
class HardwarePlanCalculatorTest {

    private val report = Fixtures.flagshipReport()

    // ---------------------------------------------------------------- 规则 1 ----

    @Test
    fun `无 CPU 滤镜且有硬编时，开启零拷贝`() {
        val plan = HardwarePlanCalculator.plan(
            report,
            Fixtures.request(hasCpuVideoFilters = false),
        )

        val decoder = plan.decoder as DecoderPlan.MediaCodec
        assertTrue("应当启用 Surface 输出", decoder.outputSurface)
        assertTrue("应当被识别为零拷贝通路", plan.isZeroCopy)

        assertEquals(
            listOf("-hwaccel", "mediacodec", "-hwaccel_output_format", "mediacodec"),
            plan.inputArgs,
        )
        assertTrue(plan.encoder is EncoderPlan.MediaCodec)
        assertEquals("h264_mediacodec", (plan.encoder as EncoderPlan.MediaCodec).ffmpegName)
        assertEquals("零拷贝硬解硬编", plan.badge)
    }

    @Test
    fun `有 CPU 滤镜时必须关掉 Surface 输出`() {
        val plan = HardwarePlanCalculator.plan(
            report,
            Fixtures.request(hasCpuVideoFilters = true),
        )

        val decoder = plan.decoder as DecoderPlan.MediaCodec
        assertFalse("有滤镜时不能开 Surface 输出，否则滤镜拿不到帧", decoder.outputSurface)
        assertFalse(plan.isZeroCopy)

        assertEquals(
            "只应带 -hwaccel mediacodec，不能带 output_format",
            listOf("-hwaccel", "mediacodec"),
            plan.inputArgs,
        )
    }

    @Test
    fun `编码器不是 MediaCodec 时不能零拷贝`() {
        // 质量优先 -> 软编，此时解码器输出必须是内存帧
        val plan = HardwarePlanCalculator.plan(
            report,
            Fixtures.request(strategy = HwStrategy.QUALITY, hasCpuVideoFilters = false),
        )

        assertTrue(plan.encoder is EncoderPlan.Software)
        val decoder = plan.decoder as DecoderPlan.MediaCodec
        assertFalse(decoder.outputSurface)
        assertFalse(plan.isZeroCopy)
    }

    // ---------------------------------------------------------------- 规则 3 ----

    @Test
    fun `硬编分辨率超限时回落软编并给出警告`() {
        val plan = HardwarePlanCalculator.plan(
            Fixtures.lowEndReport(),
            Fixtures.request(targetWidth = 1920, targetHeight = 1080),
        )

        assertTrue(
            "720p 的硬编不能接 1080p 的活，必须回落",
            plan.encoder is EncoderPlan.Software,
        )
        assertEquals("libx264", (plan.encoder as EncoderPlan.Software).ffmpegName)
        assertTrue(
            "必须告诉用户为什么没用硬编",
            plan.warnings.any { it.contains("回落") },
        )
    }

    @Test
    fun `分辨率在硬编能力范围内时正常使用硬编`() {
        val plan = HardwarePlanCalculator.plan(
            Fixtures.lowEndReport(),
            Fixtures.request(targetWidth = 1280, targetHeight = 720),
        )
        assertTrue(plan.encoder is EncoderPlan.MediaCodec)
    }

    @Test
    fun `既无硬编也无软编时报 Unavailable`() {
        // AV1 没有软件编码器，这台设备也没有 AV1 硬编
        val plan = HardwarePlanCalculator.plan(
            report,
            Fixtures.request(targetCodec = VideoCodec.AV1),
        )
        assertEquals(EncoderPlan.Unavailable, plan.encoder)
        assertTrue(plan.warnings.any { it.contains("AV1") })
    }

    // ------------------------------------------------------------ 策略分支 ----

    @Test
    fun `兼容优先策略全走软件`() {
        val plan = HardwarePlanCalculator.plan(
            report,
            Fixtures.request(strategy = HwStrategy.COMPAT),
        )

        assertEquals(DecoderPlan.Software, plan.decoder)
        assertEquals(EncoderPlan.Software("libx264"), plan.encoder)
        assertTrue("软件路径不应有任何硬件参数", plan.inputArgs.isEmpty())
        assertTrue(plan.outputArgs.isEmpty())
    }

    @Test
    fun `关闭策略与兼容优先等效`() {
        val plan = HardwarePlanCalculator.plan(
            report,
            Fixtures.request(strategy = HwStrategy.OFF),
        )
        assertEquals(DecoderPlan.Software, plan.decoder)
        assertTrue(plan.encoder is EncoderPlan.Software)
    }

    @Test
    fun `质量优先主动跳过硬编改用软件编码`() {
        val plan = HardwarePlanCalculator.plan(
            report,
            Fixtures.request(strategy = HwStrategy.QUALITY),
        )

        assertEquals(
            "文档承诺「硬解 + 软编」，规划器必须真的这么做",
            EncoderPlan.Software("libx264"),
            plan.encoder,
        )
        assertTrue(
            "仍应使用硬件解码",
            plan.decoder is DecoderPlan.MediaCodec,
        )
        assertTrue(plan.reasons.any { it.contains("质量优先") })
    }

    @Test
    fun `质量优先但目标格式没有软件编码器时退回硬编并警告`() {
        // HEVC 的 swName 是 null（默认构建不含 libx265）
        val plan = HardwarePlanCalculator.plan(
            report,
            Fixtures.request(strategy = HwStrategy.QUALITY, targetCodec = VideoCodec.HEVC),
        )

        assertTrue(
            "没有软编就只能用硬编，总比不可用好",
            plan.encoder is EncoderPlan.MediaCodec,
        )
        assertTrue(plan.warnings.any { it.contains("质量优先") && it.contains("无法生效") })
    }

    // ------------------------------------------------------------ 解码器分支 ----

    @Test
    fun `源格式没有硬件解码器时使用软件解码`() {
        val plan = HardwarePlanCalculator.plan(
            report,
            Fixtures.request(sourceMime = "video/x-vnd.on2.vp8"),
        )
        assertEquals(DecoderPlan.Software, plan.decoder)
        assertTrue(plan.reasons.any { it.contains("没有匹配的硬件解码器") })
    }

    @Test
    fun `源格式未知时保守地使用软件解码`() {
        val plan = HardwarePlanCalculator.plan(report, Fixtures.request(sourceMime = null))
        assertEquals(DecoderPlan.Software, plan.decoder)
    }

    // -------------------------------------------------------- 硬编输出侧参数 ----

    @Test
    fun `硬编输出参数包含 GOP 码率上限与关 B 帧`() {
        val plan = HardwarePlanCalculator.plan(
            report,
            Fixtures.request(targetFps = 30.0, targetBitrate = 6_000_000),
        )

        // GOP = 帧率 * 2 秒
        assertEquals("60", plan.outputArgs.valueOf("-g"))
        assertEquals("6000000", plan.outputArgs.valueOf("-b:v"))
        // 1.5 倍软上限，避免瞬时冲顶掉帧
        assertEquals("9000000", plan.outputArgs.valueOf("-maxrate"))
        assertEquals("12000000", plan.outputArgs.valueOf("-bufsize"))
        // MediaCodec 编码器普遍不支持 B 帧
        assertEquals("0", plan.outputArgs.valueOf("-bf"))
    }

    @Test
    fun `未指定码率时给出警告且不生成 -b v`() {
        val plan = HardwarePlanCalculator.plan(
            report,
            Fixtures.request(targetBitrate = 0),
        )
        assertEquals(null, plan.outputArgs.valueOf("-b:v"))
        assertTrue(plan.warnings.any { it.contains("未指定目标码率") })
    }

    @Test
    fun `空设备报告不会崩且给出可读结果`() {
        val plan = HardwarePlanCalculator.plan(Fixtures.bareReport(), Fixtures.request())
        assertEquals(DecoderPlan.Software, plan.decoder)
        assertTrue(plan.encoder is EncoderPlan.Software)
        assertNotNull(plan.badge)
    }
}
