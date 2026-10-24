package xyz.yophon.gopan.core.transfer

import androidx.core.net.toUri
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import xyz.yophon.gopan.core.offline.OfflineStore
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** 来源的基本信息;[seekable] 为 false 时(管道、部分云端 provider)只能顺序读,要先拷进私有目录 */
data class SourceStat(val size: Long, val lastModified: Long?, val seekable: Boolean)

/** 一次流式读完的结果 */
data class HashResult(val sha256: String, val size: Long)

/** 上传引擎读来源的接口(来源用 URI 字符串表示,测试里用内存实现) */
interface SourceReader {
    fun stat(source: String): SourceStat?

    /** 从 [offset] 开始读;来源必须可 seek */
    fun openAt(source: String, offset: Long): InputStream

    /** 流式算 SHA-256;[copyTo] 非空时顺手把内容写进去 */
    suspend fun hash(source: String, copyTo: File? = null, onProgress: (Long) -> Unit = {}): HashResult

    /** 不可 seek 的来源拷贝落点 */
    fun newCopyFile(name: String): File

    /** 私有文件的来源字符串(file://…) */
    fun fileSource(file: File): String
}

/**
 * 上传来源与传输用的私有文件:
 * - 读:content:// 与 file:// 统一成"从某个偏移开始的 InputStream";
 * - 哈希:1 MiB 缓冲流式 SHA-256(小写 hex),可顺手拷一份到私有目录(不可 seek 的来源);
 * - 私有目录:filesDir/transfers/{incoming,camera,text,copies,parts};"打开方式"的文件在 cacheDir/open;离线副本在 filesDir/offline。
 */
@Singleton
class SourceStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : SourceReader {
    private val resolver: ContentResolver get() = context.contentResolver

    val root: File get() = File(context.filesDir, "transfers")

    /**
     * 系统分享进来的文件(分享授权只在接收页存活期间有效,所以收到就拷)。
     * 不在 [root] 下:登出清理不碰它(未登录时收到的分享要留到登录后),孤儿由引擎启动时清理。
     */
    val incomingDir: File get() = File(context.filesDir, "share-incoming")

    /** App 内拍照 */
    val cameraDir: File get() = File(root, "camera")

    /** 传输助手里超过 4000 字节的长文本转成的 .txt */
    val textDir: File get() = File(root, "text")

    /** 不可 seek 的来源在哈希时顺手拷到这里 */
    val copiesDir: File get() = File(root, "copies")

    /** 下载中的 .part */
    val partsDir: File get() = File(root, "parts")

    /** "用其他应用打开":按 sha256 分目录缓存 */
    val openDir: File get() = File(context.cacheDir, "open")

    /** 离线可用(doc/26)的副本:filesDir/offline/<离线项 id>/<文件 id>/<名字>;不在 [root] 下,由离线模块自己清 */
    val offlineDir: File get() = File(context.filesDir, "offline")

    /** 离线下载校验完先挪到这里,再由离线模块放到位(同一分区,改名即可) */
    val offlineInbox: File get() = File(offlineDir, OfflineStore.INBOX)

    // ---- 读 ----

    override fun stat(source: String): SourceStat? = stat(source.toUri())

    override fun openAt(source: String, offset: Long): InputStream = openAt(source.toUri(), offset)

    override suspend fun hash(source: String, copyTo: File?, onProgress: (Long) -> Unit): HashResult =
        hash(source.toUri(), copyTo, onProgress)

    override fun fileSource(file: File): String = Uri.fromFile(file).toString()

