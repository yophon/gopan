package xyz.yophon.gopan.core.documents

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.NodesRepository
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.toGopanException
import xyz.yophon.gopan.core.prefs.DriveSortOrder
import xyz.yophon.gopan.core.ui.format.parseTime
import java.io.FileNotFoundException

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DocumentsEntryPoint {
    fun sessionStore(): SessionStore

    fun nodes(): NodesRepository

    fun cache(): DocumentCache

    fun pending(): PendingDocuments
}

/**
 * 系统「文件」App 与其它应用的「打开文件 / 另存为」面板里的 gopan(Storage Access Framework):
 * 浏览目录、搜索、按需下载后读取、缩略图;新文件写完进上传队列;新建文件夹、重命名、删除(进回收站)。
 * 没登录时不出现。方法都在 binder 线程里调用,网络请求直接阻塞(系统界面自己转圈)。
 *
 * 文档 id:根目录是 [ROOT_DOC],节点是服务端的节点 id,还没传完的新文件是 `pending:<uuid>`。
 */
class GopanDocumentsProvider : DocumentsProvider() {

    private val deps: DocumentsEntryPoint by lazy {
        EntryPointAccessors.fromApplication(requireNotNull(context).applicationContext, DocumentsEntryPoint::class.java)
    }

    private val authority: String get() = requireNotNull(context).packageName + AUTHORITY_SUFFIX

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: ROOT_PROJECTION)
        c.setNotificationUri(requireNotNull(context).contentResolver, DocumentsContract.buildRootsUri(authority))
        val user = deps.sessionStore().currentUser ?: return c
        c.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC)
            add(Root.COLUMN_TITLE, requireNotNull(context).getString(R.string.app_name))
            add(Root.COLUMN_SUMMARY, user.username)
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_SEARCH)
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_MIME_TYPES, "*/*")
            if (user.quotaBytes > 0) add(Root.COLUMN_AVAILABLE_BYTES, (user.quotaBytes - user.usedBytes).coerceAtLeast(0))
        }
        return c
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        when {
            documentId == ROOT_DOC -> addRoot(c)
            documentId.startsWith(PendingDocuments.PREFIX) -> {
                val p = deps.pending().get(documentId) ?: throw FileNotFoundException("文件不存在")
                addPending(c, p)
            }
            else -> addNode(c, call { deps.nodes().node(documentId) })
        }
        return c
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val c = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        c.setNotificationUri(requireNotNull(context).contentResolver, DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId))
        val parentId = if (parentDocumentId == ROOT_DOC) null else parentDocumentId
        try {
            var cursor: String? = null
            var count = 0
            do {
                val page = call { deps.nodes().children(parentId, cursor, DriveSortOrder.NAME, false) }
                page.items.forEach { addNode(c, it) }
                count += page.items.size
                cursor = page.nextCursor
            } while (cursor != null && count < MAX_CHILDREN)
            deps.pending().inParent(parentDocumentId).forEach { addPending(c, it) }
        } catch (e: FileNotFoundException) {
            // 列不出来时让系统界面显示原因,而不是一个看似为空的目录
            c.extras = Bundle().apply { putString(DocumentsContract.EXTRA_ERROR, e.message) }
        }
        return c
    }

    override fun querySearchDocuments(rootId: String, query: String, projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        try {
            call { deps.nodes().search(query, null) }.items.forEach { addNode(c, it) }
        } catch (e: FileNotFoundException) {
            c.extras = Bundle().apply { putString(DocumentsContract.EXTRA_ERROR, e.message) }
        }
        return c
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (documentId.startsWith(PendingDocuments.PREFIX)) {
            val entry = deps.pending().get(documentId) ?: throw FileNotFoundException("文件不存在")
            if (entry.written) throw FileNotFoundException("文件已经在上传")
            val pfdMode = ParcelFileDescriptor.parseMode(mode)
            // 写完(描述符关闭)进上传队列
            return ParcelFileDescriptor.open(entry.file, pfdMode, Handler(Looper.getMainLooper())) { error ->
                if (error != null) Timber.w(error, "写入 gopan 文档失败")
                deps.pending().onWritten(entry)
            }
        }
        if (mode.contains('w') || mode.contains('t')) {
            // 服务端没有版本 / 原地覆盖;已有文件只读,新文件请用「另存为」(createDocument)
            throw UnsupportedOperationException("gopan 里的已有文件不能直接改写,请另存为新文件")
        }
        if (documentId == ROOT_DOC) throw FileNotFoundException("不是文件")
        val file = deps.cache().file(documentId, signal)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openDocumentThumbnail(documentId: String, sizeHint: Point?, signal: CancellationSignal?): AssetFileDescriptor {
        if (documentId == ROOT_DOC || documentId.startsWith(PendingDocuments.PREFIX)) throw FileNotFoundException("没有缩略图")
        val file = deps.cache().thumbnail(documentId, signal) ?: throw FileNotFoundException("没有缩略图")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parentId = if (parentDocumentId == ROOT_DOC) null else parentDocumentId
        val id = if (mimeType == Document.MIME_TYPE_DIR) {
            call { deps.nodes().createFolder(parentId, displayName) }.id
        } else {
            deps.pending().create(parentDocumentId, parentId, displayName, mimeType).id
        }
        notifyChildren(parentDocumentId)
        return id
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        if (documentId == ROOT_DOC || documentId.startsWith(PendingDocuments.PREFIX)) throw UnsupportedOperationException("不能重命名")
        val node = call { deps.nodes().rename(documentId, displayName) }
        notifyChildren(node.parentId ?: ROOT_DOC)
        return null
    }

    override fun deleteDocument(documentId: String) {
        if (documentId == ROOT_DOC) throw UnsupportedOperationException("不能删除根目录")
        if (documentId.startsWith(PendingDocuments.PREFIX)) {
            deps.pending().get(documentId)?.let { deps.pending().remove(it) }
            return
        }
        val parent = runCatching { call { deps.nodes().node(documentId) }.parentId }.getOrNull()
        call { deps.nodes().delete(listOf(documentId)) }
        notifyChildren(parent ?: ROOT_DOC)
    }

    override fun getDocumentType(documentId: String): String = when {
        documentId == ROOT_DOC -> Document.MIME_TYPE_DIR
        documentId.startsWith(PendingDocuments.PREFIX) -> deps.pending().get(documentId)?.mime ?: GENERIC_MIME
        else -> mimeOf(call { deps.nodes().node(documentId) })
    }

    // ---- 行 ----

    private fun addRoot(c: MatrixCursor) {
        c.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, ROOT_DOC)
            add(Document.COLUMN_DISPLAY_NAME, requireNotNull(context).getString(R.string.app_name))
            add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE)
            add(Document.COLUMN_SIZE, null)
            add(Document.COLUMN_LAST_MODIFIED, null)
        }
    }

    private fun addNode(c: MatrixCursor, node: Node) {
        val flags = if (node.isFolder) {
            Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        } else {
            Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or (if (node.hasThumb) Document.FLAG_SUPPORTS_THUMBNAIL else 0)
        }
        c.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, node.id)
            add(Document.COLUMN_DISPLAY_NAME, node.name)
            add(Document.COLUMN_MIME_TYPE, mimeOf(node))
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_SIZE, if (node.isFolder) null else node.size)
            add(Document.COLUMN_LAST_MODIFIED, parseTime(node.updatedAt)?.toEpochMilli())
        }
    }

    private fun addPending(c: MatrixCursor, p: PendingDocuments.Entry) {
        c.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, p.id)
            add(Document.COLUMN_DISPLAY_NAME, p.name)
            add(Document.COLUMN_MIME_TYPE, p.mime)
            add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_WRITE)
            add(Document.COLUMN_SIZE, p.file.length())
            add(Document.COLUMN_LAST_MODIFIED, p.file.lastModified())
            add(Document.COLUMN_SUMMARY, requireNotNull(context).getString(R.string.documents_uploading))
        }
    }

    private fun notifyChildren(parentDocumentId: String) {
        requireNotNull(context).contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId), null)
    }

    /** 同步跑一次请求;没登录、网络、服务端错误一律变成 FileNotFoundException(系统界面显示 message) */
    private fun <T> call(block: suspend () -> T): T {
        if (deps.sessionStore().currentUser == null) throw FileNotFoundException("未登录 gopan")
        return try {
            runBlocking { block() }
        } catch (e: Exception) {
            val ge = e as? GopanException ?: e.toGopanException()
            throw FileNotFoundException(if (ge.code == GopanErrors.NOT_FOUND) "文件不存在" else ge.message ?: ge.code).apply { initCause(e) }
        }
    }

    companion object {
        const val AUTHORITY_SUFFIX = ".documents"
        const val ROOT_ID = "gopan"
        const val ROOT_DOC = "root"
        const val MAX_CHILDREN = 2000
        const val GENERIC_MIME = "application/octet-stream"

        val ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_MIME_TYPES, Root.COLUMN_AVAILABLE_BYTES,
        )
        val DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SUMMARY,
        )

        /** 服务端的 mime 优先,没有就按扩展名猜 */
        fun mimeOf(node: Node): String {
            if (node.isFolder) return Document.MIME_TYPE_DIR
            node.mime?.takeIf { it.isNotBlank() && it != GENERIC_MIME }?.let { return it.substringBefore(';').trim() }
            val ext = node.name.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: GENERIC_MIME
        }
    }
}
