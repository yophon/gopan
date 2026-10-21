package xyz.yophon.gopan.core.data.preview

import android.content.Context
import androidx.compose.runtime.Immutable
import com.apollographql.apollo.ApolloClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.yophon.gopan.core.data.PreviewKind
import xyz.yophon.gopan.core.data.previewKindOf
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.await
import xyz.yophon.gopan.core.network.requireData
import xyz.yophon.gopan.core.transfer.upload.PartUploader
import xyz.yophon.gopan.graphql.NodePreviewQuery
import xyz.yophon.gopan.graphql.RequestPreviewMutation
import xyz.yophon.gopan.graphql.type.TaskStatus
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** Office 派生 PDF 的转换状态(服务端 TaskStatus);null = 从没触发过 */
enum class DerivativeStatus { PENDING, RUNNING, DONE, FAILED, UNAVAILABLE }

/** 预览所需的单节点信息:预签名地址 15 分钟有效,即取即用 */
@Immutable
data class PreviewInfo(
    val nodeId: String,
    val parentId: String?,
    val name: String,
    val size: Long?,
    val mime: String?,
    val sha256: String?,
    val updatedAt: String?,
    val kind: PreviewKind,
    val thumbUrl: String?,
    /** 图片 2048 版 / 视频封面 */
    val largeUrl: String?,
    /** 原文件(图片原图、PDF、文本、音视频)或 Office 派生的 PDF */
    val contentUrl: String?,
    val status: DerivativeStatus?,
    val durationSec: Int?,
) {
    /** 内容寻址的缓存 key:同内容的文件(不同名字、不同目录)共用 */
    val contentKey: String get() = sha256 ?: "$nodeId@${updatedAt.orEmpty()}"
}

/** 预览:查节点的预览地址、触发 Office 转换、拉文本、把 PDF 下到缓存 */
interface PreviewRepository {
    suspend fun preview(nodeId: String): PreviewInfo

    /** Office 惰性触发转换(访客不能触发;已有产物时服务端直接返回 DONE) */
    suspend fun requestPreview(nodeId: String): DerivativeStatus?

    /**
     * 文本预览的一段(Range,每段最多 [TEXT_MAX_BYTES]):第一段识别编码(UTF-8 解不开按 GB18030,国内的老文本文件),
     * 后续段传第一段的 [charset] 与 [TextContent.nextOffset]
     */
    suspend fun fetchText(url: String, offset: Long = 0, charset: String? = null): TextContent

    /** PDF(原文件或 Office 派生)下到缓存,同内容只下一次;[onProgress] 已下字节 / 总字节(可能未知) */
    suspend fun pdfFile(url: String, key: String, onProgress: (Long, Long?) -> Unit = { _, _ -> }): File

    companion object {
        const val TEXT_MAX_BYTES = 512 * 1024
    }
}

@Singleton
class DefaultPreviewRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apollo: ApolloClient,
    @Named(PartUploader.TRANSFER_CLIENT) private val client: OkHttpClient,
) : PreviewRepository {

    private val pdfDir: File get() = File(context.cacheDir, "preview-pdf")

    override suspend fun preview(nodeId: String): PreviewInfo {
        val n = apollo.query(NodePreviewQuery(nodeId)).execute().requireData().node
        val p = n.preview
        return PreviewInfo(
            nodeId = n.id,
            parentId = n.parentId,
            name = n.name,
            size = n.size,
            mime = n.mime,
            sha256 = n.sha256,
            updatedAt = n.updatedAt,
            kind = previewKindOf(p.kind.rawValue),
            thumbUrl = p.thumbUrl,
            largeUrl = p.largeUrl,
            contentUrl = p.contentUrl,
            status = p.status.toDomain(),
            durationSec = p.durationSec,
        )
    }

    override suspend fun requestPreview(nodeId: String): DerivativeStatus? =
        apollo.mutation(RequestPreviewMutation(nodeId)).execute().requireData().requestPreview.status.toDomain()

    private val texts = TextFetcher(client)

    override suspend fun fetchText(url: String, offset: Long, charset: String?): TextContent = texts.fetch(url, offset, charset)

    /** 同一个 key 同时只下一份(相邻两页是同内容的文件时,别往同一个 .part 里并发写) */
    private val pdfLocks = ConcurrentHashMap<String, Mutex>()

    override suspend fun pdfFile(url: String, key: String, onProgress: (Long, Long?) -> Unit): File =
        pdfLocks.getOrPut(key) { Mutex() }.withLock { downloadPdf(url, key, onProgress) }

    private suspend fun downloadPdf(url: String, key: String, onProgress: (Long, Long?) -> Unit): File =
        withContext(Dispatchers.IO) {
            pdfDir.mkdirs()
            val target = File(pdfDir, "${key.filter { it.isLetterOrDigit() || it == '-' || it == '.' }}.pdf")
            if (target.isFile && target.length() > 0) {
                target.setLastModified(System.currentTimeMillis())
                return@withContext target
            }
            val tmp = File(pdfDir, "${target.name}.part")
            val request = Request.Builder().url(url).header("Accept-Encoding", "identity").build()
            val response = try {
                client.newCall(request).await()
            } catch (e: IOException) {
                throw GopanException(GopanErrors.NETWORK, GopanErrors.messageFor(GopanErrors.NETWORK, null), cause = e)
            }
            response.use { resp ->
                if (!resp.isSuccessful) throw GopanException(GopanErrors.HTTP, "加载失败(HTTP ${resp.code})")
                val body = requireNotNull(resp.body)
                val total = body.contentLength().takeIf { it >= 0 }
                try {
                    body.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buffer = ByteArray(256 * 1024)
                            var done = 0L
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                done += n
                                onProgress(done, total)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    tmp.delete()
                    if (e is IOException) throw GopanException(GopanErrors.NETWORK, GopanErrors.messageFor(GopanErrors.NETWORK, null), cause = e)
                    throw e
                }
            }
            if (!tmp.renameTo(target)) throw GopanException(GopanErrors.UNKNOWN, "保存预览文件失败")
            trimCache()
            target
        }

    /** PDF 缓存超过上限时删最久没看的 */
    private fun trimCache() {
        val files = pdfDir.listFiles { f -> f.isFile && f.name.endsWith(".pdf") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= PDF_CACHE_BYTES) break
            total -= f.length()
            f.delete()
        }
    }

    companion object {
        private const val PDF_CACHE_BYTES = 300L * 1024 * 1024

    }
}

private fun TaskStatus?.toDomain(): DerivativeStatus? = when (this) {
    null -> null
    TaskStatus.PENDING -> DerivativeStatus.PENDING
    TaskStatus.RUNNING -> DerivativeStatus.RUNNING
    TaskStatus.DONE -> DerivativeStatus.DONE
    TaskStatus.FAILED -> DerivativeStatus.FAILED
    TaskStatus.UNAVAILABLE -> DerivativeStatus.UNAVAILABLE
    else -> null
}
