package com.zhiwei.ffmpegx.ui.tool

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiwei.ffmpegx.core.cmd.OverlayMode
import com.zhiwei.ffmpegx.core.cmd.OverlayPosition
import com.zhiwei.ffmpegx.core.cmd.SubtitleMode
import com.zhiwei.ffmpegx.core.model.CompressPreset
import com.zhiwei.ffmpegx.core.model.OutputContainer
import com.zhiwei.ffmpegx.core.model.formatSize
import com.zhiwei.ffmpegx.core.task.TaskFeature
import com.zhiwei.ffmpegx.ui.components.ChoiceChips
import com.zhiwei.ffmpegx.ui.components.DropdownField
import com.zhiwei.ffmpegx.ui.components.InfoRow
import com.zhiwei.ffmpegx.ui.components.LabeledSlider
import com.zhiwei.ffmpegx.ui.components.SectionCard
import com.zhiwei.ffmpegx.ui.theme.MonospaceStyle

// ================================================================ 格式转换 ====

@Composable
fun ConvertScreen(vm: ToolViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val form = state.form
    val pick = rememberFilePicker(arrayOf("video/*", "audio/*")) { vm.onPickInput(it) }
    val video = form.mediaInfo?.primaryVideo

    // 从系统「分享」或「用其他应用打开」进来时，自动载入那个文件
    LaunchedEffect(Unit) {
        com.zhiwei.ffmpegx.ui.SharedInput.consume()?.let { vm.onPickInput(it) }
    }

    ToolScaffold(feature = TaskFeature.CONVERT, vm = vm) { f ->
        InputPickerCard(f, onPick = pick, onClear = { vm.update { it.copy(inputPath = "", mediaInfo = null) } })
        MediaInfoCard(f.mediaInfo)
        VideoEncodingSection(f, vm::update)
        ResolutionSection(f, video?.displayWidth ?: 0, video?.displayHeight ?: 0, vm::update)
        AudioEncodingSection(f, vm::update)
        OutputNamingSection(f, vm::update)
    }
}

// ================================================================ 视频压缩 ====

