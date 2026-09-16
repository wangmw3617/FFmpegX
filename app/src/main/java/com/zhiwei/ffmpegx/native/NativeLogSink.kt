package com.zhiwei.ffmpegx.native

/**
 * C 层 av_log 回调的接收端。
 *
 * 刻意声明成普通 Kotlin interface（而不是 fun interface 的 lambda）：
 * JNI 侧要对其做 NewGlobalRef + CallVoidMethod，必须是稳定的 Java 方法签名。
 */
interface NativeLogSink {
    fun onLog(level: Int, message: String)
}

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
