package com.zhiwei.ffmpegx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhiwei.ffmpegx.core.hw.DecoderPlan
import com.zhiwei.ffmpegx.core.hw.EncoderPlan
import com.zhiwei.ffmpegx.core.hw.HardwarePlan
import com.zhiwei.ffmpegx.core.model.formatDuration
import com.zhiwei.ffmpegx.core.model.formatSize
import com.zhiwei.ffmpegx.core.task.TaskEntity
import com.zhiwei.ffmpegx.core.task.TaskStatus
import com.zhiwei.ffmpegx.ui.theme.MonospaceStyle

// ================================================================ 布局基础件 ====

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScopeAlias.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                trailing?.invoke()
            }
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }
}

/** 只是为了让 SectionCard 的内容 lambda 有明确的接收者类型，避免误用 ColumnScope */
typealias ColumnScopeAlias = androidx.compose.foundation.layout.ColumnScope

@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun KeyValueChip(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}

// ================================================================ 选项控件 ====

/**
 * 横向可滚动的单选 chip 组。选项少于 8 个、标签短的时候优先用它，比下拉菜单少一次点击。
 */
@Composable
fun <T> ChoiceChips(
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: (T) -> Boolean = { true },
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 0.dp),
    ) {
        items(options.size) { index ->
            val option = options[index]
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                enabled = enabled(option),
                label = { Text(labelOf(option), maxLines = 1) },
                leadingIcon = if (option == selected) {
                    { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
fun <T> DropdownField(
    label: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    labelOf(selected),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Default.ExpandMore, contentDescription = null)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(labelOf(option)) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                    )
                }
            }
        }
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

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    valueLabel: (Float) -> String = { it.toString() },
    supporting: String? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(valueLabel(value), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
        if (supporting != null) {
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ================================================================ 结果展示 ====

@Composable
fun CommandPreviewCard(
    commands: List<List<String>>,
    modifier: Modifier = Modifier,
    onCopy: (() -> Unit)? = null,
) {
    if (commands.isEmpty()) return
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (commands.size > 1) "即将执行 ${commands.size} 条命令" else "即将执行的命令",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                if (onCopy != null) {
                    TextButton(onClick = onCopy) { Text("复制") }
                }
            }
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                commands.forEachIndexed { index, cmd ->
                    if (commands.size > 1) {
                        Text(
                            "第 ${index + 1} 遍",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = cmd.joinToString(" ") { token ->
                            if (token.any { it.isWhitespace() }) "'$token'" else token
                        },
                        style = MonospaceStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun HardwarePlanCard(plan: HardwarePlan?, modifier: Modifier = Modifier) {
    if (plan == null) return
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "硬件加速方案",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                AssistChip(
                    onClick = {},
                    label = { Text(plan.badge) },
                    colors = AssistChipDefaults.assistChipColors(
                        labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }

            val decoderName = when (val d = plan.decoder) {
                is DecoderPlan.MediaCodec -> "${d.label}（${d.codecName.substringAfterLast('.')}）"
                else -> d.label
            }
            val encoderName = when (val e = plan.encoder) {
                is EncoderPlan.MediaCodec -> "${e.label}（${e.codecName.substringAfterLast('.')}）"
                else -> e.label
            }
            InfoRow("解码", decoderName)
            InfoRow("编码", encoderName)

            plan.reasons.forEach { reason ->
                Text(
                    "· $reason",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            plan.warnings.forEach { warning ->
                Row {
                    Icon(
                        Icons.Default.WarningAmber,
                        null,
                        Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

@Composable
fun RunningTaskCard(
    task: TaskEntity?,
    percent: Int,
    speedLabel: String,
    remainingLabel: String?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (task == null) return
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("正在处理", style = MaterialTheme.typography.labelMedium)
                    Text(
                        task.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onCancel) { Text("取消") }
            }
            Spacer(Modifier.height(10.dp))
            if (percent < 0) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (percent < 0) "处理中" else "$percent%", style = MaterialTheme.typography.bodySmall)
                Text(speedLabel, style = MaterialTheme.typography.bodySmall)
                if (remainingLabel != null) {
                    Text("剩余 $remainingLabel", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun TaskRow(
    task: TaskEntity,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val statusColor = when (task.statusEnum) {
        TaskStatus.SUCCESS -> MaterialTheme.colorScheme.secondary
        TaskStatus.FAILED -> MaterialTheme.colorScheme.error
        TaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = { onClick?.invoke() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        task.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        task.featureEnum.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    task.statusEnum.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor,
                )
            }

            if (task.statusEnum == TaskStatus.RUNNING) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { task.progressPercent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            task.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
                Spacer(Modifier.height(6.dp))
                Row {
                    Icon(
                        Icons.Default.ErrorOutline,
                        null,
                        Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildString {
                        if (task.elapsedMs > 0) append("耗时 ${task.elapsedMs / 1000.0}s")
                        if (task.outputBytes > 0) {
                            if (isNotEmpty()) append("  ·  ")
                            append(formatSize(task.outputBytes))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (onRetry != null && task.isFinished) {
                    TextButton(onClick = onRetry) { Text("重跑") }
                }
                if (onDelete != null && task.isFinished) {
                    TextButton(onClick = onDelete) { Text("删除") }
                }
            }
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
fun WarningBanner(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(Modifier.padding(14.dp)) {
            Icon(
                Icons.Default.WarningAmber,
                null,
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
fun MediaSummaryRow(
    durationUs: Long,
    sizeBytes: Long,
    resolution: String,
    codec: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        KeyValueChip("时长", formatDuration(durationUs), Modifier.weight(1f))
        KeyValueChip("大小", formatSize(sizeBytes), Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        KeyValueChip("分辨率", resolution, Modifier.weight(1f))
        KeyValueChip("编码", codec, Modifier.weight(1f))
    }
}

/** 让背景色块在浅色/深色主题下都保持可读 */
@Composable
fun TintedBox(color: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier.background(color, RoundedCornerShape(8.dp)),
    ) { content() }
}

// ============================================================ 路径显示工具 ====

/**
 * 从 ffmpeg 输入串里取一个适合展示给用户的名字。
 *
 * 不能直接用 `substringAfterLast('/')`：走 SAF 直读时输入是 `ffkitsaf:3.mp4`
 * 这类虚拟协议串，里面根本没有 `/`，直接截取会把整个内部串原样显示出来，
 * 用户看到会以为出了错。
 */
fun displayNameFromInput(input: String): String {
    if (input.isBlank()) return ""
    if (input.startsWith("ffkit", ignoreCase = true)) return "已选择的文件"
    return input.substringAfterLast('/').substringAfterLast('\\')
}

/** 输入来源的一句话说明，用于替代原始路径副标题 */
fun inputSourceLabel(input: String, temporary: Boolean = false): String = when {
    input.isBlank() -> ""
    temporary -> "已复制到应用缓存"
    input.startsWith("ffkitsaf:", ignoreCase = true) -> "直接读取所选文件（零拷贝）"
    input.startsWith("ffkitmem:", ignoreCase = true) -> "来自内存数据"
    input.startsWith("ffkitstream:", ignoreCase = true) -> "来自数据流"
    else -> input
}
