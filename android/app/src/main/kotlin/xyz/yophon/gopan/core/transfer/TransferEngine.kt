package xyz.yophon.gopan.core.transfer

import androidx.core.net.toUri
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import xyz.yophon.gopan.core.auth.AuthState
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.db.GopanDatabase
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.prefs.UserPreferencesRepository
import xyz.yophon.gopan.core.share.IncomingShareStore
import xyz.yophon.gopan.core.transfer.db.DownloadTaskEntity
import xyz.yophon.gopan.core.transfer.db.UploadTaskEntity
import xyz.yophon.gopan.core.transfer.download.DownloadApi
import xyz.yophon.gopan.core.transfer.download.DownloadOutcome
import xyz.yophon.gopan.core.transfer.download.DownloadPublisher
import xyz.yophon.gopan.core.transfer.download.DownloadRun
import xyz.yophon.gopan.core.transfer.download.DownloadTaskWriter
import xyz.yophon.gopan.core.transfer.download.RangeSource
import xyz.yophon.gopan.core.transfer.upload.PartSender
import xyz.yophon.gopan.core.transfer.upload.UploadApi
import xyz.yophon.gopan.core.transfer.upload.UploadOutcome
import xyz.yophon.gopan.core.transfer.upload.UploadRun
import xyz.yophon.gopan.core.transfer.upload.UploadTaskWriter
import xyz.yophon.gopan.di.ApplicationScope
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** 选好的上传来源(界面层准备好:显示名、大小、授权) */
data class UploadSource(
    val uri: Uri,
    val name: String,
    val size: Long,
    val lastModified: Long? = null,
    val mime: String? = null,
    /** App 自己的副本(分享接收、拍照、长文本):任务结束后删 */
    val owns: Boolean = false,
    /** 已持久化的读授权:任务结束后释放 */
    val persisted: Boolean = false,
    /** 目标文件夹里有同名文件时覆盖它(旧内容进历史版本),而不是自动改名 */
    val replace: Boolean = false,
)

sealed interface TransferEvent {
    data class UploadDone(val task: UploadTaskEntity, val node: Node) : TransferEvent

    data class UploadFailed(val task: UploadTaskEntity, val error: GopanException) : TransferEvent

    data class DownloadDone(val task: DownloadTaskEntity, val location: String) : TransferEvent

    data class DownloadFailed(val task: DownloadTaskEntity, val error: GopanException) : TransferEvent
}

/** 前台通知用的汇总 */
data class TransferSummary(
    val uploads: Int,
    val downloads: Int,
    val doneBytes: Long,
    val totalBytes: Long,
    /** 正在跑的那个任务的名字(只有一个任务时显示) */
    val singleName: String?,
    val waitingNetwork: Boolean = false,
) {
    val active: Int get() = uploads + downloads

    companion object {
        val STARTING = TransferSummary(0, 0, 0, 0, null)
    }
}

/** 一轮(Worker 一次运行)结束时的结果统计 */
data class DrainReport(
    val uploadsDone: Int,
    val uploadsFailed: Int,
    val downloadsDone: List<DownloadTaskEntity>,
    val downloadsFailed: Int,
)

