package com.zhiwei.ffmpegx.core.cmd

import com.zhiwei.ffmpegx.core.hw.DecoderPlan
import com.zhiwei.ffmpegx.core.hw.EncoderPlan
import com.zhiwei.ffmpegx.core.hw.HardwarePlan
import com.zhiwei.ffmpegx.core.hw.AudioCodec
import com.zhiwei.ffmpegx.core.model.OutputContainer
import com.zhiwei.ffmpegx.core.settings.AppSettings

/**
 * 把「功能页的表单」翻译成「FFmpeg 的 argv」。
 *
 * 所有功能页共用 [render]，只有 GIF（两遍）和拼接（filter_complex / concat demuxer）
 * 这类结构特殊的才单独实现。
 */
object Commands {

    // =========================================================== 公共基础参数 ====

    fun base(settings: AppSettings, overwrite: Boolean? = null): FfmpegCommand =
        FfmpegCommand.create().apply {
            // -nostdin：App 环境没有 tty，不关掉的话 FFmpeg 可能阻塞在读取标准输入上
            raw("-hide_banner", "-nostdin")
            raw(if (overwrite ?: settings.overwriteOutput) "-y" else "-n")
            raw("-loglevel", settings.logLevelArg)
            if (settings.threadCount > 0) {
                raw("-threads", settings.threadCount.toString())
            }
        }

    /**
     * 计算最终会出现在命令行里的 CPU 端视频滤镜。
     *
     * 这个方法必须被「硬件加速规划」和「命令渲染」两边共用：
     * 规划时要知道有没有滤镜（有滤镜就不能开 Surface 零拷贝），
     * 渲染时要把这些滤镜写进 -vf。两边不一致就会出现
     * "Impossible to convert between the formats" 这类难查的错误。
     */
    fun plannedFilters(output: OutputSpec): List<String> {
        // 没有视频输出就不能生成任何视频滤镜：
        // 同时出现 -vf 与 -vn 时 FFmpeg 会直接报
        // "Filtergraph was specified through the -vf option, but no output video stream"。
        // 这个判断放在这里而不是调用点，是为了让规划侧和渲染侧共用同一个事实来源。
        if (output.video == null) return emptyList()

        return buildList {
            if (output.width > 0 && output.height > 0) {
                add("scale=${output.width}:${output.height}:flags=lanczos")
                // 缩放后重采样宽高比标记，避免播放器显示变形
                add("setsar=1")
            }
            if (output.fps > 0) {
                add("fps=${formatDouble(output.fps)}")
            }
            addAll(output.filters.filter { it.isNotBlank() })
        }
    }

    // =============================================================== 通用渲染 ====

    fun render(
        settings: AppSettings,
        plan: HardwarePlan,
        inputs: List<InputSpec>,
        output: OutputSpec,
    ): List<String> {
        require(inputs.isNotEmpty()) { "至少需要一个输入" }

        val cmd = base(settings)

        inputs.forEachIndexed { index, spec ->
            val pre = if (index == 0) plan.inputArgs + spec.preArgs else spec.preArgs
            cmd.input(spec.path, pre)
        }

        // ---- 视频滤镜（含零拷贝的兜底处理） ----
        val filters = plannedFilters(output)
        if (plan.isZeroCopy && filters.isNotEmpty()) {
            // 走到这里说明规划阶段的 hasCpuVideoFilters 没传对。
            // 不抛异常，改成显式把帧下载到内存，宁可慢一点也不生成一条必然报错的命令。
            cmd.filterGraph(listOf("hwdownload", "format=nv12") + filters + listOf("format=nv12", "hwupload"))
        } else {
            cmd.filterGraph(filters)
        }
        // 丢弃音频流时同理，-af 会让 FFmpeg 报 "no output audio stream"
        cmd.audioFilterGraph(if (output.audio == null) emptyList() else output.audioFilters)

        // ---- 流映射 ----
        if (output.video != null) cmd.map("0:v:0?") else cmd.flag("-vn")
        if (output.audio != null) cmd.map("0:a:0?") else cmd.flag("-an")
        if (!output.copySubtitles) cmd.flag("-sn")

        // ---- 视频编码 ----
        output.video?.let { video -> applyVideoEncoding(cmd, plan, video, output.fps) }

        // ---- 音频编码 ----
        output.audio?.let { audio -> applyAudioEncoding(cmd, audio) }

        // ---- 封装 ----
        applyContainer(cmd, output)

        cmd.output(output.path)
        return cmd.build()
    }

