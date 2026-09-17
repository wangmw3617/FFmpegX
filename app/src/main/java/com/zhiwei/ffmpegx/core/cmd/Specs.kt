package com.zhiwei.ffmpegx.core.cmd

import com.zhiwei.ffmpegx.core.hw.VideoCodec
import com.zhiwei.ffmpegx.core.hw.AudioCodec
import com.zhiwei.ffmpegx.core.model.OutputContainer

/** 码率控制方式 */
enum class RateMode(val label: String) {
    CRF("恒定质量（CRF）"),
    BITRATE("固定码率（ABR）"),
    COPY("直通不重编"),
}

data class InputSpec(
    val path: String,
    /** 插在 `-i` 之前的输入选项，例如 `-ss 00:00:10` / `-hwaccel mediacodec` */
    val preArgs: List<String> = emptyList(),
    val isImage: Boolean = false,
    val isLoopImage: Boolean = false,
)

data class VideoEncodeSpec(
    val codec: VideoCodec,
    val mode: RateMode,
    val crf: Int = 23,
    val bitrate: Int = 0,
    /** 软件编码的 -preset（libx264: ultrafast..veryslow） */
    val speedPreset: String = "veryfast",
    val profile: String? = null,
    val level: String? = null,
    val pixelFormat: String? = null,
    val gopSeconds: Double = 2.0,
) {
    companion object {
        val X264_PRESETS = listOf(
            "ultrafast", "superfast", "veryfast", "faster",
            "fast", "medium", "slow", "slower", "veryslow",
        )

        val CRF_RANGE = 0..51
    }
}

data class AudioEncodeSpec(
    val codec: AudioCodec,
    val bitrate: Int = 128_000,
    val sampleRate: Int = 0,
    val channels: Int = 0,
)

/**
 * 一个输出文件的完整描述。所有功能页最终都会构造出它，再交给 [Commands.render] 统一渲染。
 * 这样「命令生成」只有一份实现，不会出现某个页面漏掉 -map 或漏掉 faststart 的情况。
 */
data class OutputSpec(
    val path: String,
    val container: OutputContainer,
    /** null 表示丢弃视频流（纯音频输出） */
    val video: VideoEncodeSpec?,
    /** null 表示丢弃音频流 */
    val audio: AudioEncodeSpec?,
    /** 0 表示保持原尺寸 */
    val width: Int = 0,
    val height: Int = 0,
    /** 0 表示保持原帧率 */
    val fps: Double = 0.0,
    /** CPU 端视频滤镜，追加在自动生成的 scale/setsar/fps 之后 */
    val filters: List<String> = emptyList(),
    val audioFilters: List<String> = emptyList(),
    val fastStart: Boolean = true,
    val keepMetadata: Boolean = true,
    /** 是否保留/搬运字幕轨（默认丢弃，避免与容器不兼容导致封装失败） */
    val copySubtitles: Boolean = false,
    val customOutputArgs: List<String> = emptyList(),
)

// ------------------------------------------------------------------ 各功能页 Spec ----

data class TrimSpec(
    val output: String,
    val container: OutputContainer,
    val startUs: Long,
    val endUs: Long,
    /** true = 关键帧对齐的无损剪切（-c copy），只能切到关键帧 */
    val streamCopy: Boolean,
    /** 无损剪切时不需要编码参数，所以允许为 null */
    val video: VideoEncodeSpec? = null,
    val audio: AudioEncodeSpec? = null,
    /** 输出时长是否按帧精确重建时间戳 */
    val resetTimestamps: Boolean = true,
)

data class CompressSpec(
    val output: String,
    val container: OutputContainer,
    val targetWidth: Int,
    val targetHeight: Int,
    val targetFps: Double,
    val videoBitrate: Int,
    val audioBitrate: Int,
    val videoCodec: VideoCodec,
    val audioCodec: AudioCodec,
    /** 两遍编码，体积控制更准（只对软件编码有效） */
    val twoPass: Boolean = false,
)

data class GifSpec(
    val output: String,
    val startUs: Long,
    val durationUs: Long,
    val fps: Int,
    val width: Int,
    /** 0 = 无限循环 */
    val loopCount: Int = 0,
    /** 两遍调色板，画质明显更好 */
    val twoPass: Boolean = true,
    /** 两遍模式下第一遍生成的调色板文件路径（由调用方放在 cache 目录） */
    val palettePath: String = "",
    val paletteStatsMode: String = "diff",
    val dither: String = "bayer",
    val bayerScale: Int = 5,
) {
    companion object {
        val FPS_CHOICES = listOf(5, 8, 10, 12, 15, 20, 24, 30)
        val WIDTH_CHOICES = listOf(240, 320, 360, 480, 540, 640, 720, 0)
        val PALETTE_STATS_MODES = listOf("diff", "single", "full")
        val DITHERS = listOf("bayer", "sierra2_4a", "floyd_steinberg", "none")
    }
}

data class ConcatSpec(
    val output: String,
    val container: OutputContainer,
    /** true = concat demuxer（要求所有片段编码参数一致，可 -c copy） */
    val useDemuxer: Boolean,
    /** demuxer 模式需要的列表文件路径 */
    val listFilePath: String = "",
    /** demuxer 模式走 -c copy，不需要编码参数，所以允许为 null */
    val video: VideoEncodeSpec? = null,
    val audio: AudioEncodeSpec? = null,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Double = 0.0,
)

