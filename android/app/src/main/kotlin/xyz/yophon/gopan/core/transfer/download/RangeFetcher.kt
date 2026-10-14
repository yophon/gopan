package xyz.yophon.gopan.core.transfer.download

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import xyz.yophon.gopan.core.network.await
import xyz.yophon.gopan.core.transfer.upload.PartUploader
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

sealed interface Fetch {
    /** 200(start = 0)或 206(start 取 Content-Range 的起点);[total] 取 Content-Range 总长或 Content-Length */
    class Body(val start: Long, val total: Long?, val body: ResponseBody) : Fetch

    /** 403:URL 过期或签名不符,重取一次 */
    data object Forbidden : Fetch

    /** 404:重新解析节点,看还在不在 */
    data object NotFound : Fetch

    /** 416:起点超出(.part 已经满了或比文件还长) */
    data object RangeNotSatisfiable : Fetch

    data class Transient(val cause: Throwable?, val status: Int? = null) : Fetch
}

/** 字节层:只认 URL,不带任何身份(Cookie / Bearer) */
fun interface RangeSource {
    suspend fun open(url: String, from: Long): Fetch
}

/**
 * 预签名 GET:
 * - from > 0 时加 Range: bytes=from-(签名只覆盖 host,加头不影响签名);
 * - 总是 Accept-Encoding: identity:否则 OkHttp 会自动要 gzip、透明解压并丢掉 Content-Length;
 * - 不发 HEAD(签名绑定了 GET)。
 */
@Singleton
class RangeFetcher @Inject constructor(
    @Named(PartUploader.TRANSFER_CLIENT) private val client: OkHttpClient,
) : RangeSource {

    override suspend fun open(url: String, from: Long): Fetch {
        val request = Request.Builder().url(url).get()
            .header("Accept-Encoding", "identity")
            .apply { if (from > 0) header("Range", "bytes=$from-") }
            .build()
        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            return Fetch.Transient(e)
        }
        return when (response.code) {
            200 -> Fetch.Body(0, response.body?.contentLength()?.takeIf { it >= 0 }, requireNotNull(response.body))
            206 -> {
                val range = parseContentRange(response.header("Content-Range"))
                if (range == null) {
                    response.close()
                    Fetch.Transient(IOException("206 缺少 Content-Range"), 206)
                } else {
                    Fetch.Body(range.first, range.second, requireNotNull(response.body))
                }
            }
            403 -> Fetch.Forbidden.also { response.close() }
            404 -> Fetch.NotFound.also { response.close() }
            416 -> Fetch.RangeNotSatisfiable.also { response.close() }
            else -> Fetch.Transient(null, response.code).also { response.close() }
        }
    }

    companion object {
        /** "bytes 100-199/1000" → (100, 1000);总长为 * 时 total 为 null */
        fun parseContentRange(header: String?): Pair<Long, Long?>? {
            val h = header?.trim() ?: return null
            if (!h.startsWith("bytes ")) return null
            val spec = h.removePrefix("bytes ").trim()
            val slash = spec.indexOf('/')
            if (slash < 0) return null
            val range = spec.substring(0, slash)
            val total = spec.substring(slash + 1).toLongOrNull()
            val dash = range.indexOf('-')
            if (dash < 0) return null
            val start = range.substring(0, dash).toLongOrNull() ?: return null
            return start to total
        }
    }
}
