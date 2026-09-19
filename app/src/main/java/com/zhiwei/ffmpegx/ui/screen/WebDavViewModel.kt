package com.zhiwei.ffmpegx.ui.screen

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhiwei.ffmpegx.core.settings.SettingsRepository
import com.zhiwei.ffmpegx.core.settings.WebDavSettings
import com.zhiwei.ffmpegx.core.webdav.WebDavAccount
import com.zhiwei.ffmpegx.core.webdav.WebDavClient
import com.zhiwei.ffmpegx.core.webdav.WebDavEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * WebDAV 页面的状态。
 *
 * ## 为什么「当前目录」用栈而不是字符串
 *
 * 用「当前路径字符串」表示位置时，用户点到一半返回上一层会丢失进入时的来源；
 * 而 WebDAV 的 href 是服务端给的**绝对地址**，未必等于 `base + 相对路径`
 * （Nextcloud 会在中间插 `/remote.php/dav/`，有些服务端还会重定向）。
 * 所以这里保存**服务端返回的 href 列表**当作栈 —— 返回上一层时直接用上一项的
 * href 再列一次，不做任何字符串拼接。这样无论服务端的 URL 结构多奇怪都不会错。
 *
 * 栈空表示在配置页（还没连上）。
 */
data class WebDavUiState(
    val settings: WebDavSettings = WebDavSettings(),
    /** 地址/用户名/密码的编辑态（未保存），保存后才写入仓库 */
    val urlDraft: String = "",
    val userDraft: String = "",
    val passwordDraft: String = "",

    /** 目录栈；元素是服务端返回的 href。空表示未连接 */
    val pathStack: List<String> = emptyList(),
    val entries: List<WebDavEntry> = emptyList(),

    val connecting: Boolean = false,
    /** 从下载/上传得到的进度：0..1，负数表示不确定 */
    val progress: Float = -1f,
    /** 进度旁的文字，例如「正在上传 video.mp4」 */
    val progressLabel: String = "",

    val error: String? = null,
    val message: String? = null,
) {
    val isConnected: Boolean get() = pathStack.isNotEmpty()

    /** 当前目录的展示路径（用服务端 href 的路径部分） */
    val currentPath: String
        get() = pathStack.lastOrNull()
            ?.let { runCatching { java.net.URI(it).path }.getOrNull() }
            ?.trimEnd('/')
            ?: ""

    /** 当前目录里选中的是「哪一层」——用于面包屑 */
    val breadcrumbs: List<String>
        get() = pathStack.map { href ->
            val path = runCatching { java.net.URI(href).path }.getOrNull().orEmpty()
            path.trimEnd('/').substringAfterLast('/').ifBlank { "/" }
        }

    val busy: Boolean get() = connecting || progress >= 0f
}

/**
 * WebDAV 页面的 ViewModel。
 *
 * 职责：保管草稿状态、驱动 [WebDavClient]、把异常转成一句人话。
 * 真正的目录栈语义见 [WebDavUiState] 的注释。
 *
 * ⚠️ 所有操作都是**串行**的：一个上传/下载没结束时新的请求会被拒（`busy` 期间
 * 界面按钮也禁用）。WebDAV 服务端（尤其是自建 NAS）对并发连接很敏感，
 * 并发几个大文件传输经常直接 503，串行是最稳的选择 —— 和本项目任务队列
 * 「必须串行」的理由不同，但结论一样。
 */
