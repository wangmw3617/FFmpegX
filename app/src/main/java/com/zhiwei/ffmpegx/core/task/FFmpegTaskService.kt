package com.zhiwei.ffmpegx.core.task

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.zhiwei.ffmpegx.MainActivity
import com.zhiwei.ffmpegx.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 转码前台服务。
 *
 * 作用只有一个：让长耗时任务在切后台/锁屏时不被系统回收，并把进度放到通知栏。
 * 真正的队列消费在 [TaskRepository] 里，这里只负责「保活 + 通知」。
 */
@AndroidEntryPoint
class FFmpegTaskService : Service() {

    @Inject
    lateinit var repository: TaskRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                repository.cancelCurrent()
                return START_NOT_STICKY
            }
        }

        // 必须在 5 秒内调用 startForeground，否则系统会抛异常。
        // 这里先挂一条「准备中」的占位通知，真正的进度由 observeProgress() 后续更新。
        val taskTitle = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        startForegroundCompat(
            buildNotification(
                title = getString(R.string.notif_running_title, taskTitle),
                text = getString(R.string.notif_preparing),
                percent = 0,
                indeterminate = true,
                ongoing = true,
            ),
        )

        if (observeJob == null) {
            observeJob = scope.launch { observeProgress() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observeJob = null
        scope.cancel()
        super.onDestroy()
    }

    private fun observeProgress() {
        scope.launch {
            combine(repository.current, repository.progress) { task, progress -> task to progress }
                .collect { (task, progress) ->
                    if (task == null) return@collect
                    val percent = if (progress.percent >= 0) {
                        progress.percent.toInt()
                    } else {
                        task.progressPercent
                    }
                    notify(
                        buildNotification(
                            title = getString(R.string.notif_running_title, task.title),
                            text = buildProgressText(percent, progress.speedLabel),
                            percent = percent,
                            indeterminate = progress.percent < 0 && task.progressPercent <= 0,
                            ongoing = true,
                        ),
                    )
                }
        }
    }

    private fun buildProgressText(percent: Int, speed: String): String =
        buildString {
            append(percent).append("%")
            if (speed != "—") append("  ·  ").append(speed)
        }

    private fun buildNotification(
        title: String,
        text: String,
        percent: Int,
        indeterminate: Boolean,
        ongoing: Boolean,
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FFmpegTaskService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, percent.coerceIn(0, 100), indeterminate)
            .apply {
                if (ongoing) {
                    addAction(0, getString(R.string.action_cancel), cancelIntent)
                }
            }
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        // Android 15 起 mediaProcessing 是官方推荐类型；低版本退回 dataSync
        val type = if (Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        }.onFailure {
            Log.w(TAG, "startForeground 失败，降级为无类型前台服务", it)
            runCatching {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
            }
        }
    }

    private fun notify(notification: Notification) {
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val TAG = "FFmpegTaskService"
        private const val CHANNEL_ID = "ffmpegx_tasks"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.zhiwei.ffmpegx.action.START"
        private const val ACTION_CANCEL = "com.zhiwei.ffmpegx.action.CANCEL"
        private const val EXTRA_TITLE = "extra_title"

        /** 任务开始时拉起前台服务。后台启动受限时静默失败，任务仍会在进程内存活。 */
        fun start(context: Context, title: String) {
            val intent = Intent(context, FFmpegTaskService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure {
                Log.w(TAG, "无法启动前台服务（可能处于后台启动限制），任务继续在进程内执行", it)
            }
        }

        /** 任务结束：更新为终态通知并停止前台服务 */
        fun finish(context: Context, task: TaskEntity) {
            val manager = NotificationManagerCompat.from(context)
            val (titleRes, text) = when (task.statusEnum) {
                TaskStatus.SUCCESS -> R.string.notif_done_title to task.outputPath
                TaskStatus.CANCELLED -> R.string.status_cancelled to task.title
                else -> R.string.notif_failed_title to (task.errorMessage ?: task.title)
            }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(context.getString(titleRes))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            runCatching { manager.notify(NOTIFICATION_ID + 1, notification) }

            context.stopService(Intent(context, FFmpegTaskService::class.java))
        }
    }
}
