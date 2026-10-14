package xyz.yophon.gopan.core.transfer

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.transfer.db.UploadTaskEntity
import xyz.yophon.gopan.core.transfer.upload.PartResult
import xyz.yophon.gopan.core.transfer.upload.PartSender
import xyz.yophon.gopan.core.transfer.upload.UploadOutcome
import xyz.yophon.gopan.core.transfer.upload.UploadRun
import java.io.IOException

class UploadRunTest {
    private val sources = FakeSources()
    private val server = FakeUploadServer(partSize = 1_000)
    private val storage = FakeStorage(server, sources)
    private val clock = FakeClock()
    private val progress = mutableListOf<LiveProgress>()

    private fun run(task: UploadTaskEntity, writer: FakeUploadWriter = FakeUploadWriter(task), parts: PartSender = storage) =
        UploadRun(
            api = server,
            parts = parts,
            sources = sources,
            writer = writer,
            clock = clock,
            concurrency = { 3 },
            report = { progress += it },
            initial = writer.row,
            sleep = { clock.advance(it) },
        )

    @Test
    fun `instant upload when the server already has the content`() = runTest {
        val bytes = randomBytes(2_500)
        sources.data["mem://a"] = bytes
        server.blobs[sha256Hex(bytes)] = bytes.size.toLong()
        val writer = FakeUploadWriter(uploadTask(size = bytes.size.toLong()))

        val outcome = run(writer.row, writer).execute()

        assertThat(outcome).isInstanceOf(UploadOutcome.Done::class.java)
        assertThat((outcome as UploadOutcome.Done).instant).isTrue()
        assertThat(server.sessions).isEmpty()
        assertThat(storage.puts).isEmpty()
        assertThat(writer.row.sha256).isEqualTo(sha256Hex(bytes))
        assertThat(progress.map { it.phase }).contains(UploadPhase.HASHING.name)
    }

    @Test
    fun `multipart upload sends every part, polls processing and returns the node`() = runTest {
        val bytes = randomBytes(2_500)
        sources.data["mem://a"] = bytes
        val writer = FakeUploadWriter(uploadTask(size = bytes.size.toLong()))

        val outcome = run(writer.row, writer).execute()

        assertThat(outcome).isInstanceOf(UploadOutcome.Done::class.java)
        val sid = server.sessions.keys.single()
        assertThat(server.assembled(sid)).isEqualTo(bytes)
        assertThat(storage.puts.map { it.substringAfter('#').toInt() }.sorted()).containsExactly(1, 2, 3)
        // 第一次 complete 合并 → UPLOAD_PROCESSING,再轮询 2 次,第 4 次拿到节点
        assertThat(server.completeCalls).isEqualTo(4)
        assertThat(writer.row.sessionId).isEqualTo(sid)
        assertThat(progress.map { it.phase }).containsAtLeast(UploadPhase.UPLOADING.name, UploadPhase.VERIFYING.name)
    }

    @Test
    fun `resume only uploads the parts the server is missing`() = runTest {
        val bytes = randomBytes(2_500)
        sources.data["mem://a"] = bytes
        // 上一次传完前两片后第三片一直断网:任务进入重试,会话保留
        storage.failPart(3, IOException("1"), IOException("2"), IOException("3"), IOException("4"))
        val first = FakeUploadWriter(uploadTask(size = bytes.size.toLong()))
        assertThat(run(first.row, first).execute()).isInstanceOf(UploadOutcome.Retry::class.java)
        val sid = first.row.sessionId!!
        assertThat(server.sessions.getValue(sid).parts.keys).containsExactly(1, 2)
        storage.puts.clear()

        val outcome = run(first.row, first).execute()

        assertThat(outcome).isInstanceOf(UploadOutcome.Done::class.java)
        assertThat(storage.puts).containsExactly("$sid#3")
        assertThat(server.assembled(sid)).isEqualTo(bytes)
        assertThat(sources.hashCalls).isEqualTo(1) // 哈希缓存在任务里,续传不重算
    }

