package com.zhiwei.ffmpegx.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zhiwei.ffmpegx.core.hw.HwStrategy
import com.zhiwei.ffmpegx.core.model.CompressPreset
import com.zhiwei.ffmpegx.native.AvLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 主题模式 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

/**
 * 全局设置。这些值会影响每一条生成的 FFmpeg 命令。
 */
data class AppSettings(
    val hwStrategy: HwStrategy = HwStrategy.AUTO,
    /** 0 表示交给 FFmpeg 按 CPU 核心数自动决定 */
    val threadCount: Int = 0,
    val logLevel: Int = AvLog.INFO,
    val overwriteOutput: Boolean = true,
    val keepScreenOn: Boolean = true,
    val notifyOnFinish: Boolean = true,
    /** 空串表示使用应用私有目录 Movies/FFmpegX */
    val outputDir: String = "",
    val dynamicColor: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val preferVulkan: Boolean = false,
    val lastCompressPreset: CompressPreset = CompressPreset.WECHAT,
    val useHardwareByDefault: Boolean = true,
) {
    /** 拼进命令行的 -loglevel 取值 */
    val logLevelArg: String
        get() = when {
            logLevel <= AvLog.QUIET -> "quiet"
            logLevel <= AvLog.PANIC -> "panic"
            logLevel <= AvLog.FATAL -> "fatal"
            logLevel <= AvLog.ERROR -> "error"
            logLevel <= AvLog.WARNING -> "warning"
            logLevel <= AvLog.INFO -> "info"
            logLevel <= AvLog.VERBOSE -> "verbose"
            logLevel <= AvLog.DEBUG -> "debug"
            else -> "trace"
        }

    companion object {
        val LOG_LEVEL_CHOICES = listOf(
            AvLog.ERROR to "仅错误",
            AvLog.WARNING to "警告",
            AvLog.INFO to "标准（推荐）",
            AvLog.VERBOSE to "详细",
            AvLog.DEBUG to "调试",
        )
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ffmpegx_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val HW_STRATEGY = stringPreferencesKey("hw_strategy")
        val THREADS = intPreferencesKey("threads")
        val LOG_LEVEL = intPreferencesKey("log_level")
        val OVERWRITE = booleanPreferencesKey("overwrite")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val NOTIFY = booleanPreferencesKey("notify")
        val OUTPUT_DIR = stringPreferencesKey("output_dir")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val PREFER_VULKAN = booleanPreferencesKey("prefer_vulkan")
        val LAST_PRESET = stringPreferencesKey("last_preset")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            hwStrategy = prefs[Keys.HW_STRATEGY]?.let { name ->
                runCatching { HwStrategy.valueOf(name) }.getOrNull()
            } ?: HwStrategy.AUTO,
            threadCount = prefs[Keys.THREADS] ?: 0,
            logLevel = prefs[Keys.LOG_LEVEL] ?: AvLog.INFO,
            overwriteOutput = prefs[Keys.OVERWRITE] ?: true,
            keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: true,
            notifyOnFinish = prefs[Keys.NOTIFY] ?: true,
            outputDir = prefs[Keys.OUTPUT_DIR].orEmpty(),
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
            themeMode = prefs[Keys.THEME_MODE]?.let { name ->
                runCatching { ThemeMode.valueOf(name) }.getOrNull()
            } ?: ThemeMode.SYSTEM,
            preferVulkan = prefs[Keys.PREFER_VULKAN] ?: false,
            lastCompressPreset = prefs[Keys.LAST_PRESET]?.let { name ->
                runCatching { CompressPreset.valueOf(name) }.getOrNull()
            } ?: CompressPreset.WECHAT,
        )
    }

    suspend fun setHwStrategy(value: HwStrategy) = edit { it[Keys.HW_STRATEGY] = value.name }
    suspend fun setThreadCount(value: Int) = edit { it[Keys.THREADS] = value }
    suspend fun setLogLevel(value: Int) = edit { it[Keys.LOG_LEVEL] = value }
    suspend fun setOverwrite(value: Boolean) = edit { it[Keys.OVERWRITE] = value }
    suspend fun setKeepScreenOn(value: Boolean) = edit { it[Keys.KEEP_SCREEN_ON] = value }
    suspend fun setNotify(value: Boolean) = edit { it[Keys.NOTIFY] = value }
    suspend fun setOutputDir(value: String) = edit { it[Keys.OUTPUT_DIR] = value }
    suspend fun setDynamicColor(value: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = value }
    suspend fun setThemeMode(value: ThemeMode) = edit { it[Keys.THEME_MODE] = value.name }
    suspend fun setPreferVulkan(value: Boolean) = edit { it[Keys.PREFER_VULKAN] = value }
    suspend fun setLastCompressPreset(value: CompressPreset) = edit { it[Keys.LAST_PRESET] = value.name }

    suspend fun resetAll() {
        context.dataStore.edit { it.clear() }
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
