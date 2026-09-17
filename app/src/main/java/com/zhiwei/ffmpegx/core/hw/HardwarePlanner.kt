package com.zhiwei.ffmpegx.core.hw

/**
 * 硬件加速策略。对应设置页里的下拉项。
 */
enum class HwStrategy(
    val label: String,
    val description: String,
) {
    AUTO("智能", "无滤镜时走零拷贝硬解硬编；有滤镜时硬解 + 软编/硬编自动取舍"),
    SPEED("速度优先", "解码与编码都优先走 MediaCodec，追求最快出片"),
    QUALITY("质量优先", "硬件解码 + 软件编码，画质与体积更可控"),
    COMPAT("兼容优先", "全软编软解，兼容性最好但速度最慢"),
    OFF("关闭", "完全不使用硬件加速"),
    ;

    val usesHardware: Boolean get() = this != COMPAT && this != OFF
}

sealed interface DecoderPlan {
    /** @param outputSurface 是否启用 `-hwaccel_output_format mediacodec`（零拷贝，要求后面没有 CPU 滤镜） */
    data class MediaCodec(
        val codecName: String,
        val mime: String,
        val outputSurface: Boolean,
    ) : DecoderPlan

    data object Software : DecoderPlan
    data object StreamCopy : DecoderPlan
    data object None : DecoderPlan

    val label: String
        get() = when (this) {
            is MediaCodec -> if (outputSurface) {
                "MediaCodec 硬解（Surface 零拷贝）"
            } else {
                "MediaCodec 硬解"
            }
            Software -> "软件解码"
            StreamCopy -> "不重新编码（直通）"
            None -> "无视频流"
        }
}

sealed interface EncoderPlan {
    data class MediaCodec(val ffmpegName: String, val codecName: String) : EncoderPlan
    data class Software(val ffmpegName: String) : EncoderPlan
    data object StreamCopy : EncoderPlan
    data object None : EncoderPlan

    /** 既没有硬件编码器也没有可用的软件编码器 */
    data object Unavailable : EncoderPlan

    val label: String
        get() = when (this) {
            is MediaCodec -> "MediaCodec 硬编"
            is Software -> "软件编码（$ffmpegName）"
            StreamCopy -> "不重新编码（直通）"
            None -> "无视频流"
            Unavailable -> "无可用编码器"
        }
}

/**
 * 一次转码请求中与硬件加速决策相关的全部输入。
 */
data class TranscodeRequest(
    val strategy: HwStrategy,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val sourceFps: Double,
    /** 源视频 MIME（如 video/avc）。为空表示未知，此时不会启用硬解 */
    val sourceMime: String?,
    val targetCodec: VideoCodec,
    val targetWidth: Int,
    val targetHeight: Int,
    val targetFps: Double,
    val targetBitrate: Int,
    /** 命令行里是否存在 CPU 端视频滤镜（scale / crop / overlay / subtitles / fps ...） */
    val hasCpuVideoFilters: Boolean,
    /** 是否请求把缩放/格式转换交给 Vulkan 滤镜（仅纯缩放场景生效） */
    val preferVulkanFilters: Boolean = false,
    /** 输出是否只需要纯缩放（Vulkan 路径的前提） */
    val isPureScale: Boolean = false,
)

data class HardwarePlan(
    val strategy: HwStrategy,
    val decoder: DecoderPlan,
    val encoder: EncoderPlan,
    /** 需要插在 `-i` 之前的参数 */
    val inputArgs: List<String>,
    /** 需要插在输出侧的参数（不含 -c:v 本身） */
    val outputArgs: List<String>,
    /** 面向用户的可读说明 */
    val reasons: List<String>,
    val warnings: List<String>,
) {
    val isZeroCopy: Boolean
        get() = decoder is DecoderPlan.MediaCodec && decoder.outputSurface

    val badge: String
        get() = when {
            isZeroCopy -> "零拷贝硬解硬编"
            decoder is DecoderPlan.MediaCodec && encoder is EncoderPlan.MediaCodec -> "硬解 + 硬编"
            decoder is DecoderPlan.MediaCodec -> "硬解 + 软编"
            encoder is EncoderPlan.MediaCodec -> "软解 + 硬编"
            else -> "纯软件"
        }
}

/**
 * 把「设备能力 + 素材参数 + 用户策略」翻译成一组 FFmpeg 命令行参数。
 *
 * 三条硬规则（踩过坑的都懂）：
 *  1. `-hwaccel_output_format mediacodec` 会让解码器直接输出 MediaCodec 纹理帧，
 *     这种帧**只能**喂给 MediaCodec 编码器或 hwdownload。所以只有在「后面没有任何
 *     CPU 滤镜」且「编码器也是 MediaCodec」时才能开零拷贝，否则必然报
 *     "Impossible to convert between the formats"。
 *  2. 只写 `-hwaccel mediacodec`（不带 output_format）时，FFmpeg 会自动把帧下载到
 *     系统内存，此时 CPU 滤镜可以正常工作 —— 这是最通用也最安全的硬解姿势。
 *  3. 硬件编码器对分辨率和帧率有硬上限（来自 VideoCapabilities）。超出范围时
 *     要么降档，要么回落到软编，绝不能硬上。
 */
