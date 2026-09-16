package com.zhiwei.ffmpegx.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhiwei.ffmpegx.core.engine.TranscodeProgress
import com.zhiwei.ffmpegx.core.task.TaskEntity
import com.zhiwei.ffmpegx.core.task.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 任务列表 / 控制台 / 首页共用。
 * 只是把 TaskRepository 的状态透出去，不引入额外业务逻辑。
 */
@HiltViewModel
class TasksViewModel @Inject constructor(
    private val repository: TaskRepository,
) : ViewModel() {

    val allTasks: StateFlow<List<TaskEntity>> = repository.allTasks
    val recentTasks: StateFlow<List<TaskEntity>> = repository.recentTasks
    val current: StateFlow<TaskEntity?> = repository.current
    val progress: StateFlow<TranscodeProgress> = repository.progress
    val consoleLines: StateFlow<List<String>> = repository.consoleLines
    val activeCount: StateFlow<Int> = repository.activeCount
    val lastResult: StateFlow<TaskEntity?> = repository.lastResult

    fun cancelCurrent() = repository.cancelCurrent()

    fun retry(id: Long) = viewModelScope.launch { repository.retry(id) }

    fun remove(id: Long) = viewModelScope.launch { repository.remove(id) }

    fun clearFinished() = viewModelScope.launch { repository.clearFinished() }

    fun clearAll() = viewModelScope.launch { repository.clearAll() }

    fun clearConsole() = repository.clearConsole()

    fun consumeLastResult() = repository.consumeLastResult()
}
