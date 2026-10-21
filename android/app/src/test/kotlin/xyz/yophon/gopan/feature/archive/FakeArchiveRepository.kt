package xyz.yophon.gopan.feature.archive

import xyz.yophon.gopan.core.data.archive.ArchiveEntry
import xyz.yophon.gopan.core.data.archive.ArchiveEntryPage
import xyz.yophon.gopan.core.data.archive.ArchiveInfo
import xyz.yophon.gopan.core.data.archive.ArchiveRepository
import xyz.yophon.gopan.core.data.archive.ArchiveStatus
import xyz.yophon.gopan.core.data.archive.ExtractRequest
import xyz.yophon.gopan.core.data.archive.readTextSegment
import xyz.yophon.gopan.core.data.preview.TextContent
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import java.io.File

/** 压缩包单测用:概要按顺序依次返回(最后一个一直返回),目录按 (path, cursor) 取页,记录解压与取文件 */
class FakeArchiveRepository : ArchiveRepository {
    private val infos = ArrayDeque<ArchiveInfo>()
    var infoCalls = 0
    var infoError: GopanException? = null

    val pages = mutableMapOf<Pair<String, String?>, ArchiveEntryPage>()
    val entryCalls = mutableListOf<Pair<String, String?>>()

    val extracts = mutableListOf<ExtractRequest>()
    var extractError: GopanException? = null

    /** idx → 取到本地的文件;没有的抛 [fetchError] 或 NOT_FOUND */
    val files = mutableMapOf<Int, File>()
    var fetchError: GopanException? = null
    val fetched = mutableListOf<Int>()
    val released = mutableListOf<String>()

    fun respondInfo(vararg list: ArchiveInfo) {
        infos.clear()
        infos.addAll(list)
    }

    fun page(path: String, vararg items: ArchiveEntry, cursor: String? = null, next: String? = null, total: Int = items.size) {
        pages[path to cursor] = ArchiveEntryPage(items.toList(), next, total)
    }

    override suspend fun info(nodeId: String): ArchiveInfo {
        infoCalls++
        infoError?.let {
            infoError = null
            throw it
        }
        return if (infos.size > 1) infos.removeFirst() else infos.firstOrNull() ?: throw GopanException(GopanErrors.NOT_FOUND, "不存在")
    }

    override suspend fun entries(nodeId: String, path: String, cursor: String?): ArchiveEntryPage {
        entryCalls += path to cursor
        return pages[path to cursor] ?: throw GopanException(GopanErrors.NETWORK, "网络连接失败")
    }

    override suspend fun extract(request: ExtractRequest): String {
        extracts += request
        extractError?.let {
            extractError = null
            throw it
        }
        return "job-${extracts.size}"
    }

    override suspend fun fetchEntry(nodeId: String, entry: ArchiveEntry, onProgress: (Long, Long?) -> Unit): File {
        fetched += entry.idx
        fetchError?.let {
            fetchError = null
            throw it
        }
        val file = files[entry.idx] ?: throw GopanException(GopanErrors.NOT_FOUND, "不存在")
        onProgress(file.length() / 2, file.length())
        onProgress(file.length(), file.length())
        return file
    }

    override suspend fun readText(file: File, offset: Long, charset: String?): TextContent =
        readTextSegment(file, offset, charset, maxBytes = textMaxBytes)

    /** 文本每段读多少(测分段) */
    var textMaxBytes = 512 * 1024

    override fun release(nodeId: String) {
        released += nodeId
    }

    companion object {
        fun archiveInfo(
            status: ArchiveStatus = ArchiveStatus.DONE,
            format: String = "zip",
            fileCount: Int = 3,
            totalSize: Long = 4096,
            encrypted: Boolean = false,
            error: String? = null,
        ) = ArchiveInfo(
            status = status,
            format = format,
            entryCount = fileCount + 1,
            fileCount = fileCount,
            totalSize = totalSize,
            solid = false,
            encrypted = encrypted,
            error = error,
        )

        fun dir(idx: Int, path: String) = ArchiveEntry(idx = idx, path = path, name = path.substringAfterLast('/'), isDir = true, size = 0)

        fun file(
            idx: Int,
            path: String,
            size: Long = 10,
            flag: String? = null,
            previewable: Boolean = flag == null,
            downloadable: Boolean = flag == null,
        ) = ArchiveEntry(
            idx = idx,
            path = path,
            name = path.substringAfterLast('/'),
            isDir = false,
            size = size,
            modifiedAt = "2024-05-01T08:00:00Z",
            flag = flag,
            previewable = previewable,
            downloadable = downloadable,
        )
    }
}
