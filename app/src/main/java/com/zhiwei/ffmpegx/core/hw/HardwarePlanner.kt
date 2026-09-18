package com.zhiwei.ffmpegx.core.hw

/**
 * 硬件加速策略。对应设置页里的下拉项。
 */
enum class HwStrategy(
    val label: String,
    val description: String,
) {
    AUTO("智能", "按素材与设备能力自动选择最优方案"),
    SPEED("速度优先", "优先使用硬件编解码，出片最快"),
    QUALITY("质量优先", "硬件解码 + 软件编码，画质与体积更可控"),
    COMPAT("兼容优先", "全部使用软件编解码，兼容性最好但速度最慢"),
    OFF("关闭", "不使用硬件加速"),
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

    /** 面向用户的说法：不出现 MediaCodec / Surface 这类内部术语 */
    val label: String
        get() = when (this) {
            is MediaCodec -> if (outputSurface) "硬件解码（直通）" else "硬件解码"
            Software -> "软件解码"
            StreamCopy -> "不重新编码"
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

    /** 面向用户的说法：不出现 MediaCodec / 编码器名这类内部术语 */
    val label: String
        get() = when (this) {
            is MediaCodec -> "硬件编码"
            is Software -> "软件编码"
            StreamCopy -> "不重新编码"
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
            isZeroCopy -> "硬解 + 硬编（直通）"
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
        HardwarePlanCalculator.plan(scanner.report(), request, FfmpegEncoders.snapshot())
}

/**
 * 纯逻辑的硬件加速规划器。不依赖任何 Android API：
 * 输入「设备能力报告 + 转码请求」，输出可直接拼进命令行的方案。
 */
object HardwarePlanCalculator {

    /**
     * @param ffmpegEncoders 当前 FFmpeg 构建**实际包含**的编码器集合（见 [FfmpegEncoders]）。
     *        空集表示「未知」，此时不做可用性过滤，保持原有行为。
     */
    fun plan(
        report: DeviceCodecReport,
        request: TranscodeRequest,
        ffmpegEncoders: Set<String> = emptySet(),
    ): HardwarePlan {
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

        // 设备支持 ≠ FFmpeg 编了这个编码器。
        // MediaCodecScanner 读的是 Android 的 MediaCodecList，只能证明**芯片**能做；
        // 而 h264_mediacodec 这类编码器是否存在，取决于 AAR 构建时有没有
        // --enable-lib-android-media-codec（ffmpeg-kit-next 里默认关闭）。
        // 两者不一致时生成的 `-c:v h264_mediacodec` 会在启动瞬间报
        // "Encoder not found"，所以必须先按 FFmpeg 的实际能力过一遍。
        val hwEncoderAvailable = ffmpegEncoders.isEmpty() ||
            ffmpegEncoders.contains(request.targetCodec.mcName)
        if (!hwEncoderAvailable && !qualityFirst) {
            // 说「当前版本不含」而不是「硬件不支持」：设备可能是支持的，
            // 缺的是构建时的开关。这样既不暴露 h264_mediacodec 这类内部名，
            // 又保住了原来的诊断价值（能看出是包的问题而不是设备的问题）。
            warnings += "当前版本不含该格式的硬件编码，已改用软件编码。"
        }

        val hwEncoder = if (qualityFirst || !hwEncoderAvailable) {
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
                reasons += "「质量优先」：已改用软件编码，画质与体积更可控。"
                EncoderPlan.Software(request.targetCodec.swName!!)
            }

            qualityFirst -> {
                // 软编不可用时才退回硬编，且同样要确认 FFmpeg 里真有这个编码器
                val fallback = if (hwEncoderAvailable) {
                    report.pickHardwareEncoder(
                        codec = request.targetCodec,
                        width = request.targetWidth,
                        height = request.targetHeight,
                        fps = request.targetFps,
                        bitrate = request.targetBitrate,
                    )
                } else {
                    null
                }
                warnings += "该格式没有可用的软件编码器，「质量优先」无法生效，仍使用硬件编码。"
                if (fallback != null) {
                    EncoderPlan.MediaCodec(request.targetCodec.mcName, fallback.codecName)
                } else {
                    EncoderPlan.Unavailable
                }
            }

            hwEncoder == null && request.targetCodec.hasSoftwareEncoder -> {
                warnings += "该分辨率与帧率超出硬件编码器能力范围，已回落到软件编码。"
                softwareEncoder(request.targetCodec, warnings)
            }

            hwEncoder == null -> {
                warnings += "${request.targetCodec.shortLabel} 没有可用的编码器，请改用 H.264。"
                EncoderPlan.Unavailable
            }

            else -> {
                reasons += "已启用硬件编码（最高支持 ${hwEncoder.maxResolutionLabel}）。"
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
                reasons += "源格式没有匹配的硬件解码器，已改用软件解码。"
                DecoderPlan.Software
            }
            hwDecoder == null -> DecoderPlan.Software
            else -> {
                if (canZeroCopy) {
                    reasons += "解码与编码均使用硬件，且无需逐帧处理，已启用直通模式。"
                } else {
                    val why = when {
                        request.hasCpuVideoFilters -> "需要逐帧处理画面"
                        else -> "编码未使用硬件"
                    }
                    reasons += "使用硬件解码（$why）。"
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
                reasons += "已启用 GPU 加速的画面缩放。"
            } else {
                warnings += "当前处理包含缩放以外的操作，GPU 无法接管，已回退到常规处理。"
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
            warnings += "该格式没有可用的软件编码器。"
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
            warnings += "未指定目标码率，输出体积可能不符合预期。"
        }

        args += listOf("-bf", "0")

        return args
    }
}