/**
 * Android 侧的薄封装：只负责取「运行时枚举结果」，规划逻辑全在 [HardwarePlanCalculator]。
 *
 * 拆成两层是为了让规划逻辑能脱离 Android 做单元测试 —— 这是本项目最容易出错、
 * 也最值得测试的一段代码（三条硬规则全在这里）。
 */
class HardwarePlanner @javax.inject.Inject constructor(
    private val scanner: MediaCodecScanner,
) {
    fun plan(request: TranscodeRequest): HardwarePlan =
        HardwarePlanCalculator.plan(scanner.report(), request)
}

/**
 * 纯逻辑的硬件加速规划器。不依赖任何 Android API：
 * 输入「设备能力报告 + 转码请求」，输出可直接拼进命令行的方案。
 */
object HardwarePlanCalculator {

    fun plan(report: DeviceCodecReport, request: TranscodeRequest): HardwarePlan {
        val reasons = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (!request.strategy.usesHardware) {
            reasons += "当前策略为「${request.strategy.label}」，全部使用软件编解码。"
            return HardwarePlan(
                strategy = request.strategy,
                decoder = DecoderPlan.Software,
                encoder = softwareEncoder(request.targetCodec, warnings),
                inputArgs = emptyList(),
                outputArgs = emptyList(),
                reasons = reasons,
                warnings = warnings,
            )
        }

        // ------------------------------------------------------------ 编码器选择 ----
        // 「质量优先」要主动放弃硬件编码器（硬编的画质与体积控制不如 libx264），
        // 所以这里先把 hwEncoder 置空，而不是先查再决定 —— 否则会误报「找不到硬件编码器」。
        val qualityFirst = request.strategy == HwStrategy.QUALITY
        val hwEncoder = if (qualityFirst) {
            null
        } else {
            report.pickHardwareEncoder(
                codec = request.targetCodec,
                width = request.targetWidth,
                height = request.targetHeight,
                fps = request.targetFps,
                bitrate = request.targetBitrate,
            )
        }

        val encoder: EncoderPlan = when {
            qualityFirst && request.targetCodec.hasSoftwareEncoder -> {
                reasons += "「质量优先」策略：主动跳过硬件编码器，改用 ${request.targetCodec.swName}。"
                EncoderPlan.Software(request.targetCodec.swName!!)
            }

            qualityFirst -> {
                val fallback = report.pickHardwareEncoder(
                    codec = request.targetCodec,
                    width = request.targetWidth,
                    height = request.targetHeight,
                    fps = request.targetFps,
                    bitrate = request.targetBitrate,
                )
                warnings += "${request.targetCodec.shortLabel} 没有可用的软件编码器，" +
                    "「质量优先」无法生效，仍使用硬件编码。"
                if (fallback != null) {
                    EncoderPlan.MediaCodec(request.targetCodec.mcName, fallback.codecName)
                } else {
                    EncoderPlan.Unavailable
                }
            }

            hwEncoder == null && request.targetCodec.hasSoftwareEncoder -> {
                warnings += "未找到能处理 ${request.targetWidth}×${request.targetHeight}" +
                    "@${formatFps(request.targetFps)} 的 ${request.targetCodec.shortLabel} 硬件编码器，回落到软件编码。"
                softwareEncoder(request.targetCodec, warnings)
            }

            hwEncoder == null -> {
                warnings += "${request.targetCodec.shortLabel} 既没有可用的硬件编码器，" +
                    "当前 FFmpeg 构建也没有包含对应的软件编码器。请改用 H.264，或重新编译 FFmpeg 时启用相应 --enable-lib*。"
                EncoderPlan.Unavailable
            }

            else -> {
                reasons += "编码器选用 ${hwEncoder.codecName}（上限 ${hwEncoder.maxResolutionLabel}）。"
                EncoderPlan.MediaCodec(
                    ffmpegName = request.targetCodec.mcName,
                    codecName = hwEncoder.codecName,
                )
            }
        }

        // ------------------------------------------------------------ 解码器选择 ----
        val sourceMime = request.sourceMime
        val hwDecoder = sourceMime?.let {
            report.pickHardwareDecoder(it, request.sourceWidth, request.sourceHeight)
        }

        // 零拷贝的前提：解码是硬解、编码也是硬解、且中间没有任何 CPU 滤镜
        val canZeroCopy = hwDecoder != null &&
            encoder is EncoderPlan.MediaCodec &&
            !request.hasCpuVideoFilters

        val decoder: DecoderPlan = when {
            hwDecoder == null && sourceMime != null -> {
                reasons += "源格式 $sourceMime 没有匹配的硬件解码器，使用软件解码。"
                DecoderPlan.Software
            }
            hwDecoder == null -> DecoderPlan.Software
            else -> {
                if (canZeroCopy) {
                    reasons += "解码/编码同为 MediaCodec 且无 CPU 滤镜，启用零拷贝通路（帧不出显存）。"
                } else {
                    val why = when {
                        request.hasCpuVideoFilters -> "存在 CPU 端视频滤镜，帧需要下载到内存"
                        else -> "编码器不是 MediaCodec"
                    }
                    reasons += "使用 MediaCodec 硬解（$why，已自动关闭 Surface 输出）。"
                }
                DecoderPlan.MediaCodec(
                    codecName = hwDecoder.codecName,
                    mime = sourceMime.orEmpty(),
                    outputSurface = canZeroCopy,
                )
            }
        }

        // ------------------------------------------------------------ 参数拼装 ----
        val inputArgs = mutableListOf<String>()
        (decoder as? DecoderPlan.MediaCodec)?.let {
            inputArgs += listOf("-hwaccel", "mediacodec")
            if (it.outputSurface) {
                inputArgs += listOf("-hwaccel_output_format", "mediacodec")
            }
        }

        val outputArgs = mutableListOf<String>()
        if (encoder is EncoderPlan.MediaCodec) {
            outputArgs += buildHardwareEncoderArgs(request, warnings)
        }

        // Vulkan 滤镜链（仅纯缩放场景）
        if (request.preferVulkanFilters && report.soc.suggestVulkanFilters) {
            // isPureScale 本身已蕴含「存在 -vf 且滤镜全是缩放类」，
            // 不能再叠加 !hasCpuVideoFilters —— 只要出现 -vf 它必然为真，
            // 叠加后条件恒为 false，这里就永远走 else，Vulkan 通路永远开不起来。
            if (request.isPureScale) {
                reasons += "已启用 Vulkan 滤镜链处理缩放（需要 FFmpeg 编译时带 --enable-vulkan）。"
            } else {
                warnings += "当前处理链包含非缩放滤镜，Vulkan 通路无法整体接管，已回退到 CPU 滤镜。"
            }
        }

        return HardwarePlan(
            strategy = request.strategy,
            decoder = decoder,
            encoder = encoder,
            inputArgs = inputArgs,
            outputArgs = outputArgs,
            reasons = reasons,
            warnings = warnings,
        )
    }

