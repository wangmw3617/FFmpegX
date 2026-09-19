package com.zhiwei.ffmpegx.core.webdav

/**
 * WebDAV 远端条目的类型。
 *
 * 只用它区分「能不能进去」和「能不能下载」—— 界面上对应「进入目录」与「选用此文件」。
 */
enum class WebDavKind {
    /** 普通文件 */
    FILE,

    /** 目录（集合） */
    DIRECTORY,

    /** 其它（如 CalDAV 日历集合，本项目不处理但会显示出来） */
    COLLECTION,
}

/**
 * 一条远端条目。
 *
 * 这些字段全部来自 PROPFIND 的 `propstat`，都是**可选**的：WebDAV 服务端
 * 对各种属性的支持差异很大（有的不返回 `getcontentlength`，自建服务常见）。
 * 所以除路径以外的字段一律可空，缺失时界面留空而不是显示 0 / 空串 ——
 * 「未知」和「真的是 0 字节」在界面上必须是两回事。
 */
data class WebDavEntry(
    /** 服务器原始 href（已解析为绝对地址），后续 PUT/GET/DELETE 都用它 */
    val href: String,

    /** 展示名。服务端给了 displayname 就用它，否则退回 href 的最后一段 */
    val name: String,

    val kind: WebDavKind,

    /** 字节数，服务端未返回时为 null */
    val size: Long?,

    /** 最后修改时间（已转成毫秒时间戳），未返回时为 null */
    val lastModified: Long?,

    /** MIME 类型，未返回时为 null */
    val contentType: String?,

    /** 服务端返回的 ETag（含引号），用于条件请求与「内容是否变过」的比对 */
    val eTag: String?,
) {
    val isDirectory: Boolean get() = kind == WebDavKind.DIRECTORY

    /** 人类可读的大小，未知时返回 null */
    val displaySize: String?
        get() = size?.let { bytes ->
            when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
                bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
                else -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
            }
        }
}

/**
 * WebDAV 连接配置。
 *
 * `password` 由调用方从加密存储取出后传入，**本类型不负责持久化密码**。
 * 见 [WebDavRepository] 的说明。
 */
data class WebDavAccount(
    /** 服务器根地址，例如 `https://dav.example.com/remote.php/dav/files/alice/` */
    val baseUrl: String,

    val username: String,

    val password: String,
) {
    /**
     * 供界面展示的标识：把 URL 里的 userinfo 去掉，避免密码泄露到屏幕上。
     *
     * 用户有可能在地址栏直接写成 `https://user:pass@host/...`，
     * 那种写法必须在这里剥掉，否则日志和界面都会把凭据打出来。
     */
    val safeLabel: String
        get() = baseUrl
            .replace(Regex("^(https?://)[^/@]*@"), "$1")
            .trimEnd('/')
            .ifBlank { baseUrl }
}

/**
 * WebDAV 操作的统一异常。
 *
 * 为什么要把 dav4jvm / OkHttp 的一堆异常收敛成一个：
 * 调用方是 ViewModel，它只关心「怎么把这句话讲给用户听」。让 UI 层去
 * `catch (IOException | HttpException | DavException)` 会把网络库的实现细节
 * 泄漏到界面层，而且很容易漏掉某一类导致闪退。
 *
 * [kind] 用来区分可恢复与不可恢复，界面据此决定是提示重试还是让用户改配置。
 */
class WebDavException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    enum class Kind {
        /** 地址/端口不通、DNS 失败、超时 */
        NETWORK,

        /** 认证失败（401/403）—— 提示用户检查账号密码 */
        AUTH,

        /** 路径不存在（404）或已被移动 */
        NOT_FOUND,

        /** 服务端不支持该 WebDAV 操作（405/501），例如不是 WebDAV 端点 */
        UNSUPPORTED,

        /** 其它服务端错误（5xx） */
        SERVER,

        /** 本地磁盘问题（空间不足、目标不可写） */
        LOCAL_IO,

        /** 用户主动取消 */
        CANCELLED,

        /** 其它 */
        UNKNOWN,
    }

    /** 是否值得让用户「重试一次」 */
    val retryable: Boolean
        get() = kind == Kind.NETWORK || kind == Kind.SERVER
}
