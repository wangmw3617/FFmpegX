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
 * 输出路径解析 + 导出到系统媒体库。
 *
 * 默认输出到应用私有外部目录（`Android/data/<pkg>/files/Movies/FFmpegX`）：
 *  - 不需要任何存储权限；
 *  - 卸载 App 时一起清理，不留垃圾。
 *
 * 需要出现在相册/文件管理器里时，走 [publishToMediaStore] 显式导出。
 */
object MediaFiles {

    private const val TAG = "MediaFiles"
    const val APP_FOLDER = "FFmpegX"

    private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "mov", "ts", "m4v", "3gp", "avi")
    private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "opus", "flac", "wav", "ogg")
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif")

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

    private fun mediaKindOf(extension: String): Kind = when (extension.lowercase()) {
        in VIDEO_EXT -> Kind.VIDEO
        in AUDIO_EXT -> Kind.AUDIO
        in IMAGE_EXT -> Kind.IMAGE
        else -> Kind.OTHER
    }

    private enum class Kind { VIDEO, AUDIO, IMAGE, OTHER }

    /**
     * 把结果导出到系统媒体库，使其出现在相册 / 音乐 / 下载目录中。
     *
     * @return 新文件的 content Uri；失败返回 null
     */
    suspend fun publishToMediaStore(
        context: Context,
        source: File,
        extension: String,
        folderName: String = APP_FOLDER,
    ): Uri? = withContext(Dispatchers.IO) {
        if (!source.exists()) return@withContext null
        val mime = mimeOf(extension)
        val kind = mediaKindOf(extension)
        val displayName = source.name

        runCatching {
            val collection = when (kind) {
                Kind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                Kind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                Kind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                Kind.OTHER -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePathFor(kind, folderName))
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(collection, values)
                ?: return@runCatching null

            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            } ?: run {
                resolver.delete(uri, null, null)
                return@runCatching null
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
            } else {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(source.absolutePath),
                    arrayOf(mime),
                    null,
                )
            }
            uri
        }.onFailure { Log.e(TAG, "导出到媒体库失败", it) }.getOrNull()
    }

    private fun relativePathFor(kind: Kind, folder: String): String {
        val base = when (kind) {
            Kind.VIDEO -> Environment.DIRECTORY_MOVIES
            Kind.AUDIO -> Environment.DIRECTORY_MUSIC
            Kind.IMAGE -> Environment.DIRECTORY_PICTURES
            Kind.OTHER -> Environment.DIRECTORY_DOWNLOADS
        }
        return "$base/$folder"
    }
}
