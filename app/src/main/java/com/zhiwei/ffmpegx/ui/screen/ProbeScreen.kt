package com.zhiwei.ffmpegx.ui.screen

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
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiwei.ffmpegx.core.model.formatBitrate
import com.zhiwei.ffmpegx.core.model.formatDurationPrecise
import com.zhiwei.ffmpegx.core.model.formatSize
import com.zhiwei.ffmpegx.ui.components.InfoRow
import com.zhiwei.ffmpegx.ui.components.SectionCard
import com.zhiwei.ffmpegx.ui.components.WarningBanner
import com.zhiwei.ffmpegx.ui.theme.MonospaceStyle
import com.zhiwei.ffmpegx.ui.tool.rememberFilePicker

@Composable
fun ProbeScreen(viewModel: DeviceViewModel = hiltViewModel()) {
    val state by viewModel.probe.collectAsStateWithLifecycle()
    val device by viewModel.device.collectAsStateWithLifecycle()
    val pick = rememberFilePicker(arrayOf("video/*", "audio/*", "image/*")) { viewModel.onPickProbeFile(it) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!device.nativeReady) {
            WarningBanner(
                title = "ffprobe 不可用",
                message = device.nativeError.ifBlank { "媒体信息依赖 FFmpeg 的 ffprobe，当前后端未能加载。" },
            )
        }

        SectionCard(title = "选择素材") {
            OutlinedButton(onClick = pick, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("选择文件")
            }
            if (state.fileName.isNotBlank()) {
                Text(state.fileName, style = MaterialTheme.typography.bodyMedium)
            }
            if (state.busy) {
                Text("解析中…", style = MaterialTheme.typography.bodySmall)
            }
        }

        state.error?.let { WarningBanner(title = "解析失败", message = it) }

        state.info?.let { info ->
            SectionCard(title = "容器信息") {
                InfoRow("格式", info.formatLongName ?: info.formatName ?: "未知")
                InfoRow("时长", formatDurationPrecise(info.durationUs))
                InfoRow("体积", formatSize(info.fileSizeBytes))
                InfoRow("总码率", formatBitrate(info.bitRate))
                InfoRow("路径", info.path)
            }

            info.videoStreams.forEachIndexed { index, video ->
                SectionCard(title = "视频流 #$index") {
                    InfoRow("编码", "${video.codecName}${video.profile?.let { " ($it)" } ?: ""}")
                    InfoRow("分辨率", "${video.width}×${video.height}")
                    if (video.rotationDegrees != 0) {
                        InfoRow("旋转", "${video.rotationDegrees}°（显示尺寸 ${video.resolutionLabel}）")
                    }
                    InfoRow("帧率", video.fpsLabel)
                    InfoRow("码率", formatBitrate(video.bitRate))
                    InfoRow("像素格式", video.pixelFormat ?: "未知")
                    video.bitDepth?.let { InfoRow("位深", "$it bit") }
                    video.colorTransfer?.let { InfoRow("色彩传递", it) }
                    if (video.isHdr) InfoRow("HDR", "是")
                    video.nbFrames?.let { InfoRow("总帧数", it.toString()) }
                    InfoRow("Android MIME", video.mime ?: "无对应类型（无法使用硬件解码）")
                }
            }

            info.audioStreams.forEachIndexed { index, audio ->
                SectionCard(title = "音频流 #$index") {
                    InfoRow("编码", audio.codecName)
                    InfoRow("采样率", "${audio.sampleRate} Hz")
                    InfoRow("声道", "${audio.channelsLabel}（${audio.channelLayout ?: "-"}）")
                    InfoRow("码率", formatBitrate(audio.bitRate))
                    audio.language?.let { InfoRow("语言", it) }
                }
            }

            if (info.subtitleStreams.isNotEmpty()) {
                SectionCard(title = "字幕流") {
                    info.subtitleStreams.forEach { sub ->
                        InfoRow(
                            "#${sub.index}",
                            "${sub.codecName}${sub.language?.let { " · $it" } ?: ""}" +
                                if (sub.isBitmap) "（图形字幕，无法转成文本）" else "",
                        )
                    }
                }
            }

            SectionCard(title = "原始 JSON") {
                Column(
                    Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        info.rawJson,
                        style = MonospaceStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row {
                TextButton(onClick = { viewModel.exportToGallery() }) {
                    Icon(Icons.Default.SaveAlt, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("导出到媒体库")
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { viewModel.clearProbe() }) { Text("清除") }
            }

            state.exportedUri?.let {
                Text(
                    "已导出：$it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