/**
 * 传输引擎:上传 / 下载队列(Room)+ 执行(由 [TransferWorker] 调 [drain],前台服务里跑)。
 * - 并发:同时 [MAX_UPLOADS] 个上传文件(每个文件按网络 2–3 片并发)、[MAX_DOWNLOADS] 个下载;
 * - 状态迁移全部是带前置状态的 UPDATE:运行结果落地时用户刚暂停 / 取消,以用户为准;
 * - 进程被杀:重启后 RUNNING 回到 QUEUED,上传按 uploadSession 对账、下载按 .part 长度续;
 * - 实时进度只在内存([progress],约 3 次/秒);库里只在状态迁移时写。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class TransferEngine @Inject constructor(
    db: GopanDatabase,
    private val session: SessionStore,
    private val uploadApi: UploadApi,
    private val partSender: PartSender,
    private val downloadApi: DownloadApi,
    private val rangeSource: RangeSource,
    private val publisher: DownloadPublisher,
    private val sources: SourceStore,
    private val prefs: UserPreferencesRepository,
    private val network: NetworkMonitor,
    private val scheduler: TransferScheduler,
    private val incomingShare: IncomingShareStore,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val uploads = db.uploadDao()
    private val downloads = db.downloadDao()
    private val clock: TransferClock = SystemTransferClock

    private val userId: Flow<String?> = session.state
        .map { (it as? AuthState.SignedIn)?.user?.id }
        .distinctUntilChanged()

    /** 当前账号的上传任务(不含已取消的) */
    val uploadTasks: Flow<List<UploadTaskEntity>> =
        userId.flatMapLatest { uid -> if (uid == null) flowOf(emptyList()) else uploads.observe(uid) }

    /** 当前账号的下载任务(含文件夹批次的子任务;界面自己按 batchId 归组) */
    val downloadTasks: Flow<List<DownloadTaskEntity>> =
        userId.flatMapLatest { uid -> if (uid == null) flowOf(emptyList()) else downloads.observe(uid) }

    private val _progress = MutableStateFlow<Map<TaskKey, LiveProgress>>(emptyMap())
    val progress: StateFlow<Map<TaskKey, LiveProgress>> = _progress.asStateFlow()
    private val liveProgress = ConcurrentHashMap<TaskKey, LiveProgress>()
    private val meters = ConcurrentHashMap<TaskKey, SpeedMeter>()

    private val _events = MutableSharedFlow<TransferEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<TransferEvent> = _events.asSharedFlow()

    private val running = ConcurrentHashMap<TaskKey, Job>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val drainMutex = Mutex()
    private val lock = Any()
    private var draining = false
    private var pendingKick = false

    // 本轮统计(给结束通知)
    private val doneUploads = java.util.concurrent.atomic.AtomicInteger()
    private val failedUploads = java.util.concurrent.atomic.AtomicInteger()
    private val doneDownloads = java.util.Collections.synchronizedList(mutableListOf<DownloadTaskEntity>())
    private val failedDownloads = java.util.concurrent.atomic.AtomicInteger()

    private val uploadWriter = object : UploadTaskWriter {
        override suspend fun setSource(id: Long, uri: String, owns: Boolean, size: Long, lastModified: Long?, sha256: String) =
            uploads.setSource(id, uri, owns, size, lastModified, sha256)

        override suspend fun setSession(id: Long, sessionId: String?, createdAt: Long?) = uploads.setSession(id, sessionId, createdAt)

        override suspend fun setParent(id: Long, parentId: String?) = uploads.setParent(id, parentId)

        override suspend fun setRestarts(id: Long, n: Int) = uploads.setRestarts(id, n)
    }

    private val downloadWriter = DownloadTaskWriter { id, sha, size, mime -> downloads.setExpected(id, sha, size, mime) }

    private fun currentUserId(): String? = session.currentUser?.id

    // ---- 入队 ----

    /** 上传到 [parentId](null = 根目录);[toChat] 表示目标是传输助手文件夹 */
    suspend fun enqueueUploads(items: List<UploadSource>, parentId: String?, toChat: Boolean = false): List<Long> {
        val uid = currentUserId() ?: return emptyList()
        if (items.isEmpty()) return emptyList()
        val now = clock.now()
        val taken = mutableSetOf<String>()
        val rows = items.map {
            UploadTaskEntity(
                userId = uid,
                parentId = parentId,
                toChat = toChat,
                sourceUri = it.uri.toString(),
                ownsSource = it.owns,
                persistedGrant = it.persisted,
                // 同一批里同名:本地先错开,避免并发定稿撞名
                name = DownloadRun.uniqueName(it.name, taken),
                size = it.size,
                lastModified = it.lastModified,
                mime = it.mime,
                replaceExisting = it.replace,
                createdAt = now,
                updatedAt = now,
            )
        }
        val ids = uploads.insert(rows)
        kick()
        return ids
    }

    suspend fun enqueueFile(node: Node, dest: DownloadDest, treeUri: String? = null): Long? =
        enqueueDownload(DownloadKind.FILE, node.id, null, node.name, node.size, node.mime, dest, treeUri)

    suspend fun enqueueFolder(node: Node, dest: DownloadDest, treeUri: String? = null): Long? =
        enqueueDownload(DownloadKind.FOLDER, node.id, null, node.name, null, null, dest, treeUri)

    /** 打包下载:[nodes] 已规范化;[estimate] 是选中内容的字节估算 */
    suspend fun enqueuePack(nodes: List<Node>, estimate: Long?, dest: DownloadDest, treeUri: String? = null): Long? {
        val name = if (nodes.size == 1) "${nodes[0].name}.zip" else "gopan-打包.zip"
        return enqueueDownload(DownloadKind.PACK, null, nodes.joinToString(",") { it.id }, name, estimate, "application/zip", dest, treeUri)
    }

    private suspend fun enqueueDownload(
        kind: DownloadKind,
        nodeId: String?,
        packIds: String?,
        name: String,
        size: Long?,
        mime: String?,
        dest: DownloadDest,
        treeUri: String?,
    ): Long? {
        val uid = currentUserId() ?: return null
        val now = clock.now()
        val id = downloads.insert(
            DownloadTaskEntity(
                userId = uid,
                kind = kind,
                nodeId = nodeId,
                packNodeIds = packIds,
                name = SourceStore.sanitizeFileName(name),
                size = size,
                mime = mime,
                dest = dest,
                treeUri = treeUri,
                createdAt = now,
                updatedAt = now,
            ),
        )
        kick()
        return id
    }

    // ---- 控制 ----

    suspend fun pause(key: TaskKey) {
        val now = clock.now()
        when (key) {
            is TaskKey.Upload -> uploads.pause(key.id, now)
            is TaskKey.Download -> {
                downloads.pause(key.id, now)
                downloads.children(key.id).forEach { running[TaskKey.Download(it.id)]?.cancel() }
            }
        }
        running[key]?.cancel()
    }

    suspend fun resume(key: TaskKey) {
        val now = clock.now()
        when (key) {
            is TaskKey.Upload -> uploads.resume(key.id, now)
            is TaskKey.Download -> {
                downloads.resume(key.id, now)
                downloads.reviveBatch(key.id, now)
            }
        }
        kick()
    }

    suspend fun retry(key: TaskKey) {
        val now = clock.now()
        when (key) {
            is TaskKey.Upload -> uploads.retry(key.id, now)
            is TaskKey.Download -> {
                downloads.retry(key.id, now)
                downloads.reviveBatch(key.id, now)
            }
        }
        kick()
    }

    /** 取消:立刻从列表消失;上传会话尽力 abort,私有副本与 .part 删掉 */
    suspend fun cancel(key: TaskKey) {
        val now = clock.now()
        when (key) {
            is TaskKey.Upload -> {
                if (uploads.cancel(key.id, now) == 0) return
                running[key]?.cancelAndJoin()
                appScope.launch { finishCanceledUpload(key.id) }
            }
            is TaskKey.Download -> {
                downloads.cancel(key.id, now)
                val children = downloads.children(key.id)
                (children.map { TaskKey.Download(it.id) } + key).forEach { running[it]?.cancelAndJoin() }
                withContext(Dispatchers.IO) {
                    (children.map { it.id } + key.id).forEach { deletePartFiles(it) }
                }
                downloads.deleteWithChildren(key.id)
            }
        }
    }

    /** 从列表移除已结束(完成 / 失败)的任务;失败的上传顺手释放会话与副本 */
    suspend fun remove(key: TaskKey) {
        when (key) {
            is TaskKey.Upload -> {
                val t = uploads.get(key.id) ?: return
                if (t.state != TransferState.DONE && t.state != TransferState.FAILED) return
                uploads.cancel(key.id, clock.now())
                appScope.launch { finishCanceledUpload(key.id) }
            }
            is TaskKey.Download -> {
                val t = downloads.get(key.id) ?: return
                if (t.state != TransferState.DONE && t.state != TransferState.FAILED) return
                withContext(Dispatchers.IO) { (downloads.children(key.id).map { it.id } + key.id).forEach { deletePartFiles(it) } }
                downloads.deleteWithChildren(key.id)
            }
        }
    }

    /** 清掉已完成的任务(上传 + 下载) */
    suspend fun clearFinished() {
        val uid = currentUserId() ?: return
        uploads.withState(uid, TransferState.DONE.name).forEach {
            cleanupSource(it)
            uploads.delete(it.id)
        }
        downloads.deleteChildrenOfDone(uid)
        downloads.deleteDoneTopLevel(uid)
    }

    private suspend fun finishCanceledUpload(id: Long) {
        val t = uploads.get(id) ?: return
        t.sessionId?.let { sid ->
            try {
                uploadApi.abort(sid)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.d("取消上传时 abort 失败,下次运行再试")
                return // 留着 CANCELED 行,下一轮 drain 再 abort
            }
        }
        cleanupSource(t)
        uploads.delete(id)
    }

    private fun cleanupSource(t: UploadTaskEntity) {
        val uri = t.sourceUri.toUri()
        if (t.ownsSource) sources.deleteOwned(uri)
        if (t.persistedGrant) sources.releasePersistableRead(uri)
    }

    private fun deletePartFiles(id: Long) {
        File(sources.partsDir, "$id.part").delete()
        File(sources.partsDir, "$id.zip.part").delete()
    }

    // ---- 调度 ----

    /** 有新的可运行任务:正在跑就唤醒循环,否则排一次 Worker */
    fun kick() {
        synchronized(lock) {
            if (draining) {
                pendingKick = true
                wake.trySend(Unit)
                return
            }
        }
        scheduler.schedule()
    }

    /** App 启动:有没跑完的任务就排 Worker(进程被杀、手机重启之后接着传);顺手清分享接收目录里的孤儿 */
    fun onAppStart() {
        appScope.launch(Dispatchers.IO) {
            delay(ORPHAN_CLEAN_DELAY_MS)
            if (incomingShare.busy) return@launch
            val referenced = uploads.all().mapNotNull { it.sourceUri.toUri().path }.toSet() + incomingShare.pendingFiles()
            sources.cleanIncoming(referenced, olderThanMs = ORPHAN_AGE_MS, now = clock.now())
        }
        appScope.launch {
            val uid = userId.first { it != null } ?: return@launch
            val pending = uploads.queued(uid).isNotEmpty() || uploads.withState(uid, TransferState.RUNNING.name).isNotEmpty() ||
                downloads.queued(uid).isNotEmpty() || downloads.withState(uid, TransferState.RUNNING.name).any { !(it.kind == DownloadKind.FOLDER && it.expanded) } ||
                uploads.withState(uid, TransferState.CANCELED.name).isNotEmpty()
            if (pending) kick()
        }
    }

    /** 设置改了网络约束:按新约束重排 Worker(正在跑的会被替换,任务回到队列) */
    fun reschedule() = scheduler.schedule()

    /** 是否还有没跑完的任务(设置改了要不要重排 Worker) */
    suspend fun hasPending(): Boolean {
        val uid = currentUserId() ?: return false
        return uploads.queued(uid).isNotEmpty() || downloads.queued(uid).isNotEmpty() ||
            uploads.withState(uid, TransferState.RUNNING.name).isNotEmpty()
    }

    /**
     * 跑队列直到没有可运行的任务(Worker 调用)。被取消(Worker 停止:断网、系统回收、登出)时
     * 正在跑的任务随之取消,库里留在 RUNNING,下次 drain 开头复位成 QUEUED。
     */
    suspend fun drain(onTick: suspend (TransferSummary) -> Unit): DrainReport {
        // 等锁而不是 tryLock:REPLACE 换掉的旧 Worker 可能还在收尾,新的要接着跑而不是空手退出
        drainMutex.lock()
        try {
            val uid = currentUserId() ?: return DrainReport(0, 0, emptyList(), 0)
            synchronized(lock) {
                draining = true
                pendingKick = false
            }
            doneUploads.set(0)
            failedUploads.set(0)
            doneDownloads.clear()
            failedDownloads.set(0)
            uploads.resetRunning()
            downloads.resetRunning()
            // 新的一轮多半是网络条件变了(断网后恢复、换了网络):因网络失败在等退避的任务立刻再试
            uploads.clearNetworkBackoff(NETWORK_CODES)
            downloads.clearNetworkBackoff(NETWORK_CODES)
            retryCanceledAborts(uid)
            coroutineScope {
                // 系统网络从无到有:因网络失败在等退避的任务立刻再试
                val netWatcher = launch {
                    var wasOnline = network.online.value
                    network.online.collect { online ->
                        if (online && !wasOnline) {
                            uploads.clearNetworkBackoff(NETWORK_CODES)
                            downloads.clearNetworkBackoff(NETWORK_CODES)
                            wake.trySend(Unit)
                        }
                        wasOnline = online
                    }
                }
                val ticker = launch {
                    var n = 0
                    while (isActive) {
                        publishProgress()
                        if (n++ % 3 == 0) onTick(summary(uid))
                        delay(PROGRESS_INTERVAL_MS)
                    }
                }
                try {
                    while (true) {
                        if (currentUserId() != uid) break
                        synchronized(lock) { pendingKick = false }
                        val nextWake = fillSlots(uid, this)
                        if (running.isEmpty() && nextWake == null) {
                            val exit = synchronized(lock) {
                                if (pendingKick) {
                                    false
                                } else {
                                    draining = false
                                    true
                                }
                            }
                            if (exit) break else continue
                        }
                        val wait = nextWake?.let { (it - clock.now()).coerceIn(50L, MAX_IDLE_WAIT_MS) } ?: MAX_IDLE_WAIT_MS
                        withTimeoutOrNull(wait) { wake.receive() }
                    }
                } finally {
                    ticker.cancel()
                    netWatcher.cancel()
                    synchronized(lock) { draining = false }
                }
            }
            publishProgress()
            return DrainReport(doneUploads.get(), failedUploads.get(), doneDownloads.toList(), failedDownloads.get())
        } finally {
            drainMutex.unlock()
        }
    }

    /** 启动可运行的任务;返回最早的退避到期时刻(没有在等的就是 null) */
    private suspend fun fillSlots(uid: String, scope: CoroutineScope): Long? {
        val now = clock.now()
        var nextWake: Long? = null
        var runningUploads = running.keys.count { it is TaskKey.Upload }
        for (t in uploads.queued(uid)) {
            val key = TaskKey.Upload(t.id)
            if (running.containsKey(key)) continue
            if (t.nextAttemptAt > now) {
                nextWake = minOf(nextWake ?: Long.MAX_VALUE, t.nextAttemptAt)
                continue
            }
            if (runningUploads >= MAX_UPLOADS) continue
            if (launchUpload(scope, t)) runningUploads++
        }
        var runningDownloads = running.keys.count { it is TaskKey.Download }
        for (t in downloads.queued(uid)) {
            val key = TaskKey.Download(t.id)
            if (running.containsKey(key)) continue
            if (t.nextAttemptAt > now) {
                nextWake = minOf(nextWake ?: Long.MAX_VALUE, t.nextAttemptAt)
                continue
            }
            if (runningDownloads >= MAX_DOWNLOADS) continue
            if (launchDownload(scope, t)) runningDownloads++
        }
        return nextWake
    }

    private suspend fun launchUpload(scope: CoroutineScope, t: UploadTaskEntity): Boolean {
        if (uploads.markRunning(t.id, clock.now()) != 1) return false
        val key = TaskKey.Upload(t.id)
        val job = scope.launch(Dispatchers.IO) {
            val run = UploadRun(
                api = uploadApi,
                parts = partSender,
                sources = sources,
                writer = uploadWriter,
                clock = clock,
                concurrency = ::partsConcurrency,
                report = { setProgress(key, it) },
                initial = t,
            )
            applyUpload(t.id, run.execute())
        }
        track(key, job)
        return true
    }

    private suspend fun launchDownload(scope: CoroutineScope, t: DownloadTaskEntity): Boolean {
        if (downloads.markRunning(t.id, clock.now()) != 1) return false
        val key = TaskKey.Download(t.id)
        val job = scope.launch(Dispatchers.IO) {
            val run = DownloadRun(
                api = downloadApi,
                fetcher = rangeSource,
                publisher = publisher,
                writer = downloadWriter,
                clock = clock,
                partsDir = sources.partsDir,
                report = { setProgress(key, it) },
                task = t,
            )
            applyDownload(t, run.execute())
        }
        track(key, job)
        return true
    }

    private fun track(key: TaskKey, job: Job) {
        running[key] = job
        job.invokeOnCompletion {
            running.remove(key)
            liveProgress.remove(key)
            meters.remove(key)
            wake.trySend(Unit)
        }
    }

    private suspend fun applyUpload(id: Long, outcome: UploadOutcome) {
        val now = clock.now()
        when (outcome) {
            is UploadOutcome.Done -> {
                if (uploads.markDone(id, outcome.node.id, outcome.node.name, outcome.instant, now) == 1) {
                    val t = uploads.get(id) ?: return
                    cleanupSource(t)
                    doneUploads.incrementAndGet()
                    _events.emit(TransferEvent.UploadDone(t, outcome.node))
                }
            }
            is UploadOutcome.Retry -> {
                val t = uploads.get(id) ?: return
                val attempts = t.attempts + 1
                val message = outcome.error.message
                if (attempts > MAX_ATTEMPTS && !isNetworkish(outcome.error.code)) {
                    if (uploads.markFailed(id, attempts, outcome.error.code, message, now) == 1) onUploadFailed(id, outcome.error)
                } else {
                    val delayMs = maxOf(outcome.minDelayMs, retryDelay(attempts, outcome.error.code))
                    uploads.markRetry(id, attempts, now + delayMs, outcome.error.code, message, now)
                }
            }
            is UploadOutcome.Failed -> {
                val t = uploads.get(id) ?: return
                if (uploads.markFailed(id, t.attempts, outcome.error.code, outcome.error.message, now) == 1) onUploadFailed(id, outcome.error)
            }
        }
    }

    private suspend fun onUploadFailed(id: Long, error: GopanException) {
        failedUploads.incrementAndGet()
        uploads.get(id)?.let { _events.emit(TransferEvent.UploadFailed(it, error)) }
    }

    private suspend fun applyDownload(t: DownloadTaskEntity, outcome: DownloadOutcome) {
        val now = clock.now()
        when (outcome) {
            is DownloadOutcome.Done -> {
                if (downloads.markDone(t.id, outcome.location, now) == 1) {
                    val done = downloads.get(t.id) ?: return
                    if (done.batchId == null) doneDownloads.add(done)
                    _events.emit(TransferEvent.DownloadDone(done, outcome.location))
                }
            }
            is DownloadOutcome.Expanded -> {
                if (outcome.children.isNotEmpty()) downloads.insertAll(outcome.children)
                downloads.markExpanded(t.id, outcome.totalBytes, now)
                if (outcome.children.isEmpty()) {
                    downloads.setBatchState(t.id, TransferState.DONE.name, null, null, now, now)
                    downloads.get(t.id)?.let { doneDownloads.add(it) }
                }
            }
            is DownloadOutcome.Retry -> {
                val current = downloads.get(t.id) ?: return
                val attempts = current.attempts + 1
                if (attempts > MAX_ATTEMPTS && !isNetworkish(outcome.error.code)) {
                    if (downloads.markFailed(t.id, attempts, outcome.error.code, outcome.error.message, now) == 1) onDownloadFailed(t.id, outcome.error)
                } else {
                    val delayMs = maxOf(outcome.minDelayMs, retryDelay(attempts, outcome.error.code))
                    downloads.markRetry(t.id, attempts, now + delayMs, outcome.error.code, outcome.error.message, now)
                }
            }
            is DownloadOutcome.Failed -> {
                val current = downloads.get(t.id) ?: return
                if (downloads.markFailed(t.id, current.attempts, outcome.error.code, outcome.error.message, now) == 1) {
                    deletePartFiles(t.id)
                    onDownloadFailed(t.id, outcome.error)
                }
            }
        }
        t.batchId?.let { refreshBatch(it) }
    }

    private suspend fun onDownloadFailed(id: Long, error: GopanException) {
        val t = downloads.get(id) ?: return
        if (t.batchId == null) failedDownloads.incrementAndGet()
        _events.emit(TransferEvent.DownloadFailed(t, error))
    }

    /** 子任务状态变了:汇总到批次行 */
    private suspend fun refreshBatch(batchId: Long) {
        val batch = downloads.get(batchId) ?: return
        if (!batch.expanded || batch.state == TransferState.CANCELED) return
        val children = downloads.children(batchId)
        val now = clock.now()
        val states = children.map { it.state }
        val terminal = states.all { it == TransferState.DONE || it == TransferState.FAILED || it == TransferState.CANCELED }
        when {
            terminal && states.none { it == TransferState.FAILED } -> {
                if (batch.state != TransferState.DONE) {
                    downloads.setBatchState(batchId, TransferState.DONE.name, null, null, now, now)
                    downloads.get(batchId)?.let {
                        doneDownloads.add(it)
                        _events.emit(TransferEvent.DownloadDone(it, ""))
                    }
                }
            }
            terminal -> {
                val failed = states.count { it == TransferState.FAILED }
                downloads.setBatchState(batchId, TransferState.FAILED.name, "BATCH_PARTIAL", "$failed 个文件下载失败", now, now)
                if (batch.state != TransferState.FAILED) failedDownloads.incrementAndGet()
            }
            states.any { it == TransferState.QUEUED || it == TransferState.RUNNING } -> {
                if (batch.state != TransferState.RUNNING) downloads.setBatchState(batchId, TransferState.RUNNING.name, null, null, now, null)
            }
            else -> downloads.setBatchState(batchId, TransferState.PAUSED.name, null, null, now, null)
        }
    }

    /** 上一轮取消时没能 abort 的会话(当时没网) */
    private suspend fun retryCanceledAborts(uid: String) {
        uploads.withState(uid, TransferState.CANCELED.name).forEach { finishCanceledUpload(it.id) }
    }

    // ---- 进度 ----

    private fun setProgress(key: TaskKey, p: LiveProgress) {
        val meter = meters.getOrPut(key) { SpeedMeter() }
        liveProgress[key] = p.copy(bytesPerSecond = meter.sample(p.phase, p.doneBytes, clock.elapsed()))
    }

    private fun publishProgress() {
        _progress.value = HashMap(liveProgress)
    }

    private suspend fun summary(uid: String): TransferSummary {
        val ups = uploads.queued(uid) + uploads.withState(uid, TransferState.RUNNING.name)
        val downs = (downloads.queued(uid) + downloads.withState(uid, TransferState.RUNNING.name)).filter { it.kind != DownloadKind.FOLDER || !it.expanded }
        var done = 0L
        var total = 0L
        ups.forEach { t ->
            total += t.size.coerceAtLeast(0) // 不可 seek 的来源在哈希前大小未知(-1)
            done += liveProgress[TaskKey.Upload(t.id)]?.takeIf { it.phase != UploadPhase.HASHING.name }?.doneBytes ?: 0
        }
        downs.forEach { t ->
            total += t.size ?: 0
            done += liveProgress[TaskKey.Download(t.id)]?.doneBytes ?: 0
        }
        val single = (ups.map { it.name } + downs.map { it.name }).singleOrNull()
        return TransferSummary(ups.size, downs.size, done, total, single, waitingNetwork = !network.isOnline())
    }

    private suspend fun partsConcurrency(): Int {
        val t = prefs.preferences.first().transfer
        return if (network.isUnmetered()) t.partsOnWifi else t.partsOnMobile
    }

    // ---- 登出 ----

    /** 登出 / 会话失效:停掉执行、清空队列与私有文件、释放持久化授权 */
    suspend fun wipe() {
        scheduler.cancel()
        running.values.forEach { it.cancel() }
        withContext(Dispatchers.IO) {
            uploads.all().filter { it.persistedGrant }.forEach { sources.releasePersistableRead(it.sourceUri.toUri()) }
            uploads.deleteAll()
            downloads.deleteAll()
            sources.wipe()
        }
        liveProgress.clear()
        _progress.value = emptyMap()
    }

    companion object {
        const val MAX_UPLOADS = 2
        const val MAX_DOWNLOADS = 3
        const val MAX_ATTEMPTS = 5
        private const val PROGRESS_INTERVAL_MS = 330L
        private const val ORPHAN_CLEAN_DELAY_MS = 30_000L
        private const val ORPHAN_AGE_MS = 3_600_000L
        private const val MAX_IDLE_WAIT_MS = 30_000L

        /** 任务级退避:2s、4s、8s、16s、32s,封顶 60s */
        fun backoff(attempt: Int): Long = minOf(60_000L, 2_000L shl (attempt - 1).coerceIn(0, 5))

        /** 网络类错误不计入失败上限(断网是常态,恢复后要能自己续上),重试 5 次以后改成每 5 分钟一次 */
        val NETWORK_CODES = listOf(GopanErrors.NETWORK, GopanErrors.HTTP)

        fun isNetworkish(code: String): Boolean = code in NETWORK_CODES

        fun retryDelay(attempt: Int, code: String): Long =
            if (isNetworkish(code) && attempt > MAX_ATTEMPTS) NETWORK_SLOW_RETRY_MS else backoff(attempt)

        private const val NETWORK_SLOW_RETRY_MS = 5 * 60_000L
    }
}

/** 3 秒滑动窗口测速;阶段变化时重置(哈希阶段不算网速) */
private class SpeedMeter {
    private val samples = ArrayDeque<Pair<Long, Long>>()
    private var phase: String? = null

    @Synchronized
    fun sample(phase: String, bytes: Long, now: Long): Long {
        if (phase != this.phase) {
            samples.clear()
            this.phase = phase
        }
        samples.addLast(now to bytes)
        while (samples.size > 2 && now - samples.first().first > WINDOW_MS) samples.removeFirst()
        val first = samples.first()
        val dt = now - first.first
        if (dt < 400) return 0
        return ((bytes - first.second).coerceAtLeast(0) * 1000 / dt)
    }

    companion object {
        const val WINDOW_MS = 3_000L
    }
}
