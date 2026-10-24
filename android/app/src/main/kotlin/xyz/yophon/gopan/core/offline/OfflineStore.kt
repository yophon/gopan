package xyz.yophon.gopan.core.offline

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import xyz.yophon.gopan.core.data.db.GopanDatabase
import xyz.yophon.gopan.core.transfer.OfflineSink
import xyz.yophon.gopan.core.transfer.SourceStore
import xyz.yophon.gopan.core.transfer.db.DownloadTaskEntity
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 离线副本的文件与登记(doc/26):filesDir/offline/<离线项 id>/<文件 id>/<名字>,一个文件一个目录(改名时整目录换掉)。
 * 下载由传输引擎做([OfflineSink] 是引擎回调进来的口子);这里只管放到位、查副本、删副本。
 * 同一时刻只有一个操作在改文件与登记(引擎同时跑 3 个下载)。
 */
@Singleton
class OfflineStore internal constructor(
    private val dao: OfflineDao,
    /** filesDir/offline;收件箱在它下面的 inbox */
    private val dir: () -> File,
) : OfflineSink {
    @Inject
    constructor(db: GopanDatabase, sources: SourceStore) : this(db.offlineDao(), { sources.offlineDir })

    private val mutex = Mutex()

    private val root: File get() = dir()
    private val inbox: File get() = File(root, INBOX)

    override suspend fun existing(userId: String, nodeId: String, sha256: String, size: Long): String? = withContext(Dispatchers.IO) {
        dao.byNode(userId, nodeId).firstOrNull { f ->
            f.sha256 == sha256 && f.size == size && File(f.path).let { it.isFile && it.length() == size }
        }?.path
    }

    override suspend fun saved(task: DownloadTaskEntity, root: DownloadTaskEntity, location: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val userId = task.userId
            val rootId = root.nodeId
            val nodeId = task.nodeId
            val src = File(location)
            val pin = rootId?.let { dao.pin(userId, it) }
            // 离线项已经移除,或者已经开始了新的一轮:这一份不要了
            if (rootId == null || nodeId == null || pin == null || (pin.taskId != null && pin.taskId != root.id)) {
                if (inInbox(src)) src.delete()
                return@withLock null
            }
            val dir = fileDir(rootId, nodeId)
            val target = File(dir, SourceStore.sanitizeFileName(task.name))
            val old = dao.file(userId, rootId, nodeId)
            if (src.canonicalPath != target.canonicalPath) {
                dir.mkdirs()
                target.delete()
                // 收件箱里的、或者就是这一项原来的副本(云端改了名):挪过去;别的离线项里的同内容副本:拷一份
                val movable = inInbox(src) ||(old != null && src.canonicalPath == File(old.path).canonicalPath)
                if (!movable || !src.renameTo(target)) {
                    src.inputStream().use { input -> target.outputStream().use { input.copyTo(it, SourceStore.BUFFER) } }
                    if (movable) src.delete()
                }
            }
            dir.listFiles()?.filter { it.canonicalPath != target.canonicalPath }?.forEach { it.deleteRecursively() }
            val now = System.currentTimeMillis()
            dao.putFile(
                OfflineFileEntity(
                    userId = userId,
                    rootId = rootId,
                    nodeId = nodeId,
                    name = task.name,
                    relDir = task.relDir,
                    size = target.length(),
                    sha256 = task.sha256,
                    mime = task.mime,
                    path = target.absolutePath,
                    savedAt = now,
                ),
            )
            // 单个文件的离线项:这一个下完就是这一轮下完
            if (task.id == root.id) dao.markSynced(userId, rootId, now)
            target.absolutePath
        }
    }

    override suspend fun roundDone(root: DownloadTaskEntity, nodeIds: Set<String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val userId = root.userId
            val rootId = root.nodeId ?: return@withLock
            val pin = dao.pin(userId, rootId) ?: return@withLock
            if (pin.taskId != null && pin.taskId != root.id) return@withLock
            val stale = dao.filesOf(userId, rootId).filter { it.nodeId !in nodeIds }
            stale.forEach { fileDir(rootId, it.nodeId).deleteRecursively() }
            stale.map { it.nodeId }.chunked(500).forEach { dao.deleteFiles(userId, rootId, it) }
            dao.markSynced(userId, rootId, System.currentTimeMillis())
        }
    }

    /** 某个文件在手机上的副本(在哪个离线项里都行);文件不在了的不算 */
    suspend fun localCopy(userId: String, nodeId: String): OfflineFileEntity? = withContext(Dispatchers.IO) {
        dao.byNode(userId, nodeId).firstOrNull { File(it.path).isFile }
    }

    /** 移除一个离线项:登记与副本一起删 */
    suspend fun remove(userId: String, rootId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            dao.deletePin(userId, rootId)
            dao.deleteFilesOf(userId, rootId)
            File(root, rootId).deleteRecursively()
        }
    }

    /** 登出:全部清掉(副本属于这个账号) */
    suspend fun wipe() = withContext(Dispatchers.IO) {
        mutex.withLock {
            dao.deleteAllPins()
            dao.deleteAllFiles()
            root.deleteRecursively()
        }
    }

    /** 收件箱里放了超过一小时还没人认领的(放到位前进程被杀了) */
    suspend fun cleanInbox(now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        inbox.listFiles()?.forEach { f ->
            if (now - f.lastModified() > INBOX_MAX_AGE_MS && !f.delete()) Timber.w("清不掉离线收件箱里的 %s", f.name)
        }
    }

    private fun fileDir(rootId: String, nodeId: String): File = File(File(root, rootId), nodeId)

    private fun inInbox(f: File): Boolean = f.parentFile?.canonicalPath == inbox.canonicalPath

    companion object {
        /** 与 [SourceStore.offlineInbox] 一致 */
        const val INBOX = "inbox"
        private const val INBOX_MAX_AGE_MS = 3_600_000L
    }
}
