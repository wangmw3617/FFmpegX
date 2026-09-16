package com.zhiwei.ffmpegx.ui.tool

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhiwei.ffmpegx.core.cmd.AudioEncodeSpec
import com.zhiwei.ffmpegx.core.cmd.AudioSpec
import com.zhiwei.ffmpegx.core.cmd.Commands
import com.zhiwei.ffmpegx.core.cmd.CompressSpec
import com.zhiwei.ffmpegx.core.cmd.ConcatSpec
import com.zhiwei.ffmpegx.core.cmd.GifSpec
import com.zhiwei.ffmpegx.core.cmd.InputSpec
import com.zhiwei.ffmpegx.core.cmd.OutputSpec
import com.zhiwei.ffmpegx.core.cmd.OverlayMode
import com.zhiwei.ffmpegx.core.cmd.OverlayPosition
import com.zhiwei.ffmpegx.core.cmd.OverlaySpec
import com.zhiwei.ffmpegx.core.cmd.RateMode
import com.zhiwei.ffmpegx.core.cmd.SubtitleMode
import com.zhiwei.ffmpegx.core.cmd.SubtitleSpec
import com.zhiwei.ffmpegx.core.cmd.TrimSpec
import com.zhiwei.ffmpegx.core.cmd.VideoEncodeSpec
import com.zhiwei.ffmpegx.core.engine.FFprobeEngine
import com.zhiwei.ffmpegx.core.hw.DecoderPlan
import com.zhiwei.ffmpegx.core.hw.EncoderPlan
import com.zhiwei.ffmpegx.core.hw.HardwarePlan
import com.zhiwei.ffmpegx.core.hw.HardwarePlanner
import com.zhiwei.ffmpegx.core.hw.HwStrategy
import com.zhiwei.ffmpegx.core.hw.MediaCodecScanner
import com.zhiwei.ffmpegx.core.hw.TranscodeRequest
import com.zhiwei.ffmpegx.core.hw.VideoCodec
import com.zhiwei.ffmpegx.core.media.FileResolver
import com.zhiwei.ffmpegx.core.media.MediaFiles
import com.zhiwei.ffmpegx.core.hw.AudioCodec
import com.zhiwei.ffmpegx.core.model.CompressPreset
import com.zhiwei.ffmpegx.core.model.MediaInfo
import com.zhiwei.ffmpegx.core.model.OutputContainer
import com.zhiwei.ffmpegx.core.settings.AppSettings
import com.zhiwei.ffmpegx.core.settings.SettingsRepository
import com.zhiwei.ffmpegx.core.task.TaskFeature
import com.zhiwei.ffmpegx.core.task.TaskRepository
import com.zhiwei.ffmpegx.native.FFmpegNative
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * 工作台表单状态。
 *
 * 所有工具页共用同一份结构 —— 这是刻意的取舍：
 *  · 好处：选文件 / 探测 / 硬加速规划 / 命令预览这套流水线只写一遍，
 *    不会出现某个页面漏掉「零拷贝需要无滤镜」这类约束；
 *  · 代价：状态对象偏大，各页面只读写自己关心的字段。
 */
