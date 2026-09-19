package com.zhiwei.ffmpegx.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhiwei.ffmpegx.core.hw.DeviceCodecReport
import com.zhiwei.ffmpegx.core.hw.HwStrategy
import com.zhiwei.ffmpegx.core.hw.MediaCodecScanner
import com.zhiwei.ffmpegx.core.settings.AppSettings
import com.zhiwei.ffmpegx.core.settings.SettingsRepository
import com.zhiwei.ffmpegx.core.settings.ThemeMode
import com.zhiwei.ffmpegx.native.FFmpegNative
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 「关于」区块要展示的后端信息。
 *
 * 它跟 [DeviceCodecReport] 是两回事：后者是 MediaCodec 编解码器清单，
 * 不含执行核心的名字与 SAF 能力，所以单独抽一个状态，不要复用 deviceInfo。
 */
data class BackendInfo(
    /** 当前执行的 FFmpeg 核心展示名，例如「ffmpeg-kit-next 9.0.0」 */
    val backendName: String = "",
    /** 是否支持 SAF 直读直写（支持时可省掉缓存中转） */
    val supportsSaf: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val scanner: MediaCodecScanner,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /**
     * WebDAV 设置（供设置页显示当前服务器并清除密码）。
     *
     * 与工具页共享的 [settings] 分开收集：WebDAV 那份要解密（一次 Keystore
     * Binder 往返），不该挂在高频路径上。
     */
    val webDav: StateFlow<com.zhiwei.ffmpegx.core.settings.WebDavSettings> = repository.webDav
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            com.zhiwei.ffmpegx.core.settings.WebDavSettings(),
        )

    val deviceInfo: StateFlow<DeviceCodecReport?> = kotlinx.coroutines.flow.flow {
        emit(withContext(Dispatchers.Default) { scanner.report() })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * 后端信息。FFmpegNative 的初始化发生在 Application.onCreate，
     * 可能晚于本 ViewModel 的构造，所以这里主动触发一次 ensureLoaded()
     * 再取值，避免「关于」区块显示成“未初始化”。
     */
    val backendInfo: StateFlow<BackendInfo> = MutableStateFlow(BackendInfo()).also { flow ->
        viewModelScope.launch {
            withContext(Dispatchers.IO) { FFmpegNative.ensureLoaded() }
            flow.value = BackendInfo(
                backendName = FFmpegNative.backendName,
                supportsSaf = FFmpegNative.supportsSaf,
            )
        }
    }.asStateFlow()

    val nativeLabel: StateFlow<String> = repository.settings.map {
        if (FFmpegNative.isAvailable) FFmpegNative.version() else "未加载"
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "…")

    fun setHwStrategy(value: HwStrategy) = viewModelScope.launch { repository.setHwStrategy(value) }
    fun setThreadCount(value: Int) = viewModelScope.launch { repository.setThreadCount(value) }
    fun setLogLevel(value: Int) = viewModelScope.launch { repository.setLogLevel(value) }
    fun setOverwrite(value: Boolean) = viewModelScope.launch { repository.setOverwrite(value) }
    fun setKeepScreenOn(value: Boolean) = viewModelScope.launch { repository.setKeepScreenOn(value) }
    fun setNotify(value: Boolean) = viewModelScope.launch { repository.setNotify(value) }
    fun setOutputDir(value: String) = viewModelScope.launch { repository.setOutputDir(value) }
    fun setDynamicColor(value: Boolean) = viewModelScope.launch { repository.setDynamicColor(value) }
    fun setThemeMode(value: ThemeMode) = viewModelScope.launch { repository.setThemeMode(value) }
    fun setPreferVulkan(value: Boolean) = viewModelScope.launch { repository.setPreferVulkan(value) }
    fun reset() = viewModelScope.launch { repository.resetAll() }

    /** 只清密码，保留服务器地址与用户名 —— 便于改完密码后直接重连 */
    fun clearWebDavCredentials() = viewModelScope.launch { repository.clearWebDavCredentials() }
}
