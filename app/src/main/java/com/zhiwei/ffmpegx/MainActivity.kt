package com.zhiwei.ffmpegx

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.zhiwei.ffmpegx.core.settings.AppSettings
import com.zhiwei.ffmpegx.core.settings.SettingsRepository
import com.zhiwei.ffmpegx.core.task.TaskRepository
import com.zhiwei.ffmpegx.ui.AppRoot
import com.zhiwei.ffmpegx.ui.SharedInput
import com.zhiwei.ffmpegx.ui.theme.FFmpegXTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var taskRepository: TaskRepository

    /** 主题相关设置需要在外层（setContent 之前）拿到，用一个轻量 StateFlow 桥接 */
    private val settingsState = MutableStateFlow(AppSettings())

    override fun onCreate(savedInstanceState: Bundle?) {
        // installSplashScreen 必须在 super.onCreate 之前调用
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleShareIntent(intent)

        lifecycleScope.launch {
            settingsRepository.settings.collect { settingsState.value = it }
        }

        // 任务执行期间按用户设置保持亮屏：转码动辄几分钟，熄屏会拖慢甚至中断
        lifecycleScope.launch {
            combine(taskRepository.current, settingsRepository.settings) { task, settings ->
                task != null && settings.keepScreenOn
            }
                .distinctUntilChanged()
                .collect { keepOn ->
                    if (keepOn) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
        }

        setContent {
            val settings by settingsState.collectAsStateWithLifecycle()
            FFmpegXTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
            ) {
                AppRoot(settings = settings)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        SharedInput.extract(intent)?.let { uri ->
            // 拿到一次性读取权限，后续复制/解析才有权限访问
            runCatching {
                grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            SharedInput.post(uri)
        }
    }
}
