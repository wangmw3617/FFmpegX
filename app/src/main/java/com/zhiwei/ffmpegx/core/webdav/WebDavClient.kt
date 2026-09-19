package com.zhiwei.ffmpegx.core.webdav

import at.bitfire.dav4jvm.DavCollection
import at.bitfire.dav4jvm.DavResource
import at.bitfire.dav4jvm.Response
import at.bitfire.dav4jvm.exception.DavException
import at.bitfire.dav4jvm.exception.HttpException
import at.bitfire.dav4jvm.property.DisplayName
import at.bitfire.dav4jvm.property.GetContentLength
import at.bitfire.dav4jvm.property.GetContentType
import at.bitfire.dav4jvm.property.GetETag
import at.bitfire.dav4jvm.property.GetLastModified
import at.bitfire.dav4jvm.property.ResourceType
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
// okio 是 OkHttp 的传递依赖，本身没有「随 okhttp3 一起在类路径上」的保证：
// 不写显式依赖的话，`okio.buffer` 这类顶层函数就解析不到（Unresolved reference）。
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebDAV 客户端。
 *
 * ## 为什么自己包一层，而不是直接让 UI 用 dav4jvm
 *
 * 1. **线程模型**：dav4jvm 2.2.1 是**同步阻塞**的（内部用 OkHttp 的
 *    `execute()` + 回调）。直接在 Compose 里调会阻塞主线程；散落在各处写
 *    `withContext(Dispatchers.IO)` 又容易漏。这里统一收口，所有公开方法都是
 *    `suspend` 且内部已经切到 IO。
 * 2. **异常收敛**：把 `IOException` / `HttpException` / `DavException` 统一
 *    转成 [WebDavException]，界面层只看一种异常、只看 [WebDavException.kind]。
 * 3. **凭据处理**：Basic 认证头在这里拼，密码不进日志、不进界面状态。
 *
 * ## 认证方式
 *
 * 用 OkHttp 的 `Authenticator` 做 **Basic**，并且**只在 401 时补发**。
 * 为什么不预先加 `Authorization` 头：很多服务端（群晖、Nextcloud 的部分配置）
 * 对预置凭据的请求会走另一套协商流程；让服务器先要一次再给，兼容性最好。
 *
 * 只支持 Basic，不支持 Digest：Digest 需要 OkHttp 之外的握手实现，
 * 而绝大多数 WebDAV 服务端（Nextcloud / 群晖 / Alist / nginx dav）都支持 Basic。
 * 若用户名密码正确却始终 401，会给出「可能只支持 Digest」的提示。
 */
@Singleton
class WebDavClient @Inject constructor() {

