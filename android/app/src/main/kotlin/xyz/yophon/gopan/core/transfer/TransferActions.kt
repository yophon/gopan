package xyz.yophon.gopan.core.transfer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import xyz.yophon.gopan.core.transfer.db.UploadTaskEntity
import kotlinx.coroutines.withContext
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.chat.ChatRepository
import xyz.yophon.gopan.core.offline.OfflineRepository
import xyz.yophon.gopan.core.prefs.UserPreferencesRepository
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** "用其他应用打开"的下载任务:完成时若 App 在前台就直接弹选择器 */
@Singleton
class OpenRequests @Inject constructor() {
    private val ids: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    fun add(id: Long) {
        ids += id
    }

    fun consume(id: Long): Boolean = ids.remove(id)
}

/** 入上传队列的结果 */
data class EnqueueResult(val queued: Int, val emptySkipped: Int, val unreadable: Int)

sealed interface PackResult {
    data object Queued : PackResult

    data object TooLarge : PackResult
}

/**
 * 界面层发起传输的统一入口(文件页、搜索页、传输助手共用):把选好的 URI 变成上传来源、
 * 按设置决定下载落点、打包前做 2 GiB 预检、"打开"登记成待弹选择器的下载。
 * 接口是为了 ViewModel 单测能换成假实现。
 */
interface TransferActions {
    /** 任务完成 / 失败的事件(列表页据此静默刷新) */
    val events: Flow<TransferEvent>

    /** 当前账号的上传任务(传输助手的进度卡片) */
    val uploadTasks: Flow<List<UploadTaskEntity>>

    /** 上传任务的实时进度,按任务 id */
    val uploadProgress: Flow<Map<Long, LiveProgress>>

    suspend fun retryUpload(id: Long)

    suspend fun cancelUpload(id: Long)

    suspend fun removeUpload(id: Long)

    /** 超过 4000 字节的文本:存成「文本 yyyy-MM-dd HH-mm.txt」上传 */
    suspend fun uploadText(text: String, parentId: String?, toChat: Boolean): EnqueueResult

    /**
     * 粘贴 / 键盘插入的图片:授权只在这一刻有效,先拷进私有目录再传。
     * 没有名字或是通用名(image、clipboard……)的,命名为「截图 yyyy-MM-dd HH-mm.<ext>」(与网页端一致)。
     */
    suspend fun uploadCopies(uris: List<Uri>, parentId: String?, toChat: Boolean): EnqueueResult

    /** [replaceNames]:这些名字的文件覆盖目标文件夹里的同名文件(旧内容进历史版本),其余照常(同名自动改名) */
    suspend fun uploadUris(uris: List<Uri>, parentId: String?, toChat: Boolean = false, replaceNames: Set<String> = emptySet()): EnqueueResult

    /** 这些文件上传后叫什么(与 [uploadUris] 定的名字一致):入队前先和目标文件夹比对同名 */
    suspend fun uploadNames(uris: List<Uri>): List<String>

    suspend fun uploadOwnedFiles(files: List<File>, parentId: String?, toChat: Boolean = false): EnqueueResult

    suspend fun download(nodes: List<Node>): Int

    suspend fun pack(nodes: List<Node>): PackResult

    suspend fun open(node: Node): Boolean

    suspend fun sendToChat(node: Node)

    suspend fun needsStoragePermission(): Boolean

    /** 离线可用(doc/26):加入,返回新加的个数(已经加过的跳过) */
    suspend fun pinOffline(nodes: List<Node>): Int

    /** 离线可用:移除,手机上的副本一起删 */
    suspend fun unpinOffline(ids: List<String>)

    /** 现在用的是不计流量的网络(Wi-Fi 等) */
    fun isUnmetered(): Boolean

    /** App 私有目录所在分区的剩余空间 */
    fun freeSpace(): Long
}

