package xyz.yophon.gopan.core.transfer.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.toGopanException
import xyz.yophon.gopan.core.transfer.DownloadDest
import xyz.yophon.gopan.core.transfer.DownloadKind
import xyz.yophon.gopan.core.transfer.DownloadPhase
import xyz.yophon.gopan.core.transfer.LiveProgress
import xyz.yophon.gopan.core.transfer.SourceStore
import xyz.yophon.gopan.core.transfer.TransferClock
import xyz.yophon.gopan.core.transfer.TransferErrors
import xyz.yophon.gopan.core.transfer.db.DownloadTaskEntity
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

sealed interface DownloadOutcome {
    /** [location]:content:// URI 或文件路径(OPEN_WITH) */
    data class Done(val location: String) : DownloadOutcome

    /** 文件夹展开完毕:子任务交给引擎入库 */
    data class Expanded(val children: List<DownloadTaskEntity>, val totalBytes: Long) : DownloadOutcome

    data class Retry(val error: GopanException, val minDelayMs: Long = 0) : DownloadOutcome

    data class Failed(val error: GopanException) : DownloadOutcome
}

/** runner 写回任务行(字段级,不碰 state) */
fun interface DownloadTaskWriter {
    suspend fun setExpected(id: Long, sha256: String?, size: Long, mime: String?)
}

/**
 * 单个下载任务的一次执行。
 * FILE:node(id) 现取预签名 URL → GET(Range 续传,起点 = .part 实际长度)→ 校验 sha256 → 发布;
 *       URL 取到 10 分钟后在下一次建连前重取,403 被动重取一次;内容被覆盖写(sha/size 变了)则 .part 作废。
 * FOLDER:按广度遍历展开成子任务(客户端展开,能续传、没有 2 GiB 上限、字节不经过 Go)。
 * PACK:GET /pack 流式 zip,不能续传;下完用 ZipFile 读中央目录校验(服务端中途出错时 HTTP 层也是正常结束)。
 */
