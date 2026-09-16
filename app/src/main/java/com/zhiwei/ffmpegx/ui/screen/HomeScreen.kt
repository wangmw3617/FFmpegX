package com.zhiwei.ffmpegx.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiwei.ffmpegx.core.hw.VideoCodec
import com.zhiwei.ffmpegx.core.task.TaskFeature
import com.zhiwei.ffmpegx.ui.components.EmptyState
import com.zhiwei.ffmpegx.ui.components.InfoRow
import com.zhiwei.ffmpegx.ui.components.SectionCard
import com.zhiwei.ffmpegx.ui.components.TaskRow
import com.zhiwei.ffmpegx.ui.components.WarningBanner
import com.zhiwei.ffmpegx.ui.nav.AudioRoute
import com.zhiwei.ffmpegx.ui.nav.CompressRoute
import com.zhiwei.ffmpegx.ui.nav.ConcatRoute
import com.zhiwei.ffmpegx.ui.nav.ConvertRoute
import com.zhiwei.ffmpegx.ui.nav.GifRoute
import com.zhiwei.ffmpegx.ui.nav.OverlayRoute
import com.zhiwei.ffmpegx.ui.nav.ProbeRoute
import com.zhiwei.ffmpegx.ui.nav.RawCommandRoute
import com.zhiwei.ffmpegx.ui.nav.SubtitleRoute
import com.zhiwei.ffmpegx.ui.nav.TrimRoute

private data class ToolEntry(
    val label: String,
    val description: String,
    val icon: ImageVector,
    val route: Any,
)

private val TOOLS = listOf(
    ToolEntry("格式转换", "容器 / 编码器 / 封装", Icons.Default.SwapHoriz, ConvertRoute),
    ToolEntry("视频压缩", "目标体积 / 降分辨率", Icons.Default.Speed, CompressRoute),
    ToolEntry("剪辑截取", "起止时间 / 无损剪切", Icons.Default.ContentCut, TrimRoute),
    ToolEntry("音频处理", "提取 / 音量 / 变速", Icons.Default.GraphicEq, AudioRoute),
    ToolEntry("GIF 制作", "两遍调色板高质量", Icons.Default.Animation, GifRoute),
    ToolEntry("视频拼接", "多文件合并", Icons.Default.Link, ConcatRoute),
    ToolEntry("字幕处理", "烧录 / 提取 / 封装", Icons.Default.Subtitles, SubtitleRoute),
    ToolEntry("水印画中画", "图片水印 / 分屏", Icons.Default.Layers, OverlayRoute),
    ToolEntry("媒体信息", "ffprobe 详细解析", Icons.Default.Info, ProbeRoute),
    ToolEntry("命令行", "直接写 ffmpeg 参数", Icons.Default.Terminal, RawCommandRoute),
)

@Composable
fun HomeScreen(
    onOpenTool: (Any) -> Unit,
    deviceViewModel: DeviceViewModel = hiltViewModel(),
    tasksViewModel: TasksViewModel = hiltViewModel(),
) {
    val device by deviceViewModel.device.collectAsStateWithLifecycle()
    val recent by tasksViewModel.recentTasks.collectAsStateWithLifecycle()
    val current by tasksViewModel.current.collectAsStateWithLifecycle()
    val progress by tasksViewModel.progress.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!device.nativeReady) {
            WarningBanner(
                title = "FFmpeg 后端不可用",
                message = device.nativeError.ifBlank {
                    "后端初始化失败。请查看运行日志，或改用其它后端重新构建（gradle.properties 的 ffmpegx.backend）。"
                },
            )
        }

        DeviceCapabilityCard(
            state = device,
            onRescan = { deviceViewModel.scan() },
        )

        if (current != null) {
            com.zhiwei.ffmpegx.ui.components.RunningTaskCard(
                task = current,
                percent = if (progress.percent >= 0) progress.percent.toInt() else current!!.progressPercent,
                speedLabel = progress.speedLabel,
                remainingLabel = progress.remainingMs.takeIf { it > 0 }?.let { "${it / 1000}s" },
                onCancel = { tasksViewModel.cancelCurrent() },
            )
        }

        SectionCard(title = "常用功能") {
            TOOLS.chunked(2).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowItems.forEach { tool ->
                        ToolTile(
                            tool = tool,
                            modifier = Modifier.weight(1f),
                            onClick = { onOpenTool(tool.route) },
                        )
                    }
                    if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        SectionCard(title = "最近任务") {
            if (recent.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.Info,
                    title = "还没有任务",
                    subtitle = "从上面的功能里挑一个开始吧",
                )
            } else {
                recent.take(5).forEach { task ->
                    TaskRow(
                        task = task,
                        onRetry = { tasksViewModel.retry(task.id) },
                        onDelete = { tasksViewModel.remove(task.id) },
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ToolTile(tool: ToolEntry, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier,
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(
                    tool.icon,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(tool.label, style = MaterialTheme.typography.titleMedium)
            Text(
                tool.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeviceCapabilityCard(state: DeviceUiState, onRescan: () -> Unit) {
    val report = state.report
    SectionCard(
        title = "设备与硬件加速",
        subtitle = report?.soc?.displayName,
        trailing = {
            TextButton(onClick = onRescan) {
                Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (state.scanning) "扫描中" else "重扫")
            }
        },
    ) {
        if (report == null) {
            Text("正在枚举 MediaCodec…", style = MaterialTheme.typography.bodySmall)
            return@SectionCard
        }

        InfoRow("芯片平台", report.soc.displayName)
        InfoRow("档位", report.soc.tier.label)
        InfoRow("FFmpeg", if (state.nativeReady) state.nativeVersion else "未加载")
        InfoRow("后端", state.backendName.ifBlank { "—" })

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val hwEncoders = VideoCodec.entries.filter { report.hardwareEncoders(it).isNotEmpty() }
            if (hwEncoders.isEmpty()) {
                AssistChip(onClick = {}, label = { Text("无硬件编码器") })
            } else {
                hwEncoders.forEach { codec ->
                    AssistChip(
                        onClick = {},
                        label = { Text("硬编 ${codec.shortLabel}") },
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val hwDecoderCount = report.videoDecoders.count { it.isHardware }
            AssistChip(onClick = {}, label = { Text("硬解 $hwDecoderCount 个") })
            if (report.hasHardwareDecoder("video/av01")) {
                AssistChip(onClick = {}, label = { Text("AV1 硬解") })
            }
            if (report.hardwareEncoders(VideoCodec.AV1).isNotEmpty()) {
                AssistChip(onClick = {}, label = { Text("AV1 硬编") })
            }
            if (report.soc.suggestVulkanFilters) {
                AssistChip(onClick = {}, label = { Text("可尝试 Vulkan 滤镜") })
            }
        }

        Text(
            report.summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        report.soc.notes.forEach { note ->
            Text(
                "· $note",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            "以上能力全部来自运行时 MediaCodecList 枚举，与厂商 ROM 实际开放情况一致。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/** 任务列表里点击某条记录时，跳到对应工具页（当前先只做提示） */
fun TaskFeature.shortLabel(): String = label
