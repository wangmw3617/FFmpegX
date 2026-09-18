package com.zhiwei.ffmpegx.core.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.zhiwei.ffmpegx.native.FFmpegNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 把用户从系统文件选择器拿到的 `content://` Uri 变成 FFmpeg 能用的输入。
 *
 * 有两条路，按代价从低到高：
 *
 *  1. **`ffkitsaf:` 直读**（FFmpegKitNext 内建协议，见 [FFmpegNative.safParameterForRead]）
 *     —— 不复制文件、不占额外空间、立刻开始转码。这是现在的首选路径。
 *  2. **解析真实路径 / 复制到缓存** —— 兜底。当后端不支持 SAF 协议，
 *     或协议申请失败（例如 Uri 权限已被回收）时使用。
 *
 * 历史背景：FFmpeg 需要 `seek()`，而 SAF 给出的管道不可寻址，直接传 `/proc/self/fd/N`
 * 在大多数格式上会失败。这就是当初必须复制到缓存的原因；FFmpegKitNext 用自己实现的
 * IOContext 按 fd 做可寻址读写，把这条限制去掉了。
 */
object FileResolver {

    private const val TAG = "FileResolver"

    /**
     * 解析结果。
     *
     * @param ffmpegInput 可直接拼进 ffmpeg 命令行的字符串。
     *        可能是普通路径，也可能是 `ffkitsaf:...`
     * @param file 真实文件；走 ffkitsaf 直读且未复制时为 null
     * @param isTemporary true 表示是复制出来的临时副本，用完应删除
     * @param safUrl 非空表示使用了 ffkitsaf 协议。该 url 是 reusable 的，
     *        **必须在整个流程结束时通过 [cleanup] 释放**，否则会泄漏
     */
    data class Resolved(
        val ffmpegInput: String,
        val file: File?,
        val isTemporary: Boolean,
        val displayName: String,
        val sizeBytes: Long,
        val safUrl: String? = null,
    ) {
        /** 给 ffprobe / 缩略图等需要真实路径的场景用；走 saf 时返回空 */
        val realPath: String? get() = file?.absolutePath
    }

    /**
     * @param requireRealFile true 表示**必须**拿到真实文件路径，必要时复制到缓存，
     *        不使用 `ffkitsaf:` 直读。
     *
     *        字幕（`subtitles` 滤镜）是唯一的强制场景：该滤镜把路径交给 libass，
     *        而 libass 用普通 stdio 自己 fopen 读文件，**不走 FFmpeg 的 avio 协议层**，
     *        因此不认 `ffkitsaf:` 这类自定义协议。字幕文件通常只有几十 KB，
     *        复制一份的代价可以忽略，换来的是这条路必然可用。
     */
    suspend fun resolve(
        context: Context,
        uri: Uri,
        onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
        requireRealFile: Boolean = false,
    ): Result<Resolved> = withContext(Dispatchers.IO) {
        runCatching {
            when (uri.scheme?.lowercase()) {
                "file" -> {
                    val f = File(uri.path ?: error("空的 file:// 路径"))
                    require(f.exists()) { "文件不存在：${f.absolutePath}" }
                    Resolved(f.absolutePath, f, false, f.name, f.length())
                }

                else -> resolveContentUri(context, uri, onProgress, requireRealFile)
            }
        }.onFailure { Log.e(TAG, "解析 $uri 失败", it) }
    }

    /**
     * content:// 的解析。
     *
     * 顺序刻意如此：先试零拷贝的 saf 直读，再试真实路径，最后才复制。
     * saf 申请成功但命令后来失败的情况不会发生在这里 —— FFmpegKitNext 会用
     * Uri 对应的 fd 打开文档，失败也会在执行阶段明确报错。
     *
     * 注意 saf 分支用的是 reusable url：探测与转码是两次独立会话，
     * 自动注销的 url 撑不过第一次。释放由调用方在流程结束时负责。
     */
    private fun resolveContentUri(
        context: Context,
        uri: Uri,
        onProgress: (Long, Long) -> Unit,
        requireRealFile: Boolean,
    ): Resolved {
        val meta = queryMeta(context, uri)

        // 优先：ffkitsaf: 直读，完全不落地。
        // requireRealFile 时跳过 —— 见 resolve() 的说明（libass 不认自定义协议）。
        if (!requireRealFile && FFmpegNative.supportsSaf) {
            // 必须用 reusable = true。
            //
            // 上游语义：reusable=false 表示「文件关闭时自动注销该 url」。
            // 但本应用对同一个输入至少要开两次会话 —— 先 ffprobe 探测拿时长/分辨率，
            // 再 ffmpeg 真正转码。第一次会话一结束 url 就被注销，第二次必然
            // 「找不到 SAF id」而失败。因此这里申请可复用 url，由调用方在
            // 整个流程结束后通过 [cleanup]（或 [releaseSafUrl]）显式释放。
            val safUrl = FFmpegNative.safParameterForRead(uri, reusable = true)
            if (!safUrl.isNullOrBlank()) {
                Log.i(TAG, "使用 ffkitsaf 直读：$uri -> $safUrl")
                return Resolved(
                    ffmpegInput = safUrl,
                    file = null,
                    isTemporary = false,
                    displayName = meta.first ?: "input",
                    sizeBytes = meta.second,
                    // 记录 url 以便 cleanup 释放；reusable=true 时上游不会自动回收
                    safUrl = safUrl,
                )
            }
            Log.w(TAG, "saf 参数申请失败，回退到真实路径 / 复制")
        }

        // 次选：解析真实路径，不复制
        val direct = queryRealPath(context, uri)
        if (direct != null && direct.exists() && direct.canRead()) {
            return Resolved(
                ffmpegInput = direct.absolutePath,
                file = direct,
                isTemporary = false,
                displayName = meta.first ?: direct.name,
                sizeBytes = direct.length(),
            )
        }

        // 兜底：复制到应用缓存
        return copyToCache(context, uri, meta, onProgress)
    }

    /**
     * 申请一个可复用的 `ffkitsaf:` 读参数。
     *
     * 供「同一个输入被多条命令用到」的场景（例如两遍编码、GIF 的 palettegen/paletteuse，
     * 或多路 concat）。复用可以避免每遍都重新解析一次 Uri。
     * **用完必须调用 [releaseSafUrl]。**
     */
    fun safInputForReuse(context: Context, uri: Uri): String? =
        FFmpegNative.safParameterForRead(uri, reusable = true)

    fun releaseSafUrl(url: String?) {
        if (!url.isNullOrBlank()) FFmpegNative.releaseSafUrl(url)
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

        return Resolved(
            ffmpegInput = target.absolutePath,
            file = target,
            isTemporary = true,
            displayName = safeName,
            sizeBytes = target.length(),
        ).also {
            Log.i(TAG, "已复制到缓存：${target.absolutePath}（${target.length()} 字节）")
        }
    }

    /**
     * 清理临时副本，并释放 SAF url。
     *
     * **调用时机很重要**：saf url 现在是 reusable=true 的，探测与转码都要用它，
     * 所以必须等**整个流程结束**（转码完成或被取消）后再调用，
     * 不能在探测结束后就释放，否则转码会「找不到 SAF id」。
     */
    fun cleanup(resolved: Resolved?) {
        if (resolved?.isTemporary == true) {
            runCatching { resolved.file?.delete() }
        }
        // reusable=true 的 url 上游不会自动回收，必须手动注销，否则 safIdMap 会持续增长
        resolved?.safUrl?.let { releaseSafUrl(it) }
    }
}
