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
            val resolved = FileResolver.resolve(context, uri)
            resolved.fold(
                onSuccess = { r ->
                    // ffprobe 需要可 seek 的真实路径。若走的是 ffkitsaf 直读（没有落地文件），
                    // 就先落一份临时副本供探测用，探测完即删 —— 转码本身仍然走零拷贝路径。
                    val probeTarget = r.realPath
                    if (probeTarget == null) {
                        probeViaCache(uri, r.displayName)
                        return@fold
                    }
                    runProbe(probeTarget, r.displayName)
                },
                onFailure = { t ->
                    _probe.update { it.copy(busy = false, error = "读取文件失败：${t.message}") }
                },
            )
        }
    }

    /** 没有真实路径时（ffkitsaf 直读），复制一份副本给 ffprobe 用，探测完即删 */
    private suspend fun probeViaCache(uri: Uri, displayName: String) {
        val dir = MediaFiles.workDir(context).let { File(it, "probe").apply { mkdirs() } }
        val target = File(dir, "probe_${System.currentTimeMillis()}_$displayName")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            } ?: error("无法读取所选文件（可能没有授权）")
            runProbe(target.absolutePath, displayName)
        } catch (t: Throwable) {
            _probe.update { it.copy(busy = false, error = "解析失败：${t.message}") }
        } finally {
            // 探测用完立即删除，不占空间
            runCatching { target.delete() }
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
                _probe.update { it.copy(busy = false, error = "解析失败：${t.message}") }
            },
        )
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