data class ToolForm(
    // ---- 输入 ----
    val inputPath: String = "",
    val inputDisplayName: String = "",
    val inputTemporary: Boolean = false,
    val mediaInfo: MediaInfo? = null,
    val extraInputs: MutableList<String> = mutableListOf(),

    // ---- 输出 ----
    val outputPath: String = "",
    val container: OutputContainer = OutputContainer.MP4,
    val videoCodec: VideoCodec = VideoCodec.H264,
    val rateMode: RateMode = RateMode.CRF,
    val crf: Int = 23,
    val videoBitrate: Int = 0,
    val speedPreset: String = "veryfast",
    val width: Int = 0,
    val height: Int = 0,
    val fps: Double = 0.0,
    val audioCodec: AudioCodec = AudioCodec.AAC,
    val audioBitrate: Int = 128_000,
    val audioSampleRate: Int = 0,
    val audioChannels: Int = 0,
    val dropAudio: Boolean = false,
    val keepMetadata: Boolean = true,

    // ---- 剪辑 ----
    val startUs: Long = 0,
    val endUs: Long = 0,
    val streamCopy: Boolean = false,

    // ---- 压缩 ----
    val preset: CompressPreset = CompressPreset.WECHAT,
    val twoPass: Boolean = false,

    // ---- 音频处理 ----
    val volume: Double = 1.0,
    val tempo: Double = 1.0,
    val fadeIn: Double = 0.0,
    val fadeOut: Double = 0.0,
    val loudnessTarget: Double? = null,
    val keepVideoForAudio: Boolean = false,

    // ---- GIF ----
    val gifFps: Int = 12,
    val gifWidth: Int = 480,
    val gifTwoPass: Boolean = true,
    val gifLoop: Int = 0,
    val gifStatsMode: String = "diff",
    val gifDither: String = "bayer",

    // ---- 拼接 ----
    val concatUseDemuxer: Boolean = false,

    // ---- 字幕 ----
    val subtitlePath: String = "",
    val subtitleMode: SubtitleMode = SubtitleMode.BURN,
    val forceStyle: String = "",
    val subtitleStreamIndex: Int = 0,

    // ---- 水印 / 分屏 ----
    val overlayPath: String = "",
    val overlayMode: OverlayMode = OverlayMode.WATERMARK,
    val overlayPosition: OverlayPosition = OverlayPosition.BOTTOM_RIGHT,
    val overlayScale: Double = 0.15,
    val overlayOpacity: Double = 1.0,
    val overlayFrom: Double = 0.0,
    val overlayTo: Double = 0.0,
    val stackEdge: Int = 720,

    // ---- 命令行 ----
    val rawCommand: String = "",
)

data class ToolUiState(
    val form: ToolForm = ToolForm(),
    val settings: AppSettings = AppSettings(),
    val plan: HardwarePlan? = null,
    val previewCommands: List<List<String>> = emptyList(),
    val busy: Boolean = false,
    val busyMessage: String = "",
    val error: String? = null,
    val toast: String? = null,
    val nativeReady: Boolean = false,
    val nativeVersion: String = "",
)