    // ================================================================ 剪辑截取 ====

    fun trim(
        settings: AppSettings,
        plan: HardwarePlan,
        inputPath: String,
        spec: TrimSpec,
    ): List<String> {
        val durationUs = (spec.endUs - spec.startUs).coerceAtLeast(0L)
        val pre = buildList {
            // -ss 放在 -i 之前 = 快速定位（利用关键帧索引），比解码到指定位置快得多
            if (spec.startUs > 0) {
                add("-ss")
                add(formatTime(spec.startUs))
            }
            if (durationUs > 0) {
                add("-t")
                add(formatTime(durationUs))
            }
        }

        if (spec.streamCopy) {
            val cmd = base(settings)
            cmd.input(inputPath, pre)
            cmd.raw("-c", "copy")
            cmd.raw("-map", "0")
            if (spec.resetTimestamps) {
                cmd.raw("-avoid_negative_ts", "make_zero")
            }
            cmd.raw("-f", spec.container.muxer)
            if (spec.container == OutputContainer.MP4 || spec.container == OutputContainer.MOV) {
                cmd.raw("-movflags", "+faststart")
            }
            cmd.output(spec.output)
            return cmd.build()
        }

        val output = OutputSpec(
            path = spec.output,
            container = spec.container,
            video = spec.video,
            audio = spec.audio,
            copySubtitles = true,
        )
        return render(settings, plan, listOf(InputSpec(inputPath, pre)), output)
    }

    // ================================================================ 视频压缩 ====

    /**
     * 压缩。两遍编码时返回两条命令，调用方需按顺序执行。
     * 注意：两遍编码只对软件编码器有意义，硬件编码器不支持 `-pass`。
     */
    fun compress(
        settings: AppSettings,
        plan: HardwarePlan,
        inputPath: String,
        spec: CompressSpec,
        passLogPrefix: String = "",
    ): List<List<String>> {
        val video = VideoEncodeSpec(
            codec = spec.videoCodec,
            mode = RateMode.BITRATE,
            bitrate = spec.videoBitrate,
            speedPreset = "veryfast",
        )
        val audio = AudioEncodeSpec(
            codec = spec.audioCodec,
            bitrate = spec.audioBitrate,
        )
        val output = OutputSpec(
            path = spec.output,
            container = spec.container,
            video = video,
            audio = audio,
            width = spec.targetWidth,
            height = spec.targetHeight,
            fps = spec.targetFps,
        )

        val hardwareEncoder = plan.encoder is EncoderPlan.MediaCodec
        val useTwoPass = spec.twoPass && !hardwareEncoder && spec.videoBitrate > 0

        if (!useTwoPass) {
            return listOf(render(settings, plan, listOf(InputSpec(inputPath)), output))
        }

        val passLog = passLogPrefix.ifBlank { spec.output + ".ffpass" }
        val first = base(settings).apply {
            raw("-i", inputPath)
            cmd_filterScale(this, spec)
            flag("-an")
            raw("-c:v", spec.videoCodec.swName ?: "libx264")
            raw("-b:v", spec.videoBitrate.toString())
            raw("-pass", "1")
            raw("-passlogfile", passLog)
            raw("-f", "null")
            raw("/dev/null")
        }.build()

        val second = base(settings).apply {
            raw("-i", inputPath)
            cmd_filterScale(this, spec)
            raw("-c:v", spec.videoCodec.swName ?: "libx264")
            raw("-b:v", spec.videoBitrate.toString())
            raw("-pass", "2")
            raw("-passlogfile", passLog)
            raw("-c:a", spec.audioCodec.ffmpegName)
            if (!spec.audioCodec.isLossless) raw("-b:a", spec.audioBitrate.toString())
            raw("-map", "0:v:0?")
            raw("-map", "0:a:0?")
            raw("-movflags", "+faststart")
            raw("-f", spec.container.muxer)
            raw(spec.output)
        }.build()

        return listOf(first, second)
    }

    private fun cmd_filterScale(cmd: FfmpegCommand, spec: CompressSpec) {
        val filters = buildList {
            if (spec.targetWidth > 0 && spec.targetHeight > 0) {
                add("scale=${spec.targetWidth}:${spec.targetHeight}:flags=lanczos")
                add("setsar=1")
            }
            if (spec.targetFps > 0) add("fps=${formatDouble(spec.targetFps)}")
        }
        cmd.filterGraph(filters)
    }

