package com.zhiwei.ffmpegx.core.task

import android.content.Context
import android.util.Log
import com.zhiwei.ffmpegx.core.engine.FFmpegEngine
import com.zhiwei.ffmpegx.core.engine.TranscodeEvent
import com.zhiwei.ffmpegx.core.engine.TranscodeProgress
import com.zhiwei.ffmpegx.core.settings.SettingsRepository
import com.zhiwei.ffmpegx.di.ApplicationScope
import com.zhiwei.ffmpegx.native.FFmpegNative
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 任务参数的编解码（List<List<String>> <-> JSON） */
object TaskArgs {

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(commands: List<List<String>>): String =
        JsonArray(
            commands.map { cmd -> JsonArray(cmd.map { JsonPrimitive(it) }) },
        ).toString()

    fun decode(raw: String): List<List<String>> = runCatching {
        json.parseToJsonElement(raw).jsonArray.map { inner ->
            inner.jsonArray.map { it.jsonPrimitiveOrEmpty() }
        }
    }.getOrDefault(emptyList())

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveOrEmpty(): String =
        runCatching { (this as JsonPrimitive).content }.getOrDefault("")
}

/**
 * 任务队列。
 *
 * 设计：单消费者串行执行。原因是 C 层 FFmpeg CLI 有大量进程级全局状态，
 * 并发跑多个会话会互相踩（这也是所有成熟 FFmpeg 封装库的共同选择）。
 *
 * 队列本身跑在 Application 作用域里，配合前台服务，因此切后台/锁屏都不会中断。
 */
