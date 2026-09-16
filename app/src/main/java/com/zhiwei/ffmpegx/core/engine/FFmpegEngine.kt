package com.zhiwei.ffmpegx.core.engine

import android.os.SystemClock
import com.zhiwei.ffmpegx.native.AvLog
import com.zhiwei.ffmpegx.native.FFmpegNative
import com.zhiwei.ffmpegx.native.RawStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

sealed interface TranscodeEvent {
    /** 原始日志行，供控制台展示 */
    data class Log(val level: Int, val line: String) : TranscodeEvent

    /** 进度更新（已做节流，最多 ~8 次/秒） */
    data class Progress(val progress: TranscodeProgress) : TranscodeEvent

    data class Completed(val exitCode: Int, val elapsedMs: Long) : TranscodeEvent

    data class Failed(
        val exitCode: Int,
        val message: String,
        val elapsedMs: Long,
    ) : TranscodeEvent
}

/**
 * 单次 FFmpeg 会话的执行器。
 *
 * 关键约束（来自 C 层设计）：
 *  - 同一时刻只允许一个会话在跑，C 层用互斥锁串行化，这里也不再并发发起；
 *  - 取消是通过向执行线程 raise(SIGINT) 触发的，属于「协作式中断」，
 *    因此取消后必须等底层真正退出，否则下一个任务会被上一个的残留状态污染。
 */
@Singleton
class FFmpegEngine @Inject constructor() {

    companion object {
        private const val PROGRESS_THROTTLE_MS = 120L
        private const val CANCEL_GRACE_MS = 20_000L
        private const val MAX_ERROR_TAIL = 6
    }

    fun run(args: List<String>, totalDurationUs: Long): Flow<TranscodeEvent> = channelFlow {
        val startedAt = SystemClock.elapsedRealtime()
        val progressRef = AtomicReference(TranscodeProgress(totalDurationUs = totalDurationUs))
        val lastEmitAt = AtomicLong(0L)
        val errorTail = ArrayDeque<String>()

        // 只有提供结构化统计的后端才走 StatisticsCallback；
        // 其它后端（自研 JNI）从日志的统计行里解析，见 ProgressParser
        val structuredStats = FFmpegNative.providesStructuredStats

        fun emitProgress(p: TranscodeProgress, force: Boolean = false) {
            progressRef.set(p)
            val now = SystemClock.elapsedRealtime()
            if (force || now - lastEmitAt.get() >= PROGRESS_THROTTLE_MS) {
                lastEmitAt.set(now)
                trySend(TranscodeEvent.Progress(p))
            }
        }

        val onLog: (Int, String) -> Unit = { level, message ->
            // 保留最后若干条错误，失败时用来给用户一个可读的原因
            if (level <= AvLog.ERROR) {
                synchronized(errorTail) {
                    errorTail.addLast(message.trim())
                    while (errorTail.size > MAX_ERROR_TAIL) errorTail.removeFirst()
                }
            }

            trySend(TranscodeEvent.Log(level, message))

            if (!structuredStats && ProgressParser.looksLikeProgress(message)) {
                ProgressParser.parse(message, progressRef.get())?.let { emitProgress(it) }
            }
        }

        val onStats: ((RawStats) -> Unit)? = if (structuredStats) {
            { raw ->
                emitProgress(
                    TranscodeProgress(
                        frame = raw.frame,
                        fps = raw.fps,
                        quality = raw.quality,
                        outputBytes = raw.sizeBytes,
                        // ffmpeg-kit 的 Statistics.getTime() 是毫秒
                        timeUs = (raw.timeMs * 1000.0).toLong(),
                        bitrateKbps = raw.bitrateKbps,
                        speed = raw.speed,
                        totalDurationUs = totalDurationUs,
                    ),
                )
            }
        } else {
            null
        }

        val exitCode = AtomicInteger(FFmpegNative.EXIT_INTERNAL)
        val done = AtomicBoolean(false)

        val worker = launch(Dispatchers.IO) {
            try {
                exitCode.set(FFmpegNative.runFfmpeg(args, onLog, onStats))
            } finally {
                done.set(true)
            }
        }

        try {
            worker.join()
        } catch (ce: CancellationException) {
            // 上层取消了收集：通知 FFmpeg 中断，并等它走完自己的清理流程
            FFmpegNative.cancel()
            withContext(NonCancellable) {
                withTimeoutOrNull(CANCEL_GRACE_MS) {
                    while (!done.get()) delay(40)
                }
            }
            throw ce
        }

        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val code = exitCode.get()

        if (code == 0) {
            // 补一次最终进度，避免进度条停在 97% 之类的位置
            progressRef.get().let { final ->
                if (final.timeUs > 0) trySend(TranscodeEvent.Progress(final))
            }
            trySend(TranscodeEvent.Completed(code, elapsed))
        } else {
            val tail = synchronized(errorTail) { errorTail.toList() }
            trySend(TranscodeEvent.Failed(code, humanize(code, tail), elapsed))
        }
    }

    /** 把退出码 + 错误尾巴翻译成一句人话 */
    private fun humanize(code: Int, tail: List<String>): String = when (code) {
        FFmpegNative.EXIT_NATIVE_MISSING ->
            "FFmpeg 后端不可用：${FFmpegNative.loadError().take(200)}"
        FFmpegNative.EXIT_INTERNAL -> "原生调用异常"

        else -> buildString {
            append("FFmpeg 退出码 ").append(code)
            val reason = tail.lastOrNull { it.isNotBlank() }
            if (reason != null) {
                append("：").append(reason.take(300))
            }
        }
    }
}