    // ================================================================== GIF ====

    /**
     * GIF 制作。返回 1 条或 2 条命令：
     *  - twoPass=true：先 palettegen 生成调色板，再用 paletteuse 合成（画质最好）
     *  - twoPass=false：单条命令内用 split + palettegen + paletteuse 完成
     */
    fun gif(settings: AppSettings, inputPath: String, spec: GifSpec): List<List<String>> {
        val pre = buildList {
            if (spec.startUs > 0) {
                add("-ss"); add(formatTime(spec.startUs))
            }
            if (spec.durationUs > 0) {
                add("-t"); add(formatTime(spec.durationUs))
            }
        }
        val chain = buildList {
            add("fps=${spec.fps}")
            if (spec.width > 0) add("scale=${spec.width}:-1:flags=lanczos")
            add("setsar=1")
        }.joinToString(",")

        if (!spec.twoPass) {
            val single = base(settings).apply {
                input(inputPath, pre)
                raw(
                    "-vf",
                    "$chain,split[a][b];[a]palettegen=stats_mode=${spec.paletteStatsMode}[p];" +
                        "[b][p]paletteuse=dither=${spec.dither}" +
                        if (spec.dither == "bayer") ":bayer_scale=${spec.bayerScale}" else "",
                )
                raw("-loop", spec.loopCount.toString())
                raw("-f", "gif")
                output(spec.output)
            }.build()
            return listOf(single)
        }

        val palettePath = spec.palettePath.ifBlank { spec.output + ".palette.png" }

        val pass1 = base(settings).apply {
            input(inputPath, pre)
            raw("-vf", "$chain,palettegen=stats_mode=${spec.paletteStatsMode}")
            raw("-f", "image2")
            output(palettePath)
        }.build()

        val pass2 = base(settings).apply {
            input(inputPath, pre)
            input(palettePath)
            raw(
                "-lavfi",
                "$chain[x];[x][1:v]paletteuse=dither=${spec.dither}" +
                    if (spec.dither == "bayer") ":bayer_scale=${spec.bayerScale}:diff_mode=rectangle" else "",
            )
            raw("-loop", spec.loopCount.toString())
            raw("-f", "gif")
            output(spec.output)
        }.build()

        return listOf(pass1, pass2)
    }

    // ================================================================== 拼接 ====

    fun concat(
        settings: AppSettings,
        plan: HardwarePlan,
        inputs: List<String>,
        spec: ConcatSpec,
    ): List<String> {
        require(inputs.size >= 2) { "拼接至少需要两个文件" }

        if (spec.useDemuxer) {
            require(spec.listFilePath.isNotBlank()) { "concat demuxer 模式需要列表文件" }
            return base(settings).apply {
                // -safe 0：允许列表里出现绝对路径
                raw("-f", "concat", "-safe", "0")
                input(spec.listFilePath)
                raw("-c", "copy")
                raw("-f", spec.container.muxer)
                if (spec.container == OutputContainer.MP4 || spec.container == OutputContainer.MOV) {
                    raw("-movflags", "+faststart")
                }
                output(spec.output)
            }.build()
        }

        val n = inputs.size
        val filterParts = mutableListOf<String>()

        // 每个片段先统一到相同尺寸/帧率，否则 concat 滤镜会因为参数不一致而失败
        val normalize = buildList {
            if (spec.width > 0 && spec.height > 0) {
                add("scale=${spec.width}:${spec.height}:flags=lanczos")
                add("setsar=1")
            } else {
                add("setsar=1")
            }
            if (spec.fps > 0) add("fps=${formatDouble(spec.fps)}")
        }.joinToString(",")

        for (i in 0 until n) {
            filterParts += "[$i:v:0]$normalize[v$i]"
        }
        val concatInputs = buildString {
            for (i in 0 until n) {
                append("[v").append(i).append("]")
                append("[$i:a:0]")
            }
        }
        filterParts += "${concatInputs}concat=n=$n:v=1:a=1[v][a]"

        val cmd = base(settings)
        inputs.forEach { cmd.input(it) }
        cmd.raw("-filter_complex", filterParts.joinToString(";"))
        cmd.map("[v]").map("[a]")

        spec.video?.let { applyVideoEncoding(cmd, plan, it, spec.fps) }
        spec.audio?.let { applyAudioEncoding(cmd, it) }

        cmd.raw("-f", spec.container.muxer)
        if (spec.container == OutputContainer.MP4 || spec.container == OutputContainer.MOV) {
            cmd.raw("-movflags", "+faststart")
        }
        cmd.output(spec.output)
        return cmd.build()
    }

