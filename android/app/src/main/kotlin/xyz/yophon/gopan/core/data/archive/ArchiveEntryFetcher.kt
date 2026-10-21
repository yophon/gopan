package xyz.yophon.gopan.core.data.archive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import xyz.yophon.gopan.core.data.preview.PreviewRepository
import xyz.yophon.gopan.core.data.preview.TextContent
import xyz.yophon.gopan.core.data.preview.TextFetcher
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.RefreshOutcome
import xyz.yophon.gopan.core.network.TokenRefresher
import xyz.yophon.gopan.core.network.await
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/** /arc 与解压相关的服务端错误码(server/internal/httpx/archive.go、service/archive.go) */
object ArchiveErrors {
    const val NOT_ARCHIVE = "NOT_ARCHIVE"
    const val ARCHIVE_NOT_READY = "ARCHIVE_NOT_READY"
    const val ARCHIVE_UNAVAILABLE = "ARCHIVE_UNAVAILABLE"
    const val ARCHIVE_NOTHING = "ARCHIVE_NOTHING"
    const val ENTRY_UNAVAILABLE = "ARCHIVE_ENTRY_UNAVAILABLE"
    const val ENTRY_TOO_LARGE = "ARCHIVE_ENTRY_TOO_LARGE"
    const val ENTRY_TOO_DEEP = "ARCHIVE_ENTRY_TOO_DEEP"
    const val ARCHIVE_BUSY = "ARCHIVE_BUSY"
    const val ARCHIVE_CORRUPT = "ARCHIVE_CORRUPT"
    const val UNSUPPORTED_TYPE = "UNSUPPORTED_TYPE"
}

/** 单个文件的地址:{base}/arc/{nodeId}/{idx}[?download=1](凭证走 Authorization 头,不进 URL) */
fun archiveEntryUrl(baseUrl: String, nodeId: String, idx: Int, download: Boolean): HttpUrl =
    baseUrl.trimEnd('/').toHttpUrl().newBuilder()
        .encodedPath("/")
        .addPathSegment("arc")
        .addPathSegment(nodeId)
        .addPathSegment(idx.toString())
        .apply { if (download) addQueryParameter("download", "1") }
        .build()

private val errorJson = Json { ignoreUnknownKeys = true }

/**
 * /arc 的错误响应 → [GopanException]:服务端回 JSON `{code, message}`(message 是中文,直接给用户看);
 * 不是 JSON(反代的 502 页面之类)按状态码给个通用说法。
 */
