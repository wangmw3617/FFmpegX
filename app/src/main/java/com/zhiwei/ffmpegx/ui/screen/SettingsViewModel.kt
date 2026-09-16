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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val scanner: MediaCodecScanner,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val deviceInfo: StateFlow<DeviceCodecReport?> = kotlinx.coroutines.flow.flow {
        emit(withContext(Dispatchers.Default) { scanner.report() })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

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
}
