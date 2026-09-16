package com.zhiwei.ffmpegx.core.engine

import android.content.Context
import com.zhiwei.ffmpegx.core.model.AudioStreamInfo
import com.zhiwei.ffmpegx.core.model.MediaInfo
import com.zhiwei.ffmpegx.core.model.SubtitleStreamInfo
import com.zhiwei.ffmpegx.core.model.VideoStreamInfo
import com.zhiwei.ffmpegx.native.FFmpegNative
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ffprobe 封装。
 *
 * 用 `-o <临时文件>` 而不是重定向 stdout：在 App 里改进程级 fd 会污染日志与其它库，
 * ffprobe 5.0 之后支持 `-o`，直接落盘再读是最干净的做法。
 */
@Singleton
class FFprobeEngine @Inject constructor() {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowSpecialFloatingPointValues = true
    }

    suspend fun probe(path: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(path)
            require(file.exists()) { "文件不存在：$path" }

            // 两个后端都返回同样结构的 ffprobe JSON，解析逻辑完全共用
            val json = FFmpegNative.probeJson(path).getOrElse { throw it }
            parse(json, path, file)
        }
    }

    /** 解析 ffprobe 的 JSON。字段缺失是常态（尤其字幕流），全部走安全取值。 */
    fun parse(text: String, path: String, file: File? = null): MediaInfo {
        val root = json.parseToJsonElement(text).jsonObject
        val format = root["format"]?.jsonObject
        val streams = root["streams"]?.jsonArray ?: JsonArray(emptyList())

        val video = mutableListOf<VideoStreamInfo>()
        val audio = mutableListOf<AudioStreamInfo>()
        val subtitle = mutableListOf<SubtitleStreamInfo>()

        for (element in streams) {
            val s = element.jsonObject
            when (s.str("codec_type")) {
                "video" -> video += VideoStreamInfo(
                    index = s.int("index") ?: 0,
                    codecName = s.str("codec_name").orEmpty(),
                    codecLongName = s.str("codec_long_name"),
                    width = s.int("width") ?: 0,
                    height = s.int("height") ?: 0,
                    frameRate = parseFraction(s.str("r_frame_rate")),
                    avgFrameRate = parseFraction(s.str("avg_frame_rate")),
                    bitRate = s.long("bit_rate") ?: 0,
                    pixelFormat = s.str("pix_fmt"),
                    profile = s.str("profile"),
                    level = s.int("level"),
                    bitDepth = s.int("bits_per_raw_sample")
                        ?: s.str("pix_fmt")?.let { pixFmtToBitDepth(it) },
                    rotationDegrees = s.readRotation(),
                    colorTransfer = s.str("color_transfer"),
                    colorPrimaries = s.str("color_primaries"),
                    nbFrames = s.long("nb_frames"),
                    durationUs = s.durationUs(),
                )

                "audio" -> audio += AudioStreamInfo(
                    index = s.int("index") ?: 0,
                    codecName = s.str("codec_name").orEmpty(),
                    codecLongName = s.str("codec_long_name"),
                    sampleRate = s.int("sample_rate") ?: 0,
                    channels = s.int("channels") ?: 0,
                    channelLayout = s.str("channel_layout"),
                    bitRate = s.long("bit_rate") ?: 0,
                    durationUs = s.durationUs(),
                    language = s.tagValue("language"),
                )

                "subtitle" -> subtitle += SubtitleStreamInfo(
                    index = s.int("index") ?: 0,
                    codecName = s.str("codec_name").orEmpty(),
                    language = s.tagValue("language"),
                    title = s.tagValue("title"),
                )
            }
        }

        return MediaInfo(
            path = path,
            fileName = file?.name ?: path.substringAfterLast('/'),
            fileSizeBytes = file?.length()
                ?: format.long("size")
                ?: 0L,
            formatName = format?.str("format_name"),
            formatLongName = format?.str("format_long_name"),
            durationUs = format?.durationUs() ?: video.firstOrNull()?.durationUs ?: 0L,
            bitRate = format?.long("bit_rate") ?: 0L,
            videoStreams = video,
            audioStreams = audio,
            subtitleStreams = subtitle,
            rawJson = text,
        )
    }

    // ------------------------------------------------------------- 解析小工具 ----

    private fun JsonObject?.str(key: String): String? =
        this?.get(key)?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?.takeIf { it.isNotBlank() && it != "N/A" }

    private fun JsonObject?.int(key: String): Int? =
        this?.get(key)?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }

    private fun JsonObject?.long(key: String): Long? =
        this?.get(key)?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }

    private fun JsonObject?.bool(key: String): Boolean? =
        this?.get(key)?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }

    private fun JsonObject?.double(key: String): Double? =
        this?.get(key)?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() }

    private fun JsonObject?.tagValue(key: String): String? =
        this?.get("tags")?.let { runCatching { it.jsonObject.str(key) }.getOrNull() }

    private fun JsonObject?.durationUs(): Long {
        double("duration")?.let { return (it * 1_000_000).toLong() }
        long("duration_ts")?.let { ts ->
            val tb = str("time_base")
            if (tb != null) {
                val parts = tb.split('/')
                val num = parts.getOrNull(0)?.toDoubleOrNull()
                val den = parts.getOrNull(1)?.toDoubleOrNull()
                if (num != null && den != null && den != 0.0) {
                    return (ts * num / den * 1_000_000).toLong()
                }
            }
        }
        return 0L
    }

    /** 读取 side_data_list 里的 displaymatrix 旋转角度 */
    private fun JsonObject.readRotation(): Int {
        val sideData = this["side_data_list"] as? JsonArray ?: return 0
        for (item in sideData) {
            val obj = runCatching { item.jsonObject }.getOrNull() ?: continue
            val rotation = obj.double("rotation") ?: continue
            return (((rotation % 360) + 360) % 360).toInt()
        }
        return 0
    }

    private fun parseFraction(value: String?): Double {
        if (value.isNullOrBlank() || value == "0/0") return 0.0
        return if (value.contains('/')) {
            val parts = value.split('/')
            val num = parts.getOrNull(0)?.toDoubleOrNull() ?: return 0.0
            val den = parts.getOrNull(1)?.toDoubleOrNull() ?: return 0.0
            if (den == 0.0) 0.0 else num / den
        } else {
            value.toDoubleOrNull() ?: 0.0
        }
    }

    private fun pixFmtToBitDepth(pixFmt: String): Int? = when {
        pixFmt.contains("10le") || pixFmt.contains("10be") || pixFmt.endsWith("p010") -> 10
        pixFmt.contains("12le") || pixFmt.contains("12be") -> 12
        pixFmt.startsWith("yuv") || pixFmt.startsWith("nv") || pixFmt.startsWith("rgb") -> 8
        else -> null
    }
}