@HiltViewModel
class ToolViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanner: MediaCodecScanner,
    private val planner: HardwarePlanner,
    private val ffprobe: FFprobeEngine,
    private val settingsRepository: SettingsRepository,
    private val taskRepository: TaskRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ToolUiState())
    val state: StateFlow<ToolUiState> = _state.asStateFlow()

    /** 当前正在执行的任务（全应用共享，任何页面都能看到同一条） */
    val currentTask: StateFlow<com.zhiwei.ffmpegx.core.task.TaskEntity?> = taskRepository.current
    val currentProgress: StateFlow<com.zhiwei.ffmpegx.core.engine.TranscodeProgress> =
        taskRepository.progress

    /** 实时日志，命令行工具与队列页共用 */
    val consoleLines: StateFlow<List<String>> = taskRepository.consoleLines

    /** 当前工具，由页面在 LaunchedEffect 里设置 */
    private var feature: TaskFeature = TaskFeature.CONVERT
    private var refreshJob: kotlinx.coroutines.Job? = null

    /**
     * 当前主输入对应的解析结果。
     *
     * 持有它是为了**正确释放 SAF url**：ffkitsaf: 是可复用 url，
     * 上游不会自动回收，必须由我们显式注销。丢掉这个引用就会泄漏
     * （safIdMap 持续增长，且被注销前的 fd 也占着）。
     */
    private var currentInput: FileResolver.Resolved? = null

    override fun onCleared() {
        super.onCleared()
        releaseCurrentInput()
    }

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _state.update {
                    it.copy(
                        settings = settings,
                        form = it.form.copy(preset = settings.lastCompressPreset),
                        nativeReady = FFmpegNative.isAvailable,
                        nativeVersion = FFmpegNative.version(),
                    )
                }
                refresh()
            }
        }
    }

    fun attach(feature: TaskFeature) {
        if (this.feature == feature) return
        this.feature = feature
        // 不同工具的默认输出容器不同
        val defaultContainer = when (feature) {
            TaskFeature.AUDIO -> OutputContainer.M4A
            TaskFeature.GIF -> OutputContainer.GIF
            else -> OutputContainer.MP4
        }
        _state.update { it.copy(form = it.form.copy(container = defaultContainer)) }
        refresh()
    }

    // ---------------------------------------------------------------- 表单更新 ----

    fun update(block: (ToolForm) -> ToolForm) {
        _state.update { it.copy(form = block(it.form), error = null) }
        scheduleRefresh()
    }

    /**
     * 拖动滑块时 update 会被每帧调用，而 refresh 要重建命令 + 跑一次规划器，
     * 直接同步执行会明显掉帧。这里做 120ms 防抖。
     */
    private fun scheduleRefresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            kotlinx.coroutines.delay(120)
            refresh()
        }
    }

    fun setOutputPath(path: String) = update { it.copy(outputPath = path) }

    fun consumeToast() = _state.update { it.copy(toast = null) }
    fun consumeError() = _state.update { it.copy(error = null) }

    // ---------------------------------------------------------------- 文件选择 ----

    fun onPickInput(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, busyMessage = "正在读取文件…", error = null) }
            // 先把上一个输入的 SAF url 释放掉，避免它在 safIdMap 里越积越多
            releaseCurrentInput()
            val resolved = FileResolver.resolve(context, uri)
            resolved.fold(
                onSuccess = { r ->
                    // 探测直接用 ffmpegInput。
                    //
                    // 之前这里走的是 r.realPath，而走 SAF 直读时 file 为 null、
                    // realPath 也为 null，于是 `probeTarget?.let{...}` 直接短路，
                    // info 恒为 null —— 用户看到的就是「ffprobe 解析失败」。
                    // 实际上 ffkitsaf: 这类虚拟协议本来就能直接交给 ffprobe
                    // （上游文档：url that can be passed to FFmpegKit or FFprobeKit），
                    // 完全不需要落地副本。
                    val probeResult = ffprobe.probe(r.ffmpegInput)
                    val info = probeResult.getOrNull()
                    _state.update { current ->
                        current.copy(
                            busy = false,
                            form = current.form.copy(
                                inputPath = r.ffmpegInput,
                                inputDisplayName = r.displayName,
                                inputTemporary = r.isTemporary,
                                mediaInfo = info,
                                startUs = 0,
                                endUs = info?.durationUs ?: 0,
                                width = 0,
                                height = 0,
                                fps = 0.0,
                                videoBitrate = 0,
                                audioBitrate = current.form.audioBitrate,
                            ),
                            error = if (info == null) probeFailureMessage(probeResult) else null,
                        )
                    }
                    // 只有探测成功才接管这个 url 的生命周期。
                    // 失败时立刻释放，否则既泄漏又可能让用户反复重选堆积。
                    if (info != null) {
                        currentInput = r
                    } else {
                        FileResolver.cleanup(r)
                    }
                    refresh()
                },
                onFailure = { t ->
                    _state.update { it.copy(busy = false, error = "读取文件失败：${t.message}") }
                },
            )
        }
    }

    /**
     * 探测失败时把**真实原因**带出来。
     *
     * 原来的文案一律是「原生库可能未构建」，但原生库不可用其实在进入本页时
     * 就已经被拦下（nativeReady 检查）。到这一步还失败，原因通常是文件本身
     * 读不到或格式不认，把锅推给原生库会让人查错方向。
     */
    private fun probeFailureMessage(result: Result<MediaInfo>): String {
        val cause = result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
        return buildString {
            append("已载入文件，但无法解析其媒体信息")
            if (cause != null) {
                append("：").append(cause)
            } else {
                append("（文件可能已损坏，或格式不被支持）")
            }
            append("\n可以尝试重新选择文件；若文件在云端，请先下载到本机。")
        }
    }

    /** 释放当前输入占用的 SAF url / 临时副本 */
    private fun releaseCurrentInput() {
        currentInput?.let { FileResolver.cleanup(it) }
        currentInput = null
    }

    fun onPickExtra(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, busyMessage = "正在读取文件…") }
            FileResolver.resolve(context, uri).fold(
                onSuccess = { r ->
                    _state.update {
                        it.copy(
                            busy = false,
                            form = it.form.copy(
                                extraInputs = (it.form.extraInputs + r.ffmpegInput).toMutableList(),
                            ),
                        )
                    }
                    refresh()
                },
                onFailure = { t ->
                    _state.update { it.copy(busy = false, error = "读取文件失败：${t.message}") }
                },
            )
        }
    }

    fun removeExtra(index: Int) = update {
        it.copy(extraInputs = it.extraInputs.toMutableList().also { list -> list.removeAt(index) })
    }

    fun onPickSubtitle(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, busyMessage = "正在读取字幕…") }
            FileResolver.resolve(context, uri).fold(
                onSuccess = { r ->
                    _state.update {
                        it.copy(busy = false, form = it.form.copy(subtitlePath = r.ffmpegInput))
                    }
                    refresh()
                },
                onFailure = { t ->
                    _state.update { it.copy(busy = false, error = "读取字幕失败：${t.message}") }
                },
            )
        }
    }

    fun onPickOverlay(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, busyMessage = "正在读取素材…") }
            FileResolver.resolve(context, uri).fold(
                onSuccess = { r ->
                    _state.update {
                        it.copy(busy = false, form = it.form.copy(overlayPath = r.ffmpegInput))
                    }
                    refresh()
                },
                onFailure = { t ->
                    _state.update { it.copy(busy = false, error = "读取素材失败：${t.message}") }
                },
            )
        }
    }

    // ---------------------------------------------------------------- 命令生成 ----

    /** 重新计算输出路径、硬件方案与命令预览 */
    fun refresh() {
        val current = _state.value
        // 命令行模式不需要输入文件，其余工具必须有输入才有意义
        if (current.form.inputPath.isBlank() && feature != TaskFeature.CONSOLE) {
            _state.update { it.copy(plan = null, previewCommands = emptyList()) }
            return
        }

        // 第 1 遍：用「全软件」dry plan 生成命令，只为拿到真实的滤镜结构
        val dry = runCatching { buildCommands(current, dryPlan(current)) }
            .onFailure { _state.update { s -> s.copy(error = "生成命令失败：${it.message}") } }
            .getOrDefault(emptyList())

        // 第 2 遍：根据滤镜结构问规划器要真实方案，再重新生成命令
        val plan = runCatching { buildPlan(current, dry) }.getOrNull()

        val finalCommands = if (plan == null) {
            dry
        } else {
            runCatching { buildCommands(current, plan) }.getOrDefault(dry)
        }

        _state.update { it.copy(previewCommands = finalCommands, plan = plan) }
    }

    private fun buildCommands(state: ToolUiState, plan: HardwarePlan): List<List<String>> {
        val settings = state.settings
        val form = state.form
        val info = form.mediaInfo
        val srcW = info?.primaryVideo?.displayWidth ?: 0
        val srcH = info?.primaryVideo?.displayHeight ?: 0
        val srcFps = info?.primaryVideo?.effectiveFps ?: 0.0
        val duration = info?.durationUs ?: 0L

        val container = form.container
        val video = VideoEncodeSpec(
            codec = form.videoCodec,
            mode = if (form.streamCopy) RateMode.COPY else form.rateMode,
            crf = form.crf,
            bitrate = form.videoBitrate,
            speedPreset = form.speedPreset,
        )
        val audio = AudioEncodeSpec(
            codec = form.audioCodec,
            bitrate = form.audioBitrate,
            sampleRate = form.audioSampleRate,
            channels = form.audioChannels,
        )
        // 丢弃音频轨时传 null，render 会自动加 -an 并跳过 -c:a
        val audioOrNull = if (form.dropAudio) null else audio

        // 输出路径：每次刷新都重算，保证扩展名跟随容器
        val outputFile = resolveOutputFile(state)

        // 尚未探测出素材信息时，只能先给个占位预览
        if (info == null && feature != TaskFeature.CONSOLE) {
            return listOf(Commands.base(settings).apply {
                input(form.inputPath)
                raw("-c", "copy")
                output(outputFile.absolutePath)
            }.build())
        }

        return when (feature) {
            TaskFeature.CONVERT -> listOf(
                Commands.render(
                    settings = settings,
                    plan = plan,
                    inputs = listOf(InputSpec(form.inputPath)),
                    output = OutputSpec(
                        path = outputFile.absolutePath,
                        container = container,
                        video = video,
                        audio = audioOrNull,
                        width = form.width,
                        height = form.height,
                        fps = form.fps,
                        keepMetadata = form.keepMetadata,
                    ),
                ),
            )

            TaskFeature.COMPRESS -> {
                val (w, h) = form.preset.resolveSize(srcW, srcH)
                val targetW = if (form.width > 0) form.width else w
                val targetH = if (form.height > 0) form.height else h
                val bitrate = if (form.videoBitrate > 0) {
                    form.videoBitrate
                } else {
                    form.preset.resolveVideoBitrate(targetW, targetH)
                }
                val fpsCap = if (form.fps > 0) form.fps else form.preset.fpsCap
                Commands.compress(
                    settings = settings,
                    plan = plan,
                    inputPath = form.inputPath,
                    spec = CompressSpec(
                        output = outputFile.absolutePath,
                        container = container,
                        targetWidth = targetW,
                        targetHeight = targetH,
                        targetFps = fpsCap,
                        videoBitrate = bitrate,
                        audioBitrate = form.audioBitrate,
                        videoCodec = form.videoCodec,
                        audioCodec = form.audioCodec,
                        twoPass = form.twoPass,
                    ),
                    passLogPrefix = File(MediaFiles.workDir(context), "pass").absolutePath,
                )
            }

            TaskFeature.TRIM -> listOf(
                Commands.trim(
                    settings = settings,
                    plan = plan,
                    inputPath = form.inputPath,
                    spec = TrimSpec(
                        output = outputFile.absolutePath,
                        container = container,
                        startUs = form.startUs,
                        endUs = if (form.endUs > form.startUs) form.endUs else duration,
                        streamCopy = form.streamCopy,
                        video = video,
                        audio = audioOrNull,
                    ),
                ),
            )

            TaskFeature.AUDIO -> listOf(
                Commands.audio(
                    settings = settings,
                    plan = plan,
                    inputPath = form.inputPath,
                    spec = AudioSpec(
                        output = outputFile.absolutePath,
                        container = container,
                        codec = form.audioCodec,
                        bitrate = form.audioBitrate,
                        sampleRate = form.audioSampleRate,
                        channels = form.audioChannels,
                        volume = form.volume,
                        loudnessTargetLufs = form.loudnessTarget,
                        fadeInSeconds = form.fadeIn,
                        fadeOutSeconds = form.fadeOut,
                        tempo = form.tempo,
                        keepVideo = form.keepVideoForAudio,
                        video = video,
                    ),
                ),
            )

            TaskFeature.GIF -> Commands.gif(
                settings = settings,
                inputPath = form.inputPath,
                spec = GifSpec(
                    output = outputFile.absolutePath,
                    startUs = form.startUs,
                    durationUs = (form.endUs - form.startUs).coerceAtLeast(0L),
                    fps = form.gifFps,
                    width = form.gifWidth,
                    loopCount = form.gifLoop,
                    twoPass = form.gifTwoPass,
                    palettePath = File(MediaFiles.workDir(context), "gif_palette.png").absolutePath,
                    paletteStatsMode = form.gifStatsMode,
                    dither = form.gifDither,
                ),
            )

            TaskFeature.CONCAT -> {
                val all = (listOf(form.inputPath) + form.extraInputs).filter { it.isNotBlank() }
                if (all.size < 2) {
                    emptyList()
                } else if (form.concatUseDemuxer && all.any { isVirtualInput(it) }) {
                    // concat demuxer 让 libavformat 自己去打开列表里的每个文件，
                    // 而 ffkitsaf: 是 ffmpeg-kit 在 AVIO 层注册的协议，
                    // demuxer 的嵌套打开走不到它，必然失败。
                    // 这种情况直接不生成命令，由 UI 提示用户改用滤镜模式。
                    emptyList()
                } else {
                    val listFile = File(MediaFiles.workDir(context), "concat_list.txt")
                    if (form.concatUseDemuxer) {
                        runCatching {
                            listFile.writeText(
                                all.joinToString("\n") { "file '${it.replace("'", "'\\''")}'" },
                            )
                        }
                    }
                    listOf(
                        Commands.concat(
                            settings = settings,
                            plan = plan,
                            inputs = all,
                            spec = ConcatSpec(
                                output = outputFile.absolutePath,
                                container = container,
                                useDemuxer = form.concatUseDemuxer,
                                listFilePath = listFile.absolutePath,
                                video = video,
                                audio = audioOrNull,
                                width = if (form.width > 0) form.width else srcW,
                                height = if (form.height > 0) form.height else srcH,
                                fps = form.fps,
                            ),
                        ),
                    )
                }
            }

            TaskFeature.SUBTITLE -> {
                if (form.subtitlePath.isBlank() && form.subtitleMode != SubtitleMode.EXTRACT) {
                    emptyList()
                } else {
                    val subOutput = if (form.subtitleMode == SubtitleMode.EXTRACT) {
                        File(outputFile.parentFile, outputFile.nameWithoutExtension + ".srt").absolutePath
                    } else {
                        outputFile.absolutePath
                    }
                    listOf(
                        Commands.subtitle(
                            settings = settings,
                            plan = plan,
                            inputPath = form.inputPath,
                            spec = SubtitleSpec(
                                mode = form.subtitleMode,
                                output = subOutput,
                                subtitlePath = form.subtitlePath,
                                forceStyle = form.forceStyle,
                                container = container,
                                video = video,
                                audio = audioOrNull,
                                subtitleStreamIndex = form.subtitleStreamIndex,
                            ),
                        ),
                    )
                }
            }

            TaskFeature.OVERLAY -> {
                if (form.overlayPath.isBlank()) {
                    emptyList()
                } else {
                    listOf(
                        Commands.overlay(
                            settings = settings,
                            plan = plan,
                            mainPath = form.inputPath,
                            spec = OverlaySpec(
                                mode = form.overlayMode,
                                output = outputFile.absolutePath,
                                overlayPath = form.overlayPath,
                                position = form.overlayPosition,
                                overlayScale = form.overlayScale,
                                opacity = form.overlayOpacity,
                                showFrom = form.overlayFrom,
                                showTo = form.overlayTo,
                                container = container,
                                video = video,
                                audio = audioOrNull,
                                stackEdge = form.stackEdge,
                            ),
                        ),
                    )
                }
            }

            TaskFeature.CONSOLE -> {
                val tokens = parseRawCommand(form.rawCommand)
                if (tokens.isEmpty()) emptyList() else listOf(tokens)
            }

            TaskFeature.OTHER -> emptyList()
        }
    }

    /**
     * 计算硬件加速方案。
     *
     * 关键顺序：**先算出命令，再从命令里反推「有没有 CPU 滤镜」**，
     * 然后才去问规划器。反过来做就会出现「规划说可以零拷贝，但实际带了 scale 滤镜」的矛盾。
     */
    private fun buildPlan(state: ToolUiState, commands: List<List<String>>): HardwarePlan? {
        val form = state.form
        val info = form.mediaInfo ?: return null
        val video = info.primaryVideo ?: return null
        val strategy = state.settings.hwStrategy

        val target = form.targetSize(info)

        // 用最终命令反推滤镜情况：只要命令里出现 -vf / -filter_complex / -af 之外的处理链，
        // 就认为存在 CPU 端处理，不能开 Surface 零拷贝。
        val flat = commands.flatten()
        val hasCpuFilters = flat.contains("-vf") ||
            flat.contains("-filter_complex") ||
            flat.contains("-lavfi") ||
            flat.contains("subtitles=") ||
            commands.any { cmd -> cmd.any { it.contains("scale=") || it.contains("overlay") || it.contains("palette") } }

        val isPureScale = flat.contains("-vf") &&
            commands.all { cmd -> cmd.none { it.contains("overlay") || it.contains("palette") || it.contains("subtitles") } }

        val bitrate = when {
            form.videoBitrate > 0 -> form.videoBitrate
            feature == TaskFeature.COMPRESS -> form.preset.resolveVideoBitrate(target.first, target.second)
            else -> 0
        }

        return planner.plan(
            TranscodeRequest(
                strategy = strategy,
                sourceWidth = video.displayWidth,
                sourceHeight = video.displayHeight,
                sourceFps = video.effectiveFps,
                sourceMime = video.mime,
                targetCodec = form.videoCodec,
                targetWidth = target.first,
                targetHeight = target.second,
                targetFps = form.fps,
                targetBitrate = bitrate,
                hasCpuVideoFilters = hasCpuFilters,
                preferVulkanFilters = state.settings.preferVulkan,
                isPureScale = isPureScale,
            ),
        )
    }

    /** 预览第一遍用的「全软件」方案：只为拿到真实的滤镜结构，不带任何硬件参数 */
    private fun dryPlan(state: ToolUiState): HardwarePlan {
        val codec = state.form.videoCodec
        return HardwarePlan(
            strategy = HwStrategy.COMPAT,
            decoder = DecoderPlan.Software,
            encoder = EncoderPlan.Software(codec.swName ?: "libx264"),
            inputArgs = emptyList(),
            outputArgs = emptyList(),
            reasons = emptyList(),
            warnings = emptyList(),
        )
    }

    private fun ToolForm.targetSize(info: MediaInfo?): Pair<Int, Int> {
        if (width > 0 && height > 0) return width to height
        if (feature == TaskFeature.COMPRESS) {
            val v = info?.primaryVideo
            return preset.resolveSize(v?.displayWidth ?: 0, v?.displayHeight ?: 0)
        }
        val v = info?.primaryVideo
        return (v?.displayWidth ?: 0) to (v?.displayHeight ?: 0)
    }

    private fun resolveOutputFile(state: ToolUiState): File {
        val form = state.form
        if (form.outputPath.isNotBlank()) {
            val existing = File(form.outputPath)
            // 容器变了就同步扩展名
            if (existing.extension.equals(form.container.extension, ignoreCase = true)) return existing
            return File(existing.parentFile ?: MediaFiles.defaultOutputDir(context, state.settings.outputDir),
                existing.nameWithoutExtension + "." + form.container.extension)
        }
        val dir = MediaFiles.defaultOutputDir(context, state.settings.outputDir)
        val base = MediaFiles.baseNameOf(form.inputPath)
        val suffix = featureSuffix(feature)
        return MediaFiles.uniqueOutputFile(dir, "$base$suffix", form.container.extension)
    }

    private fun featureSuffix(feature: TaskFeature): String = when (feature) {
        TaskFeature.CONVERT -> "_converted"
        TaskFeature.COMPRESS -> "_compressed"
        TaskFeature.TRIM -> "_trimmed"
        TaskFeature.AUDIO -> "_audio"
        TaskFeature.GIF -> "_anim"
        TaskFeature.CONCAT -> "_merged"
        TaskFeature.SUBTITLE -> "_subbed"
        TaskFeature.OVERLAY -> "_overlay"
        else -> "_out"
    }

    private fun parseRawCommand(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        // 支持带引号的参数；不做 shell 展开，只是简单分词
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var quote: Char? = null
        for (ch in trimmed) {
            when {
                quote != null -> if (ch == quote) quote = null else sb.append(ch)
                ch == '"' || ch == '\'' -> quote = ch
                ch.isWhitespace() -> if (sb.isNotEmpty()) { tokens += sb.toString(); sb.clear() }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) tokens += sb.toString()
        // 用户可能把 "ffmpeg" 也写进去了，去掉
        return if (tokens.firstOrNull() == "ffmpeg") tokens.drop(1) else tokens
    }

    // ---------------------------------------------------------------- 执行 ----

    fun start() {
        val current = _state.value
        if (!current.nativeReady) {
            _state.update { it.copy(error = "原生库未构建，请先执行 scripts/build-ffmpeg-android.sh") }
            return
        }
        if (current.form.inputPath.isBlank() && feature != TaskFeature.CONSOLE) {
            _state.update { it.copy(error = "请先选择输入文件") }
            return
        }

        // 正式执行时用真实方案重新生成一遍，带上硬件加速参数
        val plan = current.plan ?: dryPlan(current)
        val commands = runCatching { buildCommands(current, plan) }.getOrDefault(emptyList())
        if (commands.isEmpty()) {
            // 拼接的 demuxer 模式在输入来自系统授权时会生成不出命令，
            // 这里给出具体原因，而不是笼统的「参数不完整」
            val concatHint = if (feature == TaskFeature.CONCAT &&
                (listOf(current.form.inputPath) + current.form.extraInputs).any { isVirtualInput(it) }
            ) {
                "拼接的「快速合并（concat demuxer）」不支持来自系统授权的文件，" +
                    "请改用「滤镜拼接」，或先把片段导入应用目录。"
            } else {
                "命令为空，请检查参数是否填写完整"
            }
            _state.update { it.copy(error = concatHint) }
            return
        }

        viewModelScope.launch {
            val form = current.form
            val outputPath = form.outputPath.ifBlank {
                resolveOutputFile(current).absolutePath
            }
            taskRepository.enqueue(
                title = "${feature.label} · ${form.inputDisplayName.ifBlank { File(form.inputPath).name }}",
                feature = feature,
                inputPath = form.inputPath,
                outputPath = outputPath,
                commands = commands,
                totalDurationUs = form.mediaInfo?.durationUs ?: 0L,
            )
            _state.update { it.copy(toast = "已加入队列") }
        }
    }

    fun cancelCurrent() = taskRepository.cancelCurrent()

    /** 把当前输出目录固定下来，避免用户改了参数后输出路径又变 */
    fun freezeOutputPath() {
        val state = _state.value
        if (state.form.outputPath.isBlank()) {
            val file = resolveOutputFile(state)
            _state.update { it.copy(form = it.form.copy(outputPath = file.absolutePath)) }
        }
    }

    override fun onCleared() {
        // 输入如果是复制到缓存的临时副本，这里不删：用户可能还要重跑。
        // 缓存目录由系统在空间紧张时自行清理。
        super.onCleared()
    }
}

/** 便捷判断：当前策略下是否会用到硬件编码器 */
fun HardwarePlan?.usesHardwareEncoder(): Boolean = this?.encoder is com.zhiwei.ffmpegx.core.hw.EncoderPlan.MediaCodec

/**
 * 输入是否是 ffmpeg-kit-next 的虚拟协议路径。
 *
 * 这类路径由 ffmpeg-kit 在 AVIO 层实现，只在「作为 ffmpeg 的输入参数」时有效。
 * 需要 libavformat 自己去打开它的场景（如 concat demuxer 的列表文件）走不到，
 * 必须在生成命令时就避开。
 */
private fun isVirtualInput(path: String): Boolean =
    path.startsWith("ffkitsaf:", ignoreCase = true) ||
        path.startsWith("ffkitmem:", ignoreCase = true) ||
        path.startsWith("ffkitstream:", ignoreCase = true)
/** 让 UI 能直接拿到策略选项 */
val hwStrategyOptions: List<HwStrategy> = HwStrategy.entries