@Composable
fun CompressScreen(vm: ToolViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val form = state.form
    val pick = rememberFilePicker(arrayOf("video/*")) { vm.onPickInput(it) }
    val video = form.mediaInfo?.primaryVideo

    ToolScaffold(feature = TaskFeature.COMPRESS, vm = vm) { f ->
        InputPickerCard(f, onPick = pick, onClear = { vm.update { it.copy(inputPath = "", mediaInfo = null) } })
        MediaInfoCard(f.mediaInfo)

        SectionCard(title = "压缩预设") {
            ChoiceChips(
                options = CompressPreset.entries.toList(),
                selected = f.preset,
                labelOf = { it.label },
                onSelect = { preset ->
                    // 预设会重置分辨率与帧率上限，但保留用户手填的码率
                    vm.update { it.copy(preset = preset, width = 0, height = 0, fps = preset.fpsCap) }
                },
            )
            Text(
                f.preset.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val srcW = video?.displayWidth ?: 0
            val srcH = video?.displayHeight ?: 0
            if (srcW > 0 && srcH > 0) {
                val (w, h) = f.preset.resolveSize(srcW, srcH)
                val bitrate = if (f.videoBitrate > 0) f.videoBitrate else f.preset.resolveVideoBitrate(w, h)
                val estimatedBytes = if (f.mediaInfo != null && f.mediaInfo!!.durationUs > 0) {
                    (bitrate + if (f.audioCodec.isLossless) 0 else f.audioBitrate) / 8L *
                        (f.mediaInfo!!.durationUs / 1_000_000)
                } else {
                    0L
                }
                InfoRow("目标尺寸", "$w × $h")
                InfoRow("目标码率", "%.2f Mbps".format(bitrate / 1_000_000.0))
                if (estimatedBytes > 0) {
                    InfoRow("预估体积", "约 ${formatSize(estimatedBytes)}")
                }
            }
        }

        VideoEncodingSection(f, vm::update, allowCopy = false)
        ResolutionSection(f, video?.displayWidth ?: 0, video?.displayHeight ?: 0, vm::update)
        AudioEncodingSection(f, vm::update)
        OutputNamingSection(f, vm::update)
    }
}

// ================================================================ 剪辑截取 ====

@Composable
fun TrimScreen(vm: ToolViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val form = state.form
    val pick = rememberFilePicker(arrayOf("video/*", "audio/*")) { vm.onPickInput(it) }
    val duration = form.mediaInfo?.durationUs ?: 0L

    ToolScaffold(feature = TaskFeature.TRIM, vm = vm) { f ->
        InputPickerCard(f, onPick = pick, onClear = { vm.update { it.copy(inputPath = "", mediaInfo = null) } })
        MediaInfoCard(f.mediaInfo)

        TrimRangeCard(f, duration, vm::update)

        SectionCard(title = "剪切方式") {
            ChoiceChips(
                options = listOf(true, false),
                selected = f.streamCopy,
                labelOf = { copy -> if (copy) "无损剪切（-c copy）" else "精确重编码" },
                onSelect = { copy -> vm.update { form -> form.copy(streamCopy = copy) } },
            )
            Text(
                if (f.streamCopy) {
                    "速度极快且完全无损，但只能切在关键帧上，实际起点可能比设定值早 0~2 秒。"
                } else {
                    "逐帧重新编码，切点精确到毫秒，代价是耗时且有一次画质损失。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!f.streamCopy) {
            VideoEncodingSection(f, vm::update, allowCopy = false)
            AudioEncodingSection(f, vm::update)
        }
        OutputNamingSection(f, vm::update)
    }
}

// ================================================================ 音频处理 ====

@Composable
fun AudioScreen(vm: ToolViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val form = state.form
    val pick = rememberFilePicker(arrayOf("video/*", "audio/*")) { vm.onPickInput(it) }

    ToolScaffold(feature = TaskFeature.AUDIO, vm = vm) { f ->
        InputPickerCard(f, onPick = pick, onClear = { vm.update { it.copy(inputPath = "", mediaInfo = null) } })
        MediaInfoCard(f.mediaInfo)

        SectionCard(title = "输出格式") {
            val containers = listOf(
                OutputContainer.M4A, OutputContainer.MP3, OutputContainer.FLAC,
                OutputContainer.WAV, OutputContainer.MKV, OutputContainer.MP4,
            )
            DropdownField(
                label = "容器",
                options = containers,
                selected = f.container,
                labelOf = { it.label },
                onSelect = { container ->
                    vm.update { it.copy(container = container, keepVideoForAudio = !container.isAudioOnly) }
                },
            )
            val audioOptions = remember(f.container) {
                f.container.supportedAudio.mapNotNull { name ->
                    com.zhiwei.ffmpegx.core.hw.AudioCodec.entries.firstOrNull { it.ffmpegName == name }
                }.sortedBy { it.ordinal }
            }
            DropdownField(
                label = "音频编码器",
                options = audioOptions,
                selected = f.audioCodec.takeIf { audioOptions.contains(it) } ?: audioOptions.first(),
                labelOf = { it.label },
                onSelect = { vm.update { s -> s.copy(audioCodec = it) } },
            )
            if (!f.audioCodec.isLossless && f.audioCodec != com.zhiwei.ffmpegx.core.hw.AudioCodec.COPY) {
                val presets = f.audioCodec.bitratePresets.filter { it > 0 }
                if (presets.isNotEmpty()) {
                    DropdownField(
                        label = "码率",
                        options = presets,
                        selected = presets.minByOrNull { kotlin.math.abs(it - f.audioBitrate) } ?: presets.first(),
                        labelOf = { "${it / 1000} kbps" },
                        onSelect = { vm.update { s -> s.copy(audioBitrate = it) } },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("保留视频轨", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "关闭则只导出音频；开启可用于替换音轨",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = f.keepVideoForAudio,
                    onCheckedChange = { vm.update { s -> s.copy(keepVideoForAudio = it) } },
                )
            }
        }

        SectionCard(title = "音频处理") {
            LabeledSlider(
                label = "音量",
                value = f.volume.toFloat(),
                valueRange = 0f..3f,
                steps = 29,
                onValueChange = { vm.update { s -> s.copy(volume = it.toDouble()) } },
                valueLabel = { "%.0f%%".format(it * 100) },
            )
            LabeledSlider(
                label = "播放速度",
                value = f.tempo.toFloat(),
                valueRange = 0.5f..3f,
                steps = 24,
                onValueChange = { vm.update { s -> s.copy(tempo = it.toDouble()) } },
                valueLabel = { "%.2fx".format(it) },
                supporting = "变速会改变音调感知，超过 2 倍会自动串联多级 atempo",
            )
            LabeledSlider(
                label = "淡入",
                value = f.fadeIn.toFloat(),
                valueRange = 0f..10f,
                steps = 19,
                onValueChange = { vm.update { s -> s.copy(fadeIn = it.toDouble()) } },
                valueLabel = { "%.1f s".format(it) },
            )
            LabeledSlider(
                label = "淡出",
                value = f.fadeOut.toFloat(),
                valueRange = 0f..10f,
                steps = 19,
                onValueChange = { vm.update { s -> s.copy(fadeOut = it.toDouble()) } },
                valueLabel = { "%.1f s".format(it) },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("响度标准化（EBU R128）", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "统一到 -16 LUFS，适合多段素材拼接或投稿",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = f.loudnessTarget != null,
                    onCheckedChange = { vm.update { s -> s.copy(loudnessTarget = if (it) -16.0 else null) } },
                )
            }
        }

        OutputNamingSection(f, vm::update)
    }
}

// ================================================================ GIF 制作 ====

@Composable
fun GifScreen(vm: ToolViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val form = state.form
    val pick = rememberFilePicker(arrayOf("video/*")) { vm.onPickInput(it) }
    val duration = form.mediaInfo?.durationUs ?: 0L

    ToolScaffold(feature = TaskFeature.GIF, vm = vm) { f ->
        InputPickerCard(f, onPick = pick, onClear = { vm.update { it.copy(inputPath = "", mediaInfo = null) } })
        MediaInfoCard(f.mediaInfo)
        TrimRangeCard(f, duration, vm::update)

        SectionCard(title = "GIF 参数") {
            DropdownField(
                label = "帧率",
                options = com.zhiwei.ffmpegx.core.cmd.GifSpec.FPS_CHOICES,
                selected = f.gifFps,
                labelOf = { "$it fps" },
                onSelect = { vm.update { s -> s.copy(gifFps = it) } },
                supporting = "GIF 没有帧间压缩，帧率每提高一倍体积大致翻倍",
            )
            DropdownField(
                label = "宽度",
                options = com.zhiwei.ffmpegx.core.cmd.GifSpec.WIDTH_CHOICES,
                selected = f.gifWidth,
                labelOf = { if (it == 0) "保持原始宽度" else "$it px" },
                onSelect = { vm.update { s -> s.copy(gifWidth = it) } },
            )
            DropdownField(
                label = "循环",
                options = listOf(0, 1, 3, 5, 10),
                selected = f.gifLoop,
                labelOf = { if (it == 0) "无限循环" else "循环 $it 次" },
                onSelect = { vm.update { s -> s.copy(gifLoop = it) } },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("两遍调色板", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "先生成专属调色板再合成，渐变与肤色明显更自然（代价是多跑一遍）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = f.gifTwoPass,
                    onCheckedChange = { vm.update { s -> s.copy(gifTwoPass = it) } },
                )
            }
            if (f.gifTwoPass) {
                DropdownField(
                    label = "调色板采样模式",
                    options = com.zhiwei.ffmpegx.core.cmd.GifSpec.PALETTE_STATS_MODES,
                    selected = f.gifStatsMode,
                    labelOf = { it },
                    onSelect = { vm.update { s -> s.copy(gifStatsMode = it) } },
                    supporting = "diff 对画面变化大的素材更省色，full 更均衡",
                )
                DropdownField(
                    label = "抖动算法",
                    options = com.zhiwei.ffmpegx.core.cmd.GifSpec.DITHERS,
                    selected = f.gifDither,
                    labelOf = { it },
                    onSelect = { vm.update { s -> s.copy(gifDither = it) } },
                )
            }
        }

        OutputNamingSection(f, vm::update)
    }
}

// ================================================================ 视频拼接 ====

@Composable
fun ConcatScreen(vm: ToolViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val form = state.form
    val pickMain = rememberFilePicker(arrayOf("video/*", "audio/*")) { vm.onPickInput(it) }
    val pickExtra = rememberFilePicker(arrayOf("video/*", "audio/*")) { vm.onPickExtra(it) }
    val video = form.mediaInfo?.primaryVideo

    ToolScaffold(feature = TaskFeature.CONCAT, vm = vm) { f ->
        InputPickerCard(f, onPick = pickMain, onClear = { vm.update { it.copy(inputPath = "", mediaInfo = null) } })
        MediaInfoCard(f.mediaInfo)

        SectionCard(title = "待拼接片段（按顺序）") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf(f.inputDisplayName.ifBlank { f.inputPath.substringAfterLast('/') }) +
                    f.extraInputs.map { it.substringAfterLast('/') })
                    .forEachIndexed { index, name ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${index + 1}. $name",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (index > 0) {
                                IconButton(onClick = { vm.removeExtra(index - 1) }) {
                                    Icon(Icons.Default.Close, "移除", Modifier.size(18.dp))
                                }
                            }
                        }
                    }
            }
            OutlinedButton(onClick = pickExtra, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("添加片段")
            }
        }

        SectionCard(title = "拼接方式") {
            ChoiceChips(
                options = listOf(false, true),
                selected = f.concatUseDemuxer,
                labelOf = { if (it) "concat demuxer（无损）" else "滤镜重编码（通用）" },
                onSelect = { vm.update { s -> s.copy(concatUseDemuxer = it) } },
            )
            Text(
                if (f.concatUseDemuxer) {
                    "速度极快且无损，但要求所有片段的编码参数、分辨率、帧率完全一致，" +
                        "否则输出会花屏或音画不同步。"
                } else {
                    "逐帧重编码，会把所有片段统一到相同尺寸与帧率，任何素材都能拼，代价是耗时。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!f.concatUseDemuxer) {
            VideoEncodingSection(f, vm::update, allowCopy = false)
            ResolutionSection(f, video?.displayWidth ?: 0, video?.displayHeight ?: 0, vm::update)
            AudioEncodingSection(f, vm::update)
        }
        OutputNamingSection(f, vm::update)
    }
}

// ================================================================ 字幕处理 ====

@Composable
fun SubtitleScreen(vm: ToolViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val form = state.form
    val pickVideo = rememberFilePicker(arrayOf("video/*")) { vm.onPickInput(it) }
    val pickSub = rememberFilePicker(arrayOf("*/*")) { vm.onPickSubtitle(it) }
    val info = form.mediaInfo

    ToolScaffold(feature = TaskFeature.SUBTITLE, vm = vm) { f ->
        InputPickerCard(f, onPick = pickVideo, onClear = { vm.update { it.copy(inputPath = "", mediaInfo = null) } })
        MediaInfoCard(f.mediaInfo)

        SectionCard(title = "处理方式") {
            ChoiceChips(
                options = SubtitleMode.entries.toList(),
                selected = f.subtitleMode,
                labelOf = { it.label },
                onSelect = { vm.update { s -> s.copy(subtitleMode = it) } },
            )
            Text(
                f.subtitleMode.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (f.subtitleMode) {
                SubtitleMode.EXTRACT -> {
                    val subs = info?.subtitleStreams.orEmpty()
                    if (subs.isEmpty()) {
                        Text(
                            "当前素材没有内嵌字幕轨，无法提取。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        DropdownField(
                            label = "选择字幕轨",
                            options = subs,
                            selected = subs.firstOrNull { it.index == f.subtitleStreamIndex } ?: subs.first(),
                            labelOf = { "#${it.index} ${it.language ?: "未知语言"} · ${it.codecName}" },
                            onSelect = { vm.update { s -> s.copy(subtitleStreamIndex = it.index) } },
                        )
                    }
                }

                SubtitleMode.BURN -> {
                    if (f.subtitlePath.isBlank()) {
                        OutlinedButton(onClick = pickSub, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("选择字幕文件（.srt / .ass）")
                        }
                    } else {
                        Text(
                            f.subtitlePath.substringAfterLast('/'),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = pickSub) { Text("重新选择") }
                    }
                    OutlinedTextField(
                        value = f.forceStyle,
                        onValueChange = { vm.update { s -> s.copy(forceStyle = it) } },
                        label = { Text("样式覆盖（可选）") },
                        placeholder = { Text("FontSize=28,PrimaryColour=&H00FFFFFF") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "留空则使用字幕文件自带的样式。烧录需要 FFmpeg 编译时包含 libass。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SubtitleMode.MUX -> {
                    if (f.subtitlePath.isBlank()) {
                        OutlinedButton(onClick = pickSub, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("选择字幕文件")
                        }
                    } else {
                        Text(f.subtitlePath.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = pickSub) { Text("重新选择") }
                    }
                }
            }
        }

        if (f.subtitleMode != SubtitleMode.EXTRACT) {
            SectionCard(title = "输出容器") {
                DropdownField(
                    label = "容器",
                    options = listOf(OutputContainer.MP4, OutputContainer.MKV, OutputContainer.MOV),
                    selected = f.container,
                    labelOf = { it.label },
                    onSelect = { vm.update { s -> s.copy(container = it) } },
                    supporting = "MP4 用 mov_text 字幕轨，MKV 用 srt（兼容性更好）",
                )
            }
        }
        OutputNamingSection(f, vm::update)
    }
}

// ============================================================ 水印与画中画 ====

@Composable
fun OverlayScreen(vm: ToolViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val form = state.form
    val pickMain = rememberFilePicker(arrayOf("video/*")) { vm.onPickInput(it) }
    val pickOverlay = rememberFilePicker(arrayOf("image/*", "video/*")) { vm.onPickOverlay(it) }

    ToolScaffold(feature = TaskFeature.OVERLAY, vm = vm) { f ->
        InputPickerCard(f, onPick = pickMain, onClear = { vm.update { it.copy(inputPath = "", mediaInfo = null) } })
        MediaInfoCard(f.mediaInfo)

        SectionCard(title = "叠加方式") {
            ChoiceChips(
                options = OverlayMode.entries.toList(),
                selected = f.overlayMode,
                labelOf = { it.label },
                onSelect = { vm.update { s -> s.copy(overlayMode = it) } },
            )
            Text(
                f.overlayMode.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (f.overlayPath.isBlank()) {
                OutlinedButton(onClick = pickOverlay, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (f.overlayMode == OverlayMode.WATERMARK) "选择水印图片" else "选择第二路视频")
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        f.overlayPath.substringAfterLast('/'),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = pickOverlay) { Text("更换") }
                }
            }
        }

        if (f.overlayMode == OverlayMode.WATERMARK || f.overlayMode == OverlayMode.PICTURE_IN_PICTURE) {
            SectionCard(title = "位置与外观") {
                DropdownField(
                    label = "位置",
                    options = OverlayPosition.entries.toList(),
                    selected = f.overlayPosition,
                    labelOf = { it.label },
                    onSelect = { vm.update { s -> s.copy(overlayPosition = it) } },
                )
                LabeledSlider(
                    label = "大小（相对主画面宽度）",
                    value = (f.overlayScale * 100).toFloat(),
                    valueRange = 5f..60f,
                    steps = 54,
                    onValueChange = { vm.update { s -> s.copy(overlayScale = it / 100.0) } },
                    valueLabel = { "%.0f%%".format(it) },
                )
                if (f.overlayMode == OverlayMode.WATERMARK) {
                    LabeledSlider(
                        label = "不透明度",
                        value = (f.overlayOpacity * 100).toFloat(),
                        valueRange = 10f..100f,
                        steps = 17,
                        onValueChange = { vm.update { s -> s.copy(overlayOpacity = it / 100.0) } },
                        valueLabel = { "%.0f%%".format(it) },
                    )
                }
                LabeledSlider(
                    label = "显示起点（秒）",
                    value = f.overlayFrom.toFloat(),
                    valueRange = 0f..120f,
                    steps = 119,
                    onValueChange = { vm.update { s -> s.copy(overlayFrom = it.toDouble()) } },
                    valueLabel = { "%.0f s".format(it) },
                )
                LabeledSlider(
                    label = "显示终点（秒，0 = 全程）",
                    value = f.overlayTo.toFloat(),
                    valueRange = 0f..120f,
                    steps = 119,
                    onValueChange = { vm.update { s -> s.copy(overlayTo = it.toDouble()) } },
                    valueLabel = { if (it < 1f) "全程" else "%.0f s".format(it) },
                )
            }
        }

        if (f.overlayMode == OverlayMode.SIDE_BY_SIDE || f.overlayMode == OverlayMode.TOP_BOTTOM) {
            SectionCard(title = "分屏参数") {
                LabeledSlider(
                    label = "单路高度",
                    value = f.stackEdge.toFloat(),
                    valueRange = 360f..1440f,
                    steps = 35,
                    onValueChange = { vm.update { s -> s.copy(stackEdge = it.toInt()) } },
                    valueLabel = { "${it.toInt()} px" },
                )
            }
        }

        VideoEncodingSection(f, vm::update, allowCopy = false)
        AudioEncodingSection(f, vm::update)
        OutputNamingSection(f, vm::update)
    }
}

// ================================================================ 命令行 ====

@Composable
fun ConsoleToolScreen(vm: ToolViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val console by vm.consoleLines.collectAsStateWithLifecycle()

    ToolScaffold(feature = TaskFeature.CONSOLE, vm = vm) { f ->
        SectionCard(title = "原始命令") {
            OutlinedTextField(
                value = f.rawCommand,
                onValueChange = { vm.update { s -> s.copy(rawCommand = it) } },
                label = { Text("ffmpeg 之后的参数") },
                placeholder = { Text("-i /sdcard/in.mp4 -c:v h264_mediacodec -b:v 4M out.mp4") },
                minLines = 4,
                maxLines = 10,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "无需写开头的 ffmpeg，也无需写 -y / -loglevel，这些会自动补上。" +
                    "参数按空格切分，含空格的路径请用引号包起来。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (console.isNotEmpty()) {
            SectionCard(title = "实时输出") {
                Column(
                    Modifier
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    console.takeLast(200).forEach { line ->
                        Text(
                            line,
                            style = MonospaceStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
