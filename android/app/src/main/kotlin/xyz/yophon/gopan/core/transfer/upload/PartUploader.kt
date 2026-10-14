package xyz.yophon.gopan.core.transfer.upload

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import xyz.yophon.gopan.core.network.await
import xyz.yophon.gopan.core.transfer.SourceReader
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** 一片 PUT 的结果(只有 2xx 算成功;ETag 不需要保存,定稿时服务端自己 ListParts) */
sealed interface PartResult {
    data object Ok : PartResult

    /** 对象存储返回的错误:[s3Code] 取自 XML 正文的 <Code>(AccessDenied / SignatureDoesNotMatch / NoSuchUpload …) */
    data class Http(val status: Int, val s3Code: String?, val s3Message: String?) : PartResult
}

/**
 * 分片直传:PUT 预签名 URL(直连对象存储,不走 GraphQL)。
 * - 不带 Cookie、不带 Authorization(签名只覆盖 host,URL 必须原样使用);
 * - 精确 Content-Length(MinIO 拒绝 chunked:411 MissingContentLength);
 * - 请求体可重放:每次 writeTo 都从片的起点重新打开来源。
 */
@Singleton
class PartUploader @Inject constructor(
    @Named(TRANSFER_CLIENT) private val client: OkHttpClient,
    private val sources: SourceReader,
) : PartSender {
    /**
     * PUT 第 [offset, offset+length) 段。[onProgress] 报告本次尝试已写出的字节(重试时从 0 重新计),
     * 由调用方按"每片当前值"汇总,天然不会因为重试重复计数。网络错误抛 IOException。
     */
    override suspend fun put(url: String, source: String, offset: Long, length: Long, onProgress: (Long) -> Unit): PartResult {
        val body = SliceBody(source, offset, length, onProgress)
        val request = Request.Builder().url(url).put(body).build()
        val response = client.newCall(request).await()
        return response.use { resp ->
            if (resp.isSuccessful) {
                PartResult.Ok
            } else {
                val text = withContext(Dispatchers.IO) {
                    try {
                        resp.body?.source()?.readUtf8()?.take(4096)
                    } catch (e: IOException) {
                        null
                    }
                }
                PartResult.Http(resp.code, xmlTag(text, "Code"), xmlTag(text, "Message"))
            }
        }
    }

    private inner class SliceBody(
        private val source: String,
        private val offset: Long,
        private val length: Long,
        private val onProgress: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType() = OCTET

        override fun contentLength(): Long = length

        override fun isOneShot(): Boolean = false

        override fun writeTo(sink: BufferedSink) {
            onProgress(0)
            sources.openAt(source, offset).use { input -> copyExactly(input, sink) }
        }

        private fun copyExactly(input: InputStream, sink: BufferedSink) {
            val buffer = ByteArray(BUFFER)
            var remaining = length
            var written = 0L
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (n < 0) throw IOException("来源在第 ${offset + written} 字节处提前结束(文件被改动了?)")
                sink.write(buffer, 0, n)
                remaining -= n
                written += n
                onProgress(written)
            }
        }
    }

    companion object {
        const val TRANSFER_CLIENT = "transfer"
        private const val BUFFER = 64 * 1024
        private val OCTET = "application/octet-stream".toMediaType()

        fun xmlTag(xml: String?, tag: String): String? {
            if (xml.isNullOrEmpty()) return null
            val start = xml.indexOf("<$tag>")
            if (start < 0) return null
            val end = xml.indexOf("</$tag>", start)
            if (end < 0) return null
            return xml.substring(start + tag.length + 2, end).trim()
        }
    }
}