    fun stat(uri: Uri): SourceStat? = try {
        when (uri.scheme) {
            ContentResolver.SCHEME_FILE -> {
                val f = File(requireNotNull(uri.path))
                if (f.isFile) SourceStat(f.length(), f.lastModified(), seekable = true) else null
            }
            else -> statContent(uri)
        }
    } catch (e: SecurityException) {
        Timber.w("来源无读权限:%s", e.javaClass.simpleName)
        null
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun statContent(uri: Uri): SourceStat? {
        var querySize: Long? = null
        var lastModified: Long? = null
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (si >= 0 && !c.isNull(si)) querySize = c.getLong(si)
                val mi = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (mi >= 0 && !c.isNull(mi)) lastModified = c.getLong(mi).takeIf { it > 0 }
            }
        }
        val pfd = resolver.openFileDescriptor(uri, "r") ?: return null
        pfd.use {
            val statSize = it.statSize
            return if (statSize >= 0) {
                SourceStat(statSize, lastModified, seekable = true)
            } else {
                // 管道:只能信 provider 报的大小(可能没有,那就读完才知道)
                SourceStat(querySize ?: -1, lastModified, seekable = false)
            }
        }
    }

    fun openAt(uri: Uri, offset: Long): InputStream {
        val stream: FileInputStream = when (uri.scheme) {
            ContentResolver.SCHEME_FILE -> FileInputStream(File(requireNotNull(uri.path)))
            else -> {
                val pfd = resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException("无法打开来源")
                ParcelFileDescriptor.AutoCloseInputStream(pfd)
            }
        }
        if (offset > 0) {
            try {
                stream.channel.position(offset)
            } catch (e: IOException) {
                stream.close()
                throw e
            }
        }
        return stream
    }

    suspend fun hash(uri: Uri, copyTo: File? = null, onProgress: (Long) -> Unit = {}): HashResult =
        withContext(Dispatchers.IO) {
            val input = when (uri.scheme) {
                ContentResolver.SCHEME_FILE -> FileInputStream(File(requireNotNull(uri.path)))
                else -> resolver.openInputStream(uri) ?: throw FileNotFoundException("无法打开来源")
            }
            hashStream(input, copyTo, onProgress)
        }

    override fun newCopyFile(name: String): File = newPrivateFile(copiesDir, name)

    /** content URI 的显示名(OpenableColumns.DISPLAY_NAME);file URI 取文件名 */
    fun displayName(uri: Uri): String? = try {
        when (uri.scheme) {
            ContentResolver.SCHEME_FILE -> uri.lastPathSegment
            else -> resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        }
    } catch (e: Exception) {
        null
    }?.trim()?.takeIf { it.isNotEmpty() }

    fun mimeOf(uri: Uri): String? = try {
        resolver.getType(uri)
    } catch (e: Exception) {
        null
    }

    // ---- 授权 ----

    /** SAF / 相册选择器的 URI:持久化读授权,进程重启后还能续传;不支持持久化时返回 false */
    fun takePersistableRead(uri: Uri): Boolean = try {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (e: SecurityException) {
        false
    }

    fun releasePersistableRead(uri: Uri) {
        try {
            resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // 已经释放过或从未持久化
        }
    }

    // ---- 私有文件 ----

    /**
     * 把 content URI 拷进 [dir](分享接收):文件名取显示名,缺扩展名时按 MIME 补上,重名加 (n)。
     * 读失败抛 IOException / SecurityException。
     */
    suspend fun copyIn(
        uri: Uri,
        dir: File,
        fallbackName: String,
        preferFallback: Boolean = false,
        onProgress: (Long) -> Unit = {},
    ): File =
        withContext(Dispatchers.IO) {
            val mime = mimeOf(uri)
            val base = if (preferFallback) fallbackName else displayName(uri) ?: fallbackName
            val name = withExtension(sanitizeFileName(base), mime)
            val target = newPrivateFile(dir, name)
            val input = resolver.openInputStream(uri) ?: throw FileNotFoundException("无法读取分享的文件")
            try {
                input.use { ins ->
                    target.outputStream().use { out ->
                        val buffer = ByteArray(BUFFER)
                        var total = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = ins.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            total += n
                            onProgress(total)
                        }
                    }
                }
            } catch (e: Throwable) {
                target.delete()
                throw e
            }
            target
        }

    /** 新建一个私有文件(拍照、长文本、拷贝);同名时加 (n) */
    fun newPrivateFile(dir: File, name: String): File {
        dir.mkdirs()
        return uniqueFile(dir, sanitizeFileName(name))
    }

    /** 删掉 App 自己的副本(只删 transfers 与分享接收目录下的 file://) */
    fun deleteOwned(uri: Uri) {
        if (uri.scheme != ContentResolver.SCHEME_FILE) return
        val f = File(uri.path ?: return)
        val path = f.canonicalPath
        if (path.startsWith(root.canonicalPath + File.separator) || path.startsWith(incomingDir.canonicalPath + File.separator)) {
            f.delete()
        }
    }

    /** 分享接收目录里没人引用、且不是刚拷进来的文件(拷完没被处理,或任务被清掉了) */
    fun cleanIncoming(referenced: Set<String>, olderThanMs: Long, now: Long) {
        incomingDir.listFiles()?.forEach { f ->
            if (f.absolutePath !in referenced && now - f.lastModified() > olderThanMs) f.delete()
        }
    }

    /** 登出:传输用的私有文件全部删掉 */
    fun wipe() {
        root.deleteRecursively()
        openDir.deleteRecursively()
    }

    companion object {
        const val BUFFER = 1 shl 20

        /** 流式哈希(可顺手拷贝);取消协程即停 */
        suspend fun hashStream(input: InputStream, copyTo: File?, onProgress: (Long) -> Unit): HashResult {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER)
            var total = 0L
            copyTo?.parentFile?.mkdirs()
            val out = copyTo?.outputStream()
            try {
                input.use { ins ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = ins.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        digest.update(buffer, 0, n)
                        out?.write(buffer, 0, n)
                        total += n
                        onProgress(total)
                    }
                }
                out?.flush()
            } catch (e: Throwable) {
                out?.close()
                copyTo?.delete()
                throw e
            }
            out?.close()
            return HashResult(digest.digest().toHex(), total)
        }

        /** 时间戳文件名(与网页端「截图 2026-10-14 21-30.png」一致) */
        fun timestampName(prefix: String, ext: String, now: Date = Date()): String {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.ROOT).format(now)
            return "$prefix $stamp.$ext"
        }

        /** 服务端只禁止 / 与 NUL;本地落盘再把各存储都不认的字符换成 _,按字节截到 200(给 " (n)" 留余量) */
        fun sanitizeFileName(name: String): String {
            val cleaned = buildString(name.length) {
                for (c in name) {
                    append(
                        when {
                            c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"' ||
                                c == '<' || c == '>' || c == '|' || c.code < 0x20 -> '_'
                            else -> c
                        },
                    )
                }
            }.trim().trimStart('.')
            return truncateUtf8(cleaned.ifEmpty { "未命名" }, 200)
        }

        /** 文件名没有扩展名时按 MIME 补一个(服务端按扩展名判断类型,缺了就没有缩略图) */
        fun withExtension(name: String, mime: String?): String {
            val dot = name.lastIndexOf('.')
            if (dot > 0 && dot < name.length - 1) return name
            val ext = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: return name
            return "$name.$ext"
        }

        fun uniqueFile(dir: File, name: String): File {
            var candidate = File(dir, name)
            if (!candidate.exists()) return candidate
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            var n = 1
            while (candidate.exists()) {
                candidate = File(dir, "$stem ($n)$ext")
                n++
            }
            return candidate
        }

        private fun truncateUtf8(s: String, maxBytes: Int): String {
            if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
            val dot = s.lastIndexOf('.')
            val ext = if (dot > 0 && s.length - dot <= 16) s.substring(dot) else ""
            var stem = if (ext.isEmpty()) s else s.substring(0, dot)
            val budget = maxBytes - ext.toByteArray(Charsets.UTF_8).size
            while (stem.isNotEmpty() && stem.toByteArray(Charsets.UTF_8).size > budget) stem = stem.dropLast(1)
            return stem + ext
        }
    }
}

fun ByteArray.toHex(): String {
    val chars = CharArray(size * 2)
    forEachIndexed { i, b ->
        val v = b.toInt() and 0xff
        chars[i * 2] = HEX[v ushr 4]
        chars[i * 2 + 1] = HEX[v and 0x0f]
    }
    return String(chars)
}

private val HEX = "0123456789abcdef".toCharArray()
