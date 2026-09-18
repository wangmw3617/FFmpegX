package com.zhiwei.ffmpegx

import com.zhiwei.ffmpegx.core.hw.AudioCodecCapability
import com.zhiwei.ffmpegx.core.hw.DecoderPlan
import com.zhiwei.ffmpegx.core.hw.DeviceCodecReport
import com.zhiwei.ffmpegx.core.hw.EncoderPlan
import com.zhiwei.ffmpegx.core.hw.HardwarePlan
import com.zhiwei.ffmpegx.core.hw.HardwarePlanCalculator
import com.zhiwei.ffmpegx.core.hw.HwStrategy
import com.zhiwei.ffmpegx.core.hw.SocProfile
import com.zhiwei.ffmpegx.core.hw.SocTier
import com.zhiwei.ffmpegx.core.hw.SocVendor
import com.zhiwei.ffmpegx.core.hw.TranscodeRequest
import com.zhiwei.ffmpegx.core.hw.VideoCodec
import com.zhiwei.ffmpegx.core.hw.VideoCodecCapability
import com.zhiwei.ffmpegx.core.settings.AppSettings

/**
 * 测试用假数据。
 *
 * 刻意不用 Mockito —— 这里要构造的都是纯 data class，直接 new 出来比 mock 更直观，
 * 也更容易看出「测试到底在假设设备有什么能力」。
 */
object Fixtures {

    fun soc(
        vendor: SocVendor = SocVendor.QUALCOMM,
        tier: SocTier = SocTier.FLAGSHIP,
        suggestVulkan: Boolean = false,
    ) = SocProfile(
        vendor = vendor,
        tier = tier,
        socModel = "测试平台",
        socManufacturer = "Test",
        hardware = "testboard",
        board = "testboard",
        deviceManufacturer = "Test",
        deviceModel = "Test Device",
        isEmulator = false,
        suggestVulkanFilters = suggestVulkan,
    )

    fun capability(
        codecName: String,
        mime: String,
        isEncoder: Boolean = false,
        isHardware: Boolean = true,
        minWidth: Int = 128,
        maxWidth: Int = 4096,
        minHeight: Int = 128,
        maxHeight: Int = 4096,
        maxBitrate: Int = 120_000_000,
        minFps: Double = 0.0,
        maxFps: Double = 240.0,
        supportsSurface: Boolean = true,
    ) = VideoCodecCapability(
        codecName = codecName,
        mime = mime,
        isEncoder = isEncoder,
        isHardware = isHardware,
        isSoftwareOnly = !isHardware,
        isVendor = isHardware,
        minWidth = minWidth,
        maxWidth = maxWidth,
        minHeight = minHeight,
        maxHeight = maxHeight,
        minBitrate = 0,
        maxBitrate = maxBitrate,
        minFps = minFps,
        maxFps = maxFps,
        widthAlignment = 2,
        heightAlignment = 2,
        supportsSurface = supportsSurface,
        supports10Bit = false,
        profiles = emptyList(),
    )

    /** 一台「H.264/H.265 都能硬编硬解」的旗舰机，但没有 AV1 硬件编码器 */
    fun flagshipReport(): DeviceCodecReport = DeviceCodecReport(
        soc = soc(),
        videoEncoders = listOf(
            capability("c2.qti.avc.encoder", "video/avc", isEncoder = true),
            capability("c2.qti.hevc.encoder", "video/hevc", isEncoder = true),
        ),
        videoDecoders = listOf(
            capability("c2.qti.avc.decoder", "video/avc"),
            capability("c2.qti.hevc.decoder", "video/hevc"),
            capability("c2.qti.av1.decoder", "video/av01"),
        ),
        audioEncoders = listOf(
            AudioCodecCapability(
                codecName = "c2.android.aac.encoder",
                mime = "audio/mp4a-latm",
                isEncoder = true,
                isHardware = false,
                maxChannels = 8,
                maxBitrate = 512_000,
            ),
        ),
        scannedAtMillis = 0L,
    )