    // ================================================================== 字幕 ====

    fun subtitle(
        settings: AppSettings,
        plan: HardwarePlan,
        inputPath: String,
        spec: SubtitleSpec,
    ): List<String> = when (spec.mode) {
        SubtitleMode.BURN -> {
            val subFilter = buildString {
                append("subtitles=")
                append(escapeFilterPath(spec.subtitlePath))
                if (spec.forceStyle.isNotBlank()) {
                    append(":force_style='").append(spec.forceStyle).append("'")
                }
            }
            val output = OutputSpec(
                path = spec.output,
                container = spec.container,
                video = spec.video,
                audio = spec.audio,
                filters = listOf(subFilter),
            )
            render(settings, plan, listOf(InputSpec(inputPath)), output)
        }

        SubtitleMode.EXTRACT -> base(settings).apply {
            input(inputPath)
            raw("-map", "0:s:${spec.subtitleStreamIndex}")
            raw("-c:s", "srt")
            raw("-f", "srt")
            output(spec.output)
        }.build()

        SubtitleMode.MUX -> base(settings).apply {
            input(inputPath)
            input(spec.subtitlePath)
            raw("-map", "0:v:0?")
            raw("-map", "0:a:0?")
            raw("-map", "1:0")
            raw("-c:v", "copy")
            raw("-c:a", "copy")
            raw("-c:s", if (spec.container == OutputContainer.MKV) "srt" else "mov_text")
            raw("-f", spec.container.muxer)
            raw("-movflags", "+faststart")
            output(spec.output)
        }.build()
    }

    // ============================================================== 水印 / 分屏 ====

    fun overlay(
        settings: AppSettings,
        plan: HardwarePlan,
        mainPath: String,
        spec: OverlaySpec,
    ): List<String> {
        val cmd = base(settings)
        val filterComplex: String
        val maps: List<String>

        when (spec.mode) {
            OverlayMode.WATERMARK -> {
                val opacity = spec.opacity.coerceIn(0.0, 1.0)
                val chain = buildString {
                    append("[1:v]format=rgba")
                    if (opacity < 1.0) {
                        append(",colorchannelmixer=aa=").append(formatDouble(opacity))
                    }
                    append(",scale=iw*").append(formatDouble(spec.overlayScale)).append(":-1[wm];")
                    append("[0:v][wm]overlay=").append(spec.position.x).append(":").append(spec.position.y)
                    if (spec.showTo > spec.showFrom && spec.showTo > 0) {
                        append(":enable='between(t,")
                            .append(formatDouble(spec.showFrom)).append(",")
                            .append(formatDouble(spec.showTo)).append(")'")
                    }
                    append("[v]")
                }
                filterComplex = chain
                maps = listOf("[v]", "0:a:0?")
            }

            OverlayMode.PICTURE_IN_PICTURE -> {
                filterComplex = buildString {
                    append("[1:v]scale=iw*").append(formatDouble(spec.overlayScale)).append(":-2")
                        .append(",setsar=1[wm];")
                    append("[0:v][wm]overlay=").append(spec.position.x).append(":").append(spec.position.y)
                    if (spec.showTo > spec.showFrom && spec.showTo > 0) {
                        append(":enable='between(t,")
                            .append(formatDouble(spec.showFrom)).append(",")
                            .append(formatDouble(spec.showTo)).append(")'")
                    }
                    append("[v]")
                }
                maps = listOf("[v]", "0:a:0?")
            }

            OverlayMode.SIDE_BY_SIDE, OverlayMode.TOP_BOTTOM -> {
                val edge = spec.stackEdge.coerceAtLeast(144)
                val stack = if (spec.mode == OverlayMode.SIDE_BY_SIDE) "hstack" else "vstack"
                filterComplex = buildString {
                    append("[0:v]scale=-2:").append(edge).append(",setsar=1[a];")
                    append("[1:v]scale=-2:").append(edge).append(",setsar=1[b];")
                    append("[a][b]").append(stack).append("=inputs=2[v]")
                }
                maps = listOf("[v]", "0:a:0?")
            }
        }

        cmd.input(mainPath)
        cmd.input(spec.overlayPath)
        cmd.raw("-filter_complex", filterComplex)
        maps.forEach { cmd.map(it) }

        spec.video?.let { applyVideoEncoding(cmd, plan, it, 0.0) }
        spec.audio?.let { applyAudioEncoding(cmd, it) }

        cmd.raw("-f", spec.container.muxer)
        if (spec.container == OutputContainer.MP4 || spec.container == OutputContainer.MOV) {
            cmd.raw("-movflags", "+faststart")
        }
        cmd.output(spec.output)
        return cmd.build()
    }