    // ------------------------------------------------------------------ 内部实现 ----

    private fun softwareEncoder(
        codec: VideoCodec,
        warnings: MutableList<String>,
    ): EncoderPlan {
        val sw = codec.swName
        return if (sw != null) {
            EncoderPlan.Software(sw)
        } else {
            warnings += "${codec.shortLabel} 在当前 FFmpeg 构建中没有软件编码器。"
            EncoderPlan.Unavailable
        }
    }

    /**
     * MediaCodec 硬件编码器的输出侧参数。
     *
     *  - `-g`：GOP 长度。硬编对关键帧间隔比较敏感，取 2 秒是比较稳妥的值；
     *          GOP 太长会让拖动定位变卡，太短会浪费码率。
     *  - `-b:v`：目标码率。
     *  - `-maxrate` / `-bufsize`：给硬编一个软上限，避免瞬时码率冲顶导致掉帧。
     *  - `-bf 0`：MediaCodec 编码器普遍不支持 B 帧，显式关掉避免协商失败。
     */
    private fun buildHardwareEncoderArgs(
        request: TranscodeRequest,
        warnings: MutableList<String>,
    ): List<String> {
        val args = mutableListOf<String>()

        val gop = if (request.targetFps > 0) {
            (request.targetFps * 2).toInt().coerceIn(12, 300)
        } else {
            48
        }
        args += listOf("-g", gop.toString())

        if (request.targetBitrate > 0) {
            args += listOf("-b:v", request.targetBitrate.toString())
            // 硬编在 ABR 下容易冲顶，给一个 1.5x 的软上限 + 1 秒缓冲
            args += listOf("-maxrate", (request.targetBitrate * 3 / 2).toString())
            args += listOf("-bufsize", (request.targetBitrate * 2).toString())
        } else {
            warnings += "未指定目标码率，硬件编码器将使用默认码率，输出体积可能不符合预期。"
        }

        args += listOf("-bf", "0")

        return args
    }

    private fun formatFps(fps: Double): String =
        if (fps <= 0) "自动" else String.format("%.2f", fps)
}