class DownloadRun(
    private val api: DownloadApi,
    private val fetcher: RangeSource,
    private val publisher: DownloadPublisher,
    private val writer: DownloadTaskWriter,
    private val clock: TransferClock,
    private val partsDir: File,
    private val report: (LiveProgress) -> Unit,
    private val task: DownloadTaskEntity,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    /** 可用空间检查(测试里替换) */
    private val usableSpace: (File) -> Long = { it.usableSpace },
) {
    suspend fun execute(): DownloadOutcome = try {
        when (task.kind) {
            DownloadKind.FILE -> runFile()
            DownloadKind.FOLDER -> expandFolder()
            DownloadKind.PACK -> runPack()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: DownloadFailure) {
        DownloadOutcome.Failed(e.error)
    } catch (e: GopanException) {
        if (e.code in DETERMINISTIC) DownloadOutcome.Failed(e) else DownloadOutcome.Retry(e)
    } catch (e: IOException) {
        if (AndroidDownloadPublisher.isNoSpace(e)) DownloadOutcome.Failed(TransferErrors.noSpace())
        else DownloadOutcome.Retry(e.toGopanException())
    }

    // ---- 单文件 ----

    private fun partFile(): File = File(partsDir, "${task.id}.part")

    private suspend fun resolve(): FileTarget {
        report(DownloadPhase.RESOLVING, partFile().length(), task.size ?: 0)
        return when (val r = api.resolve(requireNotNull(task.nodeId))) {
            is Resolve.Ok -> r.target
            Resolve.Gone -> throw DownloadFailure(TransferErrors.gone())
            Resolve.NotAFile -> throw DownloadFailure(TransferErrors.notAFile())
        }
    }

    private suspend fun runFile(): DownloadOutcome {
        val part = partFile()
        partsDir.mkdirs()
        var target = resolve()
        var expectedSha = task.sha256
        if (expectedSha == null || expectedSha != target.sha256 || task.size != target.size) {
            if (expectedSha != null) {
                Timber.d("下载任务 %d:内容已被覆盖写,从头下载", task.id)
                part.delete()
            }
            writer.setExpected(task.id, target.sha256, target.size, target.mime)
            expectedSha = target.sha256
        }
        val size = target.size
        val cacheKey = target.sha256
        if (task.dest == DownloadDest.OPEN_WITH && cacheKey != null) {
            publisher.cachedOpenFile(cacheKey, task.name, size)?.let {
                part.delete()
                return DownloadOutcome.Done(it.absolutePath)
            }
        }
        val needSpace = (size - part.length()).coerceAtLeast(0) + (if (task.dest == DownloadDest.OPEN_WITH) 0 else size) + SPACE_MARGIN
        if (usableSpace(partsDir) < needSpace) throw DownloadFailure(TransferErrors.noSpace())

        var checksumFailures = 0
        while (true) {
            target = fetchInto(part, target, size)
            report(DownloadPhase.VERIFYING, size, size)
            val sha = withContext(Dispatchers.IO) { SourceStore.hashStream(FileInputStream(part), null) {}.sha256 }
            if (expectedSha == null || sha == expectedSha) break
            part.delete()
            if (++checksumFailures >= 2) throw DownloadFailure(TransferErrors.checksum())
            Timber.w("下载任务 %d 校验失败,重下一次", task.id)
        }
        report(DownloadPhase.SAVING, size, size)
        val location = publisher.publish(part, task.name, target.mime ?: task.mime, task.relDir, task.dest, task.treeUri, cacheKey)
        return DownloadOutcome.Done(location)
    }

    /** 把 [part] 补到 [size] 字节;返回最后一次用的目标(可能重取过) */
    private suspend fun fetchInto(part: File, initial: FileTarget, size: Long): FileTarget {
        var target = initial
        var forbiddenRetried = false
        var transientFailures = 0
        while (true) {
            coroutineContext.ensureActive()
            val from = part.length()
            if (from > size) {
                part.delete()
                continue
            }
            if (from == size) return target
            if (clock.elapsed() - target.fetchedAtElapsed > URL_MAX_AGE_MS) target = refreshTarget(part)
            report(DownloadPhase.DOWNLOADING, from, size)
            when (val f = fetcher.open(target.url, from)) {
                is Fetch.Body -> {
                    if (f.start != from) {
                        // 服务端忽略了 Range(200,从 0 开始):清掉 .part 从头写;起点莫名其妙:丢掉这次响应重来
                        part.delete()
                        if (f.start != 0L) {
                            f.body.close()
                            continue
                        }
                    }
                    val before = part.length()
                    try {
                        writeBody(f, part, size)
                    } catch (e: IOException) {
                        if (AndroidDownloadPublisher.isNoSpace(e)) throw DownloadFailure(TransferErrors.noSpace())
                    }
                    val progressed = part.length() > before
                    if (progressed) transientFailures = 0
                    if (part.length() < size && !progressed) {
                        if (++transientFailures > MAX_TRANSIENT) {
                            throw GopanException(GopanErrors.NETWORK, GopanErrors.messageFor(GopanErrors.NETWORK, null))
                        }
                        sleep(backoff(transientFailures))
                    }
                }
                Fetch.Forbidden -> {
                    if (forbiddenRetried) throw DownloadFailure(TransferErrors.signature("403"))
                    forbiddenRetried = true
                    target = refreshTarget(part)
                }
                Fetch.NotFound -> {
                    resolve() // Gone 时抛 GONE
                    throw DownloadFailure(TransferErrors.storageMissing())
                }
                Fetch.RangeNotSatisfiable -> {
                    if (part.length() != size) part.delete() else return target
                }
                is Fetch.Transient -> {
                    if (++transientFailures > MAX_TRANSIENT) {
                        throw GopanException(GopanErrors.NETWORK, GopanErrors.messageFor(GopanErrors.NETWORK, null))
                    }
                    sleep(backoff(transientFailures))
                }
            }
        }
    }

    /** 重取 URL;内容被覆盖写了就清 .part */
    private suspend fun refreshTarget(part: File): FileTarget {
        val fresh = resolve()
        if (fresh.sha256 != task.sha256 && task.sha256 != null) {
            part.delete()
            writer.setExpected(task.id, fresh.sha256, fresh.size, fresh.mime)
        }
        return fresh
    }

    /** 追加写响应体,最多写到 [size] 字节(多出来的不要:校验会兜住内容不符);中途断开抛 IOException,已写的保留 */
    private suspend fun writeBody(f: Fetch.Body, part: File, size: Long) = withContext(Dispatchers.IO) {
        f.body.use { body ->
            FileOutputStream(part, true).use { out ->
                val input = body.byteStream()
                val buffer = ByteArray(BUFFER)
                var have = part.length()
                var lastReport = have
                while (have < size) {
                    coroutineContext.ensureActive()
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), size - have).toInt())
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    have += n
                    if (have - lastReport >= REPORT_BYTES) {
                        lastReport = have
                        report(DownloadPhase.DOWNLOADING, have, size)
                    }
                }
                out.flush()
            }
        }
    }

    // ---- 文件夹 ----

    private suspend fun expandFolder(): DownloadOutcome {
        report(DownloadPhase.EXPANDING, 0, 0)
        val rootRel = joinRel(task.relDir, SourceStore.sanitizeFileName(task.name))
        val queue = ArrayDeque(listOf(requireNotNull(task.nodeId) to rootRel))
        val children = mutableListOf<DownloadTaskEntity>()
        val usedNames = mutableMapOf<String, MutableSet<String>>()
        var total = 0L
        val now = clock.now()
        while (queue.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (folderId, rel) = queue.removeFirst()
            var cursor: String? = null
            do {
                val page = try {
                    api.listChildren(folderId, cursor)
                } catch (e: GopanException) {
                    if (e.code == GopanErrors.NOT_FOUND && folderId == task.nodeId) throw DownloadFailure(TransferErrors.gone())
                    if (e.code == GopanErrors.NOT_FOUND) break // 子文件夹在遍历途中被删:跳过
                    throw e
                }
                for (item in page.items) {
                    val taken = usedNames.getOrPut(rel) { mutableSetOf() }
                    val clean = uniqueName(SourceStore.sanitizeFileName(item.name), taken)
                    if (item.isFolder) {
                        queue.addLast(item.id to joinRel(rel, clean))
                    } else {
                        total += item.size ?: 0
                        children += DownloadTaskEntity(
                            userId = task.userId,
                            kind = DownloadKind.FILE,
                            batchId = task.id,
                            nodeId = item.id,
                            name = clean,
                            relDir = rel,
                            size = item.size,
                            dest = task.dest,
                            treeUri = task.treeUri,
                            createdAt = now,
                            updatedAt = now,
                        )
                    }
                }
                report(DownloadPhase.EXPANDING, children.size.toLong(), 0)
                cursor = page.nextCursor
            } while (cursor != null)
        }
        return DownloadOutcome.Expanded(children, total)
    }

    // ---- 打包 ----

    private suspend fun runPack(): DownloadOutcome {
        val ids = task.packNodeIds.orEmpty().split(',').filter { it.isNotBlank() }
        val estimate = task.size ?: 0L
        val part = File(partsDir, "${task.id}.zip.part")
        partsDir.mkdirs()
        part.delete()
        if (usableSpace(partsDir) < estimate * 2 + SPACE_MARGIN) throw DownloadFailure(TransferErrors.noSpace())
        report(DownloadPhase.RESOLVING, 0, estimate)
        when (val p = api.openPack(ids)) {
            is PackOpen.Stream -> {
                try {
                    withContext(Dispatchers.IO) {
                        p.body.use { body ->
                            FileOutputStream(part).use { out ->
                                val input = body.byteStream()
                                val buffer = ByteArray(BUFFER)
                                var total = 0L
                                var lastReport = 0L
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    out.write(buffer, 0, n)
                                    total += n
                                    if (total - lastReport >= REPORT_BYTES) {
                                        lastReport = total
                                        report(DownloadPhase.DOWNLOADING, minOf(total, estimate), maxOf(estimate, total))
                                    }
                                }
                            }
                        }
                    }
                } catch (e: IOException) {
                    part.delete()
                    throw e
                }
                report(DownloadPhase.VERIFYING, estimate, estimate)
                val ok = withContext(Dispatchers.IO) {
                    try {
                        ZipFile(part).use { it.size() >= 0 }
                    } catch (e: ZipException) {
                        false
                    } catch (e: IOException) {
                        false
                    }
                }
                if (!ok) {
                    part.delete()
                    return DownloadOutcome.Retry(TransferErrors.packCorrupt())
                }
                report(DownloadPhase.SAVING, estimate, estimate)
                val location = publisher.publish(part, task.name, "application/zip", task.relDir, task.dest, task.treeUri, null)
                return DownloadOutcome.Done(location)
            }
            is PackOpen.RetryLater -> return DownloadOutcome.Retry(
                GopanException(TransferErrors.PACK_BUSY, "打包请求过于频繁或服务器繁忙,稍后自动重试"),
                minDelayMs = p.afterSeconds * 1000,
            )
            is PackOpen.Rejected -> return DownloadOutcome.Failed(
                when (p.status) {
                    413 -> TransferErrors.packTooLarge()
                    404 -> TransferErrors.gone()
                    else -> TransferErrors.packRejected(p.status, p.message)
                },
            )
            is PackOpen.Transient -> return DownloadOutcome.Retry(p.error)
        }
    }

    private fun report(phase: DownloadPhase, done: Long, total: Long) {
        report(LiveProgress(phase.name, done, total))
    }

    companion object {
        /** 预签名 GET 15 分钟,留 5 分钟余量 */
        const val URL_MAX_AGE_MS = 10 * 60_000L
        private const val MAX_TRANSIENT = 5
        private const val BUFFER = 256 * 1024
        private const val REPORT_BYTES = 512 * 1024L
        private const val SPACE_MARGIN = 32L * 1024 * 1024

        private val DETERMINISTIC = setOf(
            GopanErrors.NOT_FOUND,
            GopanErrors.FORBIDDEN,
            GopanErrors.INVALID_INPUT,
            GopanErrors.QUERY_TOO_DEEP,
            TransferErrors.SAVE_FAILED,
            TransferErrors.NEED_STORAGE_PERMISSION,
            TransferErrors.NO_SPACE,
        )

        fun backoff(n: Int): Long = minOf(60_000L, 2_000L shl (n - 1).coerceAtMost(5))

        fun joinRel(a: String, b: String): String = when {
            a.isEmpty() -> b
            b.isEmpty() -> a
            else -> "$a/$b"
        }

        /** 同一目录里清洗后撞名:追加 " (n)" */
        fun uniqueName(name: String, taken: MutableSet<String>): String {
            var candidate = name
            if (!taken.add(candidate.lowercase())) {
                val dot = name.lastIndexOf('.')
                val stem = if (dot > 0) name.substring(0, dot) else name
                val ext = if (dot > 0) name.substring(dot) else ""
                var n = 1
                do {
                    candidate = "$stem ($n)$ext"
                    n++
                } while (!taken.add(candidate.lowercase()))
            }
            return candidate
        }
    }
}

private class DownloadFailure(val error: GopanException) : Exception(error.message)