    @Test
    fun `expired part url is refreshed through uploadSession`() = runTest {
        val bytes = randomBytes(1_500)
        sources.data["mem://a"] = bytes
        val writer = FakeUploadWriter(uploadTask(size = bytes.size.toLong()))
        // 拿到第一批 URL 之后全部过期
        val expiring = PartSender { url, source, offset, length, p ->
            if (server.minValidSig == 0) server.minValidSig = 2
            storage.put(url, source, offset, length, p)
        }

        val outcome = run(writer.row, writer, parts = expiring).execute()

        assertThat(outcome).isInstanceOf(UploadOutcome.Done::class.java)
        assertThat(server.assembled(writer.row.sessionId!!)).isEqualTo(bytes)
    }

    @Test
    fun `persistent 403 after refresh fails with a signature error`() = runTest {
        val bytes = randomBytes(800)
        sources.data["mem://a"] = bytes
        storage.failPart(1, PartResult.Http(403, "SignatureDoesNotMatch", null), PartResult.Http(403, "SignatureDoesNotMatch", null), PartResult.Http(403, "SignatureDoesNotMatch", null))
        val writer = FakeUploadWriter(uploadTask(size = bytes.size.toLong()))

        val outcome = run(writer.row, writer).execute()

        assertThat(outcome).isInstanceOf(UploadOutcome.Failed::class.java)
        assertThat((outcome as UploadOutcome.Failed).error.code).isEqualTo(TransferErrors.STORAGE_SIGNATURE)
        assertThat(server.aborted).hasSize(1) // 确定性失败:会话已 abort
        assertThat(writer.row.sessionId).isNull()
    }

    @Test
    fun `transient part failures are retried with backoff`() = runTest {
        val bytes = randomBytes(1_500)
        sources.data["mem://a"] = bytes
        storage.failPart(2, IOException("reset"), PartResult.Http(503, null, null))
        val writer = FakeUploadWriter(uploadTask(size = bytes.size.toLong()))

        val outcome = run(writer.row, writer).execute()

        assertThat(outcome).isInstanceOf(UploadOutcome.Done::class.java)
        assertThat(server.assembled(writer.row.sessionId!!)).isEqualTo(bytes)
    }

    @Test
    fun `too many transient part failures become a retryable network error`() = runTest {
        val bytes = randomBytes(800)
        sources.data["mem://a"] = bytes
        storage.failPart(1, IOException("1"), IOException("2"), IOException("3"), IOException("4"))
        val writer = FakeUploadWriter(uploadTask(size = bytes.size.toLong()))

        val outcome = run(writer.row, writer).execute()

        assertThat(outcome).isInstanceOf(UploadOutcome.Retry::class.java)
        assertThat((outcome as UploadOutcome.Retry).error.code).isEqualTo(GopanErrors.NETWORK)
        assertThat(writer.row.sessionId).isNotNull() // 瞬时错误保留会话,下次续传
        assertThat(server.aborted).isEmpty()
    }

    @Test
    fun `name conflict at finalization rebuilds the session`() = runTest {
        val bytes = randomBytes(1_200)
        sources.data["mem://a"] = bytes
        server.finalizeError = GopanErrors.NAME_CONFLICT
        val writer = FakeUploadWriter(uploadTask(size = bytes.size.toLong()))

        val outcome = run(writer.row, writer).execute()

        assertThat(outcome).isInstanceOf(UploadOutcome.Done::class.java)
        assertThat(server.sessions).hasSize(2)
        assertThat(writer.row.autoRestarts).isEqualTo(1)
        assertThat(server.aborted).containsExactly(server.sessions.keys.first())
    }

