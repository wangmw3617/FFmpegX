package com.zhiwei.ffmpegx.ui.tool

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiwei.ffmpegx.core.cmd.Commands
import com.zhiwei.ffmpegx.core.model.MediaInfo
import com.zhiwei.ffmpegx.core.model.formatDuration
import com.zhiwei.ffmpegx.core.model.formatSize
import com.zhiwei.ffmpegx.core.task.TaskFeature
import com.zhiwei.ffmpegx.ui.components.CommandPreviewCard
import com.zhiwei.ffmpegx.ui.components.HardwarePlanCard
import com.zhiwei.ffmpegx.ui.components.InfoRow
import com.zhiwei.ffmpegx.ui.components.RunningTaskCard
import com.zhiwei.ffmpegx.ui.components.SectionCard
import com.zhiwei.ffmpegx.ui.components.WarningBanner
import com.zhiwei.ffmpegx.ui.components.displayNameFromInput
import com.zhiwei.ffmpegx.ui.components.inputSourceLabel

// ============================================================== 通用小工具 ====

/**
 * 统一的文件选择入口：拿到 content Uri 后交给 ViewModel 解析。
 *
 * 选完会顺手申请**持久化读权限**。不加这一步的话，应用进程被系统回收后
 * 再回到「队列」页点重跑，Uri 权限已经失效，用户会看到一个莫名其妙的失败。
 * `OpenDocument` 拿到的 Uri 支持持久化，`GetContent` 不支持 —— 所以这里必须
 * 用 `OpenDocument`，并且拿到后立刻 take 一次。
 *
 * take 失败不影响本次使用（本次的临时授权仍然有效），所以只记日志不打断流程：
 * 某些提供方（如部分云盘）本来就不授予持久化权限。
 */
@Composable
fun rememberFilePicker(mimeTypes: Array<String>, onPicked: (Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.onFailure {
                Log.i("FilePicker", "该 Uri 不支持持久化授权，本次仍可正常使用：$uri")
            }
            onPicked(uri)
        }
    }
    val key = mimeTypes.toList()
    return remember(key) { { launcher.launch(mimeTypes) } }
}

// ================================================================ 页面骨架 ====

/**
 * 工具页统一骨架。顺序刻意固定为：
 *   运行中卡片 → 输入 → 素材信息 → 工具专属面板 → 硬件方案 → 命令预览 → 执行按钮
 * 这样用户在任何工具里都能用同样的视线路径找到「我要改什么」和「它会做什么」。
 */
@Composable
fun ToolScaffold(
    feature: TaskFeature,
    vm: ToolViewModel,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(ToolForm) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val runningTask by vm.currentTask.collectAsStateWithLifecycle()
    val progress by vm.currentProgress.collectAsStateWithLifecycle()
    val form = state.form

    LaunchedEffect(feature) { vm.attach(feature) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (runningTask != null) {
            RunningTaskCard(
                task = runningTask,
                percent = if (progress.percent >= 0) progress.percent.toInt() else runningTask!!.progressPercent,
                speedLabel = progress.speedLabel,
                remainingLabel = progress.remainingMs.takeIf { it > 0 }?.let { "${it / 1000}s" },
                onCancel = { vm.cancelCurrent() },
            )
        }

        if (!state.nativeReady) {
            WarningBanner(
                title = "FFmpeg 核心不可用",
                message = "FFmpeg 核心未能加载，当前只能浏览界面，无法执行转码。" +
                    "请确认安装包包含本机 CPU 架构（arm64-v8a），或重新安装应用。",
            )
        }

        state.error?.let { message ->
            WarningBanner(title = "无法执行", message = message)
        }

        content(form)

        HardwarePlanCard(state.plan)

        CommandPreviewCard(commands = state.previewCommands)

        Button(
            onClick = { vm.start() },
            enabled = state.nativeReady && runningTask == null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (runningTask != null) "有任务正在执行" else "开始处理")
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ================================================================ 输入卡片 ====

@Composable
fun InputPickerCard(
    form: ToolForm,
    onPick: () -> Unit,
    onClear: () -> Unit,
    label: String = "输入文件",
    modifier: Modifier = Modifier,
) {
    SectionCard(title = label, modifier = modifier) {
        if (form.inputPath.isBlank()) {
            OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("选择文件")
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        form.inputDisplayName.ifBlank { displayNameFromInput(form.inputPath) },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        // 不要直接把 inputPath 显示出来：走 SAF 直读时它是
                        // ffkitsaf:3.mp4 这种内部协议串，对用户没有意义，
                        // 看起来还像出了错。换成可理解的状态描述。
                        inputSourceLabel(form.inputPath, form.inputTemporary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.Close, contentDescription = "清除")
                }
            }
            TextButton(onClick = onPick) { Text("重新选择") }
        }
    }
}

