package com.zhiwei.ffmpegx.ui.screen

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.SlowMotionVideo
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiwei.ffmpegx.core.hw.VideoCodec
import com.zhiwei.ffmpegx.core.task.TaskEntity
import com.zhiwei.ffmpegx.core.task.TaskFeature
import com.zhiwei.ffmpegx.ui.components.DeleteTaskDialog
import com.zhiwei.ffmpegx.ui.components.EmptyState
import com.zhiwei.ffmpegx.ui.components.InfoRow
import com.zhiwei.ffmpegx.ui.components.SectionCard
import com.zhiwei.ffmpegx.ui.components.TaskRow
import com.zhiwei.ffmpegx.ui.components.WarningBanner
import com.zhiwei.ffmpegx.ui.nav.AudioRoute
import com.zhiwei.ffmpegx.ui.nav.CompressRoute
import com.zhiwei.ffmpegx.ui.nav.ConcatRoute
import com.zhiwei.ffmpegx.ui.nav.ConvertRoute
import com.zhiwei.ffmpegx.ui.nav.CropRoute
import com.zhiwei.ffmpegx.ui.nav.DelogoRoute
import com.zhiwei.ffmpegx.ui.nav.GifRoute
import com.zhiwei.ffmpegx.ui.nav.OverlayRoute
import com.zhiwei.ffmpegx.ui.nav.ProbeRoute
import com.zhiwei.ffmpegx.ui.nav.RawCommandRoute
import com.zhiwei.ffmpegx.ui.nav.RotateRoute
import com.zhiwei.ffmpegx.ui.nav.SlideshowRoute
import com.zhiwei.ffmpegx.ui.nav.SpeedRoute
import com.zhiwei.ffmpegx.ui.nav.SubtitleRoute
import com.zhiwei.ffmpegx.ui.nav.ThumbnailRoute
import com.zhiwei.ffmpegx.ui.nav.TrimRoute

private data class ToolEntry(
    val label: String,
    val description: String,
    val icon: ImageVector,
    val route: Any,
)

/** 常用功能：默认展开 */
private val COMMON_TOOLS = listOf(
    ToolEntry("格式转换", "容器 / 编码器 / 封装", Icons.Default.SwapHoriz, ConvertRoute),
    ToolEntry("视频压缩", "目标体积 / 降分辨率", Icons.Default.Speed, CompressRoute),
    ToolEntry("剪辑截取", "起止时间 / 无损剪切", Icons.Default.ContentCut, TrimRoute),
    ToolEntry("音频处理", "提取 / 音量 / 变速", Icons.Default.GraphicEq, AudioRoute),
    ToolEntry("GIF 制作", "两遍调色板高质量", Icons.Default.Animation, GifRoute),
    ToolEntry("视频拼接", "多文件合并", Icons.Default.Link, ConcatRoute),
)

/** 更多功能：默认折叠，收纳使用频率较低但更进阶的工具 */
private val MORE_TOOLS = listOf(
    ToolEntry("旋转翻转", "转 90° / 镜像", Icons.Default.ScreenRotation, RotateRoute),
    ToolEntry("画面裁剪", "按区域裁剪画面", Icons.Default.Crop, CropRoute),
    ToolEntry("提取画面", "从视频截取一帧", Icons.Default.PhotoCamera, ThumbnailRoute),
    ToolEntry("视频变速", "0.25x ~ 4x", Icons.Default.SlowMotionVideo, SpeedRoute),
    ToolEntry("去水印", "模糊 / 填补遮挡区域", Icons.Default.Healing, DelogoRoute),
    ToolEntry("图片转视频", "多张图片合成视频", Icons.Default.Slideshow, SlideshowRoute),
    ToolEntry("字幕处理", "烧录 / 提取 / 封装", Icons.Default.Subtitles, SubtitleRoute),
    ToolEntry("水印画中画", "图片水印 / 分屏", Icons.Default.Layers, OverlayRoute),
    ToolEntry("媒体信息", "查看编码 / 分辨率等信息", Icons.Default.Info, ProbeRoute),
    ToolEntry("命令行", "直接写 ffmpeg 参数", Icons.Default.Terminal, RawCommandRoute),
)

/**
 * 功能入口的两列网格。常用功能与更多功能共用。
 *
 * 内部自带纵向间距：抽成独立 composable 后就不再是 Column 的直接子元素，
 * 外层的 Arrangement.spacedBy 不会再作用于这些行。
 */
@Composable
private fun ToolGrid(tools: List<ToolEntry>, onOpenTool: (Any) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tools.chunked(2).forEach { rowItems ->
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
}

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
        if (!device.ffmpegReady) {
            WarningBanner(
                title = "转码引擎不可用",
                // 不把内部错误（ABI 不匹配、.so 加载失败之类）直接抛给用户，
                // 那些信息留在「控制台」里供排查，界面上只给出可操作的下一步。
                message = "当前设备上无法启动转码引擎，暂时不能处理任务。" +
                    "请尝试重新安装应用；若问题依旧，可在「控制台」查看详细日志。",
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
            ToolGrid(COMMON_TOOLS, onOpenTool)
        }

        // 更多功能：默认折叠，避免首屏被十几个入口撑满
        var moreExpanded by remember { mutableStateOf(false) }
        // 首页的「最近任务」也能删，同样会连产物文件一起删掉，所以也要确认
        var pendingDelete by remember { mutableStateOf<TaskEntity?>(null) }
        SectionCard(
            title = "更多功能",
            trailing = {
                Icon(
                    imageVector = if (moreExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (moreExpanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { moreExpanded = !moreExpanded },
                )
            },
        ) {
            if (moreExpanded) {
                ToolGrid(MORE_TOOLS, onOpenTool)
            } else {
                Text(
                    "字幕、水印、媒体信息、命令行等 ${MORE_TOOLS.size} 个工具",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { moreExpanded = true },
                )
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
                        onDelete = { pendingDelete = task },
                    )
                }
            }
        }

        pendingDelete?.let { task ->
            DeleteTaskDialog(
                task = task,
                onConfirm = { tasksViewModel.remove(task.id) },
                onDismiss = { pendingDelete = null },
            )
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
        InfoRow("FFmpeg", if (state.ffmpegReady) state.ffmpegVersion else "未加载")
        InfoRow("核心", state.backendName.ifBlank { "—" })

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
