package com.zhiwei.ffmpegx.core.engine

/**
 * 一次转码过程中的进度快照。
 */
data class TranscodeProgress(
    val frame: Long = 0,
    val fps: Double = 0.0,
    val quality: Double = 0.0,
    val outputBytes: Long = 0,
    val timeUs: Long = 0,
    val bitrateKbps: Double = 0.0,
    val speed: Double = 0.0,
    val dupFrames: Long = 0,
    val dropFrames: Long = 0,
    val totalDurationUs: Long = 0,
) {
    /** 0..100；总时长未知时返回 -1，UI 应改用不确定进度条 */
    val percent: Double
        get() = if (totalDurationUs <= 0) -1.0
        else ((timeUs.toDouble() / totalDurationUs) * 100.0).coerceIn(0.0, 100.0)

    /** 预计剩余时间（毫秒），无法估算时返回 -1 */
    val remainingMs: Long
        get() {
            if (totalDurationUs <= 0 || speed <= 0.01) return -1
            val remainUs = totalDurationUs - timeUs
            if (remainUs <= 0) return 0
            return (remainUs / speed / 1000.0).toLong()
        }

    val speedLabel: String
        get() = if (speed <= 0) "—" else String.format("%.2fx", speed)

    val fpsLabel: String
        get() = if (fps <= 0) "—" else String.format("%.1f fps", fps)
}

/**
 * 解析 FFmpeg 输出的进度信息。
 *
 * FFmpeg 有两种进度输出形态，这里都支持：
 *
 *  1) 默认的统计行（每 0.5 秒一行，以 \r 结尾）：
 *     `frame=  120 fps= 30 q=28.0 size=    1024kB time=00:00:04.00 bitrate=2097.2kbits/s speed=1.00x`
 *
 *  2) `-progress` 的 key=value 形态（本 App 不主动使用，但用户可能在命令行模式里自己加）：
 *     `out_time_us=4000000`
 *
 * 解析失败时返回 null，调用方保持上一次的进度即可 —— 宁可不动也不能乱跳。
 */
object ProgressParser {

    private val frameRe = Regex("""frame=\s*(\d+)""")
    private val fpsRe = Regex("""fps=\s*([\d.]+)""")
    private val qRe = Regex("""\bq=\s*([\d.]+)""")
    private val sizeRe = Regex("""(?:L?size)=\s*(\d+)\s*(kB|KiB|MiB|GiB|B)\b""")
    private val timeRe = Regex("""time=\s*(\d+):(\d{1,2}):(\d{1,2}(?:\.\d+)?)""")
    private val bitrateRe = Regex("""bitrate=\s*([\d.]+)\s*kbits/s""")
    private val speedRe = Regex("""speed=\s*([\d.]+)\s*x""")
    private val dupRe = Regex("""dup=\s*(\d+)""")
    private val dropRe = Regex("""drop=\s*(\d+)""")

    private val kvTimeUsRe = Regex("""^out_time_us=(-?\d+)""")
    private val kvTimeMsRe = Regex("""^out_time_ms=(-?\d+)""")
    private val kvSizeRe = Regex("""^total_size=(\d+)""")
    private val kvFrameRe = Regex("""^frame=(\d+)""")
    private val kvFpsRe = Regex("""^fps=([\d.]+)""")
    private val kvSpeedRe = Regex("""^speed=([\d.]+)x?""")
    private val kvBitrateRe = Regex("""^bitrate=([\d.]+)kbits/s""")
    private val kvDupRe = Regex("""^dup_frames=(\d+)""")
    private val kvDropRe = Regex("""^drop_frames=(\d+)""")

    /** 判断一行是不是进度行（避免对每行日志都做十几次正则） */
    fun looksLikeProgress(line: String): Boolean =
        line.contains("time=") && (line.contains("frame=") || line.contains("size=") || line.contains("bitrate=")) ||
            line.startsWith("out_time") ||
            line.startsWith("total_size=") ||
            line.startsWith("progress=")

    fun parse(line: String, previous: TranscodeProgress): TranscodeProgress? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        // ---- 形态 2：key=value（-progress 输出） ----
        // 判据是「不含 time=」：统计行里一定有 time=HH:MM:SS，
        // 而 -progress 的 out_time_us= 里没有 "time=" 这个子串。
        if (!trimmed.contains("time=")) {
            return parseKeyValue(trimmed, previous)
        }

