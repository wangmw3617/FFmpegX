package com.zhiwei.ffmpegx.core.hw

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log

/**
 * 运行时枚举设备的 MediaCodec 能力。
 *
 * 这是整个「硬件加速」功能的事实来源。刻意不信任任何 SoC 型号表：
 * 同一颗芯片在不同厂商 ROM、不同系统版本上开放的编码器集合都可能不同，
 * 只有 `MediaCodecList` 的结果才是权威的。
 *
 * 结果会缓存，设备能力在运行期不会变化。
 */
class MediaCodecScanner @javax.inject.Inject constructor() {

    companion object {
        private const val TAG = "MediaCodecScanner"

        /** MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface */
        private const val COLOR_FORMAT_SURFACE = 0x7F000789
    }

    @Volatile
    private var cached: DeviceCodecReport? = null

    fun report(forceRefresh: Boolean = false): DeviceCodecReport {
        cached?.let { if (!forceRefresh) return it }
        val scanned = scan()
        cached = scanned
        return scanned
    }

    private fun scan(): DeviceCodecReport {
        val videoEncoders = mutableListOf<VideoCodecCapability>()
        val videoDecoders = mutableListOf<VideoCodecCapability>()
        val audioEncoders = mutableListOf<AudioCodecCapability>()

        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            // 别名指向同一个底层实现，重复计入会误导用户
            if (isAlias(info)) continue

            for (mime in info.supportedTypes) {
                val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
                when {
                    mime.startsWith("video/") -> {
                        val vc = caps.videoCapabilities ?: continue
                        val item = buildVideoCapability(info, mime, caps, vc)
                        if (info.isEncoder) videoEncoders += item else videoDecoders += item
                    }
                    mime.startsWith("audio/") && info.isEncoder -> {
                        val ac = caps.audioCapabilities ?: continue
                        audioEncoders += AudioCodecCapability(
                            codecName = info.name,
                            mime = mime,
                            isEncoder = true,
                            isHardware = isHardware(info),
                            maxChannels = runCatching { ac.maxInputChannelCount }.getOrDefault(0),
                            maxBitrate = runCatching { ac.bitrateRange.upper }.getOrDefault(0),
                        )
                    }
                }
            }
        }

        val report = DeviceCodecReport(
            soc = SocProfile.detect(),
            videoEncoders = videoEncoders.sortedWith(
                compareByDescending<VideoCodecCapability> { it.isHardware }
                    .thenByDescending { it.maxWidth.toLong() * it.maxHeight },
            ),
            videoDecoders = videoDecoders.sortedWith(
                compareByDescending<VideoCodecCapability> { it.isHardware }
                    .thenByDescending { it.maxWidth.toLong() * it.maxHeight },
            ),
            audioEncoders = audioEncoders,
            scannedAtMillis = System.currentTimeMillis(),
        )

