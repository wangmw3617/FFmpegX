package com.zhiwei.ffmpegx.core.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 把用户从系统文件选择器拿到的 `content://` Uri 变成 FFmpeg 能用的真实文件路径。
 *
 * 为什么必须做这件事：
 * FFmpeg 需要 `seek()`，而 SAF 给出的管道是不可寻址的，直接传 `/proc/self/fd/N`
 * 在大多数格式上会失败。所以只有两条路——要么解析出真实路径，要么把文件复制到本地。
 *
 * 策略（按代价从低到高）：
 *  1. `file://` 直接取路径；
 *  2. 从 MediaStore / DocumentsProvider 的 `_data` 列解析真实路径（同一分区内的媒体文件通常有效）；
 *  3. 复制到应用缓存目录（一定可行，代价是占用等量空间）。
 */
object FileResolver {

    private const val TAG = "FileResolver"

    data class Resolved(
        val file: File,
        /** true 表示是复制出来的临时副本，用完应删除 */
        val isTemporary: Boolean,
        val displayName: String,
        val sizeBytes: Long,
    )

    suspend fun resolve(
        context: Context,
        uri: Uri,
        onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<Resolved> = withContext(Dispatchers.IO) {
        runCatching {
            when (uri.scheme?.lowercase()) {
                "file" -> {
                    val f = File(uri.path ?: error("空的 file:// 路径"))
                    require(f.exists()) { "文件不存在：${f.absolutePath}" }
                    Resolved(f, false, f.name, f.length())
                }

                else -> {
                    val meta = queryMeta(context, uri)
                    val direct = queryRealPath(context, uri)
                    if (direct != null && direct.exists() && direct.canRead()) {
                        Resolved(direct, false, meta.first ?: direct.name, direct.length())
                    } else {
                        copyToCache(context, uri, meta, onProgress)
                    }
                }
            }
        }.onFailure { Log.e(TAG, "解析 $uri 失败", it) }
    }

    /** 只查询显示名与大小，不复制。用于 UI 预览。 */
    suspend fun peek(context: Context, uri: Uri): Pair<String?, Long> = withContext(Dispatchers.IO) {
        queryMeta(context, uri)
    }

    // ------------------------------------------------------------------ 内部实现 ----

    private fun queryMeta(context: Context, uri: Uri): Pair<String?, Long> {
        var name: String? = null
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) name = cursor.getString(nameIdx)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                }
            }
        }
        return name to size
    }

    private fun queryRealPath(context: Context, uri: Uri): File? {
        // 只对 media / downloads 这类 DocumentsProvider 有效；解析不到就返回 null
        return runCatching {
            val projection = arrayOf("_data")
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex("_data")
                    if (idx >= 0) {
                        cursor.getString(idx)?.let { path -> File(path) }
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun copyToCache(
        context: Context,
        uri: Uri,
        meta: Pair<String?, Long>,
        onProgress: (Long, Long) -> Unit,
    ): Resolved {
        val (displayName, size) = meta
        val safeName = displayName?.takeIf { it.isNotBlank() } ?: "input_${UUID.randomUUID()}"
        val ext = safeName.substringAfterLast('.', "").let { if (it.isBlank()) "bin" else it }
        val dir = MediaFiles.workDir(context).let { File(it, "inputs").apply { mkdirs() } }
        val target = File(dir, "${System.currentTimeMillis()}_$safeName")

        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(1 shl 16)
                var copied = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    onProgress(copied, if (size > 0) size else -1)
                }
            }
        } ?: error("无法读取所选文件（可能没有授权）")

        return Resolved(target, true, safeName, target.length()).also {
            Log.i(TAG, "已复制到缓存：${target.absolutePath}（${target.length()} 字节）")
        }
    }

    /** 清理临时副本 */
    fun cleanup(resolved: Resolved?) {
        if (resolved?.isTemporary == true) {
            runCatching { resolved.file.delete() }
        }
    }
}