    @Test
    fun `expired session on resume starts over and may hit instant upload`() = runTest {
        val bytes = randomBytes(1_200)
        sources.data["mem://a"] = bytes
        server.blobs[sha256Hex(bytes)] = bytes.size.toLong()
        server.sessionError = GopanException(GopanErrors.UPLOAD_EXPIRED, "过期")
        val writer = FakeUploadWriter(uploadTask(size = bytes.size.toLong(), sessionId = "old-session", sha256 = sha256Hex(bytes)))

        val outcome = run(writer.row, writer).execute()

        assertThat((outcome as UploadOutcome.Done).instant).isTrue()
        assertThat(server.aborted).contains("old-session")
    }

    @Test
    fun `quota exceeded is a deterministic failure`() = runTest {
        val bytes = randomBytes(100)
        sources.data["mem://a"] = bytes
        server.initError = GopanException(GopanErrors.QUOTA_EXCEEDED, "空间不足")
        val writer = FakeUploadWriter(uploadTask(size = bytes.size.toLong()))

        val outcome = run(writer.row, writer).execute()

        assertThat((outcome as UploadOutcome.Failed).error.code).isEqualTo(GopanErrors.QUOTA_EXCEEDED)
    }

    @Test
    fun `network error at init is retryable`() = runTest {
        val bytes = randomBytes(100)
        sources.data["mem://a"] = bytes
        server.initError = GopanException(GopanErrors.NETWORK, "网络")
        val writer = FakeUploadWriter(uploadTask(size = bytes.size.toLong()))

        val outcome = run(writer.row, writer).execute()

        assertThat(outcome).isInstanceOf(UploadOutcome.Retry::class.java)
    }

    @Test
    fun `too many sessions waits at least thirty seconds`() = runTest {
        val bytes = randomBytes(100)
        sources.data["mem://a"] = bytes
        server.initError = GopanException(GopanErrors.TOO_MANY_SESSIONS, "过多")
        val writer = FakeUploadWriter(uploadTask(size = bytes.size.toLong()))

        val outcome = run(writer.row, writer).execute() as UploadOutcome.Retry

        assertThat(outcome.minDelayMs).isAtLeast(30_000)
    }

    @Test
    fun `hash mismatch with unchanged local content fails instead of looping`() = runTest {
        val bytes = randomBytes(1_200)
        sources.data["mem://a"] = bytes
        server.finalizeError = GopanErrors.HASH_MISMATCH
        val writer = FakeUploadWriter(uploadTask(size = bytes.size.toLong()))

        val outcome = run(writer.row, writer).execute()

        assertThat((outcome as UploadOutcome.Failed).error.code).isEqualTo(GopanErrors.HASH_MISMATCH)
    }

    @Test
    fun `content changed during upload is re-hashed and re-uploaded`() = runTest {
        val original = randomBytes(1_200, seed = 1)
        val changed = randomBytes(1_200, seed = 2)
        sources.data["mem://a"] = original
        // 上传完分片后文件被改:服务端定稿时算出的哈希与申报的不同(只发生一次)
        var swapped = false
        val swapping = PartSender { url, source, offset, length, p ->
            val r = storage.put(url, source, offset, length, p)
            if (url.contains("/2?") && !swapped) {
                swapped = true
                sources.data["mem://a"] = changed
                server.finalizeError = GopanErrors.HASH_MISMATCH
            }
            r
        }
        val writer = FakeUploadWriter(uploadTask(size = original.size.toLong()))

        val outcome = run(writer.row, writer, parts = swapping).execute()

        assertThat(outcome).isInstanceOf(UploadOutcome.Done::class.java)
        assertThat(writer.row.sha256).isEqualTo(sha256Hex(changed))
        assertThat(server.assembled(writer.row.sessionId!!)).isEqualTo(changed)
    }

