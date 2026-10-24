package xyz.yophon.gopan.core.data.preview

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.await
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 文本预览的一段。[nextOffset]:下一段从哪个字节开始(已跳过 BOM、不含末尾被截掉的半个字符);
 * [totalBytes]:文件总字节,未知为 null;[truncated]:后面还有。
 */
data class TextContent(
    val text: String,
    val truncated: Boolean,
    val charset: String,
    val nextOffset: Long = 0,
    val totalBytes: Long? = null,
)

/**
 * 按字节段拉文本(预签名地址,Range 请求)。第一段识别编码(UTF-8 去 BOM,解不开再试 GB18030),
 * 后续段沿用第一段的编码;段尾被截断的半个字符留给下一段。
 */
class TextFetcher(private val client: OkHttpClient) {

    suspend fun fetch(url: String, offset: Long = 0, charset: String? = null, maxBytes: Int = PreviewRepository.TEXT_MAX_BYTES): TextContent =
        withContext(Dispatchers.IO) {
            // 离线副本(doc/26):直接读手机上的文件
            if (url.startsWith("file:")) return@withContext readLocal(File(URI(url)), offset, charset, maxBytes)
            val request = Request.Builder().url(url).header("Accept-Encoding", "identity")
                .header("Range", "bytes=$offset-${offset + maxBytes - 1}").build()
            val response = try {
                client.newCall(request).await()
            } catch (e: IOException) {
                throw GopanException(GopanErrors.NETWORK, GopanErrors.messageFor(GopanErrors.NETWORK, null), cause = e)
            }
            response.use { resp ->
                if (resp.code == 416) return@withContext TextContent("", truncated = false, charset = charset ?: "UTF-8", nextOffset = offset, totalBytes = offset)
                if (!resp.isSuccessful) throw GopanException(GopanErrors.HTTP, "加载失败(HTTP ${resp.code})")
                val body = requireNotNull(resp.body)
                val bytes = try {
                    body.byteStream().use { input ->
                        // 不支持 Range 的服务端回 200 + 全文:自己跳到 offset
                        if (resp.code == 200 && offset > 0) input.skipFully(offset)
                        input.readUpTo(maxBytes)
                    }
                } catch (e: IOException) {
                    throw GopanException(GopanErrors.NETWORK, GopanErrors.messageFor(GopanErrors.NETWORK, null), cause = e)
                }
                val total = contentRangeTotal(resp.header("Content-Range"))
                    ?: body.contentLength().takeIf { it >= 0 }?.let { if (resp.code == 200) it else offset + it }
                val truncated = total != null && total > offset + bytes.size
                decode(bytes, truncated, offset, charset).copy(totalBytes = total)
            }
        }

    companion object {
        /** 本地文件的一段(与 Range 请求同样的切法与解码) */
        fun readLocal(file: File, offset: Long, charset: String?, maxBytes: Int = PreviewRepository.TEXT_MAX_BYTES): TextContent {
            val total = file.length()
            if (!file.isFile) throw GopanException(GopanErrors.HTTP, "手机上的副本不见了")
            if (offset >= total) return TextContent("", truncated = false, charset = charset ?: "UTF-8", nextOffset = offset, totalBytes = total)
            val bytes = try {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(offset)
                    ByteArray(minOf(maxBytes.toLong(), total - offset).toInt()).also { raf.readFully(it) }
                }
            } catch (e: IOException) {
                throw GopanException(GopanErrors.HTTP, "读取失败", cause = e)
            }
            return decode(bytes, truncated = total > offset + bytes.size, offset = offset, charset = charset).copy(totalBytes = total)
        }

        fun contentRangeTotal(header: String?): Long? = header?.substringAfterLast('/', "")?.toLongOrNull()

        /**
         * 解码一段:[charset] 为空时识别(UTF-8 优先,去 BOM;解不开再试 GB18030;都不行按 UTF-8 替换非法字节)。
         * 截断时末尾最多丢 3 字节(半个字符),[TextContent.nextOffset] 指向第一个没用掉的字节。
         */
        fun decode(bytes: ByteArray, truncated: Boolean, offset: Long = 0, charset: String? = null): TextContent {
            var start = 0
            if (offset == 0L && bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
                start = 3
            }
            val candidates = when (charset) {
                null -> listOf(Charsets.UTF_8, Charset.forName("GB18030"))
                else -> listOf(Charset.forName(charset))
            }
            for (cs in candidates) {
                strict(bytes, start, cs, truncated)?.let { (text, used) ->
                    return TextContent(text, truncated, cs.displayName(), nextOffset = offset + start + used)
                }
            }
            val cs = candidates.first()
            return TextContent(String(bytes, start, bytes.size - start, cs), truncated, cs.displayName(), nextOffset = offset + bytes.size)
        }

        /** 严格解码 [from] 起的字节;截断时依次去掉末尾 0–3 字节再试。返回文本与用掉的字节数 */
        private fun strict(data: ByteArray, from: Int, cs: Charset, truncated: Boolean): Pair<String, Int>? {
            val length = data.size - from
            val tries = if (truncated) 0..3 else 0..0
            for (cut in tries) {
                if (cut > length) break
                val decoder = cs.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                try {
                    return decoder.decode(ByteBuffer.wrap(data, from, length - cut)).toString() to (length - cut)
                } catch (e: CharacterCodingException) {
                    // 换下一个截法
                }
            }
            return null
        }
    }
}

/** 最多读 [max] 字节(InputStream.readNBytes 要 API 33) */
internal fun InputStream.readUpTo(max: Int): ByteArray {
    val out = ByteArrayOutputStream(minOf(max, 64 * 1024))
    val buffer = ByteArray(16 * 1024)
    var total = 0
    while (total < max) {
        val n = read(buffer, 0, minOf(buffer.size, max - total))
        if (n < 0) break
        out.write(buffer, 0, n)
        total += n
    }
    return out.toByteArray()
}

private fun InputStream.skipFully(count: Long) {
    var left = count
    val buffer = ByteArray(16 * 1024)
    while (left > 0) {
        val n = read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
        if (n < 0) break
        left -= n
    }
}