fun arcError(status: Int, body: String?): GopanException {
    val obj = body?.let { runCatching { errorJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }
    val code = (obj?.get("code") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    val message = (obj?.get("message") as? JsonPrimitive)?.contentOrNull
    if (code == null) {
        val fallback = when (status) {
            401 -> GopanErrors.UNAUTHENTICATED
            403 -> GopanErrors.FORBIDDEN
            404 -> GopanErrors.NOT_FOUND
            else -> GopanErrors.HTTP
        }
        val text = if (fallback == GopanErrors.HTTP) "加载失败(HTTP $status)" else GopanErrors.messageFor(fallback, null)
        return GopanException(fallback, text)
    }
    return GopanException(code, GopanErrors.messageFor(code, message), serverMessage = message)
}

/**
 * 把包里的一个文件取到缓存目录:带 Bearer GET /arc,401 时刷新一次凭证重放;边读边写 .part,读完改名。
 * 每个条目一个子目录({nodeId}-{idx}/原名),交给其他应用时文件名就是包里的名字。
 * 同一条目同时只下一份;总量超过 [cacheBytes] 时删最久没用的。纯 JVM,单测直接用 MockWebServer。
 */
class ArchiveEntryFetcher(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val dir: File,
    private val accessToken: () -> String?,
    private val refresher: () -> TokenRefresher,
    private val maxBytes: Long = ARCHIVE_ENTRY_MAX_BYTES,
    private val cacheBytes: Long = CACHE_BYTES,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun fetch(nodeId: String, entry: ArchiveEntry, onProgress: (Long, Long?) -> Unit = { _, _ -> }): File {
        val key = entryKey(nodeId, entry.idx)
        return locks.getOrPut(key) { Mutex() }.withLock {
            withContext(Dispatchers.IO) { download(nodeId, entry, key, onProgress) }
        }
    }

    /** 删掉某个压缩包取下来的全部条目(离开浏览页时) */
    fun release(nodeId: String) {
        val prefix = "${safeKey(nodeId)}-"
        dir.listFiles { f -> f.isDirectory && f.name.startsWith(prefix) }?.forEach { it.deleteRecursively() }
    }

    /** 清空(登出、进程重启后的残留) */
    fun clearAll() {
        dir.deleteRecursively()
    }

    private suspend fun download(nodeId: String, entry: ArchiveEntry, key: String, onProgress: (Long, Long?) -> Unit): File {
        val folder = File(dir, key)
        val target = File(folder, safeFileName(entry.name))
        if (target.isFile && target.length() == entry.size) {
            folder.setLastModified(System.currentTimeMillis())
            onProgress(entry.size, entry.size)
            return target
        }
        if (entry.size > maxBytes) throw tooLarge()
        val url = archiveEntryUrl(baseUrl, nodeId, entry.idx, download = !entry.previewable)
        var token = accessToken()
        var refreshed = false
        while (true) {
            val response = call(url, token)
            if (response.code == 401 && !refreshed) {
                response.close()
                refreshed = true
                when (val outcome = refresher().refreshAfterUnauthenticated(token)) {
                    is RefreshOutcome.Refreshed -> token = outcome.accessToken
                    RefreshOutcome.SignedOut -> throw GopanException(
                        GopanErrors.UNAUTHENTICATED,
                        GopanErrors.messageFor(GopanErrors.UNAUTHENTICATED, null),
                    )
                    RefreshOutcome.Unavailable -> throw networkError(null)
                }
                continue
            }
            response.use { resp ->
                if (!resp.isSuccessful) throw arcError(resp.code, errorBody(resp))
                folder.mkdirs()
                write(requireNotNull(resp.body), target, entry.size, onProgress)
            }
            trim(except = folder)
            return target
        }
    }

    private suspend fun call(url: HttpUrl, token: String?): Response {
        val request = Request.Builder().url(url).get()
            .header("Accept-Encoding", "identity")
            .apply { if (!token.isNullOrEmpty()) header("Authorization", "Bearer $token") }
            .build()
        return try {
            client.newCall(request).await()
        } catch (e: IOException) {
            throw networkError(e)
        }
    }

    private suspend fun write(body: ResponseBody, target: File, declared: Long, onProgress: (Long, Long?) -> Unit) {
        val total = body.contentLength().takeIf { it >= 0 } ?: declared.takeIf { it > 0 }
        if (total != null && total > maxBytes) throw tooLarge()
        val tmp = File(target.parentFile, "${target.name}.part")
        try {
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        done += n
                        if (done > maxBytes) throw tooLarge()
                        out.write(buffer, 0, n)
                        onProgress(done, total)
                    }
                }
            }
        } catch (e: Throwable) {
            tmp.delete()
            if (e is IOException) throw networkError(e)
            throw e
        }
        target.delete()
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw GopanException(GopanErrors.UNKNOWN, "保存文件失败")
        }
    }

    /** 缓存总量超过上限时按最近使用删整条目录;刚下好的那个不删 */
    private fun trim(except: File) {
        val folders = dir.listFiles { f -> f.isDirectory }?.map { it to folderBytes(it) }?.sortedBy { it.first.lastModified() } ?: return
        var total = folders.sumOf { it.second }
        for ((folder, bytes) in folders) {
            if (total <= cacheBytes) break
            if (folder == except) continue
            folder.deleteRecursively()
            total -= bytes
        }
    }

    private fun folderBytes(folder: File): Long = folder.listFiles()?.sumOf { it.length() } ?: 0L

    private fun errorBody(resp: Response): String? = try {
        resp.peekBody(ERROR_BODY_MAX).string()
    } catch (e: IOException) {
        null
    }

    private fun tooLarge() = GopanException(ArchiveErrors.ENTRY_TOO_LARGE, "单个文件超过 100 MB,请解压后再看")

    private fun networkError(cause: Throwable?) =
        GopanException(GopanErrors.NETWORK, GopanErrors.messageFor(GopanErrors.NETWORK, null), cause = cause)

    companion object {
        const val CACHE_BYTES = 200L * 1024 * 1024
        private const val ERROR_BODY_MAX = 4096L

        fun entryKey(nodeId: String, idx: Int): String = "${safeKey(nodeId)}-$idx"

        private fun safeKey(nodeId: String): String = nodeId.filter { it.isLetterOrDigit() || it == '-' }

        /** 包里的名字放进文件系统:去掉路径分隔与保留字符,太长截短(保留扩展名) */
        fun safeFileName(name: String): String {
            val cleaned = name.map { c -> if (c < ' ' || c in "/\\:*?\"<>|") '_' else c }.joinToString("").trim().trim('.')
            if (cleaned.isEmpty()) return "file"
            if (cleaned.length <= MAX_NAME) return cleaned
            val ext = cleaned.substringAfterLast('.', "").take(16)
            return if (ext.isEmpty()) cleaned.take(MAX_NAME) else cleaned.take(MAX_NAME - ext.length - 1) + "." + ext
        }

        private const val MAX_NAME = 120
    }
}

/**
 * 取到本地的文本条目读一段(每段最多 [maxBytes]):编码识别与网盘文本预览一致(UTF-8 去 BOM,解不开按 GB18030),
 * 后续段沿用第一段的 [charset];段尾截断的半个字符留给下一段。
 */
fun readTextSegment(
    file: File,
    offset: Long = 0,
    charset: String? = null,
    maxBytes: Int = PreviewRepository.TEXT_MAX_BYTES,
): TextContent {
    val length = file.length()
    val bytes = RandomAccessFile(file, "r").use { raf ->
        val n = (length - offset).coerceIn(0, maxBytes.toLong()).toInt()
        val buffer = ByteArray(n)
        raf.seek(offset)
        raf.readFully(buffer)
        buffer
    }
    val truncated = offset + bytes.size < length
    return TextFetcher.decode(bytes, truncated, offset, charset).copy(totalBytes = length)
}
