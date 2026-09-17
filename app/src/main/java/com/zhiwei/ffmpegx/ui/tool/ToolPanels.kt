package com.zhiwei.ffmpegx.ui.tool

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zhiwei.ffmpegx.core.cmd.RateMode
import com.zhiwei.ffmpegx.core.cmd.VideoEncodeSpec
import com.zhiwei.ffmpegx.core.hw.VideoCodec
import com.zhiwei.ffmpegx.core.hw.AudioCodec
import com.zhiwei.ffmpegx.core.model.OutputContainer
import com.zhiwei.ffmpegx.ui.components.ChoiceChips
import com.zhiwei.ffmpegx.ui.components.DropdownField
import com.zhiwei.ffmpegx.ui.components.LabeledSlider
import com.zhiwei.ffmpegx.ui.components.SectionCard

typealias FormUpdate = ((ToolForm) -> ToolForm) -> Unit

// ============================================================ 视频编码设置 ====

@Composable
fun VideoEncodingSection(
    form: ToolForm,
    onUpdate: FormUpdate,
    allowCopy: Boolean = true,
) {
    SectionCard(title = "视频编码") {
        val containers = remember { OutputContainer.entries.filter { !it.isAudioOnly } }
        DropdownField(
            label = "封装格式",
            options = containers,
            selected = form.container.takeIf { containers.contains(it) } ?: containers.first(),
            labelOf = { it.label },
            onSelect = { container ->
                // 容器换了，不兼容的编码器要跟着回落，否则必然 mux 失败
                onUpdate { f ->
                    val codec = if (container.supportedVideo.contains(f.videoCodec)) {
                        f.videoCodec
                    } else {
                        VideoCodec.H264
                    }
                    f.copy(container = container, videoCodec = codec)
                }
            },
        )

        val codecOptions = remember(form.container) {
            form.container.supportedVideo.toList()
        }

        DropdownField(
            label = "视频编码器",
            options = codecOptions,
            selected = form.videoCodec.takeIf { codecOptions.contains(it) } ?: codecOptions.first(),
            labelOf = { codec ->
                if (codec.hasSoftwareEncoder) codec.label else "${codec.label}（仅硬件可用）"
            },
            onSelect = { onUpdate { f -> f.copy(videoCodec = it) } },
            supporting = "能否走硬件编码由「硬件加速方案」决定",
        )

        val modes = remember(allowCopy) {
            if (allowCopy) RateMode.entries.toList() else listOf(RateMode.CRF, RateMode.BITRATE)
        }
        ChoiceChips(
            options = modes,
            selected = form.rateMode,
            labelOf = { it.label },
            onSelect = { onUpdate { f -> f.copy(rateMode = it) } },
        )

        when (form.rateMode) {
            RateMode.CRF -> {
                LabeledSlider(
                    label = "CRF（越小画质越好）",
                    value = form.crf.toFloat(),
                    valueRange = 14f..34f,
                    steps = 19,
                    onValueChange = { onUpdate { f -> f.copy(crf = it.toInt()) } },
                    valueLabel = { it.toInt().toString() },
                    supporting = "18 接近视觉无损，23 通用平衡点，28 以上适合预览",
                )
                DropdownField(
                    label = "编码速度",
                    options = VideoEncodeSpec.X264_PRESETS,
                    selected = form.speedPreset,
                    labelOf = { it },
                    onSelect = { onUpdate { f -> f.copy(speedPreset = it) } },
                    supporting = "越慢压缩率越高；硬件编码器会忽略此项",
                )
            }

            RateMode.BITRATE -> LabeledSlider(
                label = "目标码率",
                value = (form.videoBitrate.takeIf { it > 0 } ?: 4_000_000).toFloat(),
                valueRange = 200_000f..30_000_000f,
                onValueChange = { onUpdate { f -> f.copy(videoBitrate = it.toInt()) } },
                valueLabel = {
                    val mbps = it / 1_000_000
                    val dec = ((it % 1_000_000) / 100_000).toInt()
                    "%.1f Mbps".format(mbps + dec / 10.0)
                },
            )

            RateMode.COPY -> Text(
                "视频流直接复制，不做重新编码。无损的前提是切点落在关键帧上。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ================================================================ 分辨率 ====

@Composable
fun ResolutionSection(
    form: ToolForm,
    sourceWidth: Int,
    sourceHeight: Int,
    onUpdate: FormUpdate,
) {
    val options = remember(sourceWidth, sourceHeight) {
        buildList {
            add(0 to 0)
            if (sourceWidth > 0 && sourceHeight > 0) add(sourceWidth to sourceHeight)
            add(1920 to 1080)
            add(1280 to 720)
            add(854 to 480)
            add(640 to 360)
            add(1080 to 1920)
            add(720 to 1280)
        }.distinct()
    }

    SectionCard(title = "分辨率与帧率") {
        DropdownField(
            label = "输出尺寸",
            options = options,
            selected = form.width to form.height,
            labelOf = { (w, h) -> if (w == 0) "保持原始" else "$w × $h" },
            onSelect = { (w, h) -> onUpdate { it.copy(width = w, height = h) } },
            supporting = "硬件编码器对分辨率有上限，超出会自动回落到软编",
        )

        LabeledSlider(
            label = "帧率上限",
            value = form.fps.toFloat(),
            valueRange = 0f..60f,
            steps = 11,
            onValueChange = { onUpdate { f -> f.copy(fps = it.toDouble()) } },
            valueLabel = { if (it < 1f) "保持原始" else "${it.toInt()} fps" },
            supporting = "降到 30fps 通常能省 30% 以上的体积",
        )
    }
}

// ================================================================ 音频设置 ====

@Composable
fun AudioEncodingSection(
    form: ToolForm,
    onUpdate: FormUpdate,
    allowCopy: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val audioOptions = remember(form.container, allowCopy) {
        buildList {
            if (allowCopy) add(AudioCodec.COPY)
            add(AudioCodec.AAC)
            if (form.container == OutputContainer.MKV || form.container == OutputContainer.WEBM) {
                add(AudioCodec.OPUS)
            }
            if (form.container != OutputContainer.WEBM) add(AudioCodec.MP3)
            if (form.container == OutputContainer.MKV) add(AudioCodec.FLAC)
            if (form.container == OutputContainer.MOV || form.container == OutputContainer.MKV) {
                add(AudioCodec.PCM)
            }
        }.distinct()
    }

    SectionCard(title = "音频编码", modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("丢弃音频轨", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "只保留画面，适合做素材或后续再配音",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.Switch(
                checked = form.dropAudio,
                onCheckedChange = { onUpdate { f -> f.copy(dropAudio = it) } },
            )
        }

        if (!form.dropAudio) {
            DropdownField(
                label = "音频编码器",
                options = audioOptions,
                selected = form.audioCodec.takeIf { audioOptions.contains(it) } ?: audioOptions.first(),
                labelOf = { it.label },
                onSelect = { onUpdate { f -> f.copy(audioCodec = it) } },
            )

            if (form.audioCodec != AudioCodec.COPY && !form.audioCodec.isLossless) {
                val presets = form.audioCodec.bitratePresets.filter { it > 0 }
                if (presets.isNotEmpty()) {
                    DropdownField(
                        label = "音频码率",
                        options = presets,
                        selected = presets.minByOrNull { kotlin.math.abs(it - form.audioBitrate) }
                            ?: presets.first(),
                        labelOf = { "${it / 1000} kbps" },
                        onSelect = { onUpdate { f -> f.copy(audioBitrate = it) } },
                    )
                }
            }
        }
    }
}

// ================================================================ 输出路径 ====

@Composable
fun OutputNamingSection(form: ToolForm, onUpdate: FormUpdate) {
    SectionCard(title = "输出文件") {
        OutlinedTextField(
            value = form.outputPath,
            onValueChange = { onUpdate { f -> f.copy(outputPath = it) } },
            label = { Text("输出路径（留空自动生成）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "留空时会自动保存到 Download/FFmpegX，无需任何权限。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ================================================================ 小工具 ====

@Composable
fun NumericField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Number,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (supporting != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
