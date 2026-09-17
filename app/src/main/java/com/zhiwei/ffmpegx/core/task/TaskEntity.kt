package com.zhiwei.ffmpegx.core.task

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** 任务状态 */
enum class TaskStatus(val label: String) {
    PENDING("等待中"),
    RUNNING("执行中"),
    SUCCESS("已完成"),
    FAILED("失败"),
    CANCELLED("已取消"),
}

/** 功能分类，用于任务列表的图标与筛选 */
enum class TaskFeature(val label: String) {
    CONVERT("格式转换"),
    COMPRESS("视频压缩"),
    TRIM("剪辑截取"),
    AUDIO("音频处理"),
    GIF("GIF 制作"),
    CONCAT("视频拼接"),
    SUBTITLE("字幕处理"),
    OVERLAY("水印与画中画"),
    ROTATE("旋转与翻转"),
    CROP("画面裁剪"),
    THUMBNAIL("提取画面"),
    SPEED("视频变速"),
    DELOGO("去水印与遮挡"),
    SLIDESHOW("图片转视频"),
    CONSOLE("命令行"),
    OTHER("其它"),
}

@Entity(tableName = "ffmpeg_tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val feature: String,
    val inputPath: String,
    val outputPath: String,
    /** JSON 数组的数组，保存每条待执行命令的完整 argv，便于「重跑」与「复制命令」 */
    val argsJson: String,
    /** 素材总时长，用于把 FFmpeg 的 time= 换算成百分比 */
    val totalDurationUs: Long = 0,
    val status: String = TaskStatus.PENDING.name,
    val progressPercent: Int = 0,
    val exitCode: Int = 0,
    val errorMessage: String? = null,
    val elapsedMs: Long = 0,
    val outputBytes: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
) {
    val statusEnum: TaskStatus
        get() = runCatching { TaskStatus.valueOf(status) }.getOrDefault(TaskStatus.PENDING)

    val featureEnum: TaskFeature
        get() = runCatching { TaskFeature.valueOf(feature) }.getOrDefault(TaskFeature.OTHER)

    val isFinished: Boolean
        get() = statusEnum == TaskStatus.SUCCESS ||
            statusEnum == TaskStatus.FAILED ||
            statusEnum == TaskStatus.CANCELLED
}

@Dao
interface TaskDao {

    @Query("SELECT * FROM ffmpeg_tasks ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<TaskEntity>>

    @Query("SELECT * FROM ffmpeg_tasks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM ffmpeg_tasks WHERE id = :id")
    suspend fun findById(id: Long): TaskEntity?

    @Query("SELECT * FROM ffmpeg_tasks WHERE status = 'PENDING' ORDER BY createdAt ASC")
    suspend fun pendingTasks(): List<TaskEntity>

    @Query("SELECT COUNT(*) FROM ffmpeg_tasks WHERE status IN ('PENDING','RUNNING')")
    fun observeActiveCount(): Flow<Int>

    @Insert
    suspend fun insert(task: TaskEntity): Long

    @Update
    suspend fun update(task: TaskEntity)

    @Query("DELETE FROM ffmpeg_tasks WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 已结束的任务。清理前先取出来，才能连带删掉它们的产物文件 */
    @Query("SELECT * FROM ffmpeg_tasks WHERE status IN ('SUCCESS','FAILED','CANCELLED')")
    suspend fun finishedTasks(): List<TaskEntity>

    /** 全量任务快照，用途同上 */
    @Query("SELECT * FROM ffmpeg_tasks")
    suspend fun allTasksOnce(): List<TaskEntity>

    @Query("DELETE FROM ffmpeg_tasks WHERE status IN ('SUCCESS','FAILED','CANCELLED')")
    suspend fun clearFinished()

    @Query("DELETE FROM ffmpeg_tasks")
    suspend fun clearAll()

    @Query(
        "UPDATE ffmpeg_tasks SET status = 'PENDING', progressPercent = 0, exitCode = 0, " +
            "errorMessage = NULL WHERE status IN ('RUNNING','FAILED','CANCELLED')",
    )
    suspend fun resetUnfinished()
}

@Database(
    entities = [TaskEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class FFmpegXDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
}
