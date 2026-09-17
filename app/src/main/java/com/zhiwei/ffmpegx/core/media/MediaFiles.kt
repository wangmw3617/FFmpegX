package com.zhiwei.ffmpegx.core.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 输出路径解析 + 导出到公共 Download 目录。
 *
 * ffmpeg 先写到应用私有外部目录（`Android/data/<pkg>/files/...`）—— 不需要任何存储权限，
 * 写入最可靠；任务成功后由 [publishToMediaStore] 自动导出到 `Download/FFmpegX`，
 * 用户能在文件管理器里直接看到结果。
 *
 * 不直接让 ffmpeg 写公共目录的原因：Android 10 起不允许按路径写公共存储，
 * 只能走 MediaStore（拿到的是 content Uri，而 ffmpeg 需要文件路径）。
 */
object MediaFiles {

    private const val TAG = "MediaFiles"
    const val APP_FOLDER = "FFmpegX"

    /** 默认输出目录，不存在会自动创建 */
    fun defaultOutputDir(context: Context, customDir: String?): File {
        val base = if (!customDir.isNullOrBlank()) {
            File(customDir)
        } else {
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        }
        val dir = if (base.name == APP_FOLDER) base else File(base, APP_FOLDER)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 临时文件目录（GIF 调色板、concat 列表、两遍编码日志都放这里） */
    fun workDir(context: Context): File {
        val dir = File(context.cacheDir, "work")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 生成一个不冲突的输出文件。同名时追加 `_1`、`_2`……
     */
    fun uniqueOutputFile(
        dir: File,
        baseName: String,
        extension: String,
    ): File {
        val safeBase = baseName
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()
            .ifBlank { "output" }
        var candidate = File(dir, "$safeBase.$extension")
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "${safeBase}_$index.$extension")
            index++
            if (index > 999) break
        }
        return candidate
    }

    /** 去掉扩展名的文件名 */
    fun baseNameOf(path: String): String =
        File(path).name.substringBeforeLast('.').ifBlank { "output" }

    fun mimeOf(extension: String): String = when (extension.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "ts" -> "video/mp2t"
        "avi" -> "video/x-msvideo"
        "3gp" -> "video/3gpp"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "opus" -> "audio/opus"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "gif" -> "image/gif"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "srt" -> "application/x-subrip"
        "ass" -> "text/x-ssa"
        else -> "application/octet-stream"
    }

    /**
     * 把结果导出到公共 `Download/<folderName>` 目录。
     *
     * - Android 10+：走 MediaStore（不能按路径写公共目录），无需任何权限；
     * - Android 9 及以下：直接写文件 + 媒体扫描，依赖清单里已声明的
     *   `WRITE_EXTERNAL_STORAGE`（`maxSdkVersion=28`）。
     *
     * @return 新文件的 Uri；失败返回 null（调用方应保留私有副本作为兜底）
     */
    suspend fun publishToMediaStore(
        context: Context,
        source: File,
        extension: String,
        folderName: String = APP_FOLDER,
    ): Uri? = withContext(Dispatchers.IO) {
        if (!source.exists()) return@withContext null
        val mime = mimeOf(extension)
        val displayName = source.name

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/$folderName",
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching null

                resolver.openOutputStream(uri)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
                } ?: run {
                    resolver.delete(uri, null, null)
                    return@runCatching null
                }

                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
                uri
            } else {
                @Suppress("DEPRECATION")
                val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val dir = File(base, folderName)
                if (!dir.exists() && !dir.mkdirs()) return@runCatching null
                val target = File(dir, displayName)
                source.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
                }
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(target.absolutePath),
                    arrayOf(mime),
                    null,
                )
                Uri.fromFile(target)
            }
        }.onFailure { Log.e(TAG, "导出到 Download/$folderName 失败", it) }.getOrNull()
    }
}
