package xyz.yophon.gopan.core.transfer

import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.NodeKind
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.transfer.db.UploadTaskEntity
import xyz.yophon.gopan.core.transfer.upload.InitResult
import xyz.yophon.gopan.core.transfer.upload.PartResult
import xyz.yophon.gopan.core.transfer.upload.PartSender
import xyz.yophon.gopan.core.transfer.upload.PartUrl
import xyz.yophon.gopan.core.transfer.upload.SessionInfo
import xyz.yophon.gopan.core.transfer.upload.UploadApi
import xyz.yophon.gopan.core.transfer.upload.UploadTaskWriter
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.random.Random

class FakeClock(var nowMs: Long = 1_700_000_000_000L, var elapsedMs: Long = 0) : TransferClock {
    override fun now(): Long = nowMs

    override fun elapsed(): Long = elapsedMs

    fun advance(ms: Long) {
        nowMs += ms
        elapsedMs += ms
    }
}

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

fun randomBytes(n: Int, seed: Int = 1): ByteArray = Random(seed).nextBytes(n)

/** 内存来源:mem://名字 → 字节;file:// 走真实文件(不可 seek 的来源会被拷成私有文件) */
class FakeSources : SourceReader {
    val data = mutableMapOf<String, ByteArray>()
    var seekable = true
    var lastModified: Long? = 1_000L
    var hashCalls = 0

    override fun stat(source: String): SourceStat? {
        if (source.startsWith("file://")) {
            val f = File(source.removePrefix("file://"))
            return if (f.isFile) SourceStat(f.length(), f.lastModified(), true) else null
        }
        val bytes = data[source] ?: return null
        return SourceStat(bytes.size.toLong(), lastModified, seekable)
    }

    override fun openAt(source: String, offset: Long): InputStream {
        val bytes = bytesOf(source)
        return ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
    }

    override suspend fun hash(source: String, copyTo: File?, onProgress: (Long) -> Unit): HashResult {
        hashCalls++
        return SourceStore.hashStream(ByteArrayInputStream(bytesOf(source)), copyTo, onProgress)
    }

    override fun newCopyFile(name: String): File = File.createTempFile("gopan-copy", ".bin").apply { deleteOnExit() }

    override fun fileSource(file: File): String = "file://" + file.absolutePath

    private fun bytesOf(source: String): ByteArray =
        if (source.startsWith("file://")) File(source.removePrefix("file://")).readBytes() else data.getValue(source)
}

/** 服务端会话 */
class FakeSession(
    val id: String,
    val parentId: String?,
    val name: String,
    val sha: String,
    val size: Long,
    val partSize: Long,
) {
    var status = "uploading"
    val parts = sortedMapOf<Int, ByteArray>()
    var pollsLeft = 0
    var failCode: String? = null
    var node: Node? = null
    val partCount: Int get() = ((size + partSize - 1) / partSize).toInt()
}

/**
 * 内存版上传服务端,语义照 server/internal/service/upload.go、browser_upload.go:
 * 秒传按已验证 blob;complete 第一次合并返回 UPLOAD_PROCESSING,轮询 [processingPolls] 次后定稿;
 * 定稿时重算 sha256,不符记 failed。注入点:[initError]、[sessionError]、[finalizeError] 等。
 */
class FakeUploadServer(private val partSize: Long = 1_000) : UploadApi {
    val blobs = mutableMapOf<String, Long>()
    val sessions = linkedMapOf<String, FakeSession>()
    var processingPolls = 2

    /** 下一次 init 抛出的错误(用一次清掉) */
    var initError: GopanException? = null

    /** 下一次 session() 抛出的错误 */
    var sessionError: GopanException? = null

    /** 定稿时(轮询结束那一刻)记 failed 的错误码,用一次清掉 */
    var finalizeError: String? = null

    /** URL 世代:小于它的签名视为过期 */
    var minValidSig = 0
    private var sigCounter = 0
    private var idCounter = 0
    var chatFolderId = "chat-1"
    var ensureCalls = 0
    val aborted = mutableListOf<String>()
    val initCalls = mutableListOf<String?>()
    var completeCalls = 0

    override suspend fun init(parentId: String?, name: String, sha256: String, size: Long): InitResult {
        initCalls += parentId
        initError?.let {
            initError = null
            throw it
        }
        if (blobs[sha256] != null) {
            return InitResult.Instant(Node(id = "node-${++idCounter}", parentId = parentId, name = name, kind = NodeKind.FILE))
        }
        val s = FakeSession("sess-${++idCounter}", parentId, name, sha256, size, partSize)
        sessions[s.id] = s
        return InitResult.Session(info(s))
    }

    override suspend fun session(id: String): SessionInfo {
        sessionError?.let {
            sessionError = null
            throw it
        }
        val s = sessions[id] ?: throw GopanException(GopanErrors.NOT_FOUND, "对象不存在")
        if (s.status != "uploading") return SessionInfo(s.id, s.partSize, emptyList(), emptySet(), "2030-01-01T00:00:00Z", s.status)
        return info(s)
    }

    private fun info(s: FakeSession): SessionInfo {
        val sig = ++sigCounter
        val missing = (1..s.partCount).filter { it !in s.parts }
        return SessionInfo(
            id = s.id,
            partSize = s.partSize,
            partUrls = missing.map { PartUrl(it, "https://s3.test/${s.id}/$it?sig=$sig") },
            uploadedParts = s.parts.keys.toSet(),
            expiresAt = "2030-01-01T00:00:00Z",
            status = s.status,
        )
    }

