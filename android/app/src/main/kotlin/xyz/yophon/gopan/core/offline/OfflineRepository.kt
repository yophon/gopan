package xyz.yophon.gopan.core.offline

import androidx.compose.runtime.Immutable
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import xyz.yophon.gopan.core.auth.AuthState
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.auth.SignOutHook
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.NodeKind
import xyz.yophon.gopan.core.data.NodesRepository
import xyz.yophon.gopan.core.data.db.GopanDatabase
import xyz.yophon.gopan.core.transfer.DownloadDest
import xyz.yophon.gopan.core.transfer.LiveProgress
import xyz.yophon.gopan.core.transfer.OfflineSink
import xyz.yophon.gopan.core.transfer.TaskKey
import xyz.yophon.gopan.core.transfer.TransferEngine
import xyz.yophon.gopan.core.transfer.TransferState
import xyz.yophon.gopan.core.transfer.db.DownloadTaskEntity
import xyz.yophon.gopan.di.ApplicationScope
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 列表上的离线标记:[pinned] 是手动加过的项(文件或文件夹),[saved] 是手机上有副本的文件 */
@Immutable
data class OfflineMarks(val pinned: Set<String> = emptySet(), val saved: Set<String> = emptySet()) {
    fun has(id: String): Boolean = id in pinned || id in saved
}

/** 离线项这一轮的状态 */
sealed interface OfflineStatus {
    /** 全部存到手机上了 */
    data object Ready : OfflineStatus

    /** 正在下;[fraction] 未知时为 null,文件夹带上文件数 */
    data class Downloading(val fraction: Float?, val filesDone: Int? = null, val filesTotal: Int? = null) : OfflineStatus

    /** 排队、暂停或在等网络 */
    data object Waiting : OfflineStatus

    data class Failed(val message: String?) : OfflineStatus

    /** 这一轮的任务在「传输」里被取消了,没下完 */
    data object Incomplete : OfflineStatus
}

@Immutable
data class OfflineItem(
    val nodeId: String,
    val name: String,
    val isFolder: Boolean,
    val mime: String?,
    /** 加入 / 上次更新时云端的大小 */
    val cloudBytes: Long?,
    /** 手机上已经存了的文件数与字节 */
    val files: Int,
    val bytes: Long,
    val status: OfflineStatus,
    val syncedAt: Long?,
)

/** 手机上的一份副本 */
@Immutable
data class LocalCopy(
    val nodeId: String,
    val name: String,
    val size: Long,
    val sha256: String?,
    val mime: String?,
    val file: File,
)

