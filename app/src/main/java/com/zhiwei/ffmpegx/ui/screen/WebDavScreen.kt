package com.zhiwei.ffmpegx.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiwei.ffmpegx.core.webdav.WebDavEntry
import com.zhiwei.ffmpegx.ui.components.BottomBarReserve
import com.zhiwei.ffmpegx.ui.components.EmptyState
import com.zhiwei.ffmpegx.ui.components.SectionCard
import com.zhiwei.ffmpegx.ui.components.WarningBanner
import java.io.File

/**
 * WebDAV 文件浏览页。
 *
 * 分两态：
 * - **未连接** → 只显示配置卡片（地址 / 用户名 / 密码 / 连接按钮）；
 * - **已连接** → 面包屑 + 上传/刷新 + 文件列表。
 *
 * 进入已连接态后配置卡片**整个收起**。换服务器/改密码的场景是低频的，
 * 而低频操作占用首屏高度、把最常用的文件列表挤到下面，得不偿失。
 * 想改配置就返回上一层，栈空后自然回到配置态。
 */
@Composable
fun WebDavScreen(viewModel: WebDavViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var pendingDelete by remember { mutableStateOf<WebDavEntry?>(null) }
    var showNewFolderDialog by remember { mutableStateOf(false) }

    // 上传用系统文件选择器。这里**不限 MIME**：WebDAV 上放什么都有意义
    // （字幕、字体、图片素材），限死 media/* 会把这些都挡掉。
    val uploadPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 系统给的是 content:// Uri，而 WebDAV 上传需要一个真实文件。
        // 先落到 cacheDir 再上传：把 WebDavClient 改造成流式接收 InputStream
        // 成本更高（要自己实现 RequestBody 的 contentLength 与重试语义），
        // 而这里的用途是「上传一个本机文件」，先落盘的代价可以接受。
        // 文件名由 ViewModel 自己查（queryDisplayName），无需在这里传。
        viewModel.copyToCacheAndUpload(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.error?.let { message ->
            WarningBanner(title = "操作失败", message = message)
        }
        state.message?.let { InfoBanner(it) }

        if (state.busy) {
            BusyCard(
                label = state.progressLabel.ifBlank { "正在连接…" },
                progress = state.progress,
            )
        }

        if (!state.isConnected) {
            ConnectionCard(
                url = state.urlDraft,
                user = state.userDraft,
                password = state.passwordDraft,
                onUrlChange = viewModel::setUrlDraft,
                onUserChange = viewModel::setUserDraft,
                onPasswordChange = viewModel::setPasswordDraft,
                onConnect = viewModel::connect,
                onSave = viewModel::saveDraft,
                connecting = state.connecting,
            )
        } else {
            ConnectedHeader(
                breadcrumbs = state.breadcrumbs,
                onJump = viewModel::jumpTo,
                onUp = viewModel::navigateUp,
                onRefresh = viewModel::refresh,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { uploadPicker.launch(arrayOf("*/*")) },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.ArrowUpward, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("上传文件到此处")
                }
                OutlinedButton(
                    onClick = { showNewFolderDialog = true },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.CreateNewFolder, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("新建文件夹")
                }
                OutlinedButton(
                    onClick = viewModel::refresh,
                    enabled = !state.busy,
                ) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                }
            }

            if (state.entries.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.Cloud,
                    title = "此目录为空",
                    subtitle = "可以先上传一个文件试试。",
                )
            } else {
                SectionCard(title = "目录内容", subtitle = "${state.entries.size} 项") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        state.entries.forEach { entry ->
                            DavEntryRow(
                                entry = entry,
                                enabled = !state.busy,
                                onOpen = { viewModel.openDirectory(entry) },
                                onDownload = { viewModel.downloadToCache(entry) },
                                onDelete = { pendingDelete = entry },
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        BottomBarReserve()
    }

    pendingDelete?.let { entry ->
        ConfirmDeleteDialog(
            entry = entry,
            onConfirm = {
                viewModel.delete(entry)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    if (showNewFolderDialog) {
        NewFolderDialog(
            onConfirm = { name ->
                viewModel.createDirectory(name)
                showNewFolderDialog = false
            },
            onDismiss = { showNewFolderDialog = false },
        )
    }
}

// ============================================================== 新建文件夹 ====

/**
 * 新建文件夹对话框。
 *
 * 用普通 `AlertDialog` 而不是 `BasicAlertDialog`：后者在 M3 1.2 起才有，
 * 且本项目其余弹窗（如删除确认）都统一用前者，保持一致比「用更新的 API」更重要。
 */
@Composable
private fun NewFolderDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val canConfirm = name.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建文件夹") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "在当前目录下创建。可以输入多级名称，例如 `素材/2026`。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("文件夹名") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = canConfirm) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// ============================================================== 连接配置卡片 ====

@Composable
private fun ConnectionCard(
    url: String,
    user: String,
    password: String,
    onUrlChange: (String) -> Unit,
    onUserChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConnect: () -> Unit,
    onSave: () -> Unit,
    connecting: Boolean,
) {
    var showPassword by remember { mutableStateOf(false) }

    SectionCard(
        title = "WebDAV 服务器",
        subtitle = "填入你自己的 WebDAV 地址，例如 Nextcloud、群晖、坚果云",
    ) {
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            label = { Text("服务器地址") },
            placeholder = { Text("https://dav.example.com/dav/") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = user,
            onValueChange = onUserChange,
            label = { Text("用户名") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = if (showPassword) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showPassword) "隐藏密码" else "显示密码",
                    )
                }
            },
            supportingText = {
                Text(
                    "密码加密后保存在本机，不会明文写入文件。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onConnect,
                enabled = !connecting && url.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                if (connecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("连接中…")
                } else {
                    Icon(Icons.Default.Cloud, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("连接")
                }
            }
            OutlinedButton(onClick = onSave, enabled = !connecting) {
                Text("仅保存")
            }
        }

        Text(
            text = "只支持 https。WebDAV 会在请求头里携带账号密码，" +
                "明文 http 会让凭据直接暴露在网络上。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ================================================================ 已连接头部 ====

@Composable
private fun ConnectedHeader(
    breadcrumbs: List<String>,
    onJump: (Int) -> Unit,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
) {
    SectionCard(title = "当前目录") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(onClick = onUp, enabled = breadcrumbs.size > 1) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "上一层",
                )
            }
            // 面包屑横向滚动而不是折行：路径深的时候折行会把卡片撑得很高，
            // 把下面的文件列表顶出屏幕。
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                breadcrumbs.forEachIndexed { index, segment ->
                    if (index > 0) {
                        Text(
                            "/",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val isLast = index == breadcrumbs.lastIndex
                    Text(
                        text = segment,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isLast) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = !isLast) { onJump(index) }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
        }
    }
}

// ================================================================== 条目行 ====

@Composable
private fun DavEntryRow(
    entry: WebDavEntry,
    enabled: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled && entry.isDirectory, onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (entry.isDirectory) {
                Icons.Default.Folder
            } else {
                Icons.AutoMirrored.Filled.InsertDriveFile
            },
            contentDescription = null,
            tint = if (entry.isDirectory) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 副标题只在有信息时才占位，避免空行把列表撑散
            val meta = listOfNotNull(entry.displaySize, entry.contentType).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (!entry.isDirectory) {
            IconButton(onClick = onDownload, enabled = enabled) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = "下载到本机缓存",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// ================================================================ 小部件 ====

@Composable
private fun BusyCard(label: String, progress: Float) {
    SectionCard(title = label) {
        if (progress >= 0f) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // 不确定进度（连接测试、列目录）用无限循环条
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun InfoBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun ConfirmDeleteDialog(
    entry: WebDavEntry,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entry.isDirectory) "删除远端目录？" else "删除远端文件？") },
        text = {
            Text(
                // 目录要额外提醒「连同里面的内容一起删」—— WebDAV 的 DELETE
                // 是递归的（Depth: infinity），只提示「删除目录」会让人以为只删空壳。
                if (entry.isDirectory) {
                    "「${entry.name}」及其中的全部内容会从服务器上永久删除，无法恢复。"
                } else {
                    "「${entry.name}」会从服务器上永久删除，无法恢复。"
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