    /** 一台只支持 720p H.264 硬编的老机器 */
    fun lowEndReport(): DeviceCodecReport = DeviceCodecReport(
        soc = soc(vendor = SocVendor.UNISOC, tier = SocTier.ENTRY),
        videoEncoders = listOf(
            capability(
                "OMX.unisoc.avc.encoder",
                "video/avc",
                isEncoder = true,
                maxWidth = 1280,
                maxHeight = 720,
                maxBitrate = 20_000_000,
            ),
        ),
        videoDecoders = listOf(
            capability("OMX.unisoc.avc.decoder", "video/avc", maxWidth = 1920, maxHeight = 1080),
        ),
        audioEncoders = emptyList(),
        scannedAtMillis = 0L,
    )

    /** 一台什么都没有的机器（模拟器 / 未开放硬件编解码的 ROM） */
    fun bareReport(): DeviceCodecReport = DeviceCodecReport(
        soc = soc(vendor = SocVendor.UNKNOWN, tier = SocTier.UNKNOWN),
        videoEncoders = emptyList(),
        videoDecoders = emptyList(),
        audioEncoders = emptyList(),
        scannedAtMillis = 0L,
    )

    fun request(
        strategy: HwStrategy = HwStrategy.AUTO,
        sourceWidth: Int = 1920,
        sourceHeight: Int = 1080,
        sourceFps: Double = 30.0,
        sourceMime: String? = "video/avc",
        targetCodec: VideoCodec = VideoCodec.H264,
        targetWidth: Int = 1920,
        targetHeight: Int = 1080,
        targetFps: Double = 30.0,
        targetBitrate: Int = 6_000_000,
        hasCpuVideoFilters: Boolean = false,
    ) = TranscodeRequest(
        strategy = strategy,
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        sourceFps = sourceFps,
        sourceMime = sourceMime,
        targetCodec = targetCodec,
        targetWidth = targetWidth,
        targetHeight = targetHeight,
        targetFps = targetFps,
        targetBitrate = targetBitrate,
        hasCpuVideoFilters = hasCpuVideoFilters,
    )

    fun settings(
        strategy: HwStrategy = HwStrategy.AUTO,
        threads: Int = 0,
        logLevel: Int = com.zhiwei.ffmpegx.native.AvLog.INFO,
        overwrite: Boolean = true,
    ) = AppSettings(
        hwStrategy = strategy,
        threadCount = threads,
        logLevel = logLevel,
        overwriteOutput = overwrite,
    )

    /** 全软件方案，用来单独测试命令渲染（不掺硬件参数） */
    fun softPlan(targetCodec: VideoCodec = VideoCodec.H264) = HardwarePlan(
        strategy = HwStrategy.COMPAT,
        decoder = DecoderPlan.Software,
        encoder = EncoderPlan.Software(targetCodec.swName ?: "libx264"),
        inputArgs = emptyList(),
        outputArgs = emptyList(),
        reasons = emptyList(),
        warnings = emptyList(),
    )

    /** 真实跑一遍规划器得到的硬件方案 */
    fun hwPlan(
        report: DeviceCodecReport = flagshipReport(),
        request: TranscodeRequest = request(),
    ) = HardwarePlanCalculator.plan(report, request)
}

/**
 * argv 是「顺序敏感」的，断言时必须检查**连续子序列**，
 * 否则 `-map 0:v:0?` 和 `-map 0:a:0?` 会互相误判。
 */
fun List<String>.containsSequence(vararg seq: String): Boolean {
    if (seq.isEmpty()) return true
    if (seq.size > size) return false
    outer@ for (i in 0..size - seq.size) {
        for (j in seq.indices) {
            if (this[i + j] != seq[j]) continue@outer
        }
        return true
    }
    return false
}

/** 取 `-key` 后面紧跟的值；不存在返回 null */
fun List<String>.valueOf(key: String): String? {
    val i = indexOf(key)
    return if (i >= 0 && i + 1 < size) this[i + 1] else null
}
