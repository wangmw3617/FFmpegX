package com.zhiwei.ffmpegx

import android.app.Application
import android.util.Log
import com.zhiwei.ffmpegx.core.task.TaskRepository
import com.zhiwei.ffmpegx.native.FFmpegNative
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class FFmpegXApp : Application() {

    @Inject
    lateinit var taskRepository: TaskRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // 必须最先做：选定 FFmpeg 后端（kit 预编译包 / 自研 JNI），
        // 之后所有 ensureLoaded() / probeJson() 调用都依赖它
        FFmpegNative.initialize(this)

        // 预热后端：加载 .so 有几十到几百毫秒开销，放启动时做掉，
        // 用户点「开始」时就不用等。加载失败也只是记状态，不影响启动。
        appScope.launch {
            val ok = FFmpegNative.ensureLoaded()
            Log.i(
                TAG,
                if (ok) {
                    "FFmpeg 就绪：${FFmpegNative.backendName} / ${FFmpegNative.version()}"
                } else {
                    "FFmpeg 不可用（${FFmpegNative.backendName}）：${FFmpegNative.loadError()}"
                },
            )
        }

        // 恢复上次未跑完的任务队列
        taskRepository.startWorker()
    }

    private companion object {
        const val TAG = "FFmpegXApp"
    }
}