data class SubtitleSpec(
    val mode: SubtitleMode,
    val output: String,
    val subtitlePath: String,
    /** 烧录时的样式覆盖，例如 FontSize=28 */
    val forceStyle: String = "",
    val container: OutputContainer = OutputContainer.MP4,
    val video: VideoEncodeSpec? = null,
    val audio: AudioEncodeSpec? = null,
    val subtitleStreamIndex: Int = 0,
)

enum class SubtitleMode(val label: String, val description: String) {
    BURN("烧录到画面", "字幕变成画面的一部分，任何播放器都能看到（需 libass）"),
    EXTRACT("提取字幕文件", "把内嵌字幕导出为 .srt"),
    MUX("封装外挂字幕", "字幕作为独立轨道，播放器可开关"),
}

enum class OverlayMode(val label: String, val description: String) {
    WATERMARK("图片水印", "在画面上叠加图片，可设位置、透明度、显示时段"),
    PICTURE_IN_PICTURE("画中画", "视频叠加视频，常见于解说类内容"),
    SIDE_BY_SIDE("左右分屏", "两个视频横向并排"),
    TOP_BOTTOM("上下分屏", "两个视频纵向堆叠"),
}

enum class OverlayPosition(val label: String, val x: String, val y: String) {
    TOP_LEFT("左上角", "16", "16"),
    TOP_RIGHT("右上角", "W-w-16", "16"),
    BOTTOM_LEFT("左下角", "16", "H-h-16"),
    BOTTOM_RIGHT("右下角", "W-w-16", "H-h-16"),
    CENTER("居中", "(W-w)/2", "(H-h)/2"),
    TOP_CENTER("顶部居中", "(W-w)/2", "16"),
    BOTTOM_CENTER("底部居中", "(W-w)/2", "H-h-16"),
}

data class OverlaySpec(
    val mode: OverlayMode,
    val output: String,
    val overlayPath: String,
    val position: OverlayPosition = OverlayPosition.BOTTOM_RIGHT,
    /** 水印宽度占主视频宽度的比例，0.15 表示 15% */
    val overlayScale: Double = 0.15,
    /** 0..1，1 为完全不透明 */
    val opacity: Double = 1.0,
    /** 显示时段，单位秒；showFrom >= showTo 表示全程显示 */
    val showFrom: Double = 0.0,
    val showTo: Double = 0.0,
    val container: OutputContainer = OutputContainer.MP4,
    val video: VideoEncodeSpec? = null,
    val audio: AudioEncodeSpec? = null,
    /** 分屏模式下统一后的单路高度（左右分屏）或宽度基准（上下分屏） */
    val stackEdge: Int = 720,
    /** 分屏模式下第二路是否静音 */
    val muteSecondAudio: Boolean = true,
)

data class AudioSpec(
    val output: String,
    val container: OutputContainer,
    val codec: AudioCodec,
    val bitrate: Int,
    val sampleRate: Int,
    val channels: Int,
    /** 线性音量系数，1.0 为原始 */
    val volume: Double = 1.0,
    /** EBU R128 响度标准化目标，null 表示不做 */
    val loudnessTargetLufs: Double? = null,
    val fadeInSeconds: Double = 0.0,
    val fadeOutSeconds: Double = 0.0,
    /** 变速倍率，0.5~2.0 */
    val tempo: Double = 1.0,
    /** 保留视频（true = 换音轨而不是提取音频） */
    val keepVideo: Boolean = false,
    val video: VideoEncodeSpec? = null,
)

data class ThumbnailSpec(
    val output: String,
    val atUs: Long,
    val width: Int,
    val format: String = "jpg",
    val quality: Int = 3,
)

/** 旋转 / 翻转 */
data class RotateSpec(
    val output: String,
    /** 顺时针角度，取值 0 / 90 / 180 / 270 */
    val degrees: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val video: VideoEncodeSpec,
    val audio: AudioEncodeSpec?,
)

/** 画面裁剪 */
data class CropSpec(
    val output: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val video: VideoEncodeSpec,
    val audio: AudioEncodeSpec?,
)

/** 视频变速 */
data class SpeedSpec(
    val output: String,
    /** 播放倍速：>1 加速（时长变短），<1 减速 */
    val factor: Double,
    /** 保持音调，避免加速后声音变尖 */
    val keepPitch: Boolean = true,
    val video: VideoEncodeSpec,
    val audio: AudioEncodeSpec?,
)

/** 去水印 / 区域遮挡 */
data class DelogoSpec(
    val output: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    /** blur / delogo / mosaic */
    val mode: String = "blur",
    val video: VideoEncodeSpec,
    val audio: AudioEncodeSpec?,
)

/** 图片转视频 */
data class SlideshowSpec(
    val output: String,
    /** 图片路径列表，按顺序播放 */
    val inputs: List<String>,
    /** 每张停留秒数 */
    val secondsEach: Double = 3.0,
    val fps: Int = 30,
    val video: VideoEncodeSpec,
)