    // ================================================================== 音频 ====

    fun audio(
        settings: AppSettings,
        plan: HardwarePlan,
        inputPath: String,
        spec: AudioSpec,
    ): List<String> {
        val audioFilters = buildList {
            if (spec.tempo != 1.0 && spec.tempo > 0) {
                // atempo 单次只支持 0.5~2.0，超出范围需要串联
                add(buildAtempoChain(spec.tempo))
            }
            if (spec.volume != 1.0) {
                add("volume=${formatDouble(spec.volume)}")
            }
            spec.loudnessTargetLufs?.let {
                add("loudnorm=I=${formatDouble(it)}:TP=-1.5:LRA=11")
            }
            if (spec.fadeInSeconds > 0) {
                add("afade=t=in:st=0:d=${formatDouble(spec.fadeInSeconds)}")
            }
            if (spec.fadeOutSeconds > 0) {
                add("areverse,afade=t=in:st=0:d=${formatDouble(spec.fadeOutSeconds)},areverse")
            }
        }

        // 音频滤镜与 -c:a copy 互斥：有滤镜时必须真的解码重编
        val effectiveCodec = if (audioFilters.isNotEmpty() && spec.codec == AudioCodec.COPY) {
            AudioCodec.AAC
        } else {
            spec.codec
        }

        val output = OutputSpec(
            path = spec.output,
            container = spec.container,
            video = if (spec.keepVideo) spec.video else null,
            audio = AudioEncodeSpec(
                codec = effectiveCodec,
                bitrate = spec.bitrate,
                sampleRate = spec.sampleRate,
                channels = spec.channels,
            ),
            audioFilters = audioFilters,
        )
        return render(settings, plan, listOf(InputSpec(inputPath)), output)
    }

    /** atempo 串联：3.0 -> atempo=2.0,atempo=1.5 */
    private fun buildAtempoChain(tempo: Double): String {
        var remaining = tempo
        val parts = mutableListOf<String>()
        while (remaining > 2.0) {
            parts += "atempo=2.0"
            remaining /= 2.0
        }
        while (remaining < 0.5) {
            parts += "atempo=0.5"
            remaining /= 0.5
        }
        parts += "atempo=${formatDouble(remaining)}"
        return parts.joinToString(",")
    }

    // ================================================================== 缩略图 ====

    fun thumbnail(settings: AppSettings, inputPath: String, spec: ThumbnailSpec): List<String> =
        base(settings).apply {
            input(inputPath, listOf("-ss", formatTime(spec.atUs)))
            raw("-frames:v", "1")
            if (spec.width > 0) {
                filterGraph(listOf("scale=${spec.width}:-1:flags=lanczos"))
            }
            if (spec.format == "jpg") raw("-q:v", spec.quality.toString())
            raw("-f", if (spec.format == "jpg") "image2" else spec.format)
            output(spec.output)
        }.build()

    // ============================================================ 内部：编码参数 ====

