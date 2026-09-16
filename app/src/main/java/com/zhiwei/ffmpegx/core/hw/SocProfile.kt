package com.zhiwei.ffmpegx.core.hw

import android.os.Build

/**
 * SoC 厂商。
 *
 * 注意：这里的判断只用于「调优提示」——例如推荐哪个 MediaCodec 名称前缀、
 * 是否值得尝试 Vulkan 滤镜链。**真正的能力判定一律以 MediaCodecList 运行时枚举为准**，
 * 因为同一颗 SoC 在不同厂商 ROM 上开放的能力可能不同（尤其 AV1 编码）。
 */
enum class SocVendor(val label: String) {
    QUALCOMM("高通骁龙"),
    MEDIATEK("联发科天玑"),
    SAMSUNG("三星 Exynos"),
    GOOGLE("Google Tensor"),
    HISILICON("海思麒麟"),
    UNISOC("紫光展锐"),
    NVIDIA("NVIDIA"),
    UNKNOWN("未知平台"),
}

/** 粗略档位，用于默认参数（GOP、码率上限、是否默认开 Vulkan 滤镜） */
enum class SocTier(val label: String) {
    FLAGSHIP("旗舰"),
    UPPER_MID("次旗舰 / 中高端"),
    MID("中端"),
    ENTRY("入门"),
    UNKNOWN("未知"),
}

/**
 * 设备平台画像。所有字段都来自 `android.os.Build`，**不含任何硬编码的能力断言**。
 */
