package xyz.yophon.gopan.core.data.archive

import androidx.compose.runtime.Immutable

/*
 * 压缩包(doc/21 四)的领域模型与纯函数:服务端按文件内容建一次目录索引(还没建好时 PENDING),
 * 浏览按目录分页,单个文件经 GET /arc/{nodeId}/{idx} 由服务端解出来流式返回,解压是后台任务。
 */

/** 与 GraphQL 的 ArchiveStatus 同名;服务端新增的值按 FAILED 处理 */
enum class ArchiveStatus { PENDING, DONE, TRUNCATED, UNSUPPORTED, FAILED }

/** 压缩包概要 */
@Immutable
data class ArchiveInfo(
    val status: ArchiveStatus,
    /** zip / 7z / rar / tar / tar.gz / tar.bz2 / tar.xz */
    val format: String,
    val entryCount: Int,
    val fileCount: Int,
    /** 解压后总大小(按条目声明) */
    val totalSize: Long,
    val solid: Boolean,
    val encrypted: Boolean,
    /** UNSUPPORTED / FAILED 的原因(中文) */
    val error: String? = null,
) {
    /** 目录读完了(全部,或条目太多只列了一部分):可以浏览、解压 */
    val ready: Boolean get() = status == ArchiveStatus.DONE || status == ArchiveStatus.TRUNCATED
}

/** 包里的一项(文件或目录) */
@Immutable
data class ArchiveEntry(
    /** 包内行号:单个文件的地址是 /arc/{nodeId}/{idx} */
    val idx: Int,
    /** 包内完整路径,"/" 分隔、不带开头的 / */
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modifiedAt: String? = null,
    /** 非空时不能预览、不能解压:encrypted / unsafe(路径带 ..)/ link / unsupported */
    val flag: String? = null,
    /** 能直接预览(图片、PDF、文本,且大小与在包里的深度都在限度内) */
    val previewable: Boolean = false,
    /** 能单独取出来(大小与深度在限度内) */
    val downloadable: Boolean = false,
) {
    /** 带标记的条目解压时会被跳过,所以不能勾选 */
    val selectable: Boolean get() = flag == null
}

@Immutable
data class ArchiveEntryPage(val items: List<ArchiveEntry>, val nextCursor: String?, val total: Int) {
    companion object {
        val EMPTY = ArchiveEntryPage(emptyList(), null, 0)
    }
}

/**
 * 一次解压:[paths] 是包内完整路径,空 = [base] 下的全部;解出来的路径相对 [base]
 * (浏览 docs/、勾选 docs/2024 → 新文件夹/2024/…)。解到 [parentId] 下以压缩包命名的新文件夹(撞名改名,不合并);
 * [parentId] 为 null = 根目录。
 */
@Immutable
data class ExtractRequest(
    val nodeId: String,
    val base: String? = null,
    val paths: List<String> = emptyList(),
    val parentId: String? = null,
)

/** 条目的服务端标记 */
object ArchiveFlags {
    const val ENCRYPTED = "encrypted"
    const val UNSAFE = "unsafe"
    const val LINK = "link"
    const val UNSUPPORTED = "unsupported"
}

/** 包里的文件按什么方式看(服务端只放行图片、PDF、文本的预览) */
enum class EntryKind { IMAGE, PDF, TEXT }

/** 单个文件的上限:超过的服务端不给单独取(ARCHIVE_ENTRY_TOO_LARGE),要解压后再看 */
const val ARCHIVE_ENTRY_MAX_BYTES: Long = 100L * 1024 * 1024

private val ARCHIVE_NAME = Regex("""\.(zip|7z|rar|tar|tgz|tbz2?|txz|tar\.(gz|bz2|xz))$""", RegexOption.IGNORE_CASE)
private val RAR_PART = Regex("""\.part0*([0-9]+)\.rar$""", RegexOption.IGNORE_CASE)

/** 文件名看起来是压缩包:与服务端 archive.ByName 同一口径,分卷 rar 只认第一卷 */
fun isArchiveName(name: String): Boolean {
    if (!ARCHIVE_NAME.containsMatchIn(name)) return false
    val part = RAR_PART.find(name) ?: return true
    return part.groupValues[1] == "1"
}

private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "avif", "ico")

/** 可预览条目的查看方式:按扩展名分图片 / PDF,其余按文本(与网页端 entryPreviewKind 一致) */
fun entryKind(name: String): EntryKind {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        ext in IMAGE_EXTS -> EntryKind.IMAGE
        ext == "pdf" -> EntryKind.PDF
        else -> EntryKind.TEXT
    }
}

/** 包内路径的上一级:"a/b/c" → "a/b","a" → "",根目录还是根目录 */
fun archiveParentPath(path: String): String = path.trim('/').substringBeforeLast('/', "")
