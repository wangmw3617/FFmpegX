package com.zhiwei.ffmpegx.native

/** FFmpeg av_log 级别，取值与 libavutil/log.h 一致 */
object AvLog {
    const val QUIET = -8
    const val PANIC = 0
    const val FATAL = 8
    const val ERROR = 16
    const val WARNING = 24
    const val INFO = 32
    const val VERBOSE = 40
    const val DEBUG = 48
    const val TRACE = 56

    fun name(level: Int): String = when {
        level <= QUIET -> "quiet"
        level <= PANIC -> "panic"
        level <= FATAL -> "fatal"
        level <= ERROR -> "error"
        level <= WARNING -> "warning"
        level <= INFO -> "info"
        level <= VERBOSE -> "verbose"
        level <= DEBUG -> "debug"
        else -> "trace"
    }
}
