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
    val ffmpegReady: Boolean = false,
    val ffmpegVersion: String = "unknown",
    val ffmpegBuildInfo: String = "",
    val ffmpegError: String = "",
    /** 当前使用的 FFmpeg 核心（ffmpeg-kit-next） */
    val backendName: String = "",
    val backendId: String = "",
    /** 是否支持 SAF 直读直写（省掉缓存中转） */
    val supportsSaf: Boolean = false,
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

    /**
     * 当前用于探测的输入。持有它是为了释放 ffkitsaf: url ——
     * 这类 url 是 reusable 的，上游不会自动回收。
     */
    private var probeInput: FileResolver.Resolved? = null

    override fun onCleared() {
        super.onCleared()
        probeInput?.let { FileResolver.cleanup(it) }
        probeInput = null
    }

    init {
        viewModelScope.launch {
            val ready = FFmpegNative.ensureLoaded()
            _device.update {
                it.copy(
                    ffmpegReady = ready,
                    ffmpegVersion = FFmpegNative.version(),
                    ffmpegBuildInfo = FFmpegNative.buildInfo(),
                    ffmpegError = if (ready) "" else FFmpegNative.loadError(),
                    backendName = FFmpegNative.backendName,
                    backendId = FFmpegNative.backendId,
                    supportsSaf = FFmpegNative.supportsSaf,
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
            // 上一个素材若占用着 SAF url，先释放
            probeInput?.let { FileResolver.cleanup(it) }
            probeInput = null

            val resolved = FileResolver.resolve(context, uri)
            resolved.fold(
                onSuccess = { r ->
                    // 直接用 ffmpegInput 探测。
                    //
                    // 之前这里判断 r.realPath 为 null 就走 probeViaCache，把整个文件
                    // 复制一份到缓存再探测 —— 只是看个分辨率/时长，却要等一个完整拷贝，
                    // 大文件下几乎等同于卡死。而 ffkitsaf: 这类虚拟协议本来就能直接
                    // 交给 ffprobe（上游文档：url that can be passed to FFprobeKit），
                    // 完全不需要落地。
                    probeInput = r
                    runProbe(r.ffmpegInput, r.displayName)
                },
                onFailure = { t ->
                    _probe.update { it.copy(busy = false, error = "读取文件失败：${t.message}") }
                },
            )
        }
    }

    private suspend fun runProbe(path: String, displayName: String) {
        ffprobe.probe(path).fold(
            onSuccess = { info ->
                _probe.update {
                    it.copy(busy = false, info = info, rawJson = info.rawJson, fileName = displayName)
                }
            },
            onFailure = { t ->
                // 探测失败就没什么可导出的了，顺手把 url 还回去
                probeInput?.let { FileResolver.cleanup(it) }
                probeInput = null
                _probe.update { it.copy(busy = false, error = "解析失败：${t.message}") }
            },
        )
    }

    /** 把当前素材导出到系统媒体库，方便在相册里看到 */
    fun exportToGallery() {
        val info = _probe.value.info ?: return
        viewModelScope.launch {
            _probe.update { it.copy(busy = true) }
            // 走 SAF 直读时 info.path 是 ffkitsaf:3.mp4 这类虚拟协议串，
            // File() 解不出真实内容，导出必然失败 —— 这种情况下直接告诉用户原因，
            // 而不是抛一个「文件不存在」让人摸不着头脑。
            val source = File(info.path)
            if (!source.exists()) {
                _probe.update {
                    it.copy(
                        busy = false,
                        error = "该素材是通过系统授权直接读取的，没有可导出的本地副本。" +
                            "如需导出，请先把它保存到本机后再操作。",
                    )
                }
                return@launch
            }
            val uri = MediaFiles.publishToMediaStore(context, source, source.extension.ifBlank { "mp4" })
            _probe.update {
                it.copy(
                    busy = false,
                    exportedUri = uri?.toString(),
                    error = if (uri == null) "导出失败，请检查文件是否仍然存在" else null,
                )
            }
        }
    }

    fun clearProbe() {
        probeInput?.let { FileResolver.cleanup(it) }
        probeInput = null
        _probe.update { ProbeUiState() }
    }
}