        // ---- 形态 1：统计行 ----
        if (!trimmed.contains("time=") && !trimmed.contains("frame=")) return null

        val timeUs = timeRe.find(trimmed)?.let { m ->
            val h = m.groupValues[1].toLongOrNull() ?: 0
            val min = m.groupValues[2].toLongOrNull() ?: 0
            val sec = m.groupValues[3].toDoubleOrNull() ?: 0.0
            ((h * 3600 + min * 60) * 1_000_000L + (sec * 1_000_000).toLong())
        }

        val hasAnything = timeUs != null ||
            frameRe.containsMatchIn(trimmed) ||
            sizeRe.containsMatchIn(trimmed)
        if (!hasAnything) return null

        return TranscodeProgress(
            frame = frameRe.find(trimmed)?.groupValues?.get(1)?.toLongOrNull() ?: previous.frame,
            fps = fpsRe.find(trimmed)?.groupValues?.get(1)?.toDoubleOrNull() ?: previous.fps,
            quality = qRe.find(trimmed)?.groupValues?.get(1)?.toDoubleOrNull() ?: previous.quality,
            outputBytes = sizeRe.find(trimmed)?.let { parseSize(it.groupValues[1], it.groupValues[2]) }
                ?: previous.outputBytes,
            timeUs = timeUs ?: previous.timeUs,
            bitrateKbps = bitrateRe.find(trimmed)?.groupValues?.get(1)?.toDoubleOrNull()
                ?: previous.bitrateKbps,
            speed = speedRe.find(trimmed)?.groupValues?.get(1)?.toDoubleOrNull() ?: previous.speed,
            dupFrames = dupRe.find(trimmed)?.groupValues?.get(1)?.toLongOrNull() ?: previous.dupFrames,
            dropFrames = dropRe.find(trimmed)?.groupValues?.get(1)?.toLongOrNull() ?: previous.dropFrames,
            totalDurationUs = previous.totalDurationUs,
        )
    }

    private fun parseKeyValue(line: String, previous: TranscodeProgress): TranscodeProgress? {
        var matched = false
        var p = previous

        kvTimeUsRe.find(line)?.let {
            val v = it.groupValues[1].toLongOrNull() ?: return@let
            if (v >= 0) { p = p.copy(timeUs = v); matched = true }
        }
        kvTimeMsRe.find(line)?.let {
            // 注意：FFmpeg 的 out_time_ms 实际单位是微秒（历史遗留命名），不要除以 1000
            val v = it.groupValues[1].toLongOrNull() ?: return@let
            if (v >= 0 && !matched) { p = p.copy(timeUs = v); matched = true }
        }
        kvSizeRe.find(line)?.let {
            it.groupValues[1].toLongOrNull()?.let { v -> p = p.copy(outputBytes = v); matched = true }
        }
        kvFrameRe.find(line)?.let {
            it.groupValues[1].toLongOrNull()?.let { v -> p = p.copy(frame = v); matched = true }
        }
        kvFpsRe.find(line)?.let {
            it.groupValues[1].toDoubleOrNull()?.let { v -> p = p.copy(fps = v); matched = true }
        }
        kvSpeedRe.find(line)?.let {
            it.groupValues[1].toDoubleOrNull()?.let { v -> p = p.copy(speed = v); matched = true }
        }
        kvBitrateRe.find(line)?.let {
            it.groupValues[1].toDoubleOrNull()?.let { v -> p = p.copy(bitrateKbps = v); matched = true }
        }
        kvDupRe.find(line)?.let {
            it.groupValues[1].toLongOrNull()?.let { v -> p = p.copy(dupFrames = v); matched = true }
        }
        kvDropRe.find(line)?.let {
            it.groupValues[1].toLongOrNull()?.let { v -> p = p.copy(dropFrames = v); matched = true }
        }

        return if (matched) p else null
    }

    private fun parseSize(value: String, unit: String): Long {
        val n = value.toLongOrNull() ?: return 0
        return when (unit) {
            "B" -> n
            "kB", "KiB" -> n * 1024
            "MiB" -> n * 1024 * 1024
            "GiB" -> n * 1024 * 1024 * 1024
            else -> n * 1024
        }
    }
}