        Log.i(
            TAG,
            "扫描完成：硬编 ${report.videoEncoders.count { it.isHardware }} 个，" +
                "硬解 ${report.videoDecoders.count { it.isHardware }} 个",
        )
        return report
    }

    private fun buildVideoCapability(
        info: MediaCodecInfo,
        mime: String,
        caps: MediaCodecInfo.CodecCapabilities,
        vc: MediaCodecInfo.VideoCapabilities,
    ): VideoCodecCapability {
        val widthRange = runCatching { vc.supportedWidths }.getOrNull()
        val heightRange = runCatching { vc.supportedHeights }.getOrNull()
        val bitrateRange = runCatching { vc.bitrateRange }.getOrNull()
        val fpsRange = runCatching { vc.supportedFrameRates }.getOrNull()

        val colorFormats = runCatching { caps.colorFormats }.getOrDefault(IntArray(0))
        val profileNames = runCatching {
            caps.profileLevels.map { profileName(mime, it.profile) }.distinct()
        }.getOrDefault(emptyList())

        return VideoCodecCapability(
            codecName = info.name,
            mime = mime,
            isEncoder = info.isEncoder,
            isHardware = isHardware(info),
            isSoftwareOnly = isSoftwareOnly(info),
            isVendor = isVendor(info),
            minWidth = widthRange?.lower ?: 0,
            maxWidth = widthRange?.upper ?: 0,
            minHeight = heightRange?.lower ?: 0,
            maxHeight = heightRange?.upper ?: 0,
            minBitrate = bitrateRange?.lower ?: 0,
            maxBitrate = bitrateRange?.upper ?: 0,
            // android.util.Range<Double> 在 Kotlin 侧会被推成捕获类型，
            // 显式 toDouble() 才能对上 VideoCodecCapability 的字段类型
            minFps = fpsRange?.lower?.toDouble() ?: 0.0,
            maxFps = fpsRange?.upper?.toDouble() ?: 0.0,
            widthAlignment = runCatching { vc.widthAlignment }.getOrDefault(2),
            heightAlignment = runCatching { vc.heightAlignment }.getOrDefault(2),
            supportsSurface = colorFormats.contains(COLOR_FORMAT_SURFACE),
            supports10Bit = profileNames.any { it.contains("10") || it.contains("HDR") },
            profiles = profileNames,
        )
    }

    // ---------------------------------------------------------------- 版本兼容 ----

    private fun isHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return runCatching { info.isHardwareAccelerated }.getOrDefault(false)
        }
        // API 29 以下没有官方标记，按命名约定判断：
        // 软件实现固定是 OMX.google.* / c2.android.*
        val n = info.name.lowercase()
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains("software"))
    }

    private fun isSoftwareOnly(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return runCatching { info.isSoftwareOnly }.getOrDefault(false)
        }
        return !isHardware(info)
    }

    private fun isVendor(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return runCatching { info.isVendor }.getOrDefault(false)
        }
        return info.name.lowercase().startsWith("omx.") &&
            !info.name.lowercase().startsWith("omx.google.")
    }

    private fun isAlias(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return runCatching { info.isAlias }.getOrDefault(false)
        }
        return false
    }

    // ------------------------------------------------------------- Profile 名称 ----

    private fun profileName(mime: String, profile: Int): String = when (mime) {
        MediaFormat.MIMETYPE_VIDEO_AVC -> when (profile) {
            MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline -> "Baseline"
            MediaCodecInfo.CodecProfileLevel.AVCProfileMain -> "Main"
            MediaCodecInfo.CodecProfileLevel.AVCProfileExtended -> "Extended"
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh -> "High"
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10 -> "High10"
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh422 -> "High422"
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh444 -> "High444"
            else -> "AVC(0x${profile.toString(16)})"
        }

        MediaFormat.MIMETYPE_VIDEO_HEVC -> when (profile) {
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain -> "Main"
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 -> "Main10"
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMainStill -> "MainStill"
            0x1000 -> "Main10HDR10"
            0x2000 -> "Main10HDR10Plus"
            else -> "HEVC(0x${profile.toString(16)})"
        }

        MediaFormat.MIMETYPE_VIDEO_VP9 -> when (profile) {
            MediaCodecInfo.CodecProfileLevel.VP9Profile0 -> "Profile0"
            MediaCodecInfo.CodecProfileLevel.VP9Profile1 -> "Profile1"
            MediaCodecInfo.CodecProfileLevel.VP9Profile2 -> "Profile2"
            MediaCodecInfo.CodecProfileLevel.VP9Profile3 -> "Profile3"
            0x1000 -> "Profile2HDR"
            0x2000 -> "Profile3HDR"
            else -> "VP9(0x${profile.toString(16)})"
        }

        MediaFormat.MIMETYPE_VIDEO_AV1 -> when (profile) {
            0x1 -> "Main8"
            0x2 -> "Main10"
            0x1000 -> "Main10HDR10"
            0x2000 -> "Main10HDR10Plus"
            else -> "AV1(0x${profile.toString(16)})"
        }

        else -> "0x${profile.toString(16)}"
    }
}