    private fun applyVideoEncoding(
        cmd: FfmpegCommand,
        plan: HardwarePlan,
        video: VideoEncodeSpec,
        /** 输出帧率，仅用于推算 GOP；0 表示保持原始帧率 */
        fps: Double,
    ) {
        if (video.mode == RateMode.COPY) {
            cmd.raw("-c:v", "copy")
            return
        }

        cmd.applyVideoEncoder(
            plan = plan,
            copy = false,
            softwareFallbackName = video.codec.swName,
        )

        if (plan.encoder is EncoderPlan.MediaCodec) {
            // 硬件编码器的码率/GOP 已由 HardwarePlanner 放进 plan.outputArgs
            return
        }

        val encoderName = (plan.encoder as? EncoderPlan.Software)?.ffmpegName ?: video.codec.swName

        when (video.mode) {
            RateMode.CRF -> {
                if (supportsCrf(encoderName)) {
                    cmd.raw("-crf", video.crf.coerceIn(0, 51).toString())
                }
            }
            RateMode.BITRATE -> {
                if (video.bitrate > 0) {
                    cmd.raw("-b:v", video.bitrate.toString())
                    cmd.raw("-maxrate", (video.bitrate * 3 / 2).toString())
                    cmd.raw("-bufsize", (video.bitrate * 2).toString())
                }
            }
            RateMode.COPY -> Unit
        }

        if (supportsPreset(encoderName)) {
            cmd.raw("-preset", video.speedPreset)
        }
        video.profile?.let { cmd.raw("-profile:v", it) }
        video.level?.let { cmd.raw("-level:v", it) }
        video.pixelFormat?.let { cmd.raw("-pix_fmt", it) }

        if (fps > 0) {
            val gop = (fps * video.gopSeconds).toInt().coerceAtLeast(1)
            cmd.raw("-g", gop.toString())
        }
    }

    private fun applyAudioEncoding(cmd: FfmpegCommand, audio: AudioEncodeSpec) {
        if (audio.codec == AudioCodec.COPY) {
            cmd.raw("-c:a", "copy")
            return
        }
        cmd.raw("-c:a", audio.codec.ffmpegName)
        if (!audio.codec.isLossless && audio.bitrate > 0) {
            cmd.raw("-b:a", audio.bitrate.toString())
        }
        if (audio.sampleRate > 0) cmd.raw("-ar", audio.sampleRate.toString())
        if (audio.channels > 0) cmd.raw("-ac", audio.channels.toString())
    }

    private fun applyContainer(cmd: FfmpegCommand, output: OutputSpec) {
        if (!output.keepMetadata) {
            cmd.raw("-map_metadata", "-1")
        }
        if (output.copySubtitles) {
            cmd.raw("-c:s", if (output.container == OutputContainer.MKV) "srt" else "mov_text")
        }
        if (output.customOutputArgs.isNotEmpty()) cmd.raw(output.customOutputArgs)

        when (output.container) {
            OutputContainer.MP4, OutputContainer.MOV -> {
                if (output.fastStart) cmd.raw("-movflags", "+faststart")
            }
            OutputContainer.M4A -> {
                if (output.fastStart) cmd.raw("-movflags", "+faststart")
            }
            else -> Unit
        }

        // 显式指定封装器：临时文件路径的扩展名不一定能正确推断
        cmd.raw("-f", output.container.muxer)
    }

    private fun supportsCrf(encoderName: String?): Boolean =
        encoderName in setOf("libx264", "libx265", "libvpx", "libvpx-vp9", "libaom-av1", "libsvtav1")

    private fun supportsPreset(encoderName: String?): Boolean =
        encoderName in setOf("libx264", "libx265", "libvpx", "libvpx-vp9")

    // ================================================================== 格式化 ====

    /** 微秒 -> `HH:MM:SS.mmm`，FFmpeg 的 -ss / -t 都接受这种格式 */
    fun formatTime(us: Long): String {
        if (us <= 0) return "0"
        val totalMs = us / 1000
        val h = totalMs / 3_600_000
        val m = (totalMs % 3_600_000) / 60_000
        val s = (totalMs % 60_000) / 1000
        val ms = totalMs % 1000
        return String.format("%02d:%02d:%02d.%03d", h, m, s, ms)
    }

    fun formatDouble(value: Double): String {
        if (value == value.toLong().toDouble()) return value.toLong().toString()
        return String.format("%.4f", value).trimEnd('0').trimEnd('.')
    }

    /**
     * filtergraph 里的路径转义。
     * 冒号、反斜杠、单引号、方括号、逗号在 filter 语法里有特殊含义，必须转义，
     * 否则路径里带 `:` 的（比如 SAF 卷 ID）会直接把滤镜串切坏。
     */
    fun escapeFilterPath(path: String): String {
        val sb = StringBuilder(path.length + 8)
        for (ch in path) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                ':' -> sb.append("\\:")
                '\'' -> sb.append("\\'")
                ',' -> sb.append("\\,")
                '[' -> sb.append("\\[")
                ']' -> sb.append("\\]")
                ';' -> sb.append("\\;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
