package xyz.yophon.gopan.core.documents

import android.content.Context
import android.os.CancellationSignal
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.yophon.gopan.core.data.preview.PreviewRepository
import xyz.yophon.gopan.core.transfer.download.DownloadApi
import xyz.yophon.gopan.core.transfer.download.Resolve
import xyz.yophon.gopan.core.transfer.upload.PartUploader
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * 给系统「文件」/ 别的应用读 gopan 文件用的本地缓存(按需下载):打开时整份下到 cacheDir/documents,
 * 同内容(sha256)只下一次,下完校验;总量超过 [MAX_BYTES] 时删最久没用的。缩略图另放 thumbs/。
 * 都在 binder 线程里同步跑(DocumentsProvider 的约定),取消信号到了就断开连接。
 */
@Singleton
class DocumentCache @Inject constructor(
    @ApplicationContext context: Context,
    private val downloads: DownloadApi,
    private val previews: PreviewRepository,
    @Named(PartUploader.TRANSFER_CLIENT) private val client: OkHttpClient,
) {
    private val dir = File(context.cacheDir, "documents")
    private val thumbs = File(context.cacheDir, "documents-thumbs")
    private val locks = ConcurrentHashMap<String, Any>()

    /** 下到缓存(已有就直接用);被删 / 不是文件抛 FileNotFoundException */
    fun file(nodeId: String, signal: CancellationSignal?): File {
        val target = when (val r = runBlocking { downloads.resolve(nodeId) }) {
            is Resolve.Ok -> r.target
            Resolve.Gone -> throw FileNotFoundException("文件已删除")
            Resolve.NotAFile -> throw FileNotFoundException("不是文件")
        }
        val key = (target.sha256 ?: "$nodeId-${target.size}").filter { it.isLetterOrDigit() || it == '-' }
        dir.mkdirs()
        val file = File(dir, key)
        synchronized(locks.getOrPut(key) { Any() }) {
            if (file.isFile && file.length() == target.size) {
                file.setLastModified(System.currentTimeMillis())
                return file
            }
            val tmp = File(dir, "$key.part")
            val call = client.newCall(Request.Builder().url(target.url).header("Accept-Encoding", "identity").build())
            signal?.setOnCancelListener { call.cancel() }
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw FileNotFoundException("下载失败(HTTP ${resp.code})")
                    val digest = MessageDigest.getInstance("SHA-256")
                    requireNotNull(resp.body).byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                signal?.throwIfCanceled()
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                            }
                        }
                    }
                    val sha = digest.digest().joinToString("") { "%02x".format(it) }
                    if (target.sha256 != null && sha != target.sha256.lowercase()) throw FileNotFoundException("文件校验失败")
                }
            } catch (e: IOException) {
                tmp.delete()
                if (e is FileNotFoundException) throw e
                throw FileNotFoundException(if (call.isCanceled()) "已取消" else "网络连接失败").apply { initCause(e) }
            } catch (e: RuntimeException) {
                tmp.delete()
                throw e
            } finally {
                signal?.setOnCancelListener(null)
            }
            if (!tmp.renameTo(file)) throw FileNotFoundException("保存失败")
            // 刚下好的这份不参与淘汰:单个文件超过上限时也要能打开,下次有新文件进来时再淘汰它
            trim(keep = file)
            return file
        }
    }

    /** 缩略图(服务端的缩略图派生);没有时返回 null */
    fun thumbnail(nodeId: String, signal: CancellationSignal?): File? {
        val info = runCatching { runBlocking { previews.preview(nodeId) } }.getOrNull() ?: return null
        val url = info.thumbUrl ?: return null
        thumbs.mkdirs()
        val key = info.contentKey.filter { it.isLetterOrDigit() || it == '-' }
        val file = File(thumbs, key)
        // 同一张缩略图并发请求时排队,免得两个请求写同一个 .part
        synchronized(locks.getOrPut("thumb:$key") { Any() }) {
            if (file.isFile && file.length() > 0) return file
            val tmp = File(thumbs, "$key.part")
            val call = client.newCall(Request.Builder().url(url).build())
            signal?.setOnCancelListener { call.cancel() }
            return try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) return null
                    requireNotNull(resp.body).byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    if (tmp.renameTo(file)) file else null
                }
            } catch (e: IOException) {
                tmp.delete()
                null
            } finally {
                signal?.setOnCancelListener(null)
            }
        }
    }

    private fun trim(keep: File) = trimDir(dir, keep, MAX_BYTES)

    /** 登出时清掉(上一个账号的文件不留在缓存里) */
    fun clear() {
        dir.deleteRecursively()
        thumbs.deleteRecursively()
    }

    companion object {
        const val MAX_BYTES = 1L shl 30

        /** 总量超过 [max] 时从最久没用的删起;[keep](刚下好的那份)不删,单个文件超过上限也要能打开 */
        internal fun trimDir(dir: File, keep: File, max: Long) {
            val files = dir.listFiles { f -> f.isFile && !f.name.endsWith(".part") }?.sortedBy { it.lastModified() } ?: return
            var total = files.sumOf { it.length() }
            for (f in files) {
                if (total <= max) break
                if (f == keep) continue
                total -= f.length()
                // 正被别的应用读着的文件删掉也不影响它手里的描述符
                f.delete()
            }
        }
    }
}
