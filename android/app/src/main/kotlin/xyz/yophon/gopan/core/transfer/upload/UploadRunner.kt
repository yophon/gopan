package xyz.yophon.gopan.core.transfer.upload

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.toGopanException
import xyz.yophon.gopan.core.transfer.LiveProgress
import xyz.yophon.gopan.core.transfer.SourceReader
import xyz.yophon.gopan.core.transfer.TransferClock
import xyz.yophon.gopan.core.transfer.TransferErrors
import xyz.yophon.gopan.core.transfer.UploadPhase
import xyz.yophon.gopan.core.transfer.db.UploadTaskEntity
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** 一次运行的结果;引擎据此写回任务状态 */
sealed interface UploadOutcome {
    data class Done(val node: Node, val instant: Boolean) : UploadOutcome

    /** 瞬时错误:引擎按 attempts 指数退避,至少等 [minDelayMs] */
    data class Retry(val error: GopanException, val minDelayMs: Long = 0) : UploadOutcome

    /** 确定性错误:进 FAILED(会话已尽力 abort 并清掉) */
    data class Failed(val error: GopanException) : UploadOutcome
}

/** runner 写回任务行:字段级更新,不碰 state(用户这时可能刚点了暂停 / 取消) */
interface UploadTaskWriter {
    suspend fun setSource(id: Long, uri: String, owns: Boolean, size: Long, lastModified: Long?, sha256: String)

    suspend fun setSession(id: Long, sessionId: String?, createdAt: Long?)

    suspend fun setParent(id: Long, parentId: String?)

    suspend fun setRestarts(id: Long, n: Int)
}

/** 分片 PUT(生产实现 [PartUploader];测试用假实现) */
fun interface PartSender {
    suspend fun put(url: String, source: String, offset: Long, length: Long, onProgress: (Long) -> Unit): PartResult
}

/**
 * 单个上传任务的一次执行(协议见 doc/03 与 scratch 里的上传协议规格):
 * 哈希 → initUpload(秒传直接结束)或 uploadSession 对账 → 缺失分片并发 PUT → completeUpload 轮询定稿。
 *
 * - 续传完全靠服务端:有 sessionId 就先 uploadSession,只传它说缺的片;
 * - 预签名 1 小时过期:取到 50 分钟后主动换一批,PUT 403 时被动换(同一片连续两次 403 判定签名配置有问题);
 * - 定稿撞名 / 会话过期 / 传输助手文件夹被删 / 内容在上传途中被改:自动重建会话,最多 [MAX_RESTARTS] 次;
 * - 取消协程(暂停、取消、Worker 被停)会中断进行中的 PUT,会话保留。
 */
