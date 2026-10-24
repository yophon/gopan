package xyz.yophon.gopan.core.transfer.download

import androidx.core.net.toUri
import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.transfer.DownloadDest
import xyz.yophon.gopan.core.transfer.SourceStore
import xyz.yophon.gopan.core.transfer.TransferErrors
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** 已校验的下载内容落到最终位置 */
interface DownloadPublisher {
    /**
     * 把 [file](私有目录里的 .part,已校验)发布到 [dest]。返回最终位置:content:// URI 或(OPEN_WITH)文件路径。
     * 成功后 [file] 已被移走或删除。失败抛 [GopanException]。
     */
    suspend fun publish(
        file: File,
        name: String,
        mime: String?,
        relDir: String,
        dest: DownloadDest,
        treeUri: String?,
        cacheKey: String?,
    ): String

    /** OPEN_WITH:缓存里已有同内容的文件就直接用(按 sha256 分目录) */
    fun cachedOpenFile(cacheKey: String, name: String, size: Long): File?
}

/**
 * - DOWNLOADS:Android 10+ 写 MediaStore.Downloads(Download/gopan/<相对目录>,IS_PENDING 写完再公开);
 *   8–9 写公共「下载/gopan」目录(需要 WRITE_EXTERNAL_STORAGE)并通知媒体库;
 * - TREE:用户选的 SAF 目录树,逐级找或建子目录;
 * - OPEN_WITH:改名移到 cacheDir/open/<sha256>/<名字>(同一分区,不用拷贝);
 * - OFFLINE:改名移到离线收件箱,由离线模块放到 filesDir/offline 下(doc/26)。
 */