@Singleton
class DefaultTransferActions @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: TransferEngine,
    private val sources: SourceStore,
    private val prefs: UserPreferencesRepository,
    private val openRequests: OpenRequests,
    private val chat: ChatRepository,
    private val offline: OfflineRepository,
    private val network: NetworkMonitor,
    private val opener: FileOpener,
) : TransferActions {
    override val events: Flow<TransferEvent> get() = engine.events

    override val uploadTasks: Flow<List<UploadTaskEntity>> get() = engine.uploadTasks

    override val uploadProgress: Flow<Map<Long, LiveProgress>>
        get() = engine.progress.map { all ->
            all.entries.mapNotNull { (k, v) -> (k as? TaskKey.Upload)?.let { it.id to v } }.toMap()
        }

    override suspend fun retryUpload(id: Long) = engine.retry(TaskKey.Upload(id))

    override suspend fun cancelUpload(id: Long) = engine.cancel(TaskKey.Upload(id))

    override suspend fun removeUpload(id: Long) = engine.remove(TaskKey.Upload(id))

    override suspend fun uploadCopies(uris: List<Uri>, parentId: String?, toChat: Boolean): EnqueueResult {
        val files = mutableListOf<File>()
        var unreadable = 0
        for (uri in uris.distinct()) {
            try {
                val display = sources.displayName(uri)
                val generic = display == null || display.substringBeforeLast('.').lowercase() in GENERIC_NAMES
                val name = if (generic) SourceStore.timestampName("截图", "").trimEnd('.') else display!!
                files += sources.copyIn(uri, sources.incomingDir, name, preferFallback = generic)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                unreadable++
            }
        }
        val r = uploadOwnedFiles(files, parentId, toChat)
        return r.copy(unreadable = r.unreadable + unreadable)
    }

    override suspend fun uploadText(text: String, parentId: String?, toChat: Boolean): EnqueueResult {
        val file = withContext(Dispatchers.IO) {
            sources.newPrivateFile(sources.textDir, SourceStore.timestampName("文本", "txt")).apply { writeText(text, Charsets.UTF_8) }
        }
        return uploadOwnedFiles(listOf(file), parentId, toChat)
    }

    /** SAF / 相册选择器选中的 URI:持久化读授权(续传要用),取名字与大小;空文件跳过 */
    override suspend fun uploadNames(uris: List<Uri>): List<String> = withContext(Dispatchers.IO) {
        uris.distinct().map { uri -> SourceStore.withExtension(sources.displayName(uri) ?: "未命名", sources.mimeOf(uri)) }
    }

    override suspend fun uploadUris(uris: List<Uri>, parentId: String?, toChat: Boolean, replaceNames: Set<String>): EnqueueResult {
        val prepared = withContext(Dispatchers.IO) {
            uris.distinct().map { uri ->
                val persisted = sources.takePersistableRead(uri)
                val stat = sources.stat(uri)
                if (stat == null) {
                    if (persisted) sources.releasePersistableRead(uri)
                    null to 1
                } else if (stat.size == 0L) {
                    if (persisted) sources.releasePersistableRead(uri)
                    null to 0
                } else {
                    val mime = sources.mimeOf(uri)
                    val name = SourceStore.withExtension(sources.displayName(uri) ?: "未命名", mime)
                    UploadSource(uri, name, stat.size, stat.lastModified, mime, owns = false, persisted = persisted, replace = name in replaceNames) to -1
                }
            }
        }
        val items = prepared.mapNotNull { it.first }
        engine.enqueueUploads(items, parentId, toChat)
        return EnqueueResult(items.size, prepared.count { it.second == 0 }, prepared.count { it.second == 1 })
    }

    /** App 自己的私有文件(拍照、长文本、分享接收):上传完删 */
    override suspend fun uploadOwnedFiles(files: List<File>, parentId: String?, toChat: Boolean): EnqueueResult {
        val items = files.filter { it.isFile && it.length() > 0 }.map {
            UploadSource(Uri.fromFile(it), it.name, it.length(), it.lastModified(), null, owns = true, persisted = false)
        }
        files.filter { it.isFile && it.length() == 0L }.forEach { it.delete() }
        engine.enqueueUploads(items, parentId, toChat)
        return EnqueueResult(items.size, files.size - items.size, 0)
    }

    /** 下载到设置里的位置:文件逐个,文件夹客户端展开 */
    override suspend fun download(nodes: List<Node>): Int {
        val (dest, tree) = destination()
        var n = 0
        for (node in normalize(nodes)) {
            val id = if (node.isFolder) engine.enqueueFolder(node, dest, tree) else engine.enqueueFile(node, dest, tree)
            if (id != null) n++
        }
        return n
    }

    /** 打包成一个 zip(≤ 2 GiB,按文件大小与文件夹子树统计预检;统计可能滞后,服务端还会再拦一次) */
    override suspend fun pack(nodes: List<Node>): PackResult {
        val roots = normalize(nodes)
        val estimate = roots.sumOf { if (it.isFolder) it.subtreeBytes ?: 0L else it.size ?: 0L }
        if (estimate > PACK_MAX_BYTES) return PackResult.TooLarge
        val (dest, tree) = destination()
        engine.enqueuePack(roots, estimate, dest, tree)
        return PackResult.Queued
    }

    /**
     * 下到缓存后弹"用其他应用打开"。没网时手机上有离线副本就直接打开它(有网时照常取最新的,
     * 内容没变的话下载那一步会直接用离线副本,不重下)
     */
    override suspend fun open(node: Node): Boolean {
        if (!network.isOnline()) {
            offline.localCopy(node.id)?.let { copy ->
                if (!opener.openFile(copy.file, copy.name, copy.mime, context.getString(R.string.drive_action_open))) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, R.string.transfer_msg_no_app, Toast.LENGTH_SHORT).show() }
                }
                return false
            }
        }
        val id = engine.enqueueFile(node, DownloadDest.OPEN_WITH) ?: return false
        openRequests.add(id)
        return true
    }

    override suspend fun sendToChat(node: Node) {
        chat.sendNode(node.id)
    }

    override suspend fun pinOffline(nodes: List<Node>): Int = offline.pin(normalize(nodes))

    override suspend fun unpinOffline(ids: List<String>) = offline.unpin(ids)

    override fun isUnmetered(): Boolean = network.isUnmetered()

    override fun freeSpace(): Long = context.filesDir.usableSpace

    private suspend fun destination(): Pair<DownloadDest, String?> {
        val tree = prefs.preferences.first().transfer.downloadTreeUri
        return if (tree != null) DownloadDest.TREE to tree else DownloadDest.DOWNLOADS to null
    }

    /** 已选了某个文件夹时,它的子孙不再单独处理(列表里同时出现父子的情况很少,这里只做去重) */
    private fun normalize(nodes: List<Node>): List<Node> = nodes.distinctBy { it.id }

    /** Android 9 及以下、落点是系统「下载」目录、还没有存储权限 */
    override suspend fun needsStoragePermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            destination().first == DownloadDest.DOWNLOADS &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    companion object {
        /** 服务端 PackMaxBytes = 2 << 30,恰好 2 GiB 可以 */
        const val PACK_MAX_BYTES = 2L shl 30

        /** 剪贴板 / 输入法给的通用文件名(网页端同一张表) */
        private val GENERIC_NAMES = setOf("image", "blob", "unnamed", "untitled", "clipboard")
    }
}