    @Test
    fun `chat folder deleted during upload is recreated and the file re-sent there`() = runTest {
        val bytes = randomBytes(1_200)
        sources.data["mem://a"] = bytes
        server.finalizeError = GopanErrors.NOT_A_FOLDER
        server.chatFolderId = "chat-2"
        val writer = FakeUploadWriter(uploadTask(size = bytes.size.toLong(), parentId = "chat-1", toChat = true))

        val outcome = run(writer.row, writer).execute()

        assertThat(outcome).isInstanceOf(UploadOutcome.Done::class.java)
        assertThat(server.ensureCalls).isEqualTo(1)
        assertThat(writer.row.parentId).isEqualTo("chat-2")
        assertThat(server.initCalls).containsExactly("chat-1", "chat-2").inOrder()
    }

    @Test
    fun `missing target folder for a normal upload fails`() = runTest {
        val bytes = randomBytes(1_200)
        sources.data["mem://a"] = bytes
        server.finalizeError = GopanErrors.NOT_FOUND
        val writer = FakeUploadWriter(uploadTask(size = bytes.size.toLong()))

        val outcome = run(writer.row, writer).execute()

        assertThat((outcome as UploadOutcome.Failed).error.message).contains("目标文件夹")
    }

    @Test
    fun `empty file is rejected locally`() = runTest {
        sources.data["mem://a"] = ByteArray(0)
        val writer = FakeUploadWriter(uploadTask(size = 0))

        val outcome = run(writer.row, writer).execute()

        assertThat((outcome as UploadOutcome.Failed).error.code).isEqualTo(TransferErrors.EMPTY_FILE)
        assertThat(server.initCalls).isEmpty()
    }

    @Test
    fun `missing source fails with source gone`() = runTest {
        val writer = FakeUploadWriter(uploadTask(size = 10))

        val outcome = run(writer.row, writer).execute()

        assertThat((outcome as UploadOutcome.Failed).error.code).isEqualTo(TransferErrors.SOURCE_GONE)
    }

    @Test
    fun `non seekable source is copied while hashing`() = runTest {
        val bytes = randomBytes(2_100)
        sources.data["mem://pipe"] = bytes
        sources.seekable = false
        val writer = FakeUploadWriter(uploadTask(source = "mem://pipe", size = -1))

        val outcome = run(writer.row, writer).execute()

        assertThat(outcome).isInstanceOf(UploadOutcome.Done::class.java)
        assertThat(writer.row.ownsSource).isTrue()
        assertThat(writer.row.sourceUri).startsWith("file://")
        assertThat(writer.row.size).isEqualTo(bytes.size.toLong())
        assertThat(server.assembled(writer.row.sessionId!!)).isEqualTo(bytes)
    }

    @Test
    fun `cancellation keeps the session for a later resume`() = runTest {
        val bytes = randomBytes(2_500)
        sources.data["mem://a"] = bytes
        val gate = CompletableDeferred<Unit>()
        val blocking = PartSender { url, source, offset, length, p ->
            if (url.contains("/2?")) gate.await()
            storage.put(url, source, offset, length, p)
        }
        val writer = FakeUploadWriter(uploadTask(size = bytes.size.toLong()))
        val job = async { run(writer.row, writer, parts = blocking).execute() }
        // 进度上报是个周期循环,不能 advanceUntilIdle
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertThat(server.sessions.values.single().parts.keys).doesNotContain(2)
        job.cancel()
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()

        assertThat(job.isCancelled).isTrue()
        assertThat(writer.row.sessionId).isNotNull()
        assertThat(server.aborted).isEmpty()
    }

    @Test
    fun `poll delay backs off and caps`() {
        assertThat(UploadRun.pollDelay(0, 0)).isEqualTo(1_000)
        assertThat(UploadRun.pollDelay(9, 0)).isEqualTo(1_000)
        assertThat(UploadRun.pollDelay(10, 0)).isEqualTo(1_500)
        assertThat(UploadRun.pollDelay(40, 0)).isEqualTo(5_000)
        assertThat(UploadRun.pollDelay(3, 11 * 60_000L)).isEqualTo(30_000)
    }
}