@Singleton
class AndroidDownloadPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sources: SourceStore,
) : DownloadPublisher {
    private val resolver: ContentResolver get() = context.contentResolver

    override suspend fun publish(
        file: File,
        name: String,
        mime: String?,
        relDir: String,
        dest: DownloadDest,
        treeUri: String?,
        cacheKey: String?,
    ): String = withContext(Dispatchers.IO) {
        val type = mimeFor(name, mime)
        val result = try {
            when (dest) {
                DownloadDest.OPEN_WITH -> toOpenCache(file, name, cacheKey)
                DownloadDest.OFFLINE -> toOfflineInbox(file)
                DownloadDest.DOWNLOADS ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) toMediaStore(file, name, type, relDir) else toLegacyDownloads(file, name, type, relDir)
                DownloadDest.TREE -> toTree(file, name, type, relDir, treeUri ?: throw TransferErrors.saveFailed("没有选择下载目录"))
            }
        } catch (e: GopanException) {
            throw e
        } catch (e: SecurityException) {
            throw TransferErrors.saveFailed("没有写入权限,请到设置里重新选择下载目录")
        } catch (e: IOException) {
            if (isNoSpace(e)) throw TransferErrors.noSpace()
            throw TransferErrors.saveFailed(e.message)
        }
        if (dest != DownloadDest.OPEN_WITH && dest != DownloadDest.OFFLINE) file.delete()
        result
    }

    override fun cachedOpenFile(cacheKey: String, name: String, size: Long): File? {
        val f = File(File(sources.openDir, cacheKey), SourceStore.sanitizeFileName(name))
        return f.takeIf { it.isFile && it.length() == size }
    }

    /** 离线可用:先挪进收件箱(同一分区改名),引擎随后交给离线模块放到位 */
    private fun toOfflineInbox(file: File): String {
        val inbox = sources.offlineInbox
        inbox.mkdirs()
        val target = File(inbox, file.name)
        target.delete()
        if (!file.renameTo(target)) {
            file.inputStream().use { input -> target.outputStream().use { input.copyTo(it, SourceStore.BUFFER) } }
            file.delete()
        }
        return target.absolutePath
    }

    private fun toOpenCache(file: File, name: String, cacheKey: String?): String {
        val dir = File(sources.openDir, cacheKey ?: "n-${file.nameWithoutExtension}")
        dir.mkdirs()
        val target = File(dir, SourceStore.sanitizeFileName(name))
        target.delete()
        if (!file.renameTo(target)) {
            file.inputStream().use { input -> target.outputStream().use { input.copyTo(it, SourceStore.BUFFER) } }
            file.delete()
        }
        return target.absolutePath
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun toMediaStore(file: File, name: String, type: String, relDir: String): String {
        val relative = buildString {
            append(Environment.DIRECTORY_DOWNLOADS).append("/gopan")
            if (relDir.isNotEmpty()) append('/').append(relDir)
            append('/')
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, type)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw TransferErrors.saveFailed("系统下载目录不可用")
        try {
            resolver.openOutputStream(uri, "w")?.use { out -> copy(file, out) } ?: throw IOException("无法写入下载目录")
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri.toString()
    }

    private suspend fun toLegacyDownloads(file: File, name: String, type: String, relDir: String): String {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) throw TransferErrors.needStoragePermission()
        @Suppress("DEPRECATION")
        val base = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "gopan")
        val dir = if (relDir.isEmpty()) base else File(base, relDir)
        if (!dir.isDirectory && !dir.mkdirs()) throw TransferErrors.saveFailed("无法创建下载目录")
        val target = SourceStore.uniqueFile(dir, name)
        target.outputStream().use { out -> copy(file, out) }
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(type), null)
        return Uri.fromFile(target).toString()
    }

    private suspend fun toTree(file: File, name: String, type: String, relDir: String, treeUri: String): String {
        val tree = treeUri.toUri()
        var parentDoc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        for (segment in relDir.split('/').filter { it.isNotEmpty() }) {
            parentDoc = findChild(tree, parentDoc, segment, wantDir = true)
                ?: DocumentsContract.createDocument(resolver, parentDoc, DocumentsContract.Document.MIME_TYPE_DIR, segment)
                ?: throw TransferErrors.saveFailed("无法创建文件夹 $segment")
        }
        // provider 会在扩展名与 MIME 不符时追加扩展名:类型对不上时按二进制写,名字原样保留
        val ext = name.substringAfterLast('.', "").lowercase()
        val mapped = MimeTypeMap.getSingleton().getExtensionFromMimeType(type)
        val createType = if (ext.isNotEmpty() && mapped == ext) type else OCTET
        val doc = DocumentsContract.createDocument(resolver, parentDoc, createType, name)
            ?: throw TransferErrors.saveFailed("无法在所选目录里创建文件")
        try {
            resolver.openOutputStream(doc, "w")?.use { out -> copy(file, out) } ?: throw IOException("无法写入所选目录")
        } catch (e: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, doc) }
            throw e
        }
        return doc.toString()
    }

    private fun findChild(tree: Uri, parentDoc: Uri, name: String, wantDir: Boolean): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parentDoc))
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        resolver.query(childrenUri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                if (c.getString(1) == name && isDir == wantDir) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                }
            }
        }
        return null
    }

    private suspend fun copy(file: File, out: OutputStream) {
        file.inputStream().use { input -> copyStream(input, out) }
        out.flush()
    }

    private suspend fun copyStream(input: InputStream, out: OutputStream) {
        val buffer = ByteArray(SourceStore.BUFFER)
        while (true) {
            coroutineContext.ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
    }

    companion object {
        private const val OCTET = "application/octet-stream"

        /** 按名字的扩展名推断 MIME(blob 的 MIME 是第一次上传时按别的名字猜的,不可信),推不出再用服务端给的 */
        fun mimeFor(name: String, serverMime: String?): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            val byName = if (ext.isNotEmpty()) MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) else null
            return byName ?: serverMime?.takeIf { it.isNotBlank() } ?: OCTET
        }

        fun isNoSpace(e: IOException): Boolean = e.message?.contains("ENOSPC") == true || e.message?.contains("No space") == true
    }
}
