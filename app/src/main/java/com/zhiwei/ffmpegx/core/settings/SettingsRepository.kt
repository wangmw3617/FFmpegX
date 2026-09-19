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

/**
 * WebDAV 连接设置。
 *
 * 与 [AppSettings] 分开（原因见 `SettingsRepository.webDav` 的注释）。
 * [password] 在这里是**明文**——它只在内存中短暂存在，落盘的是密文。
 * 界面层拿到后只应把它填进密码框、拼进请求头，**不要写日志、不要放进
 * `data class` 的 `toString()` 输出里**。为此重写了 `toString()`。
 */
data class WebDavSettings(
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    /** 上次使用的远端目录，作为上传对话框的默认值 */
    val lastRemoteDir: String = "",
) {
    /** 三个必填项是否都齐了 */
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && username.isNotBlank() && password.isNotEmpty()

    /**
     * 覆盖 toString 把密码藏掉。
     *
     * data class 默认生成的 toString 会原样打印每个字段，一旦有人
     * `Log.d(TAG, "$settings")` 或把它带进异常信息，密码就直接躺在 logcat 里了。
     * 这在开源项目里尤其危险——用户贴日志求助时不会注意到这一点。
     */
    override fun toString(): String =
        "WebDavSettings(baseUrl=$baseUrl, username=$username, password=***, " +
            "lastRemoteDir=$lastRemoteDir)"
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

        // ---- WebDAV ----
        val DAV_URL = stringPreferencesKey("dav_url")
        val DAV_USER = stringPreferencesKey("dav_user")
        /**
         * 密码的**密文**（Base64 后的 AES-GCM）。
         *
         * ⚠️ 为什么不存明文：DataStore 落在应用私有目录 `/data/data/<pkg>/files/`，
         * root 设备、以及本项目开着 `allowBackup` 时的云备份都可能把它带走。
         * 这里先加密再存，密钥放在 Android Keystore 里 —— Keystore 的密钥
         * **不参与备份**，所以备份被拿走也解不开。
         *
         * ⚠️ 这不是「绝对安全」：同设备上有 root 的进程仍可借 Keystore 解密
         * （只是不能导出密钥）。它防的是「备份泄漏」和「随手翻文件看到密码」
         * 这两类最常见的问题，不是防本地高权限攻击者。
         */
        val DAV_SECRET = stringPreferencesKey("dav_secret")

        /** 上次上传到的远端目录，下次默认填它 */
        val DAV_LAST_DIR = stringPreferencesKey("dav_last_dir")
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

    /**
     * WebDAV 账户设置。
     *
     * 单独一个 Flow 而不是并进 [AppSettings]，理由是**解密是有代价的**：
     * `settings` 被工具页的 ViewModel 每个实例都收集、每次改参数都会走一遍，
     * 而 Keystore 解密要跨进程调用（几百微秒到几毫秒）。把它塞进那个高频流里，
     * 就是给「拖动滑块」这条路径平白加一次 Binder 往返。
     * 只有 WebDAV 页面才收集这个 Flow，代价就只落在需要它的地方。
     */
    val webDav: Flow<WebDavSettings> = context.dataStore.data.map { prefs ->
        WebDavSettings(
            baseUrl = prefs[Keys.DAV_URL].orEmpty(),
            username = prefs[Keys.DAV_USER].orEmpty(),
            password = WebDavCredentialCipher.decrypt(prefs[Keys.DAV_SECRET].orEmpty()),
            lastRemoteDir = prefs[Keys.DAV_LAST_DIR].orEmpty(),
        )
    }

    suspend fun setWebDav(baseUrl: String, username: String, password: String) {
        val encrypted = WebDavCredentialCipher.encrypt(password)
        context.dataStore.edit { prefs ->
            prefs[Keys.DAV_URL] = baseUrl.trim()
            prefs[Keys.DAV_USER] = username.trim()
            if (encrypted != null) {
                prefs[Keys.DAV_SECRET] = encrypted
            } else if (password.isEmpty()) {
                // 用户清空了密码 → 把密文一并清掉，不要留着一个解不开的旧值
                prefs.remove(Keys.DAV_SECRET)
            }
            // encrypted == null 且 password 非空：本机无法加密，保留原有密文不动，
            // 并让调用方通过返回值/设置页提示「密码未能保存」。
        }
    }

    suspend fun setWebDavLastDir(dir: String) =
        edit { it[Keys.DAV_LAST_DIR] = dir.trim() }

    /** 清除 WebDAV 凭据（保留服务器地址与用户名，便于重新登录） */
    suspend fun clearWebDavCredentials() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.DAV_SECRET)
        }
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
