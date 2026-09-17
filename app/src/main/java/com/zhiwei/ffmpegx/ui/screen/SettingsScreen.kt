package com.zhiwei.ffmpegx.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiwei.ffmpegx.BuildConfig
import com.zhiwei.ffmpegx.core.hw.HwStrategy
import com.zhiwei.ffmpegx.core.hw.VideoCodec
import com.zhiwei.ffmpegx.core.settings.AppSettings
import com.zhiwei.ffmpegx.core.settings.ThemeMode
import com.zhiwei.ffmpegx.ui.components.ChoiceChips
import com.zhiwei.ffmpegx.ui.components.DropdownField
import com.zhiwei.ffmpegx.ui.components.InfoRow
import com.zhiwei.ffmpegx.ui.components.SectionCard

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val device by viewModel.deviceInfo.collectAsStateWithLifecycle()
    val nativeLabel by viewModel.nativeLabel.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---------------------------------------------------------- 硬件加速 ----
        SectionCard(title = "硬件加速", subtitle = "决定每次转码用硬编还是软编") {
            DropdownField(
                label = "策略",
                options = HwStrategy.entries.toList(),
                selected = settings.hwStrategy,
                labelOf = { it.label },
                onSelect = { viewModel.setHwStrategy(it) },
                supporting = settings.hwStrategy.description,
            )

            val report = device
            if (report != null) {
                InfoRow("芯片", report.soc.displayName)
                val hwEncode = VideoCodec.entries
                    .filter { report.hardwareEncoders(it).isNotEmpty() }
                    .joinToString("/") { it.shortLabel }
                    .ifBlank { "无" }
                InfoRow("可用硬编", hwEncode)
                InfoRow("硬解数量", "${report.videoDecoders.count { it.isHardware }} 个")
            }

            SwitchRow(
                title = "启用 Vulkan 滤镜链",
                subtitle = "把缩放、格式转换等处理交给 GPU，可降低 CPU 占用。" +
                    "仅在部分设备上生效，未生效时会自动回退到 CPU 处理。",
                checked = settings.preferVulkan,
                onCheckedChange = { viewModel.setPreferVulkan(it) },
            )
        }

        // -------------------------------------------------------------- 性能 ----
        SectionCard(title = "性能") {
            DropdownField(
                label = "编码线程数",
                options = listOf(0, 2, 4, 6, 8, 12, 16),
                selected = settings.threadCount,
                labelOf = { if (it == 0) "自动（按 CPU 核心数）" else "$it 线程" },
                onSelect = { viewModel.setThreadCount(it) },
                supporting = "硬件编码器不使用这个参数，只有软编才会生效",
            )
            DropdownField(
                label = "日志详细程度",
                options = AppSettings.LOG_LEVEL_CHOICES.map { it.first },
                selected = settings.logLevel,
                labelOf = { level ->
                    AppSettings.LOG_LEVEL_CHOICES.firstOrNull { it.first == level }?.second ?: "标准"
                },
                onSelect = { viewModel.setLogLevel(it) },
                supporting = "更详细的日志会占用更多空间，一般保持默认即可",
            )
            SwitchRow(
                title = "转码时保持亮屏",
                subtitle = "长任务期间阻止屏幕熄灭",
                checked = settings.keepScreenOn,
                onCheckedChange = { viewModel.setKeepScreenOn(it) },
            )
        }

        // -------------------------------------------------------------- 输出 ----
        SectionCard(title = "输出") {
            OutlinedTextField(
                value = settings.outputDir,
                onValueChange = { viewModel.setOutputDir(it) },
                label = { Text("默认输出目录（留空则保存到 Download/FFmpegX）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow(
                title = "自动覆盖同名文件",
                subtitle = "关闭后会跳过已存在的文件，而不是覆盖",
                checked = settings.overwriteOutput,
                onCheckedChange = { viewModel.setOverwrite(it) },
            )
            SwitchRow(
                title = "完成后通知",
                subtitle = "任务结束时在通知栏提示",
                checked = settings.notifyOnFinish,
                onCheckedChange = { viewModel.setNotify(it) },
            )
        }

        // -------------------------------------------------------------- 外观 ----
        SectionCard(title = "外观") {
            DropdownField(
                label = "主题",
                options = ThemeMode.entries.toList(),
                selected = settings.themeMode,
                labelOf = { it.label },
                onSelect = { viewModel.setThemeMode(it) },
            )
            SwitchRow(
                title = "动态取色（Material You）",
                subtitle = "跟随壁纸取色，需要 Android 12 及以上",
                checked = settings.dynamicColor,
                onCheckedChange = { viewModel.setDynamicColor(it) },
            )
        }

        // -------------------------------------------------------------- 关于 ----
        SectionCard(title = "关于") {
            InfoRow("应用版本", BuildConfig.VERSION_NAME)
            InfoRow("转码引擎版本", nativeLabel)
            Text(
                "所有处理都在本机完成，不上传任何文件，也不需要联网。" +
                    "处理时直接读取你选择的文件，不会额外复制一份到缓存。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                TextButton(onClick = { viewModel.reset() }) { Text("恢复默认设置") }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
