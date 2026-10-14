package xyz.yophon.gopan.feature.drive

import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.NodeKind
import xyz.yophon.gopan.core.data.PreviewKind

/** 列表图标与详情页"类型"的分类(纯函数,可单测) */
enum class FileCategory {
    FOLDER, IMAGE, VIDEO, AUDIO, PDF, DOCUMENT, SPREADSHEET, PRESENTATION, ARCHIVE, TEXT, OTHER
}

/**
 * 分类规则:文件夹 → 服务端预览类型(最可靠)→ mime → 扩展名 → 其它。
 * OFFICE 预览类型再按扩展名细分表格/演示/文档,与网页端 fileIcon 一致。
 */
fun fileCategory(kind: NodeKind, name: String, mime: String?, previewKind: PreviewKind = PreviewKind.NONE): FileCategory {
    if (kind == NodeKind.FOLDER) return FileCategory.FOLDER
    val ext = extensionOf(name)
    when (previewKind) {
        PreviewKind.IMAGE -> return FileCategory.IMAGE
        PreviewKind.VIDEO -> return FileCategory.VIDEO
        PreviewKind.AUDIO -> return FileCategory.AUDIO
        PreviewKind.PDF, PreviewKind.NATIVE_PDF -> return FileCategory.PDF
        PreviewKind.TEXT -> return FileCategory.TEXT
        PreviewKind.OFFICE -> return officeCategory(ext)
        PreviewKind.NONE -> Unit
    }
    val m = mime?.lowercase()?.substringBefore(';')?.trim().orEmpty()
    when {
        m.startsWith("image/") -> return FileCategory.IMAGE
        m.startsWith("video/") -> return FileCategory.VIDEO
        m.startsWith("audio/") -> return FileCategory.AUDIO
        m == "application/pdf" -> return FileCategory.PDF
        m in ARCHIVE_MIMES -> return FileCategory.ARCHIVE
        m.startsWith("text/") -> return if (ext in SPREADSHEET_EXTS) FileCategory.SPREADSHEET else FileCategory.TEXT
    }
    return when (ext) {
        in IMAGE_EXTS -> FileCategory.IMAGE
        in VIDEO_EXTS -> FileCategory.VIDEO
        in AUDIO_EXTS -> FileCategory.AUDIO
        "pdf" -> FileCategory.PDF
        in SPREADSHEET_EXTS -> FileCategory.SPREADSHEET
        in PRESENTATION_EXTS -> FileCategory.PRESENTATION
        in DOCUMENT_EXTS -> FileCategory.DOCUMENT
        in ARCHIVE_EXTS -> FileCategory.ARCHIVE
        in TEXT_EXTS -> FileCategory.TEXT
        else -> FileCategory.OTHER
    }
}

fun fileCategory(node: Node): FileCategory = fileCategory(node.kind, node.name, node.mime, node.preview.kind)

/** 小写扩展名,没有则空串;"archive.tar.gz" 取 "gz" */
fun extensionOf(name: String): String {
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.length - 1) return ""
    return name.substring(dot + 1).lowercase()
}

private fun officeCategory(ext: String): FileCategory = when (ext) {
    in SPREADSHEET_EXTS -> FileCategory.SPREADSHEET
    in PRESENTATION_EXTS -> FileCategory.PRESENTATION
    else -> FileCategory.DOCUMENT
}

private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg", "tif", "tiff")
private val VIDEO_EXTS = setOf("mp4", "mkv", "mov", "avi", "webm", "m4v", "wmv", "flv", "ts", "3gp")
private val AUDIO_EXTS = setOf("mp3", "flac", "wav", "aac", "m4a", "ogg", "opus", "wma", "ape")
private val SPREADSHEET_EXTS = setOf("xls", "xlsx", "xlsm", "csv", "ods", "numbers")
private val PRESENTATION_EXTS = setOf("ppt", "pptx", "odp", "key")
private val DOCUMENT_EXTS = setOf("doc", "docx", "odt", "rtf", "pages", "wps")
private val ARCHIVE_EXTS = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "iso", "jar", "apk")
private val TEXT_EXTS = setOf(
    "txt", "md", "markdown", "log", "json", "xml", "yaml", "yml", "toml", "ini", "cfg", "conf",
    "kt", "kts", "java", "go", "py", "js", "ts", "vue", "html", "htm", "css", "sh", "bat", "sql", "c", "h", "cpp", "rs",
)
private val ARCHIVE_MIMES = setOf(
    "application/zip", "application/x-zip-compressed", "application/x-rar-compressed", "application/vnd.rar",
    "application/x-7z-compressed", "application/x-tar", "application/gzip", "application/x-gzip",
    "application/x-bzip2", "application/x-xz", "application/zstd",
)