@Singleton
class TaskRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: TaskDao,
    private val engine: FFmpegEngine,
    @ApplicationScope private val appScope: CoroutineScope,
) {

    companion object {
        private const val TAG = "TaskRepository"
        private const val MAX_CONSOLE_LINES = 800

        /** ffmpeg-kit-next 的虚拟协议前缀；命令行里出现就说明引用了进程内的 SAF 注册项 */
        private const val SAF_PROTOCOL_PREFIX = "ffkitsaf:"
    }

    private val queue = Channel<Long>(Channel.UNLIMITED)
    private var workerJob: Job? = null

    /**
     * SAF url 注册表：命令行里出现过的 `ffkitsaf:` url -> 引用它的任务 id 集合。
     *
     * 为什么不放在 ViewModel：任务在 Application 作用域里串行执行，可能排队很久。
     * ViewModel 随时会被销毁（用户退出页面），由它持有 url 会在任务还没开始时就
     * 把 url 释放掉，任务必然失败。
     *
     * 用引用计数而非单值：同一输入可能被多个任务共用（先导出音频、再转视频）。
     */
    private val safUrlRefs = mutableMapOf<String, MutableSet<Long>>()

    /** 登记某个任务引用了哪些 SAF url */
    @Synchronized
    private fun retainSafUrls(taskId: Long, commands: List<List<String>>) {
        commands.flatten()
            .filter { it.startsWith(SAF_PROTOCOL_PREFIX, ignoreCase = true) }
            .forEach { url -> safUrlRefs.getOrPut(url) { mutableSetOf() }.add(taskId) }
    }

    /** 任务结束时释放它引用的 SAF url；引用计数归零才真正注销 */
    @Synchronized
    private fun releaseSafUrls(taskId: Long) {
        val iter = safUrlRefs.iterator()
        while (iter.hasNext()) {
            val (url, ids) = iter.next()
            ids.remove(taskId)
            if (ids.isEmpty()) {
                iter.remove()
                runCatching { FFmpegNative.releaseSafUrl(url) }
                    .onFailure { Log.w(TAG, "释放 saf url 失败：$url") }
            }
        }
    }

    /** 全量任务列表（新到旧） */
    val allTasks: StateFlow<List<TaskEntity>> = dao.observeAll()
        .stateIn(appScope, SharingStarted.Eagerly, emptyList())

    /** 首页展示用的最近任务 */
    val recentTasks: StateFlow<List<TaskEntity>> = dao.observeRecent(20)
        .stateIn(appScope, SharingStarted.Eagerly, emptyList())

    val activeCount: StateFlow<Int> = dao.observeActiveCount()
        .stateIn(appScope, SharingStarted.Eagerly, 0)

    private val _current = MutableStateFlow<TaskEntity?>(null)
    val current: StateFlow<TaskEntity?> = _current.asStateFlow()

    private val _progress = MutableStateFlow(TranscodeProgress())
    val progress: StateFlow<TranscodeProgress> = _progress.asStateFlow()

    /** 控制台缓冲，只保留最近若干行，避免长任务把内存吃满 */
    private val _console = MutableStateFlow<List<String>>(emptyList())
    val consoleLines: StateFlow<List<String>> = _console.asStateFlow()

    private val _lastResult = MutableStateFlow<TaskEntity?>(null)

    /** 最近一次结束的任务，UI 用它弹提示 */
    val lastResult: StateFlow<TaskEntity?> = _lastResult.asStateFlow()

    // ------------------------------------------------------------------ 生命周期 ----

    /** 幂等启动消费者；App 启动时调用一次即可 */
    fun startWorker() {
        if (workerJob?.isActive == true) return
        workerJob = appScope.launch {
            // 恢复上次被系统杀掉时残留的任务
            runCatching { dao.resetUnfinished() }
            runCatching { dao.pendingTasks() }.getOrDefault(emptyList()).forEach { queue.trySend(it.id) }

            for (id in queue) {
                val task = dao.findById(id) ?: continue
                if (task.statusEnum != TaskStatus.PENDING) continue
                // 恢复出来的 SAF 任务在真正开始前就被判失败，避免用户看到
                // 「找不到 SAF id」这类无从下手的报错
                val cmds = TaskArgs.decode(task.argsJson)
                if (runCatching { failIfStaleSaf(task, cmds) }.getOrDefault(false)) continue
                runCatching { execute(task) }
                    .onFailure { Log.e(TAG, "任务 #${task.id} 执行异常", it) }
            }
        }
    }

    /**
     * 从数据库恢复出来的任务：其命令行里的 `ffkitsaf:` url 指向**上一个进程**的
     * safIdMap，本进程里根本不存在，直接重跑只会得到一句莫名其妙的
     * 「找不到 SAF id」。所以在**开始执行前**就把它标记为失败，并给出可操作的提示，
     * 而不是让它跑一段再报一个用户看不懂的错。
     *
     * @return true 表示该任务已被处理（失败），调用方应跳过执行
     */
    private suspend fun failIfStaleSaf(task: TaskEntity, commands: List<List<String>>): Boolean {
        val hasSaf = commands.flatten().any { it.startsWith(SAF_PROTOCOL_PREFIX, ignoreCase = true) }
        if (!hasSaf) return false
        // 本进程为这个任务登记过 url，说明它还有效（例如刚入队、还在排队）
        val registered = synchronized(this) { safUrlRefs.values.any { task.id in it } }
        if (registered) return false

        Log.w(TAG, "任务 #${task.id} 引用的 SAF url 已随上个进程失效，标记为失败")
        finish(
            task,
            TaskStatus.FAILED,
            -1,
            "该任务引用的是上次运行时的文件授权，已经失效。请在工具页重新选择文件后再执行。",
            0,
            0,
        )
        return true
    }

    // ------------------------------------------------------------------ 队列操作 ----

    suspend fun enqueue(
        title: String,
        feature: TaskFeature,
        inputPath: String,
        outputPath: String,
        commands: List<List<String>>,
        totalDurationUs: Long = 0,
    ): Long {
        require(commands.isNotEmpty()) { "命令不能为空" }
        val id = dao.insert(
            TaskEntity(
                title = title,
                feature = feature.name,
                inputPath = inputPath,
                outputPath = outputPath,
                argsJson = TaskArgs.encode(commands),
                totalDurationUs = totalDurationUs,
            ),
        )
        // 命令里若含 ffkitsaf: url，现在起由队列持有它，直到任务结束才释放。
        // 必须在这里就登记：入队与真正执行之间可能隔很久（前面还有别的任务）。
        retainSafUrls(id, commands)
        startWorker()
        queue.trySend(id)
        return id
    }

    /** 取消当前正在执行的任务 */
    fun cancelCurrent() {
        val running = _current.value ?: return
        Log.i(TAG, "请求取消任务 #${running.id}")
        com.zhiwei.ffmpegx.native.FFmpegNative.cancel()
    }

    suspend fun retry(id: Long) {
        val task = dao.findById(id) ?: return
        val reset = task.copy(
            status = TaskStatus.PENDING.name,
            progressPercent = 0,
            exitCode = 0,
            errorMessage = null,
            elapsedMs = 0,
            outputBytes = 0,
            startedAt = 0,
            finishedAt = 0,
        )
        dao.update(reset)
        startWorker()
        queue.trySend(id)
    }

    suspend fun remove(id: Long) {
        val task = dao.findById(id)
        // 删除记录时顺手清掉产物，避免输出目录越堆越多
        if (task != null && task.outputPath.isNotBlank()) {
            runCatching { File(task.outputPath).takeIf { it.exists() }?.delete() }
        }
        // 已删掉的任务不会再执行，它占用的 SAF url 立刻归还，否则要等进程退出才释放
        releaseSafUrls(id)
        dao.deleteById(id)
    }

    suspend fun clearFinished() {
        dao.clearFinished()
        // 正常情况下已完成任务的 SAF url 在 execute 的 finally 里已经归还，
        // 注册表里不该再有它们的引用。这里只做一次兜底清理：
        // 剔除那些既不在队列里、也不是当前正在执行的任务的残留引用。
        val activeIds = runCatching { dao.pendingTasks() }
            .getOrDefault(emptyList())
            .map { it.id }
            .toSet()
        val runningId = _current.value?.id
        synchronized(this) {
            val iter = safUrlRefs.iterator()
            while (iter.hasNext()) {
                val (url, ids) = iter.next()
                ids.retainAll { it in activeIds || it == runningId }
                if (ids.isEmpty()) {
                    iter.remove()
                    runCatching { FFmpegNative.releaseSafUrl(url) }
                }
            }
        }
    }

    suspend fun clearAll() {
        val runningId = _current.value?.id
        dao.clearAll()
        // 已结束任务的 url 全部归还。当前正在执行的那条要留着 ——
        // 它的命令还在跑，把输入 url 抽掉会让它立刻失败。
        synchronized(this) {
            val iter = safUrlRefs.iterator()
            while (iter.hasNext()) {
                val (url, ids) = iter.next()
                ids.retainAll { it == runningId }
                if (ids.isEmpty()) {
                    iter.remove()
                    runCatching { FFmpegNative.releaseSafUrl(url) }
                }
            }
        }
    }

    fun clearConsole() {
        _console.value = emptyList()
    }

    fun consumeLastResult() {
        _lastResult.value = null
    }

    // ------------------------------------------------------------------ 执行核心 ----

    private suspend fun execute(task: TaskEntity) {
        val commands = TaskArgs.decode(task.argsJson)
        if (commands.isEmpty()) {
            finish(task, TaskStatus.FAILED, -1, "任务参数为空", 0, 0)
            releaseSafUrls(task.id)
            return
        }
        // 任务结束（无论成功、失败还是取消）都释放它引用的 SAF url。
        // 放在 finally 里：中途抛异常时同样要释放，否则 safIdMap 会泄漏。
        try {
            executeInner(task, commands)
        } finally {
            releaseSafUrls(task.id)
        }
    }

    private suspend fun executeInner(task: TaskEntity, commands: List<List<String>>) {
        val running = task.copy(
            status = TaskStatus.RUNNING.name,
            startedAt = System.currentTimeMillis(),
            progressPercent = 0,
            errorMessage = null,
        )
        dao.update(running)
        _current.value = running
        _progress.value = TranscodeProgress(totalDurationUs = task.totalDurationUs)
        _console.value = emptyList()

        FFmpegTaskService.start(context, running.title)

        var finalStatus = TaskStatus.SUCCESS
        var exitCode = 0
        var message: String? = null
        val startedAt = System.currentTimeMillis()

        try {
            commands.forEachIndexed { index, args ->
                val isLast = index == commands.lastIndex
                // 多遍任务：把每一遍的进度折算进整体进度
                var passProgress = 0.0

                engine.run(args, task.totalDurationUs).collect { event ->
                    when (event) {
                        is TranscodeEvent.Log -> appendConsole(event.line)
                        is TranscodeEvent.Progress -> {
                            passProgress = if (event.progress.percent >= 0) event.progress.percent else 0.0
                            val overall = ((index + passProgress / 100.0) / commands.size * 100.0)
                                .toInt()
                                .coerceIn(0, 99)
                            _progress.value = event.progress.copy(
                                totalDurationUs = task.totalDurationUs,
                            )
                            _current.value = _current.value?.copy(progressPercent = overall)
                        }
                        is TranscodeEvent.Completed -> {
                            exitCode = event.exitCode
                        }
                        is TranscodeEvent.Failed -> {
                            exitCode = event.exitCode
                            message = event.message
                            finalStatus = TaskStatus.FAILED
                        }
                    }
                }

                if (finalStatus == TaskStatus.FAILED) return@forEachIndexed
                if (!isLast) {
                    // 中间遍次结束，把整体进度推到该遍的边界
                    val boundary = ((index + 1).toDouble() / commands.size * 100).toInt()
                    _current.value = _current.value?.copy(progressPercent = boundary)
                }
            }
        } catch (ce: CancellationException) {
            finalStatus = TaskStatus.CANCELLED
            message = "用户取消"
        } catch (t: Throwable) {
            finalStatus = TaskStatus.FAILED
            message = t.message ?: t::class.java.simpleName
            Log.e(TAG, "任务 #${task.id} 失败", t)
        }

        val elapsed = System.currentTimeMillis() - startedAt
        val outputSize = runCatching { File(task.outputPath).length() }.getOrDefault(0L)
        if (finalStatus == TaskStatus.SUCCESS && outputSize <= 0) {
            finalStatus = TaskStatus.FAILED
            message = "命令返回成功但输出文件为空，请检查输出路径是否可写"
        }

        finish(task, finalStatus, exitCode, message, elapsed, outputSize)
    }

    private suspend fun finish(
        task: TaskEntity,
        status: TaskStatus,
        exitCode: Int,
        message: String?,
        elapsedMs: Long,
        outputBytes: Long,
    ) {
        val finished = task.copy(
            status = status.name,
            exitCode = exitCode,
            errorMessage = message,
            elapsedMs = elapsedMs,
            outputBytes = outputBytes,
            progressPercent = if (status == TaskStatus.SUCCESS) 100 else _current.value?.progressPercent ?: 0,
            finishedAt = System.currentTimeMillis(),
        )
        dao.update(finished)
        _current.value = null
        _lastResult.value = finished
        FFmpegTaskService.finish(context, finished)
    }

    private fun appendConsole(line: String) {
        val trimmed = line.trimEnd('\n', '\r')
        if (trimmed.isBlank()) return
        val current = _console.value
        // 进度行会高频刷新，覆盖最后一条而不是无限追加
        val updated = if (
            current.isNotEmpty() &&
            current.last().contains("time=") &&
            trimmed.contains("time=")
        ) {
            current.dropLast(1) + trimmed
        } else {
            (current + trimmed).takeLast(MAX_CONSOLE_LINES)
        }
        _console.value = updated
    }
}