data class SocProfile(
    val vendor: SocVendor,
    val tier: SocTier,
    /** Build.SOC_MODEL（API 31+），拿不到时退回 Build.HARDWARE */
    val socModel: String,
    val socManufacturer: String,
    val hardware: String,
    val board: String,
    val deviceManufacturer: String,
    val deviceModel: String,
    val isEmulator: Boolean,
    /** 该平台的调优说明，展示在首页「设备与硬件加速」卡片里 */
    val notes: List<String>,
    /** 是否建议默认开启 Vulkan 滤镜链（需要 libplacebo 级别的滤镜时才真正有价值） */
    val suggestVulkanFilters: Boolean,
) {
    val displayName: String
        get() = buildString {
            append(vendor.label)
            val model = socModel.ifBlank { hardware }
            if (model.isNotBlank()) append(" · ").append(model)
        }

    companion object {

        /** 已知的旗舰平台代号 → 展示名。仅用于让 UI 显示得更友好。 */
        private val FLAGSHIP_CODENAMES: Map<String, Pair<SocVendor, String>> = buildMap {
            // ---- 高通骁龙 ----
            put("sm8750", SocVendor.QUALCOMM to "骁龙 8 Elite")
            put("sun", SocVendor.QUALCOMM to "骁龙 8 Elite")
            put("sm8650", SocVendor.QUALCOMM to "骁龙 8 Gen 3")
            put("pineapple", SocVendor.QUALCOMM to "骁龙 8 Gen 3")
            put("sm8550", SocVendor.QUALCOMM to "骁龙 8 Gen 2")
            put("kalama", SocVendor.QUALCOMM to "骁龙 8 Gen 2")
            put("sm8450", SocVendor.QUALCOMM to "骁龙 8 Gen 1")
            put("waipio", SocVendor.QUALCOMM to "骁龙 8 Gen 1")
            put("sm8475", SocVendor.QUALCOMM to "骁龙 8+ Gen 1")
            put("cape", SocVendor.QUALCOMM to "骁龙 8+ Gen 1")
            put("sm7675", SocVendor.QUALCOMM to "骁龙 7+ Gen 3")
            put("crow", SocVendor.QUALCOMM to "骁龙 7+ Gen 3")
            // ---- 联发科天玑 ----
            put("mt6991", SocVendor.MEDIATEK to "天玑 9400")
            put("mt6989", SocVendor.MEDIATEK to "天玑 9300")
            put("mt6985", SocVendor.MEDIATEK to "天玑 9200")
            put("mt6983", SocVendor.MEDIATEK to "天玑 9000")
            put("mt6897", SocVendor.MEDIATEK to "天玑 8300")
            // ---- 三星 ----
            put("s5e9945", SocVendor.SAMSUNG to "Exynos 2400")
            put("s5e9935", SocVendor.SAMSUNG to "Exynos 2200")
            put("erd9945", SocVendor.SAMSUNG to "Exynos 2400")
            // ---- Google Tensor ----
            put("zuma", SocVendor.GOOGLE to "Tensor G2")
            put("zumapro", SocVendor.GOOGLE to "Tensor G3")
            put("ripcurrent", SocVendor.GOOGLE to "Tensor G4")
            put("laguna", SocVendor.GOOGLE to "Tensor G5")
            put("gs101", SocVendor.GOOGLE to "Tensor G1")
            // ---- 海思 ----
            put("kirin9000", SocVendor.HISILICON to "麒麟 9000")
            put("kirin9000s", SocVendor.HISILICON to "麒麟 9000S")
        }

        private val FLAGSHIP_HINTS = setOf(
            "8gen1", "8gen2", "8gen3", "8gen4", "8elite", "8+gen",
            "9300", "9400", "9200", "9000",
            "2400", "2200",
            "tensor", "zumapro", "ripcurrent", "laguna",
            "kirin9",
        )

        private val UPPER_MID_HINTS = setOf(
            "7+gen", "7gen", "870", "888", "865", "860",
            "8300", "8200", "8100", "8000",
            "exynos1", "tensor",
            "kirin8",
        )

        fun detect(): SocProfile {
            val hardware = Build.HARDWARE.orEmpty()
            val board = Build.BOARD.orEmpty()
            val manufacturer = Build.MANUFACTURER.orEmpty()
            val socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MANUFACTURER.orEmpty()
            } else ""
            val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL.orEmpty()
            } else ""

            val fingerprint = buildString {
                append(hardware).append(' ')
                append(board).append(' ')
                append(manufacturer).append(' ')
                append(socManufacturer).append(' ')
                append(socModel)
            }.lowercase()

            val vendor = when {
                fingerprint.contains("qcom") || fingerprint.contains("qualcomm") ||
                    fingerprint.contains("snapdragon") -> SocVendor.QUALCOMM

                fingerprint.contains("mediatek") || fingerprint.contains("mtk") ||
                    Regex("\\bmt\\d{4}").containsMatchIn(fingerprint) -> SocVendor.MEDIATEK

                fingerprint.contains("samsung") || fingerprint.contains("exynos") ||
                    Regex("s5e\\d{4}").containsMatchIn(fingerprint) -> SocVendor.SAMSUNG

                fingerprint.contains("google") || fingerprint.contains("tensor") -> SocVendor.GOOGLE
                fingerprint.contains("hisilicon") || fingerprint.contains("kirin") -> SocVendor.HISILICON
                fingerprint.contains("unisoc") || fingerprint.contains("spreadtrum") -> SocVendor.UNISOC
                fingerprint.contains("nvidia") || fingerprint.contains("tegra") -> SocVendor.NVIDIA
                else -> SocVendor.UNKNOWN
            }

            val isEmulator = fingerprint.contains("goldfish") ||
                fingerprint.contains("ranchu") ||
                fingerprint.contains("generic") ||
                fingerprint.contains("emulator")

            val tier = when {
                isEmulator -> SocTier.UNKNOWN
                FLAGSHIP_HINTS.any { fingerprint.contains(it) } -> SocTier.FLAGSHIP
                UPPER_MID_HINTS.any { fingerprint.contains(it) } -> SocTier.UPPER_MID
                else -> SocTier.UNKNOWN
            }

            val codenameHit = FLAGSHIP_CODENAMES.entries.firstOrNull { (code, _) ->
                fingerprint.contains(code)
            }?.value

            val friendlyModel = codenameHit?.second ?: socModel.ifBlank { hardware }

            return SocProfile(
                vendor = vendor,
                tier = tier,
                socModel = friendlyModel,
                socManufacturer = socManufacturer,
                hardware = hardware,
                board = board,
                deviceManufacturer = manufacturer,
                deviceModel = Build.MODEL.orEmpty(),
                isEmulator = isEmulator,
                notes = buildNotes(vendor, tier, isEmulator),
                suggestVulkanFilters = tier == SocTier.FLAGSHIP && !isEmulator,
            )
        }

        private fun buildNotes(vendor: SocVendor, tier: SocTier, isEmulator: Boolean): List<String> {
            if (isEmulator) {
                return listOf(
                    "当前运行在模拟器上，MediaCodec 走的是软件模拟实现，",
                    "硬件加速开关不会有实际收益，建议用真机验证性能。",
                )
            }
            return when (vendor) {
                SocVendor.QUALCOMM -> listOf(
                    "编码器通常以 c2.qti.* 开头，骁龙 8 Gen 2 及之后支持 AV1 硬解，8 Gen 3 起部分机型开放 AV1 硬编。",
                    "MediaCodec 编码对 GOP 敏感，建议关键帧间隔设为帧率的 1~2 倍。",
                )
                SocVendor.MEDIATEK -> listOf(
                    "编码器通常以 c2.mtk.* 开头，天玑 9200 起支持 AV1 硬编，9000 起支持 AV1 硬解。",
                    "部分天玑平台的 HEVC 硬编在 4K 下对码率上限较敏感，超出会回落到软编。",
                )
                SocVendor.SAMSUNG -> listOf(
                    "Exynos 平台编码器常见 c2.exynos.* / OMX.Exynos.*，AV1 硬解自 Exynos 2200 起。",
                    "部分三星 ROM 对第三方 App 的硬件编码器开放有限，请以本页枚举结果为准。",
                )
                SocVendor.GOOGLE -> listOf(
                    "Tensor 平台的编解码器多来自三星 MFC，命名常为 c2.gs.* 或 c2.exynos.*。",
                    "Tensor 系列 AV1 硬解支持较好，硬编能力请以枚举结果为准。",
                )
                SocVendor.HISILICON -> listOf(
                    "麒麟平台编码器常见 OMX.hisi.* / c2.hisi.*，能力随 ROM 版本差异较大。",
                )
                SocVendor.UNISOC -> listOf(
                    "展锐平台硬件编解码能力较弱，建议对高分辨率素材使用软编以保证画质。",
                )
                SocVendor.NVIDIA -> listOf("Tegra 平台多见于平板/车机，编码器为 c2.nvidia.*。")
                SocVendor.UNKNOWN -> listOf(
                    "未能识别芯片平台，将完全依赖 MediaCodec 运行时枚举结果。",
                )
            } + if (tier == SocTier.FLAGSHIP) {
                listOf("识别为旗舰档位，可尝试开启 Vulkan 滤镜链以降低高分辨率缩放/叠加的功耗。")
            } else {
                emptyList()
            }
        }
    }
}
