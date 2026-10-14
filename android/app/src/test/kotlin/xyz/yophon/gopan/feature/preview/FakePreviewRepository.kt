package xyz.yophon.gopan.feature.preview

import xyz.yophon.gopan.core.data.PreviewKind
import xyz.yophon.gopan.core.data.preview.DerivativeStatus
import xyz.yophon.gopan.core.data.preview.PreviewInfo
import xyz.yophon.gopan.core.data.preview.PreviewRepository
import xyz.yophon.gopan.core.data.preview.TextContent
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import java.io.File

/** 预览单测用:按节点排好依次返回的信息(最后一个一直返回),记录调用 */
class FakePreviewRepository : PreviewRepository {
    private val infos = mutableMapOf<String, ArrayDeque<PreviewInfo>>()
    val previewCalls = mutableMapOf<String, Int>()
    val requested = mutableListOf<String>()
    var requestResult: DerivativeStatus? = DerivativeStatus.PENDING
    var failNextPreview: GopanException? = null
    val texts = mutableMapOf<String, TextContent>()
    val pdfs = mutableListOf<Pair<String, String>>()

    fun respond(nodeId: String, vararg list: PreviewInfo) {
        infos[nodeId] = ArrayDeque(list.toList())
    }

    override suspend fun preview(nodeId: String): PreviewInfo {
        previewCalls[nodeId] = (previewCalls[nodeId] ?: 0) + 1
        failNextPreview?.let {
            failNextPreview = null
            throw it
        }
        val queue = infos[nodeId] ?: throw GopanException(GopanErrors.NOT_FOUND, "文件不存在")
        return if (queue.size > 1) queue.removeFirst() else queue.first()
    }

    override suspend fun requestPreview(nodeId: String): DerivativeStatus? {
        requested += nodeId
        return requestResult
    }

    val textCalls = mutableListOf<Triple<String, Long, String?>>()

    override suspend fun fetchText(url: String, offset: Long, charset: String?): TextContent {
        textCalls += Triple(url, offset, charset)
        return texts["$url@$offset"] ?: texts[url]?.takeIf { offset == 0L } ?: throw GopanException(GopanErrors.HTTP, "加载失败(HTTP 403)")
    }

    override suspend fun pdfFile(url: String, key: String, onProgress: (Long, Long?) -> Unit): File {
        pdfs += url to key
        onProgress(50, 100)
        onProgress(100, 100)
        return File("preview-pdf/$key.pdf")
    }
}

fun info(
    id: String,
    kind: PreviewKind,
    name: String = "$id.bin",
    largeUrl: String? = null,
    contentUrl: String? = "https://s3/$id",
    status: DerivativeStatus? = null,
    sha256: String? = "sha-$id",
) = PreviewInfo(
    nodeId = id,
    parentId = "p",
    name = name,
    size = 1234,
    mime = null,
    sha256 = sha256,
    updatedAt = "2026-10-14T00:00:00Z",
    kind = kind,
    thumbUrl = null,
    largeUrl = largeUrl,
    contentUrl = contentUrl,
    status = status,
    durationSec = null,
)
