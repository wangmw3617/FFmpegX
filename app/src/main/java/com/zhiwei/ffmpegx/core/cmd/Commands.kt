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
        // 显式 -map 会关闭 FFmpeg 的自动流选择。想让字幕跟着走就必须显式 map 一次，
        // 否则 -c:s 只是一句空话，字幕会被静默丢弃（且没有任何报错）。
        if (output.copySubtitles) cmd.map("0:s?") else cmd.flag("-sn")

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
            // 统一走 image2 封装器：**png 并不是一个 muxer 名**，`-f png` 会被
            // FFmpeg 直接拒绝（Requested output format 'png' is not known），
            // 而它的退出码仍然是 0 —— 只有产物不存在才看得出来，很难查。
            // 具体编解码器由输出文件扩展名推断，image2 对 jpg / png 都适用。
            raw("-f", "image2")
            output(spec.output)
        }.build()

    // ============================================================ 旋转 / 翻转 ====

    fun rotate(
        settings: AppSettings,
        plan: HardwarePlan,
        inputPath: String,
        spec: RotateSpec,
    ): List<String> {
        val cmd = base(settings)
        cmd.input(inputPath, plan.inputArgs)

        // transpose 只认 0/1/2/3（逆时针 90 / 顺时针 90 / 顺时针 90 的镜像 / 逆时针 90 的镜像），
        // 180° 用两次 90° 表达。
        val filters = buildList {
            when ((spec.degrees % 360 + 360) % 360) {
                90 -> add("transpose=1")
                180 -> add("transpose=2,transpose=2")
                270 -> add("transpose=2")
            }
            if (spec.flipHorizontal) add("hflip")
            if (spec.flipVertical) add("vflip")
        }
        cmd.filterGraph(filters)

        cmd.map("0:v:0?")
        // 角度为 0 且不翻转时 filters 为空，此时直通仍然合法；有滤镜才降级
        applyVideoEncoding(cmd, plan, if (filters.isEmpty()) spec.video else spec.video.reencodedIfFiltered(), 0.0)
        applyAudioTrack(cmd, spec.audio)
        cmd.output(spec.output)
        return cmd.build()
    }

    // ================================================================ 画面裁剪 ====

    fun crop(
        settings: AppSettings,
        plan: HardwarePlan,
        inputPath: String,
        spec: CropSpec,
    ): List<String> {
        val cmd = base(settings)
        cmd.input(inputPath, plan.inputArgs)

        // 宽高各取偶数：yuv420p 的色度是 2×2 采样，奇数尺寸会导致错位或直接报错。
        // 至少留 2px —— 尺寸为 0 时 crop 会直接失败。
        val w = ((spec.width / 2) * 2).coerceAtLeast(2)
        val h = ((spec.height / 2) * 2).coerceAtLeast(2)
        // 起点为负会直接被 crop 拒绝
        val x = spec.x.coerceAtLeast(0)
        val y = spec.y.coerceAtLeast(0)
        cmd.filterGraph(listOf("crop=$w:$h:$x:$y"))

        cmd.map("0:v:0?")
        // crop 是功能本体，滤镜去不掉，所以这里必须重编码
        applyVideoEncoding(cmd, plan, spec.video.reencodedIfFiltered(), 0.0)
        applyAudioTrack(cmd, spec.audio)
        cmd.output(spec.output)
        return cmd.build()
    }

    // ================================================================ 变速 ====

    fun speed(
        settings: AppSettings,
        plan: HardwarePlan,
        inputPath: String,
        spec: SpeedSpec,
    ): List<String> {
        val cmd = base(settings)
        cmd.input(inputPath, plan.inputArgs)

        val factor = spec.factor.coerceIn(0.1, 10.0)
        // 视频靠改时间戳实现：factor > 1（加速）时 PTS 变小，时长随之缩短
        cmd.filterGraph(listOf("setpts=${formatDouble(1.0 / factor)}*PTS"))

        cmd.map("0:v:0?")
        // setpts 是功能本体，去不掉 → 不能 -c:v copy
        applyVideoEncoding(cmd, plan, spec.video.reencodedIfFiltered(), 0.0)

        // 音频用 atempo 变速但保持音调；它单次只支持 0.5~2.0，
        // 超出范围由 buildAtempoChain 自动串联。
        // 注意 keepPitch 为 false 时这里仍然走 atempo —— 变调需要 asetrate，
        // 会连带改变采样率，暂不支持，所以参数只作展示用。
        applyAudioTrack(cmd, spec.audio, listOf(buildAtempoChain(factor)))

        cmd.output(spec.output)
        return cmd.build()
    }

    // ============================================================ 去水印 / 遮挡 ====

    fun delogo(
        settings: AppSettings,
        plan: HardwarePlan,
        inputPath: String,
        spec: DelogoSpec,
    ): List<String> {
        val cmd = base(settings)
        cmd.input(inputPath, plan.inputArgs)

        // 宽高各取偶数：yuv420p 的色度是 2×2 采样
        val w = ((spec.width / 2) * 2).coerceAtLeast(2)
        val h = ((spec.height / 2) * 2).coerceAtLeast(2)

        if (spec.mode == "delogo") {
            // delogo 用周边像素插值填补，区域必须完全落在画面内部，所以至少离边 1px
            val x = spec.x.coerceAtLeast(1)
            val y = spec.y.coerceAtLeast(1)
            cmd.filterGraph(listOf("delogo=x=$x:y=$y:w=$w:h=$h"))
            cmd.map("0:v:0?")
        } else {
            // 「模糊」与「马赛克」都是「把该区域抠出来处理，再盖回原位置」，
            // 有两路输入，-vf 表达不了，必须走 filter_complex。
            //
            // 三点都是踩过的坑：
            //   1. 输出**必须**打 [v] 标签 —— -map "[v]" 找不到标签会直接报
            //      「matches no streams」，整个模式不可用；
            //   2. 同一个 [0:v] 被两路消费，要显式 split，
            //      否则输入流被重复引用；
            //   3. 模糊与马赛克是两种不同处理，不能共用 boxblur ——
            //      早先两者都走 boxblur，于是「马赛克」实际是模糊，
            //      而「模糊」掉进了 delogo 分支，跟「智能填补」完全一样。
            val regionFilter = if (spec.mode == "mosaic") {
                // pixelize 才是块状像素化。块比区域还大时它会失败，按区域尺寸收一下
                val block = minOf(16, maxOf(2, minOf(w, h) / 2))
                "pixelize=w=$block:h=$block"
            } else {
                // 模糊：20 的半径足够糊掉文字，power=3 让边缘更柔和
                "boxblur=20:3"
            }
            // 起点为负会直接被 crop 拒绝
            val rx = spec.x.coerceAtLeast(0)
            val ry = spec.y.coerceAtLeast(0)
            cmd.raw(
                "-filter_complex",
                "[0:v]split=2[base][src];" +
                    "[src]crop=$w:$h:$rx:$ry,$regionFilter[m];" +
                    "[base][m]overlay=$rx:$ry[v]",
            )
            cmd.raw("-map", "[v]")
        }

        // 去遮挡的滤镜去不掉，所以这里必须重编码
        applyVideoEncoding(cmd, plan, spec.video.reencodedIfFiltered(), 0.0)
        applyAudioTrack(cmd, spec.audio)
        cmd.output(spec.output)
        return cmd.build()
    }

    // ============================================================ 图片转视频 ====

    fun slideshow(
        settings: AppSettings,
        plan: HardwarePlan,
        spec: SlideshowSpec,
    ): List<String> {
        require(spec.inputs.isNotEmpty()) { "至少需要一张图片" }

        val cmd = base(settings)
        val fps = spec.fps.coerceIn(1, 60)
        // 停留时长是自由输入，0 或负数会让 -t 直接失败（输出为空）
        val seconds = spec.secondsEach.coerceIn(0.1, 3600.0)

        // 每张图先用 -loop 1 生成一段固定时长的视频流，再用 concat 滤镜串联。
        // 必须先统一尺寸：concat 遇到分辨率不一致会直接报错。
        spec.inputs.forEach { path ->
            cmd.input(
                path,
                listOf(
                    "-loop", "1",
                    "-framerate", fps.toString(),
                    "-t", formatDouble(seconds),
                ),
            )
        }

        val scaled = spec.inputs.indices.joinToString("") { i ->
            "[$i:v]scale=1920:1080:force_original_aspect_ratio=decrease," +
                "pad=1920:1080:(ow-iw)/2:(oh-ih)/2,setsar=1[v$i];"
        }
        val joined = spec.inputs.indices.joinToString("") { "[v$it]" }
        cmd.raw(
            "-filter_complex",
            "$scaled$joined concat=n=${spec.inputs.size}:v=1:a=0[v]",
        )
        cmd.raw("-map", "[v]")

        applyVideoEncoding(cmd, plan, spec.video, fps.toDouble())
        cmd.output(spec.output)
        return cmd.build()
    }

    // ============================================================ 内部：编码参数 ====

    /**
     * 有滤镜时不能直通。
     *
     * FFmpeg 对「既有 `-vf`/`-filter_complex` 又 `-c:v copy`」会直接报
     * `Filtering and streamcopy cannot be used together`。
     * 旋转 / 裁剪 / 变速 / 去遮挡这几项，滤镜就是功能本体，去不掉，
     * 所以只能把「直通」降级成重编码。
     */
    private fun VideoEncodeSpec.reencodedIfFiltered(): VideoEncodeSpec =
        if (mode == RateMode.COPY) copy(mode = RateMode.CRF) else this

    /** 同上：`-af` 与 `-c:a copy` 互斥。 */
    private fun AudioEncodeSpec.reencodedIfFiltered(): AudioEncodeSpec =
        if (codec == AudioCodec.COPY) copy(codec = AudioCodec.AAC) else this

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

    /**
     * 音频轨的「映射 + 滤镜 + 编码」三件事。
     *
     * 约定与 [render] 保持一致：`audio` 为 null 表示**丢弃**音频轨，
     * 而不是「不指定编码参数」。早先的写法无条件 `-map 0:a:0?`，
     * 于是「丢弃音频」开关点了等于没点 —— 音频照样被带进输出。
     *
     * @param audioFilters 非空时会写 `-af`，此时 `-c:a copy` 必须降级成重编码
     *        （FFmpeg 报 `Filtering and streamcopy cannot be used together`）
     */
    private fun applyAudioTrack(
        cmd: FfmpegCommand,
        audio: AudioEncodeSpec?,
        audioFilters: List<String> = emptyList(),
    ) {
        if (audio == null) {
            cmd.flag("-an")
            return
        }
        cmd.map("0:a:0?")
        if (audioFilters.isEmpty()) {
            applyAudioEncoding(cmd, audio)
        } else {
            cmd.audioFilterGraph(audioFilters)
            applyAudioEncoding(cmd, audio.reencodedIfFiltered())
        }
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