    override suspend fun complete(sessionId: String): Node {
        completeCalls++
        val s = sessions[sessionId] ?: throw GopanException(GopanErrors.NOT_FOUND, "对象不存在")
        when (s.status) {
            "done" -> return requireNotNull(s.node)
            "failed" -> throw GopanException(requireNotNull(s.failCode), "定稿失败")
            "aborted" -> throw GopanException(GopanErrors.BAD_SESSION_STATE, "会话状态异常")
            "uploading" -> {
                val missing = (1..s.partCount).firstOrNull { it !in s.parts }
                if (missing != null) throw GopanException(GopanErrors.MISSING_PART, "第 $missing 片未上传")
                s.status = "completing"
                s.pollsLeft = processingPolls
                throw GopanException(GopanErrors.UPLOAD_PROCESSING, "处理中")
            }
            else -> {
                if (s.pollsLeft-- > 0) throw GopanException(GopanErrors.UPLOAD_PROCESSING, "处理中")
                finalizeError?.let { code ->
                    finalizeError = null
                    s.status = "failed"
                    s.failCode = code
                    throw GopanException(code, "定稿失败")
                }
                val all = s.parts.values.fold(ByteArray(0)) { acc, b -> acc + b }
                if (all.size.toLong() != s.size) {
                    s.status = "failed"
                    s.failCode = GopanErrors.SIZE_MISMATCH
                    throw GopanException(GopanErrors.SIZE_MISMATCH, "大小不符")
                }
                if (sha256Hex(all) != s.sha) {
                    s.status = "failed"
                    s.failCode = GopanErrors.HASH_MISMATCH
                    throw GopanException(GopanErrors.HASH_MISMATCH, "哈希不符")
                }
                s.status = "done"
                blobs[s.sha] = s.size
                val node = Node(id = "node-${++idCounter}", parentId = s.parentId, name = s.name, kind = NodeKind.FILE, size = s.size)
                s.node = node
                return node
            }
        }
    }

    override suspend fun abort(sessionId: String) {
        aborted += sessionId
        sessions[sessionId]?.let { if (it.status == "uploading" || it.status == "completing") it.status = "aborted" }
    }

    override suspend fun ensureChatFolder(): String {
        ensureCalls++
        return chatFolderId
    }

    fun assembled(sessionId: String): ByteArray = sessions.getValue(sessionId).parts.values.fold(ByteArray(0)) { acc, b -> acc + b }
}

/** 内存对象存储:按 URL 里的会话与片号存字节;可注入每片的失败序列 */
class FakeStorage(private val server: FakeUploadServer, private val sources: SourceReader) : PartSender {
    val puts = mutableListOf<String>()
    val failures = mutableMapOf<Int, ArrayDeque<Any>>()

    fun failPart(part: Int, vararg results: Any) {
        failures.getOrPut(part) { ArrayDeque() }.addAll(results)
    }

    override suspend fun put(url: String, source: String, offset: Long, length: Long, onProgress: (Long) -> Unit): PartResult {
        val path = url.removePrefix("https://s3.test/").substringBefore('?')
        val sid = path.substringBefore('/')
        val part = path.substringAfter('/').toInt()
        val sig = url.substringAfter("sig=").toInt()
        failures[part]?.removeFirstOrNull()?.let {
            when (it) {
                is IOException -> throw it
                is PartResult -> return it
            }
        }
        val s = server.sessions[sid] ?: return PartResult.Http(404, "NoSuchUpload", null)
        if (s.status != "uploading") return PartResult.Http(404, "NoSuchUpload", null)
        if (sig < server.minValidSig) return PartResult.Http(403, "AccessDenied", "Request has expired")
        val bytes = sources.openAt(source, offset).use { it.readNBytes(length.toInt()) }
        onProgress(length)
        s.parts[part] = bytes
        puts += "$sid#$part"
        return PartResult.Ok
    }
}

/** 记录 runner 的字段级写回,模拟库里那一行 */
class FakeUploadWriter(var row: UploadTaskEntity) : UploadTaskWriter {
    override suspend fun setSource(id: Long, uri: String, owns: Boolean, size: Long, lastModified: Long?, sha256: String) {
        row = row.copy(sourceUri = uri, ownsSource = owns, size = size, lastModified = lastModified, sha256 = sha256)
    }

    override suspend fun setSession(id: Long, sessionId: String?, createdAt: Long?) {
        row = row.copy(sessionId = sessionId, sessionCreatedAt = createdAt)
    }

    override suspend fun setParent(id: Long, parentId: String?) {
        row = row.copy(parentId = parentId)
    }

    override suspend fun setRestarts(id: Long, n: Int) {
        row = row.copy(autoRestarts = n)
    }
}

fun uploadTask(
    source: String = "mem://a",
    size: Long,
    name: String = "a.bin",
    parentId: String? = "folder-1",
    toChat: Boolean = false,
    sessionId: String? = null,
    sha256: String? = null,
) = UploadTaskEntity(
    id = 1,
    userId = "u1",
    parentId = parentId,
    toChat = toChat,
    sourceUri = source,
    name = name,
    size = size,
    lastModified = 1_000L,
    sha256 = sha256,
    sessionId = sessionId,
    createdAt = 0,
    updatedAt = 0,
)
