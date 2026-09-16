package com.zhiwei.ffmpegx.ui.screen

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhiwei.ffmpegx.core.engine.FFprobeEngine
import com.zhiwei.ffmpegx.core.hw.DeviceCodecReport
import com.zhiwei.ffmpegx.core.hw.MediaCodecScanner
import com.zhiwei.ffmpegx.core.media.FileResolver
import com.zhiwei.ffmpegx.core.media.MediaFiles
import com.zhiwei.ffmpegx.core.model.MediaInfo
import com.zhiwei.ffmpegx.native.FFmpegNative
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class DeviceUiState(
    val report: DeviceCodecReport? = null,
    val nativeReady: Boolean = false,
    val nativeVersion: String = "unknown",
    val nativeError: String = "",
    /** 当前使用的 FFmpeg 后端（kit 预编译包 / 自研 JNI） */
    val backendName: String = "",
    val backendId: String = "",
    val scanning: Boolean = false,
)

data class ProbeUiState(
    val info: MediaInfo? = null,
    val rawJson: String = "",
    val fileName: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val exportedUri: String? = null,
)

/**
 * 设备能力 + 媒体信息查询。这两块都不依赖 FFmpeg 执行会话，可以独立工作。
 */
@HiltViewModel
class DeviceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanner: MediaCodecScanner,
    private val ffprobe: FFprobeEngine,
) : ViewModel() {

    private val _device = MutableStateFlow(DeviceUiState())
    val device: StateFlow<DeviceUiState> = _device.asStateFlow()

    private val _probe = MutableStateFlow(ProbeUiState())
    val probe: StateFlow<ProbeUiState> = _probe.asStateFlow()

    init {
        viewModelScope.launch {
            val ready = FFmpegNative.ensureLoaded()
            _device.update {
                it.copy(
                    nativeReady = ready,
                    nativeVersion = FFmpegNative.version(),
                    nativeError = if (ready) "" else FFmpegNative.loadError(),
                    backendName = FFmpegNative.backendName,
                    backendId = FFmpegNative.backendId,
                )
            }
            scan()
        }
    }

    /** MediaCodec 枚举有点耗时（几十到几百毫秒），放 IO 线程 */
    fun scan() {
        viewModelScope.launch {
            _device.update { it.copy(scanning = true) }
            val report = withContext(Dispatchers.Default) { scanner.report(forceRefresh = true) }
            _device.update { it.copy(report = report, scanning = false) }
        }
    }

    fun onPickProbeFile(uri: Uri) {
        viewModelScope.launch {
            _probe.update { it.copy(busy = true, error = null, exportedUri = null) }
            val resolved = FileResolver.resolve(context, uri)
            resolved.fold(
                onSuccess = { r ->
                    val result = ffprobe.probe(r.file.absolutePath)
                    result.fold(
                        onSuccess = { info ->
                            _probe.update {
                                it.copy(
                                    busy = false,
                                    info = info,
                                    rawJson = info.rawJson,
                                    fileName = r.displayName,
                                )
                            }
                        },
                        onFailure = { t ->
                            _probe.update { it.copy(busy = false, error = "解析失败：${t.message}") }
                        },
                    )
                },
                onFailure = { t ->
                    _probe.update { it.copy(busy = false, error = "读取文件失败：${t.message}") }
                },
            )
        }
    }

    /** 把当前素材导出到系统媒体库，方便在相册里看到 */
    fun exportToGallery() {
        val info = _probe.value.info ?: return
        viewModelScope.launch {
            _probe.update { it.copy(busy = true) }
            val file = File(info.path)
            val uri = MediaFiles.publishToMediaStore(context, file, file.extension.ifBlank { "mp4" })
            _probe.update {
                it.copy(
                    busy = false,
                    exportedUri = uri?.toString(),
                    error = if (uri == null) "导出失败，请检查文件是否仍然存在" else null,
                )
            }
        }
    }

    fun clearProbe() = _probe.update { ProbeUiState() }
}