/**
 * 离线可用(doc/26):手动选文件或文件夹存到手机上,没网也能打开;不同步,云端改了要在「离线可用」页手动更新。
 * 下载走传输引擎(落点 [DownloadDest.OFFLINE]:续传、校验、后台、通知都是现成的),放到位与登记在 [OfflineStore]。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class OfflineRepository @Inject constructor(
    db: GopanDatabase,
    private val store: OfflineStore,
    private val engine: TransferEngine,
    private val session: SessionStore,
    private val nodes: NodesRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val dao = db.offlineDao()

    private val userId: Flow<String?> = session.state
        .map { (it as? AuthState.SignedIn)?.user?.id }
        .distinctUntilChanged()

    private val pins: Flow<List<OfflinePinEntity>> =
        userId.flatMapLatest { uid -> if (uid == null) flowOf(emptyList()) else dao.observePins(uid) }

    private val files: Flow<List<OfflineFileEntity>> =
        userId.flatMapLatest { uid -> if (uid == null) flowOf(emptyList()) else dao.observeFiles(uid) }

    val marks: StateFlow<OfflineMarks> = combine(pins, files) { p, f ->
        OfflineMarks(pinned = p.mapTo(HashSet()) { it.nodeId }, saved = f.mapTo(HashSet()) { it.nodeId })
    }.stateIn(appScope, SharingStarted.Eagerly, OfflineMarks())

    /** 「离线可用」页:最近加的在前,带这一轮的状态与手机上的占用 */
    val items: Flow<List<OfflineItem>> = combine(pins, files, engine.downloadTasks, engine.progress) { p, f, tasks, progress ->
        val byRoot = f.groupBy { it.rootId }
        val taskById = tasks.associateBy { it.id }
        val children = tasks.filter { it.batchId != null }.groupBy { it.batchId }
        p.map { pin ->
            val local = byRoot[pin.nodeId].orEmpty()
            OfflineItem(
                nodeId = pin.nodeId,
                name = pin.name,
                isFolder = pin.isFolder,
                mime = pin.mime,
                cloudBytes = pin.size,
                files = local.size,
                bytes = local.sumOf { it.size },
                status = statusOf(pin, pin.taskId?.let(taskById::get), children, progress),
                syncedAt = pin.syncedAt,
            )
        }
    }

    init {
        appScope.launch { store.cleanInbox() }
    }

    /** 离线项里某个目录下的文件(浏览文件夹项用) */
    fun filesOf(rootId: String): Flow<List<OfflineFileEntity>> =
        userId.flatMapLatest { uid -> if (uid == null) flowOf(emptyList()) else dao.observeFilesOf(uid, rootId) }

    /** 加入离线可用:已经加过的跳过;返回新加的个数 */
    suspend fun pin(nodes: List<Node>): Int {
        val uid = session.currentUser?.id ?: return 0
        var n = 0
        for (node in nodes.distinctBy { it.id }) {
            if (dao.pin(uid, node.id) != null) continue
            val now = System.currentTimeMillis()
            dao.putPin(
                OfflinePinEntity(
                    userId = uid,
                    nodeId = node.id,
                    name = node.name,
                    isFolder = node.isFolder,
                    mime = node.mime,
                    size = sizeOf(node),
                    pinnedAt = now,
                    roundAt = now,
                ),
            )
            startRound(uid, node)
            n++
        }
        return n
    }

    /** 重新取一轮:没变的文件直接用手机上的副本,改过的重下,云端删掉的清掉 */
    suspend fun update(nodeId: String) {
        val uid = session.currentUser?.id ?: return
        val pin = dao.pin(uid, nodeId) ?: return
        pin.taskId?.let { dropTask(it) }
        dao.putPin(pin.copy(taskId = null, roundAt = System.currentTimeMillis()))
        val kind = if (pin.isFolder) NodeKind.FOLDER else NodeKind.FILE
        startRound(uid, Node(id = pin.nodeId, name = pin.name, kind = kind, size = if (pin.isFolder) null else pin.size, mime = pin.mime))
    }

    /** 移除:先删登记(还在下的那份落地时会被丢掉),再停任务、删副本 */
    suspend fun unpin(nodeIds: Collection<String>) {
        val uid = session.currentUser?.id ?: return
        for (id in nodeIds) {
            val pin = dao.pin(uid, id) ?: continue
            dao.deletePin(uid, id)
            pin.taskId?.let { dropTask(it) }
            store.remove(uid, id)
        }
    }

    /** 某个文件在手机上的副本;没有返回 null */
    suspend fun localCopy(nodeId: String): LocalCopy? {
        val uid = session.currentUser?.id ?: return null
        val f = store.localCopy(uid, nodeId) ?: return null
        return LocalCopy(f.nodeId, f.name, f.size, f.sha256, f.mime, File(f.path))
    }

    private suspend fun startRound(uid: String, node: Node) {
        val taskId = if (node.isFolder) engine.enqueueFolder(node, DownloadDest.OFFLINE) else engine.enqueueFile(node, DownloadDest.OFFLINE)
        dao.setTask(uid, node.id, taskId)
        // 文件夹:顺手把每一层的目录列表存进缓存,没网时文件页也能一层层点进去
        if (node.isFolder) {
            appScope.launch {
                runCatching { nodes.cacheTree(node.id) }.onFailure { Timber.w(it, "缓存离线文件夹的目录失败") }
            }
        }
    }

    /** 上一轮的任务:还在跑的停掉,跑完的也从传输列表里拿掉(落点是离线副本,留着没意义) */
    private suspend fun dropTask(taskId: Long) {
        val key = TaskKey.Download(taskId)
        engine.cancel(key)
    }

    companion object {
        /** 文件夹用子树统计(可能滞后) */
        fun sizeOf(node: Node): Long? = if (node.isFolder) node.subtreeBytes ?: node.size else node.size

        internal fun statusOf(
            pin: OfflinePinEntity,
            task: DownloadTaskEntity?,
            children: Map<Long?, List<DownloadTaskEntity>>,
            progress: Map<TaskKey, LiveProgress>,
        ): OfflineStatus {
            if (task == null) return if (pin.syncedAt != null) OfflineStatus.Ready else OfflineStatus.Incomplete
            return when (task.state) {
                TransferState.DONE -> OfflineStatus.Ready
                TransferState.FAILED -> OfflineStatus.Failed(task.errorMessage)
                TransferState.CANCELED -> OfflineStatus.Incomplete
                TransferState.PAUSED -> OfflineStatus.Waiting
                TransferState.QUEUED, TransferState.RUNNING -> {
                    if (pin.isFolder) {
                        val kids = children[task.id].orEmpty()
                        if (!task.expanded) return OfflineStatus.Downloading(null)
                        val total = task.size ?: 0L
                        val done = kids.filter { it.state == TransferState.DONE }
                        val live = kids.sumOf { progress[TaskKey.Download(it.id)]?.doneBytes ?: 0L }
                        val bytes = done.sumOf { it.size ?: 0L } + live
                        val fraction = if (total > 0) (bytes.toDouble() / total).toFloat().coerceIn(0f, 1f) else null
                        OfflineStatus.Downloading(fraction, done.size, kids.size)
                    } else {
                        val p = progress[TaskKey.Download(task.id)]
                        if (p == null && task.state == TransferState.QUEUED) OfflineStatus.Waiting else OfflineStatus.Downloading(p?.takeIf { it.totalBytes > 0 }?.fraction)
                    }
                }
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class OfflineBindsModule {
    @Binds
    abstract fun bindOfflineSink(impl: OfflineStore): OfflineSink

    @Binds
    abstract fun bindOfflineLookup(impl: DefaultOfflineLookup): OfflineLookup
}

@Module
@InstallIn(SingletonComponent::class)
object OfflineModule {
    /** 登出 / 会话失效:手机上的副本属于这个账号,一起清掉 */
    @Provides
    @IntoSet
    fun provideOfflineSignOutHook(store: OfflineStore): SignOutHook = SignOutHook { store.wipe() }
}