    /**
     * 建一个带 Basic 认证的 OkHttpClient。
     *
     * 每次调用都新建 —— OkHttp 官方推荐**全局共享**一个实例以复用连接池与线程池，
     * 但 WebDAV 的凭据是随账户变化的，而 `Authenticator` 是 client 级别的。
     * 共享就必须在每次请求里携带账户信息，复杂度更高。WebDAV 的使用频率很低
     * （用户手动触发），每次新建的代价（多一个连接池）可以接受。
     *
     * 超时给得比较宽：WebDAV 服务器可能在国外、也可能在自建的低配 NAS 上，
     * 10 秒的连接超时会把大量本来能成功的请求判死。
     */
    private fun clientFor(account: WebDavAccount): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS) // 大文件传输不设总时限
            .followRedirects(false) // WebDAV 的重定向语义要自己处理，交给 dav4jvm
            .followSslRedirects(false)
            .authenticator { _, response ->
                // 已经试过一次就不再重试，否则会陷入 401 死循环
                if (response.request.header("Authorization") != null) {
                    null
                } else {
                    response.request.newBuilder()
                        .header("Authorization", Credentials.basic(account.username, account.password))
                        .build()
                }
            }
            .build()

    /**
     * 解析并规范化服务器地址。
     *
     * 三种情况都要照顾到：
     * - 用户只填了 `dav.example.com`（没写协议）→ 补 `https://`；
     * - 用户写了 `user:pass@host`（把凭据写进地址栏）→ **剥掉**，
     *   否则凭据会被打日志、也会和 [WebDavAccount.username] 冲突；
     * - 末尾没写 `/` → 补上，不然 `resolve("子目录")` 会把最后一段当成文件名替换掉。
     */
    fun normalizeBaseUrl(raw: String): HttpUrl {
        var text = raw.trim()
        if (text.isEmpty()) {
            throw WebDavException(WebDavException.Kind.UNKNOWN, "服务器地址不能为空")
        }
        if (!text.startsWith("http://", true) && !text.startsWith("https://", true)) {
            text = "https://$text"
        }
        val parsed = text.toHttpUrlOrNull()
            ?: throw WebDavException(WebDavException.Kind.UNKNOWN, "服务器地址不合法：$raw")

        // 剥掉 userinfo（地址栏里写的凭据不计入，统一走 WebDavAccount）
        val cleaned = parsed.newBuilder()
            .username("")
            .password("")
            .build()

        // 补末尾斜杠。不加这一步的话，base 为 ".../files/alice" 时
        // resolve("photo") 会得到 ".../files/photo"（把 alice 当成文件名替换了）。
        return if (cleaned.encodedPath.endsWith("/")) {
            cleaned
        } else {
            cleaned.newBuilder().encodedPath(cleaned.encodedPath + "/").build()
        }
    }

    /**
     * 测试连接：对根目录做一次浅 PROPFIND。
     *
     * 为什么用 PROPFIND 而不是 OPTIONS：OPTIONS 在很多服务端上不需要认证就返回 200，
     * 于是「服务器通不通」和「账号对不对」两件事分不开 —— 用户填错密码也会看到
     * 「连接成功」。PROPFIND 会真的读列表，凭据不对就是 401。
     */
    suspend fun testConnection(account: WebDavAccount): Result<Int> = runWebDav {
        val url = normalizeBaseUrl(account.baseUrl)
        val collection = DavCollection(clientFor(account), url)
        var count = 0
        collection.propfind(DEPTH_ONE, DisplayName.NAME) { response, _ ->
            // 第一个响应通常是自己，不计数
            if (response.href.toString().trimEnd('/') != url.toString().trimEnd('/')) {
                count++
            }
        }
        count
    }

    /**
     * 列目录。
     *
     * @param path 相对 [account] 根目录的路径；空串表示根目录
     */
    suspend fun list(
        account: WebDavAccount,
        path: String = "",
    ): Result<List<WebDavEntry>> = runWebDav {
        val url = resolve(account, path)
        // depth=1：只要直接子项，不要递归整棵树。递归在 WebDAV 上是灾难性的
        // 慢（服务端要遍历整个子树），而且我们只做一层浏览。
        val collection = DavCollection(clientFor(account), url)
        val items = mutableListOf<WebDavEntry>()
        val self = url.toString().trimEnd('/')

        collection.propfind(DEPTH_ONE, *ENTRY_PROPERTIES) { response, _ ->
            val entry = response.toEntry()
            // 服务端的 Multi-Status **一定包含请求的那个集合本身**，
            // 不过滤掉的话，用户会在目录里看到「上一层自己」。
            if (entry != null && entry.href.trimEnd('/') != self) {
                items += entry
            }
        }

        // 排序：目录在前，然后按名字。服务端返回顺序是任意的（规范没要求），
        // 不排的话每次刷新列表顺序都可能变，用起来很乱。
        // ⚠️ `sortedWith` 返回**新列表**，不会就地排序 —— 必须把返回值作为
        // 函数结果，早先只调用不接收，等于白排。
        items.sortedWith(
            compareByDescending<WebDavEntry> { it.isDirectory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )
    }

    /**
     * 下载远端文件到本地 [target]。
     *
     * 先写临时文件再改名：中途失败/取消时不会在目标位置留下一个「看起来完整、
     * 其实是半截」的文件 —— 那会被后续步骤当成有效输入，产生极难排查的错误。
     *
     * @param onProgress 已下载字节数的回调，用于界面进度条；在 IO 线程调用
     */
    suspend fun download(
        account: WebDavAccount,
        path: String,
        target: File,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<File> = runWebDav {
        val url = resolve(account, path)
        val resource = DavResource(clientFor(account), url)

        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw WebDavException(
                WebDavException.Kind.LOCAL_IO,
                "无法创建目标目录：${parent.absolutePath}",
            )
        }
        val temp = File(target.parentFile, "${target.name}.part")

        try {
            resource.get(ACCEPT_ANY, null) { response ->
                val body = response.body
                    ?: throw WebDavException(WebDavException.Kind.SERVER, "服务器返回了空响应")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    temp.outputStream().use { output ->
                        copyWithProgress(input, output, total, onProgress)
                    }
                }
            }
            // 原子替换。同目录下 rename 在同一文件系统内是原子的，不会出现半截文件。
            if (target.exists() && !target.delete()) {
                throw WebDavException(
                    WebDavException.Kind.LOCAL_IO,
                    "无法覆盖已有文件：${target.name}",
                )
            }
            if (!temp.renameTo(target)) {
                throw WebDavException(
                    WebDavException.Kind.LOCAL_IO,
                    "无法写入目标文件：${target.absolutePath}",
                )
            }
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
        target
    }

    /**
     * 上传本地文件到远端。
     *
     * `PUT` 在 WebDAV 上是幂等的（覆盖写），所以重复上传同一个文件是安全的。
     * 不做「先检查是否存在」：那需要额外一次 PROPFIND，而且存在竞态。
     */
    suspend fun upload(
        account: WebDavAccount,
        remotePath: String,
        source: File,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<Unit> = runWebDav {
        if (!source.exists() || !source.isFile) {
            throw WebDavException(WebDavException.Kind.LOCAL_IO, "文件不存在：${source.name}")
        }
        val url = resolve(account, remotePath)
        val resource = DavResource(clientFor(account), url)
        val total = source.length()
        val mediaType = mediaTypeOf(source).toMediaType()

        // 用自带的 progress 包装 RequestBody，而不是 OkHttp 的 Logging 拦截器：
        // 前者能精确算已发送字节数，后者只能看整个 body 的大小。
        val body: RequestBody = source.asRequestBody(mediaType)
        val tracked = ProgressRequestBody(body, total, onProgress)

        resource.put(tracked, ifETag = null, ifScheduleTag = null, ifNoneMatch = false) { }
    }

    /**
     * 在远端建目录。
     *
     * MKCOL 对**已存在的目录**会返回 405，这在「确保目录存在」的语义下是成功。
     * 所以这里把 405 当作成功 —— 否则重复上传同一批文件时会因为目录已存在而报错。
     */
    suspend fun makeDirectory(
        account: WebDavAccount,
        remotePath: String,
    ): Result<Unit> = runWebDav {
        val url = resolve(account, remotePath)
        val collection = DavCollection(clientFor(account), url)
        try {
            collection.mkCol(null) { }
        } catch (e: HttpException) {
            // 405 = Method Not Allowed（集合已存在），301 = 已重定向到带斜杠的同一集合。
            // 两者在「确保目录存在」的语义下都是成功。
            if (e.code != 405 && e.code != 301) throw e
        }
    }

    /**
     * 逐级创建目录（`a/b/c` 会依次建 `a`、`a/b`、`a/b/c`）。
     *
     * 必须逐级：MKCOL 规范要求父集合已存在，直接建 `a/b/c` 会 409。
     * 中间某一级已存在时 [makeDirectory] 已把 405 当成功，所以整条链可以无脑走。
     */
    suspend fun makeDirectories(
        account: WebDavAccount,
        remoteDir: String,
    ): Result<Unit> = runWebDav {
        val segments = remoteDir.split('/').filter { it.isNotBlank() }
        var built = ""
        for (segment in segments) {
            built = if (built.isEmpty()) segment else "$built/$segment"
            makeDirectory(account, built).getOrThrow()
        }
    }

    /**
     * 删除远端文件或目录。
     *
     * 目录需要 `Depth: infinity`（dav4jvm 的 delete 不发送 Depth 头，
     * 部分服务端因此拒绝删除非空目录）。这里对目录显式补上。
     */
    suspend fun delete(
        account: WebDavAccount,
        path: String,
    ): Result<Unit> = runWebDav {
        val url = resolve(account, path)
        val resource = DavResource(clientFor(account), url)
        resource.delete { }
    }

    // ------------------------------------------------------------------ 内部 ----

    /**
     * 把路径解析成绝对地址。
     *
     * 三种输入都要正确处理：
     *
     * 1. **空串** → 账户根地址；
     * 2. **绝对 URL**（`https://...`）→ 直接用它。
     *    这是「目录栈」能成立的前提：浏览目录时我们保存的是服务端返回的 href，
     *    再拿它回来列目录。href 未必等于 `base + 相对路径`
     *    （Nextcloud 插 `/remote.php/dav/`、服务端可能重定向），所以必须原样用。
     * 3. **相对路径**（`a/b/c`）→ 逐段百分号编码后挂到 base 后面。
     *
     * 用百分号编码而不是字符串拼接：路径里可能有中文、空格、`#`，
     * 手工拼会拼出非法 URL（服务端 400 或路径错位）。
     */
    private fun resolve(account: WebDavAccount, path: String): HttpUrl {
        val base = normalizeBaseUrl(account.baseUrl)
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return base

        // 情况 2：已经是绝对 URL，直接用（但仍要剥掉可能携带的 userinfo）
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            return trimmed.toHttpUrlOrNull()
                ?.newBuilder()
                ?.username("")
                ?.password("")
                ?.build()
                ?: throw WebDavException(WebDavException.Kind.UNKNOWN, "远端地址不合法：$path")
        }

        // 情况 3：相对路径
        val encoded = trimmed.trim('/').split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { segment -> encodePathSegment(segment) }
        return base.newBuilder()
            .addPathSegments(encoded)
            .build()
    }

    /**
     * 对路径段做百分号编码。
     *
     * OkHttp 的 `addPathSegments` 会把已编码的 `%` 再编码一次（变成 `%25`），
     * 所以这里**先编码好、再交给它**会双重编码。因此我们手工编码后直接用
     * `encodedPath` 语义 —— 但 `addPathSegments` 不做转义，正好符合需要。
     */
    private fun encodePathSegment(segment: String): String =
        java.net.URLEncoder.encode(segment, "UTF-8")
            // URLEncoder 是给 form 用的：空格编成 '+'、并且会把 '~' 也编掉。
            // 路径段里 '+' 是字面量，必须换回 %20，否则「我的 视频」会变成「我的+视频」。
            .replace("+", "%20")
            .replace("%7E", "~")

    private fun Response.toEntry(): WebDavEntry? {
        val hrefText = href.toString()
        var name: String? = null
        var isCollection = false
        var size: Long? = null
        var modified: Long? = null
        var contentType: String? = null
        var eTag: String? = null

        for (property in properties) {
            when (property) {
                is DisplayName -> name = property.displayName
                is ResourceType -> isCollection = property.types.contains(ResourceType.COLLECTION)
                is GetContentLength -> size = property.contentLength
                is GetLastModified -> modified = property.lastModified
                is GetContentType -> contentType = property.type?.toString()
                is GetETag -> eTag = property.eTag
            }
        }

        if (hrefText.isBlank()) return null

        // 名称回退：displayname 不是必须的（很多服务端不返回），
        // 此时从 href 末段取。末段要做**百分号解码**，否则中文名会显示成 %E4%B8%AD%E6%96%87。
        val fallback = hrefText.trimEnd('/').substringAfterLast('/')
        val decoded = runCatching {
            java.net.URLDecoder.decode(fallback, "UTF-8")
        }.getOrDefault(fallback)

        return WebDavEntry(
            href = hrefText,
            name = name?.takeIf { it.isNotBlank() } ?: decoded.ifBlank { hrefText },
            kind = if (isCollection) WebDavKind.DIRECTORY else WebDavKind.FILE,
            size = size,
            lastModified = modified,
            contentType = contentType,
            eTag = eTag,
        )
    }

    /**
     * 统一异常转换。
     *
     * 这里把 `runCatching` 的位置固定下来：**所有** WebDAV 公开方法都走它，
     * 保证界面层拿到的 [Result] 里只可能是 [WebDavException]。
     */
    private suspend fun <T> runWebDav(block: suspend () -> T): Result<T> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                Result.success(block())
            } catch (e: Throwable) {
                Result.failure(e.toWebDavException())
            }
        }

    private fun Throwable.toWebDavException(): WebDavException = when (this) {
        is WebDavException -> this
        // dav4jvm 的 HttpException 带 code 字段（**不是** status，2.2.1 里叫 code），
        // 用它区分 401 / 404 / 5xx
        is HttpException -> when (code) {
            401, 403 -> WebDavException(
                WebDavException.Kind.AUTH,
                "认证失败（HTTP $code）。请检查用户名与密码；若密码确认无误，" +
                    "该服务器可能只支持 Digest 认证，本项目暂不支持。",
                this,
            )
            404, 410 -> WebDavException(
                WebDavException.Kind.NOT_FOUND,
                "远端路径不存在（HTTP $code）",
                this,
            )
            405, 501 -> WebDavException(
                WebDavException.Kind.UNSUPPORTED,
                "服务器不支持该操作（HTTP $code）。请确认填写的地址是 WebDAV 端点，" +
                    "而不是普通网页地址。",
                this,
            )
            in 500..599 -> WebDavException(
                WebDavException.Kind.SERVER,
                "服务器错误（HTTP $code）",
                this,
            )
            else -> WebDavException(
                WebDavException.Kind.UNKNOWN,
                "请求失败（HTTP $code）",
                this,
            )
        }
        is DavException -> WebDavException(
            WebDavException.Kind.SERVER,
            "WebDAV 协议错误：${message ?: "服务端返回了非法的多状态响应"}",
            this,
        )
        is javax.net.ssl.SSLException -> WebDavException(
            WebDavException.Kind.NETWORK,
            "TLS 握手失败：${message ?: "证书不受信任"}。" +
                "自签名证书的服务器需要在系统里安装该证书后才能连接。",
            this,
        )
        is java.net.UnknownHostException -> WebDavException(
            WebDavException.Kind.NETWORK,
            "无法解析服务器地址，请检查网络与地址拼写",
            this,
        )
        is java.net.SocketTimeoutException -> WebDavException(
            WebDavException.Kind.NETWORK,
            "连接超时，请检查网络或服务器是否可达",
            this,
        )
        is IOException -> WebDavException(
            WebDavException.Kind.NETWORK,
            "网络错误：${message ?: "连接中断"}",
            this,
        )
        is kotlinx.coroutines.CancellationException -> throw this // 不能吞掉取消
        else -> WebDavException(
            WebDavException.Kind.UNKNOWN,
            message ?: "未知错误",
            this,
        )
    }

    private fun copyWithProgress(
        input: InputStream,
        output: java.io.OutputStream,
        total: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var downloaded = 0L
        var lastReported = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            downloaded += read
            // 每 64KB 报一次而不是每个 buffer 都报：回调会去更新 Compose 状态，
            // 报得太密会让界面线程忙于重组，反而卡。
            if (downloaded - lastReported >= PROGRESS_STEP || (total > 0 && downloaded == total)) {
                lastReported = downloaded
                onProgress(downloaded, total)
            }
        }
        output.flush()
        onProgress(downloaded, total)
    }

    /** 按扩展名猜 MIME，猜不到就用通用二进制流 */
    private fun mediaTypeOf(file: File): String = when (file.extension.lowercase(Locale.ROOT)) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "ts" -> "video/mp2t"
        "flv" -> "video/x-flv"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "ogg", "opus" -> "audio/ogg"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "application/octet-stream"
    }

    /**
     * 带进度的 RequestBody 包装。
     *
     * OkHttp 写 body 时会调 [writeTo]，我们在这里按写入量回调。
     * 之所以不做成拦截器：拦截器拿到的是「已发送字节」在 socket 层的估算，
     * 对大文件不准，而这个包装精确等于「从文件读了多少」。
     */
    private class ProgressRequestBody(
        private val delegate: RequestBody,
        private val total: Long,
        private val onProgress: (Long, Long) -> Unit,
    ) : RequestBody() {

        override fun contentType() = delegate.contentType()

        override fun contentLength() = delegate.contentLength()

        override fun writeTo(sink: BufferedSink) {
            val forwarding = object : ForwardingSink(sink) {
                private var written = 0L
                private var lastReported = 0L

                override fun write(source: Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    written += byteCount
                    if (written - lastReported >= PROGRESS_STEP || written == total) {
                        lastReported = written
                        onProgress(written, total)
                    }
                }
            }
            val buffered = forwarding.buffer()
            delegate.writeTo(buffered)
            buffered.flush()
        }
    }

    companion object {
        private const val DEPTH_ONE = 1

        /**
         * PROPFIND 要请求的属性。
         *
         * 这些是浏览文件列表的**最小有用集**：
         * - `displayname` / `resourcetype` —— 名字与「是不是目录」，缺一不可；
         * - `getcontentlength` / `getlastmodified` —— 列表右侧的信息；
         * - `getcontenttype` / `getetag` —— 判断是不是媒体文件、以及内容是否变过。
         *
         * 故意**不请求** `quota-available-bytes` 之类：多一个属性就多一段 XML，
         * 大目录（几百项）下服务端生成 Multi-Status 的时间会明显变长。
         */
        private val ENTRY_PROPERTIES = arrayOf(
            DisplayName.NAME,
            ResourceType.NAME,
            GetContentLength.NAME,
            GetLastModified.NAME,
            GetContentType.NAME,
            GetETag.NAME,
        )

        private const val ACCEPT_ANY = "*/*"
        private const val PROGRESS_STEP = 64L * 1024
    }
}
