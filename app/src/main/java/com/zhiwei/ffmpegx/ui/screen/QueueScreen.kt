package com.zhiwei.ffmpegx.ui.screen

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiwei.ffmpegx.core.media.MediaFiles
import com.zhiwei.ffmpegx.core.task.TaskStatus
import com.zhiwei.ffmpegx.ui.components.ChoiceChips
import com.zhiwei.ffmpegx.ui.components.EmptyState
import com.zhiwei.ffmpegx.ui.components.RunningTaskCard
import com.zhiwei.ffmpegx.ui.components.TaskRow

private enum class QueueFilter(val label: String) {
    ALL("全部"),
    ACTIVE("进行中"),
    DONE("已完成"),
    FAILED("失败"),
}

@Composable
fun QueueScreen(viewModel: TasksViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val tasks by viewModel.allTasks.collectAsStateWithLifecycle()
    val current by viewModel.current.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(QueueFilter.ALL) }

    val filtered = remember(tasks, filter) {
        when (filter) {
            QueueFilter.ALL -> tasks
            QueueFilter.ACTIVE -> tasks.filter {
                it.statusEnum == TaskStatus.PENDING || it.statusEnum == TaskStatus.RUNNING
            }
            QueueFilter.DONE -> tasks.filter { it.statusEnum == TaskStatus.SUCCESS }
            QueueFilter.FAILED -> tasks.filter {
                it.statusEnum == TaskStatus.FAILED || it.statusEnum == TaskStatus.CANCELLED
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            current?.let { task ->
                RunningTaskCard(
                    task = task,
                    percent = if (progress.percent >= 0) progress.percent.toInt() else task.progressPercent,
                    speedLabel = progress.speedLabel,
                    remainingLabel = progress.remainingMs.takeIf { it > 0 }?.let { "${it / 1000}s" },
                    onCancel = { viewModel.cancelCurrent() },
                )
                Spacer(Modifier.height(12.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "共 ${filtered.size} 条",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.clearFinished() }) {
                    Icon(Icons.Default.DeleteSweep, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("清理已完成")
                }
            }
            Spacer(Modifier.height(4.dp))
            ChoiceChips(
                options = QueueFilter.entries.toList(),
                selected = filter,
                labelOf = { it.label },
                onSelect = { filter = it },
            )
        }

        if (filtered.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Inbox,
                title = "队列是空的",
                subtitle = "回到首页选一个功能开始处理",
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(filtered, key = { it.id }) { task ->
                    TaskRow(
                        task = task,
                        onOpen = {
                            if (!MediaFiles.openOutput(context, task.outputPath)) {
                                Toast.makeText(
                                    context,
                                    "没有能打开该文件的应用",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        onRetry = { viewModel.retry(task.id) },
                        onDelete = { viewModel.remove(task.id) },
                    )
                }
            }
        }
    }
}

@Composable
fun ConsoleScreen(viewModel: TasksViewModel = hiltViewModel()) {
    val lines by viewModel.consoleLines.collectAsStateWithLifecycle()
    val current by viewModel.current.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                current?.title ?: "空闲中",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            TextButton(onClick = { viewModel.clearConsole() }) { Text("清空") }
        }

        if (lines.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Inbox,
                title = "暂无输出",
                subtitle = "执行任务后，FFmpeg 的完整日志会实时显示在这里",
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(lines) { line ->
                    Text(
                        line,
                        style = com.zhiwei.ffmpegx.ui.theme.MonospaceStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