@Composable
fun MediaInfoCard(info: MediaInfo?, modifier: Modifier = Modifier) {
    if (info == null) return
    SectionCard(title = "素材信息", modifier = modifier) {
        val video = info.primaryVideo
        val audio = info.primaryAudio
        InfoRow("容器", info.formatLongName ?: info.formatName ?: "未知")
        InfoRow("时长", info.durationLabel)
        InfoRow("大小", formatSize(info.fileSizeBytes))
        if (video != null) {
            InfoRow("视频", "${video.codecName} · ${video.resolutionLabel} · ${video.fpsLabel}")
            if (video.rotationDegrees != 0) {
                InfoRow("旋转", "${video.rotationDegrees}°")
            }
            if (video.isHdr) {
                InfoRow("HDR", "是（${video.colorTransfer ?: "10bit"}）")
            }
        }
        if (audio != null) {
            InfoRow(
                "音频",
                "${audio.codecName} · ${audio.sampleRate}Hz · ${audio.channelsLabel}",
            )
        }
        if (info.subtitleStreams.isNotEmpty()) {
            InfoRow("字幕", "${info.subtitleStreams.size} 条轨道")
        }
    }
}

// ================================================================ 输出设置 ====

@Composable
fun OutputPathCard(
    form: ToolForm,
    onFreeze: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "输出位置", modifier = modifier) {
        if (form.outputPath.isBlank()) {
            Text(
                "将自动保存到应用专属目录（无需存储权限）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                form.outputPath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 时间轴输入：拖拽时用本地状态，松手才提交，避免每帧都重算命令 */
@Composable
fun TrimRangeCard(
    form: ToolForm,
    durationUs: Long,
    onUpdate: ((ToolForm) -> ToolForm) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "截取范围", modifier = modifier) {
        val total = if (durationUs > 0) durationUs.toFloat() else 1f
        var range by remember(form.startUs, form.endUs, total) {
            mutableStateOf(
                form.startUs.toFloat().coerceIn(0f, total)..
                    form.endUs.coerceIn(form.startUs, durationUs).toFloat().coerceIn(0f, total),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("起点", style = MaterialTheme.typography.labelMedium)
                Text(Commands.formatTime(range.start.toLong()), style = MaterialTheme.typography.bodyMedium)
            }
            Column(Modifier.weight(1f)) {
                Text("终点", style = MaterialTheme.typography.labelMedium)
                Text(Commands.formatTime(range.endInclusive.toLong()), style = MaterialTheme.typography.bodyMedium)
            }
            Column(Modifier.weight(1f)) {
                Text("时长", style = MaterialTheme.typography.labelMedium)
                Text(
                    formatDuration((range.endInclusive - range.start).toLong().coerceAtLeast(0)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        RangeSlider(
            value = range,
            onValueChange = { range = it },
            onValueChangeFinished = {
                onUpdate {
                    it.copy(
                        startUs = range.start.toLong(),
                        endUs = range.endInclusive.toLong(),
                    )
                }
            },
            valueRange = 0f..total,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    range = 0f..total
                    onUpdate { it.copy(startUs = 0, endUs = durationUs) }
                },
            ) { Text("整段") }
            OutlinedButton(
                onClick = {
                    val quarter = (durationUs / 4).coerceAtLeast(1)
                    range = 0f..quarter.toFloat()
                    onUpdate { it.copy(startUs = 0, endUs = quarter) }
                },
            ) { Text("前 1/4") }
            OutlinedButton(
                onClick = {
                    val half = durationUs / 2
                    range = half.toFloat()..total
                    onUpdate { it.copy(startUs = half, endUs = durationUs) }
                },
            ) { Text("后 1/2") }
        }
    }
}