class UploadRun(
    private val api: UploadApi,
    private val parts: PartSender,
    private val sources: SourceReader,
    private val writer: UploadTaskWriter,
    private val clock: TransferClock,
    /** 单文件并发分片数(按当前网络取) */
    private val concurrency: suspend () -> Int,
    private val report: (LiveProgress) -> Unit,
    initial: UploadTaskEntity,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private var task = initial

    suspend fun execute(): UploadOutcome {
        val outcome = try {
            prepareSource()
            loop()
        } catch (e: CancellationException) {
            throw e
        } catch (e: UploadFailure) {
            UploadOutcome.Failed(e.error)
        } catch (e: GopanException) {
            classify(e)
        } catch (e: SecurityException) {
            UploadOutcome.Failed(TransferErrors.sourceUnreadable())
        } catch (e: IOException) {
            UploadOutcome.Retry(e.toGopanException())
        }
        if (outcome is UploadOutcome.Failed) dropSession()
        return outcome
    }

    // ---- 来源与哈希 ----

    private suspend fun prepareSource() {
        val uri = task.sourceUri
        val stat = sources.stat(uri) ?: throw UploadFailure(TransferErrors.sourceGone())
        if (!stat.seekable) {
            // 管道类来源:边哈希边拷进私有目录,之后按私有文件分片读
            progress(UploadPhase.HASHING, 0, task.size)
            val copy = sources.newCopyFile(task.name)
            val h = sources.hash(uri, copy) { progress(UploadPhase.HASHING, it, maxOf(task.size, it)) }
            val copyUri = sources.fileSource(copy)
            writer.setSource(task.id, copyUri, owns = true, size = h.size, lastModified = copy.lastModified(), sha256 = h.sha256)
            onHashed(h.sha256)
            task = task.copy(sourceUri = copyUri, ownsSource = true, size = h.size, lastModified = copy.lastModified(), sha256 = h.sha256)
        } else {
            val changed = stat.size != task.size ||
                (stat.lastModified != null && task.lastModified != null && stat.lastModified != task.lastModified)
            if (task.sha256 == null || changed) {
                progress(UploadPhase.HASHING, 0, stat.size)
                val h = sources.hash(uri) { progress(UploadPhase.HASHING, it, maxOf(stat.size, it)) }
                writer.setSource(task.id, task.sourceUri, task.ownsSource, h.size, stat.lastModified, h.sha256)
                onHashed(h.sha256)
                task = task.copy(size = h.size, lastModified = stat.lastModified, sha256 = h.sha256)
            }
        }
        if (task.size <= 0L) throw UploadFailure(TransferErrors.emptyFile())
    }

    /** 新哈希与旧会话申报的不同:旧会话作废 */
    private suspend fun onHashed(sha: String) {
        val old = task.sha256
        if (old != null && old != sha && task.sessionId != null) dropSession()
    }

    // ---- 主循环 ----

    private suspend fun loop(): UploadOutcome {
        while (true) {
            val session = when (val opened = openSession()) {
                is Opened.Instant -> return UploadOutcome.Done(opened.node, instant = true)
                is Opened.Ready -> opened.session
                is Opened.Restart -> {
                    restart(opened.reason)
                    continue
                }
            }
            when (val result = transferAndComplete(session)) {
                is Finished.Completed -> return UploadOutcome.Done(result.node, instant = false)
                is Finished.Restart -> restart(result.reason)
                is Finished.Rehash -> rehashAfterMismatch(result.error)
            }
        }
    }

    private sealed interface Opened {
        data class Instant(val node: Node) : Opened

        data class Ready(val session: SessionInfo) : Opened

        data class Restart(val reason: GopanException) : Opened
    }

    private suspend fun openSession(): Opened {
        progress(UploadPhase.PREPARING, 0, task.size)
        val sid = task.sessionId
        if (sid == null) {
            val result = try {
                api.init(task.parentId, task.name, requireNotNull(task.sha256), task.size, task.replaceExisting)
            } catch (e: GopanException) {
                return when {
                    task.toChat && (e.code == GopanErrors.NOT_A_FOLDER || e.code == GopanErrors.NOT_FOUND) ->
                        Opened.Restart(e.withChatFolderRefresh())
                    // 秒传路径在改名与建节点之间撞名:重来一次服务端会选下一个空名字
                    e.code == GopanErrors.NAME_CONFLICT -> Opened.Restart(e)
                    else -> throw e
                }
            }
            return when (result) {
                is InitResult.Instant -> Opened.Instant(result.node)
                is InitResult.Session -> {
                    val now = clock.now()
                    writer.setSession(task.id, result.session.id, now)
                    task = task.copy(sessionId = result.session.id, sessionCreatedAt = now)
                    Opened.Ready(result.session)
                }
            }
        }
        val s = try {
            api.session(sid)
        } catch (e: GopanException) {
            return when (e.code) {
                GopanErrors.NOT_FOUND, GopanErrors.UPLOAD_EXPIRED -> Opened.Restart(e)
                // 会话超过一天:对象存储可能已清掉分片(MinIO 默认 24h),ListParts 失败表现为 INTERNAL
                GopanErrors.INTERNAL -> if (sessionAgeMs() > STALE_SESSION_MS) Opened.Restart(e) else throw e
                else -> throw e
            }
        }
        // 只记片数,不记 URL(预签名地址是凭据)
        Timber.i("续传:会话 %s 状态 %s,服务端已有 %d 片,缺 %d 片", s.id, s.status, s.uploadedParts.size, s.partUrls.size)
        return when (s.status) {
            STATUS_UPLOADING, STATUS_COMPLETING, STATUS_DONE, STATUS_FAILED -> Opened.Ready(s)
            else -> Opened.Restart(GopanException(GopanErrors.BAD_SESSION_STATE, "上传会话已失效(${s.status})"))
        }
    }

    private sealed interface Finished {
        data class Completed(val node: Node) : Finished

        data class Restart(val reason: GopanException) : Finished

        data class Rehash(val error: GopanException) : Finished
    }

    private suspend fun transferAndComplete(initial: SessionInfo): Finished {
        var session = initial
        var missingRounds = 0
        var goneRounds = 0
        val sid = session.id
        while (true) {
            if (session.status == STATUS_UPLOADING && session.partUrls.isNotEmpty()) {
                try {
                    uploadMissing(session)
                } catch (e: SessionGone) {
                    // 对象存储说 multipart 不存在(404 NoSuchUpload):看服务端会话怎么说
                    val s2 = try {
                        api.session(sid)
                    } catch (ge: GopanException) {
                        if (ge.code in GONE_CODES) return Finished.Restart(ge) else throw ge
                    }
                    if (s2.status != STATUS_UPLOADING || ++goneRounds > 2) {
                        if (s2.status in COMPLETABLE) {
                            session = s2.copy(partUrls = emptyList())
                        } else {
                            return Finished.Restart(GopanException(GopanErrors.UPLOAD_EXPIRED, "上传会话已失效"))
                        }
                    } else {
                        session = s2
                        continue
                    }
                }
            }
            when (val c = completeLoop()) {
                is Completion.Node -> return Finished.Completed(c.node)
                Completion.MissingParts -> {
                    if (++missingRounds > MAX_MISSING_ROUNDS) {
                        throw GopanException(GopanErrors.MISSING_PART, GopanErrors.messageFor(GopanErrors.MISSING_PART, null))
                    }
                    session = api.session(sid)
                }
                is Completion.Restart -> return Finished.Restart(c.reason)
                is Completion.Rehash -> return Finished.Rehash(c.error)
            }
        }
    }

    // ---- 分片 ----

    private class SessionGone : Exception()

    private inner class PartState(val session: SessionInfo) {
        val partSize = session.partSize
        val done: MutableSet<Int> = ConcurrentHashMap.newKeySet<Int>().apply { addAll(session.uploadedParts) }
        val urls = ConcurrentHashMap<Int, String>().apply { session.partUrls.forEach { put(it.partNumber, it.url) } }

        @Volatile
        var urlsFetchedAt = clock.elapsed()

        /** 每换一批签名 +1:等锁期间别人已经换过就不再重复请求 */
        @Volatile
        var urlGeneration = 0
        val inFlight = ConcurrentHashMap<Int, Long>()
        val queue = ArrayDeque(session.partUrls.map { it.partNumber }.filter { it !in done })
        val queueLock = Mutex()
        val refreshLock = Mutex()

        fun partLength(n: Int): Long = minOf(partSize, task.size - (n - 1) * partSize)

        fun bytesDone(): Long = done.sumOf { partLength(it) } + inFlight.entries.filter { it.key !in done }.sumOf { it.value }
    }

    private suspend fun uploadMissing(session: SessionInfo) = coroutineScope {
        val partCount = ((task.size + session.partSize - 1) / session.partSize)
        if (partCount > MAX_PARTS) throw UploadFailure(TransferErrors.fileTooLarge())
        val state = PartState(session)
        val source = task.sourceUri
        val reporter = launch {
            while (isActive) {
                progress(UploadPhase.UPLOADING, state.bytesDone(), task.size)
                delay(REPORT_INTERVAL_MS)
            }
        }
        val workers = concurrency().coerceIn(1, 8)
        try {
            (1..workers).map {
                launch {
                    while (true) {
                        val part = state.queueLock.withLock { state.queue.removeFirstOrNull() } ?: break
                        uploadPart(state, source, part)
                    }
                }
            }.joinAll()
        } finally {
            reporter.cancel()
        }
        progress(UploadPhase.UPLOADING, task.size, task.size)
    }

    private suspend fun uploadPart(state: PartState, source: String, part: Int) {
        var transientFailures = 0
        var signatureFailures = 0
        val offset = (part - 1) * state.partSize
        val length = state.partLength(part)
        while (true) {
            if (part in state.done) return
            if (clock.elapsed() - state.urlsFetchedAt > URL_MAX_AGE_MS) refreshUrls(state, force = false)
            if (part in state.done) return
            val url = state.urls[part] ?: run {
                refreshUrls(state, force = true)
                state.urls[part]
            } ?: if (part in state.done) return else throw SessionGone()
            val result = try {
                parts.put(url, source, offset, length) { state.inFlight[part] = it }
            } catch (e: IOException) {
                null
            } finally {
                state.inFlight.remove(part)
            }
            when {
                result == PartResult.Ok -> {
                    state.done.add(part)
                    return
                }
                result == null || result is PartResult.Http && result.isTransient() -> {
                    if (++transientFailures > PART_RETRIES) {
                        throw GopanException(GopanErrors.NETWORK, "分片上传失败:网络不稳定")
                    }
                    sleep(PART_BACKOFF_MS shl (transientFailures - 1))
                }
                result is PartResult.Http && result.status == 403 -> {
                    // 别的分片已经换过一批:直接用新的,不算失败
                    if (state.urls[part] != url) continue
                    if (++signatureFailures > 2) throw UploadFailure(TransferErrors.signature(result.s3Code))
                    refreshUrls(state, force = true)
                }
                result is PartResult.Http && result.status == 404 -> throw SessionGone()
                result is PartResult.Http && result.status == 400 && result.s3Code == "InvalidArgument" ->
                    throw UploadFailure(TransferErrors.fileTooLarge())
                result is PartResult.Http -> throw UploadFailure(TransferErrors.storageRejected(result.status, result.s3Code))
            }
        }
    }

    /** 单飞换签名:合并"已传分片"与"缺失分片的新 URL";[force] 为 false 时只在 URL 确实老了才换 */
    private suspend fun refreshUrls(state: PartState, force: Boolean) {
        val before = state.urlGeneration
        state.refreshLock.withLock {
            if (state.urlGeneration != before) return // 等锁期间别人已经换过
            if (!force && clock.elapsed() - state.urlsFetchedAt <= URL_MAX_AGE_MS) return
            val s = try {
                api.session(state.session.id)
            } catch (e: GopanException) {
                if (e.code in GONE_CODES) throw SessionGone() else throw e
            }
            if (s.status != STATUS_UPLOADING) throw SessionGone()
            state.done.addAll(s.uploadedParts)
            s.partUrls.forEach { if (it.partNumber !in state.done) state.urls[it.partNumber] = it.url }
            state.urlsFetchedAt = clock.elapsed()
            state.urlGeneration++
        }
    }

    // ---- 定稿 ----

    private sealed interface Completion {
        data class Node(val node: xyz.yophon.gopan.core.data.Node) : Completion

        data object MissingParts : Completion

        data class Restart(val reason: GopanException) : Completion

        data class Rehash(val error: GopanException) : Completion
    }

    private suspend fun completeLoop(): Completion {
        progress(UploadPhase.VERIFYING, task.size, task.size)
        val sid = requireNotNull(task.sessionId)
        val started = clock.elapsed()
        var polls = 0
        var completeFailures = 0
        while (true) {
            try {
                return Completion.Node(api.complete(sid))
            } catch (e: GopanException) {
                when (e.code) {
                    GopanErrors.UPLOAD_PROCESSING -> sleep(pollDelay(polls++, clock.elapsed() - started))
                    GopanErrors.MISSING_PART -> return Completion.MissingParts
                    GopanErrors.COMPLETE_FAILED -> {
                        if (++completeFailures >= 3) return Completion.Restart(e)
                        sleep(2_000L * completeFailures)
                    }
                    // 定稿时目标位置被占(init 不预留名字):重建会话,服务端会选下一个空名字
                    GopanErrors.NAME_CONFLICT,
                    GopanErrors.UPLOAD_EXPIRED,
                    GopanErrors.BAD_SESSION_STATE,
                    -> return Completion.Restart(e)
                    GopanErrors.HASH_MISMATCH, GopanErrors.SIZE_MISMATCH -> return Completion.Rehash(e)
                    GopanErrors.NOT_A_FOLDER, GopanErrors.NOT_FOUND -> return diagnoseGone(sid, e)
                    else -> throw e
                }
            }
        }
    }

    /** complete 返回 NOT_FOUND / NOT_A_FOLDER:是会话没了、目标文件夹没了,还是文件传完后被删了 */
    private suspend fun diagnoseGone(sid: String, e: GopanException): Completion {
        val s = try {
            api.session(sid)
        } catch (ge: GopanException) {
            if (ge.code in GONE_CODES) return Completion.Restart(ge) else throw ge
        }
        return when (s.status) {
            STATUS_DONE -> throw UploadFailure(TransferErrors.deletedAfterUpload())
            STATUS_FAILED -> {
                if (task.toChat) Completion.Restart(e.withChatFolderRefresh())
                else throw UploadFailure(GopanException(e.code, "目标文件夹已不存在或已移到回收站"))
            }
            else -> Completion.Restart(e)
        }
    }

    private suspend fun rehashAfterMismatch(error: GopanException) {
        val uri = task.sourceUri
        progress(UploadPhase.HASHING, 0, task.size)
        val h = sources.hash(uri) { progress(UploadPhase.HASHING, it, maxOf(task.size, it)) }
        if (h.sha256 == task.sha256 && h.size == task.size) {
            // 本地内容没变,服务端却算出不同:读文件出错或切片有问题,不要无限重传
            throw UploadFailure(error)
        }
        writer.setSource(task.id, task.sourceUri, task.ownsSource, h.size, sources.stat(uri)?.lastModified, h.sha256)
        task = task.copy(size = h.size, sha256 = h.sha256)
        restart(error)
    }

    // ---- 会话重建与收尾 ----

    private suspend fun restart(reason: GopanException) {
        val n = task.autoRestarts + 1
        if (n > MAX_RESTARTS) throw UploadFailure(reason)
        dropSession()
        writer.setRestarts(task.id, n)
        task = task.copy(autoRestarts = n)
        if (reason.refreshChatFolder && task.toChat) {
            val folder = api.ensureChatFolder()
            writer.setParent(task.id, folder)
            task = task.copy(parentId = folder)
        }
        Timber.d("上传任务 %d 重建会话(%s,第 %d 次)", task.id, reason.code, n)
    }

    /** 尽力 abort 并清掉本地的会话 id */
    private suspend fun dropSession() {
        val sid = task.sessionId ?: return
        try {
            api.abort(sid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("abortUpload 失败(忽略):%s", e.javaClass.simpleName)
        }
        writer.setSession(task.id, null, null)
        task = task.copy(sessionId = null, sessionCreatedAt = null)
    }

    private fun classify(e: GopanException): UploadOutcome = when (e.code) {
        in DETERMINISTIC -> UploadOutcome.Failed(e)
        GopanErrors.TOO_MANY_SESSIONS -> UploadOutcome.Retry(e, minDelayMs = 30_000)
        else -> UploadOutcome.Retry(e)
    }

    private fun sessionAgeMs(): Long = task.sessionCreatedAt?.let { clock.now() - it } ?: 0

    private fun progress(phase: UploadPhase, done: Long, total: Long) {
        report(LiveProgress(phase.name, done, total))
    }

    companion object {
        const val MAX_RESTARTS = 3
        const val MAX_PARTS = 10_000
        private const val MAX_MISSING_ROUNDS = 3
        private const val PART_RETRIES = 3
        private const val PART_BACKOFF_MS = 1_000L
        private const val REPORT_INTERVAL_MS = 250L

        /** 预签名 1 小时,留 10 分钟余量 */
        const val URL_MAX_AGE_MS = 50 * 60_000L

        /** MinIO 默认 24h 清理未完成的 multipart */
        const val STALE_SESSION_MS = 24 * 3_600_000L

        const val STATUS_UPLOADING = "uploading"
        const val STATUS_COMPLETING = "completing"
        const val STATUS_DONE = "done"
        const val STATUS_FAILED = "failed"
        private val COMPLETABLE = setOf(STATUS_COMPLETING, STATUS_DONE, STATUS_FAILED)
        private val GONE_CODES = setOf(GopanErrors.NOT_FOUND, GopanErrors.UPLOAD_EXPIRED, GopanErrors.INTERNAL)

        /** 不自动重试的错误码 */
        val DETERMINISTIC = setOf(
            GopanErrors.QUOTA_EXCEEDED,
            GopanErrors.INVALID_INPUT,
            GopanErrors.FORBIDDEN,
            GopanErrors.NOT_FOUND,
            GopanErrors.NOT_A_FOLDER,
            GopanErrors.NAME_CONFLICT,
            GopanErrors.HASH_MISMATCH,
            GopanErrors.SIZE_MISMATCH,
            GopanErrors.BAD_SESSION_STATE,
            GopanErrors.UPLOAD_EXPIRED,
            GopanErrors.QUERY_TOO_DEEP,
            "GRAPHQL_PARSE_FAILED",
            "GRAPHQL_VALIDATION_FAILED",
        )

        /** UPLOAD_PROCESSING 的轮询间隔:前 10 次 1 秒,之后 ×1.5 封顶 5 秒;10 分钟后 30 秒 */
        fun pollDelay(n: Int, elapsedMs: Long): Long = when {
            elapsedMs > 10 * 60_000L -> 30_000L
            n < 10 -> 1_000L
            else -> minOf(5_000.0, 1_000.0 * Math.pow(1.5, (n - 9).toDouble())).toLong()
        }
    }
}

/** 本地判定的确定性失败(控制流用,不出 runner) */
private class UploadFailure(val error: GopanException) : Exception(error.message)

private fun PartResult.Http.isTransient(): Boolean = status == 408 || status == 429 || status >= 500

/** 传输助手文件夹失效:重建会话前先重新 ensure(只对 toChat 任务生效) */
private val GopanException.refreshChatFolder: Boolean
    get() = code == GopanErrors.NOT_A_FOLDER

private fun GopanException.withChatFolderRefresh(): GopanException =
    GopanException(GopanErrors.NOT_A_FOLDER, message ?: "传输助手文件夹已失效", serverMessage, this)