@HiltViewModel
class WebDavViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SettingsRepository,
    private val client: WebDavClient,
) : ViewModel() {

    private val _state = MutableStateFlow(WebDavUiState())
    val state: StateFlow<WebDavUiState> = _state.asStateFlow()

    private var activeJob: Job? = null

    init {
        // 首次读到已保存的设置后填进草稿（只在草稿还空着时填，不覆盖用户正在输入的内容）
        viewModelScope.launch {
            repository.webDav.collect { saved ->
                _state.update { current ->
                    if (current.urlDraft.isBlank() && current.userDraft.isBlank()) {
                        current.copy(
                            settings = saved,
                            urlDraft = saved.baseUrl,
                            userDraft = saved.username,
                            passwordDraft = saved.password,
                        )
                    } else {
                        current.copy(settings = saved)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ 草稿编辑 ----

    fun setUrlDraft(value: String) = _state.update { it.copy(urlDraft = value, error = null) }
    fun setUserDraft(value: String) = _state.update { it.copy(userDraft = value, error = null) }
    fun setPasswordDraft(value: String) = _state.update { it.copy(passwordDraft = value, error = null) }
    fun consumeError() = _state.update { it.copy(error = null) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    /** 把草稿写入仓库（不验证连通性，供「保存」按钮用） */
    fun saveDraft() {
        val current = _state.value
        viewModelScope.launch {
            repository.setWebDav(
                baseUrl = current.urlDraft,
                username = current.userDraft,
                password = current.passwordDraft,
            )
            _state.update { it.copy(message = "已保存") }
        }
    }

    // -------------------------------------------------------------- 连接 ----

    /**
     * 测试连接并进入根目录。
     *
     * 顺序刻意是「先存 → 再连」：如果不先保存，这次连接用的是草稿里的凭据，
     * 而下次打开页面又从仓库里读——用户会看到「连上了但密码是空的」这种怪状态。
     */
    fun connect() {
        if (_state.value.busy) return
        val draft = _state.value
        activeJob = viewModelScope.launch {
            _state.update { it.copy(connecting = true, error = null) }

            repository.setWebDav(draft.urlDraft, draft.userDraft, draft.passwordDraft)

            val account = WebDavAccount(
                baseUrl = draft.urlDraft,
                username = draft.userDraft,
                password = draft.passwordDraft,
            )

            // 地址合法性直接反馈，不走网络
            val base = runCatching { client.normalizeBaseUrl(account.baseUrl) }.getOrElse { e ->
                _state.update {
                    it.copy(connecting = false, error = e.message ?: "地址不合法")
                }
                return@launch
            }

            client.testConnection(account)
                .onFailure { e ->
                    _state.update { it.copy(connecting = false, error = e.message ?: "连接失败") }
                }
                .onSuccess { count ->
                    // 优先回到上次离开的目录（比如专门放素材的那个目录），
                    // 但只在该目录仍然可列时才用 —— 服务端可能已经删掉它了。
                    // 恢复失败就静默退回根目录，不该为「记不住上次位置」报错。
                    val remembered = _state.value.settings.lastRemoteDir
                    val restored = remembered
                        .takeIf { it.isNotBlank() && it != base.toString() }
                        ?.let { candidate ->
                            client.list(account, candidate).getOrNull()?.let { candidate }
                        }
                    val target = restored ?: base.toString()

                    _state.update {
                        it.copy(
                            connecting = false,
                            // 直接进目录：点「连接」的用户预期就是看列表，
                            // 让他再点一次「根目录」是多余的一步。
                            pathStack = listOf(target),
                            message = if (count > 0) "连接成功，根目录有 $count 项" else "连接成功",
                        )
                    }
                    listInternal(target, listOf(target))
                }
        }
    }

    // ------------------------------------------------------------ 目录浏览 ----

    /** 重新列当前目录（下拉刷新用）。栈不变 —— 刷新的是同一个目录。 */
    fun refresh() {
        val stack = _state.value.pathStack
        val href = stack.lastOrNull() ?: return
        listInternal(href, stack)
    }

    /** 进入某个子目录 */
    fun openDirectory(entry: WebDavEntry) {
        if (!entry.isDirectory) return
        listInternal(entry.href, _state.value.pathStack + entry.href)
    }

    /**
     * 返回上一层。
     *
     * 栈里只剩一项时不退 —— 那一项是根目录，退出去没有意义，
     * 用户想离开用系统返回键。
     */
    fun navigateUp() {
        val stack = _state.value.pathStack
        if (stack.size <= 1) return
        listInternal(stack[stack.size - 2], stack.dropLast(1))
    }

    /** 回到指定层级（面包屑点击） */
    fun jumpTo(depth: Int) {
        val stack = _state.value.pathStack
        if (depth !in stack.indices || depth == stack.lastIndex) return
        listInternal(stack[depth], stack.take(depth + 1))
    }

    /**
     * 列目录，并把目录栈换成 [targetStack]。
     *
     * 栈**由调用方算好传入**，而不是在这里靠一个 `replace` 布尔值推断 ——
     * 早先用布尔值时，「刷新」和「进入新目录」两条相反语义共用同一个入口，
     * 一旦推断写错（刷新时会把自己又追加一遍），面包屑就会越滚越长。
     * 传完整目标栈，任何调用点都没有歧义。
     */
    private fun listInternal(
        href: String,
        targetStack: List<String>,
    ) {
        if (_state.value.busy) return
        val account = currentAccount() ?: return
        activeJob = viewModelScope.launch {
            _state.update { it.copy(connecting = true, error = null) }
            // 用 href 作为 path 传入：client.resolve 会把它当成 base 的相对路径，
            // 但因为 href 是绝对 URL，`addPathSegments` 前的 normalize 会识破。
            client.list(account, href)
                .onFailure { e ->
                    _state.update { it.copy(connecting = false, error = e.message ?: "读取目录失败") }
                }
                .onSuccess { items ->
                    _state.update {
                        it.copy(
                            connecting = false,
                            entries = items,
                            pathStack = targetStack,
                        )
                    }
                    // 列表成功即认为这个目录是「有效位置」，记下来供下次连接恢复。
                    // 放在成功后而不是点击时：点进去才发现目录已被删的情况不该被记住。
                    rememberCurrentDir()
                }
        }
    }

    /**
     * 在**当前目录下**建一个子目录。
     *
     * 用 [WebDavClient.makeDirectories] 而不是 `makeDirectory`：用户可能输入
     * `素材/2026/09` 这样的多级名字，逐级建才不会 409。单级名字只是它的特例。
     */
    fun createDirectory(name: String) {
        val trimmed = name.trim().trim('/')
        if (trimmed.isEmpty()) {
            _state.update { it.copy(error = "请输入文件夹名") }
            return
        }
        val href = _state.value.pathStack.lastOrNull() ?: return
        val account = currentAccount() ?: return
        if (_state.value.busy) return
        // 相对当前目录的路径：makeDirectories 会相对 baseUrl 逐级建，
        // 所以要把当前目录的 path 拼在前面。
        val parent = runCatching { java.net.URI(href).path }.getOrNull().orEmpty().trim('/')
        val remoteDir = if (parent.isEmpty()) trimmed else "$parent/$trimmed"

        activeJob = viewModelScope.launch {
            _state.update { it.copy(connecting = true, error = null) }
            client.makeDirectories(account, remoteDir)
                .onFailure { e ->
                    _state.update { it.copy(connecting = false, error = e.message ?: "新建文件夹失败") }
                }
                .onSuccess {
                    _state.update { it.copy(connecting = false, message = "已创建 $trimmed") }
                    refresh()
                }
        }
    }

    // ------------------------------------------------------------ 选取文件 ----

    /**
     * 下载远端文件到应用缓存。
     *
     * 为什么下到 cacheDir 而不是用户可见目录：这只是「拿到本机」的临时中转，
     * 系统在空间紧张时会自动回收，不会污染用户的文件列表。用户若想长期保留，
     * 用系统文件管理器另存即可。
     *
     * [onReady] 可选，用于把下载好的路径交给别的流程（例如作为转码输入）；
     * 只做浏览时不需要它。
     */
    fun downloadToCache(entry: WebDavEntry, onReady: ((String) -> Unit)? = null) {
        if (_state.value.busy || entry.isDirectory) return
        val account = currentAccount() ?: return
        activeJob = viewModelScope.launch {
            val dir = File(context.cacheDir, "webdav").apply { mkdirs() }
            val target = File(dir, entry.name)
            _state.update {
                it.copy(progress = 0f, progressLabel = "正在下载 ${entry.name}", error = null)
            }
            client.download(account, entry.href, target) { done, total ->
                val fraction = if (total > 0) done.toFloat() / total else -1f
                _state.update { it.copy(progress = fraction) }
            }
                .onFailure { e ->
                    _state.update {
                        it.copy(progress = -1f, progressLabel = "", error = e.message ?: "下载失败")
                    }
                }
                .onSuccess { file ->
                    _state.update {
                        it.copy(
                            progress = -1f,
                            progressLabel = "",
                            message = "已下载到 ${file.absolutePath}",
                        )
                    }
                    onReady?.invoke(file.absolutePath)
                }
        }
    }

    /**
     * 把系统文件选择器给的 content Uri 复制到缓存，再上传。
     *
     * 分成两步而不是直接流式上传：`content://` 的流不能随机访问，也无法预先
     * 知道长度，而 WebDAV 的 PUT 带 Content-Length 时服务端行为最稳、
     * 进度条也才准。先落盘换来的是「长度可知 + 可重试」。
     *
     * 文件名取自 [queryDisplayName]；极少数查不到名字的 Uri 退化成时间戳名，
     * 而不是固定的 `upload.bin` —— 后者会让连续上传的多个文件互相覆盖。
     */
    fun copyToCacheAndUpload(uri: android.net.Uri) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(progress = -1f, progressLabel = "正在读取文件…", error = null) }
            val copied = runCatching {
                val resolver = context.contentResolver
                val displayName = queryDisplayName(uri).ifBlank {
                    "upload-${System.currentTimeMillis()}.bin"
                }
                val dir = File(context.cacheDir, "upload").apply { mkdirs() }
                val target = File(dir, displayName)
                resolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: error("无法读取所选文件")
                target
            }.onFailure { e ->
                _state.update {
                    it.copy(progress = -1f, progressLabel = "", error = "读取文件失败：${e.message}")
                }
            }.getOrNull() ?: return@launch

            _state.update { it.copy(progress = -1f, progressLabel = "") }
            upload(copied)
        }
    }

    /** 查 content Uri 的显示名 */
    private fun queryDisplayName(uri: android.net.Uri): String = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0).orEmpty()
            } else {
                ""
            }
        }.orEmpty()
    }.getOrDefault("")

    /**
     * 上传本地文件到当前目录。
     *
     * 上传完成后把目录刷一遍 —— 用户立刻看到新文件出现在列表里，
     * 这比弹一个「上传成功」然后文件不在列表里要踏实得多。
     */
    fun upload(localFile: File) {
        if (_state.value.busy) return
        val account = currentAccount() ?: return
        val currentHref = _state.value.pathStack.lastOrNull() ?: return
        activeJob = viewModelScope.launch {
            _state.update {
                it.copy(progress = 0f, progressLabel = "正在上传 ${localFile.name}", error = null)
            }
            // 远端路径 = 当前目录的路径 + 文件名。
            // 这里用 href 而不是路径拼接：有些服务端（Nextcloud）的 href 带
            // 额外前缀，用 `base + 相对路径` 会拼错位置。
            val remote = currentHref.trimEnd('/') + "/" + localFile.name
            client.upload(account, remote, localFile) { sent, total ->
                val fraction = if (total > 0) sent.toFloat() / total else -1f
                _state.update { it.copy(progress = fraction) }
            }
                .onFailure { e ->
                    _state.update {
                        it.copy(progress = -1f, progressLabel = "", error = e.message ?: "上传失败")
                    }
                }
                .onSuccess {
                    _state.update {
                        it.copy(
                            progress = -1f,
                            progressLabel = "",
                            message = "已上传 ${localFile.name}",
                        )
                    }
                    refresh()
                }
        }
    }

    /** 删除远端条目（界面会先弹确认） */
    fun delete(entry: WebDavEntry) {
        if (_state.value.busy) return
        val account = currentAccount() ?: return
        activeJob = viewModelScope.launch {
            _state.update { it.copy(connecting = true, error = null) }
            client.delete(account, entry.href)
                .onFailure { e ->
                    _state.update { it.copy(connecting = false, error = e.message ?: "删除失败") }
                }
                .onSuccess {
                    _state.update { it.copy(connecting = false, message = "已删除 ${entry.name}") }
                    refresh()
                }
        }
    }

    /** 让界面把「已选中」的远端目录记下来，供下次上传默认使用 */
    fun rememberCurrentDir() {
        val path = _state.value.currentPath
        if (path.isNotBlank()) {
            viewModelScope.launch { repository.setWebDavLastDir(path) }
        }
    }

    private fun currentAccount(): WebDavAccount? {
        val s = _state.value
        if (s.urlDraft.isBlank()) {
            _state.update { it.copy(error = "请先填写服务器地址") }
            return null
        }
        return WebDavAccount(
            baseUrl = s.urlDraft,
            username = s.userDraft,
            password = s.passwordDraft,
        )
    }
}
