package xyz.yophon.gopan.feature.archive

import androidx.compose.runtime.Immutable
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.PreviewKind
import xyz.yophon.gopan.core.data.archive.ARCHIVE_ENTRY_MAX_BYTES
import xyz.yophon.gopan.core.data.archive.ArchiveEntry
import xyz.yophon.gopan.core.data.archive.ArchiveFlags
import xyz.yophon.gopan.core.data.archive.ArchiveInfo
import xyz.yophon.gopan.core.data.archive.ArchiveStatus
import xyz.yophon.gopan.core.data.archive.isArchiveName
import xyz.yophon.gopan.feature.drive.UiText

/*
 * 压缩包浏览的展示口径(与网页端 utils/archive.ts 一致)。纯函数,单测直接引。
 */

/** 网盘里的这个文件能当压缩包浏览 / 解压:服务端认出来了(预览类型 ARCHIVE),或名字像(索引还没建) */
fun isArchiveNode(node: Node): Boolean =
    !node.isFolder && (node.preview.kind == PreviewKind.ARCHIVE || isArchiveName(node.name))

/** 面包屑的一级(不含根);[path] 是包内完整路径 */
@Immutable
data class ArchiveCrumb(val name: String, val path: String)

/** 包内路径 → 面包屑(不含根)。"a/b/c" → a, a/b, a/b/c */
fun archiveCrumbs(path: String): List<ArchiveCrumb> {
    val parts = path.split('/').filter { it.isNotEmpty() }
    return parts.mapIndexed { i, name -> ArchiveCrumb(name, parts.subList(0, i + 1).joinToString("/")) }
}

/** 面包屑根上显示的格式名:zip → ZIP,tar.gz → TAR.GZ */
fun formatLabel(format: String): String = format.uppercase()

/** 条目标记的短标签(行尾小标签):已加密 / 路径不安全 / 链接 / 不支持的压缩方式;不认识的原样显示 */
fun flagLabel(flag: String?): UiText? = when (flag) {
    null, "" -> null
    ArchiveFlags.ENCRYPTED -> UiText.Res(R.string.archive_flag_encrypted)
    ArchiveFlags.UNSAFE -> UiText.Res(R.string.archive_flag_unsafe)
    ArchiveFlags.LINK -> UiText.Res(R.string.archive_flag_link)
    ArchiveFlags.UNSUPPORTED -> UiText.Res(R.string.archive_flag_unsupported)
    else -> UiText.Plain(flag)
}

/** 文件为什么不能单独打开 */
enum class EntryBlock {
    /** 带标记(加密、路径不安全、链接、不支持的压缩方式) */
    FLAGGED,

    /** 超过 100 MB */
    TOO_LARGE,

    /** 要先解开前面太多数据(固实 7z、rar、压缩的 tar) */
    TOO_DEEP,
}

/** 不能单独打开的原因;目录和能打开的文件为 null */
fun ArchiveEntry.block(): EntryBlock? = when {
    isDir -> null
    flag != null -> EntryBlock.FLAGGED
    previewable || downloadable -> null
    size > ARCHIVE_ENTRY_MAX_BYTES -> EntryBlock.TOO_LARGE
    else -> EntryBlock.TOO_DEEP
}

/** 点了打不开的条目时的说明 */
fun ArchiveEntry.blockMessage(): UiText? = when (block()) {
    null -> null
    EntryBlock.FLAGGED -> when (flag) {
        ArchiveFlags.ENCRYPTED -> UiText.Res(R.string.archive_flag_msg_encrypted)
        ArchiveFlags.UNSAFE -> UiText.Res(R.string.archive_flag_msg_unsafe)
        ArchiveFlags.LINK -> UiText.Res(R.string.archive_flag_msg_link)
        else -> UiText.Res(R.string.archive_flag_msg_unsupported)
    }
    EntryBlock.TOO_LARGE -> UiText.Res(R.string.archive_entry_too_large)
    EntryBlock.TOO_DEEP -> UiText.Res(R.string.archive_entry_too_deep)
}

/** 点一行做什么 */
enum class EntryTap {
    /** 进入目录 */
    OPEN_DIR,

    /** 在 App 里看(图片、PDF、文本) */
    PREVIEW,

    /** 取下来交给其他应用打开 / 分享 */
    FILE_ACTIONS,

    /** 打不开,提示原因 */
    BLOCKED,
}

fun ArchiveEntry.tapAction(): EntryTap = when {
    isDir -> EntryTap.OPEN_DIR
    block() != null -> EntryTap.BLOCKED
    previewable -> EntryTap.PREVIEW
    else -> EntryTap.FILE_ACTIONS
}

/** 解压按钮的说法:勾了几项 → 解压选中的 N 项;没勾 → 全部解压(根目录)/ 解压此目录 */
sealed interface ExtractLabel {
    data object All : ExtractLabel

    data object CurrentDir : ExtractLabel

    data class Selected(val count: Int) : ExtractLabel

    val text: UiText
        get() = when (this) {
            All -> UiText.Res(R.string.archive_extract_all)
            CurrentDir -> UiText.Res(R.string.archive_extract_dir)
            is Selected -> UiText.Res(R.string.archive_extract_selected, count)
        }
}

fun extractLabel(dir: String, selected: Int): ExtractLabel = when {
    selected > 0 -> ExtractLabel.Selected(selected)
    dir.isEmpty() -> ExtractLabel.All
    else -> ExtractLabel.CurrentDir
}

/** 预览页上压缩包概要卡片的几种样子 */
sealed interface ArchiveSummary {
    /** 服务端还在读目录(每 2 秒问一次) */
    data object Pending : ArchiveSummary

    /** [truncated]:条目太多或包太大,只列了一部分;[encrypted]:有文件加密了,能看名字不能预览和解压 */
    data class Ready(
        val format: String,
        val fileCount: Int,
        val totalSize: Long,
        val truncated: Boolean,
        val encrypted: Boolean,
    ) : ArchiveSummary

    /** 打不开(格式不支持、读取失败);[message] 服务端给的原因,null 用默认说法 */
    data class Unavailable(val message: String?) : ArchiveSummary
}

fun ArchiveInfo.summary(): ArchiveSummary = when (status) {
    ArchiveStatus.PENDING -> ArchiveSummary.Pending
    ArchiveStatus.DONE, ArchiveStatus.TRUNCATED -> ArchiveSummary.Ready(
        format = formatLabel(format),
        fileCount = fileCount,
        totalSize = totalSize,
        truncated = status == ArchiveStatus.TRUNCATED,
        encrypted = encrypted,
    )
    ArchiveStatus.UNSUPPORTED, ArchiveStatus.FAILED -> ArchiveSummary.Unavailable(error?.takeIf { it.isNotBlank() })
}
