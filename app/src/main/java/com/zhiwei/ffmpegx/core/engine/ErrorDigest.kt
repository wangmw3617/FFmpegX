package com.zhiwei.ffmpegx.core.engine

/**
 * 从 FFmpeg 的错误尾巴里挑出**最该给用户看的那一行**。
 *
 * FFmpeg 的报错是**级联**的：根因通常出现在最前面，后面几行是它的连带后果。
 * 早先的实现直接取最后一行，于是用户看到的多半是最没信息量的那句。两个真实例子：
 *
 * ```
 * 源没有字幕轨：
 *   Stream map '0:s:0' matches no streams. To ignore this, add a trailing '?'   ← 真正的根因
 *   Failed to set value '0:s:0' for option 'map': Invalid argument
 *   Error parsing options for output file out.srt.                              ← 早先取的是这行
 *
 * 拼接时某一路没有音频轨：
 *   Stream specifier ':a:0' in filtergraph description ... matches no streams.   ← 真正的根因
 *   Error initializing complex filters: Invalid argument                       ← 早先取的是这行
 * ```
 *
 * 策略：优先返回**第一条命中已知根因特征**的行；一条都不命中时退回最后一行
 * （即保持原行为，不猜）。特征串都是 FFmpeg 自己会打印的、指向具体原因的说法，
 * 刻意不含 `Error opening input/output` 这类泛化措辞 —— 它们通常正是被级联出来的那句。
 *
 * 纯字符串处理，不依赖 Android，可直接单测。
 */
internal fun pickErrorLine(tail: List<String>): String? {
    val lines = tail.filter { it.isNotBlank() }
    if (lines.isEmpty()) return null
    return lines.firstOrNull { line -> ROOT_CAUSE_HINTS.any { line.contains(it) } }
        ?: lines.last()
}

/**
 * 指向具体原因的报错特征。按「越具体越靠前」排列，仅用于匹配，顺序不影响结果。
 */
private val ROOT_CAUSE_HINTS = listOf(
    // 流/映射层面
    "matches no streams",
    "Stream map",
    // 滤镜/编码器缺失
    "No such filter",
    "Unknown filter",
    "Encoder not found",
    "Decoder not found",
    "Unknown encoder",
    // 文件与权限
    "No such file or directory",
    "Permission denied",
    "Unable to open",
    "Invalid data found",
    // 组合限制
    "Impossible to convert",
    "Filtering and streamcopy",
    "does not contain any stream",
    // 资源
    "Cannot allocate memory",
)
