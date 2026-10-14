package xyz.yophon.gopan.core.transfer.upload

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.toNode
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.requireData
import xyz.yophon.gopan.graphql.AbortUploadMutation
import xyz.yophon.gopan.graphql.CompleteUploadMutation
import xyz.yophon.gopan.graphql.EnsureChatFolderMutation
import xyz.yophon.gopan.graphql.InitUploadMutation
import xyz.yophon.gopan.graphql.UploadSessionQuery
import xyz.yophon.gopan.graphql.fragment.UploadSessionFields
import javax.inject.Inject
import javax.inject.Singleton

data class PartUrl(val partNumber: Int, val url: String)

/** 服务端上传会话的快照(uploadSession / initUpload 返回) */
data class SessionInfo(
    val id: String,
    val partSize: Long,
    /** 当前缺失分片的预签名 PUT(1 小时有效),按片号升序;可能为空 */
    val partUrls: List<PartUrl>,
    /** 对象存储里已经完整收到的片号 */
    val uploadedParts: Set<Int>,
    val expiresAt: String,
    /** uploading / completing / done / failed / aborted(旧数据还有 verifying) */
    val status: String,
)

sealed interface InitResult {
    /** 秒传:服务端已有这份内容,节点直接建好了 */
    data class Instant(val node: Node) : InitResult

    data class Session(val session: SessionInfo) : InitResult
}

/**
 * 上传协议的 GraphQL 部分(server/internal/service/upload.go、browser_upload.go)。
 * 失败一律抛 [GopanException](code 是服务端的 extensions.code 或本地 NETWORK 等)。
 */
interface UploadApi {
    /** 不幂等:每调一次都建一个新会话(秒传时建一个新节点),只在确认上一次没发出去时重试 */
    suspend fun init(parentId: String?, name: String, sha256: String, size: Long): InitResult

    /** 只读:已传分片 + 缺失分片的新签名;非 uploading 状态返回快照(partUrls 为空) */
    suspend fun session(id: String): SessionInfo

    /** etags 永远传空:服务端自己 ListParts。第一次合并成功返回的也是 UPLOAD_PROCESSING */
    suspend fun complete(sessionId: String): Node

    suspend fun abort(sessionId: String)

    /** 传输助手文件夹(幂等,失效时服务端会重建),返回 id */
    suspend fun ensureChatFolder(): String
}

@Singleton
class ApolloUploadApi @Inject constructor(private val apollo: ApolloClient) : UploadApi {

    override suspend fun init(parentId: String?, name: String, sha256: String, size: Long): InitResult {
        val data = apollo.mutation(
            InitUploadMutation(
                parentId = Optional.presentIfNotNull(parentId),
                name = name,
                sha256 = sha256,
                size = size,
            ),
        ).execute().requireData().initUpload
        val node = data.node?.nodeItem
        val session = data.session?.uploadSessionFields
        return when {
            data.instant && node != null -> InitResult.Instant(node.toNode())
            session != null -> InitResult.Session(session.toInfo())
            else -> throw GopanException("EMPTY_RESPONSE", "服务器返回了空数据")
        }
    }

    override suspend fun session(id: String): SessionInfo =
        apollo.query(UploadSessionQuery(id)).execute().requireData().uploadSession.uploadSessionFields.toInfo()

    override suspend fun complete(sessionId: String): Node =
        apollo.mutation(CompleteUploadMutation(sessionId = sessionId, etags = emptyList()))
            .execute().requireData().completeUpload.nodeItem.toNode()

    override suspend fun abort(sessionId: String) {
        apollo.mutation(AbortUploadMutation(sessionId)).execute().requireData()
    }

    override suspend fun ensureChatFolder(): String =
        apollo.mutation(EnsureChatFolderMutation()).execute().requireData().ensureChatFolder.id
}

private fun UploadSessionFields.toInfo() = SessionInfo(
    id = id,
    partSize = partSize.toLong(),
    partUrls = partUrls.map { PartUrl(it.partNumber, it.url) }.sortedBy { it.partNumber },
    uploadedParts = uploadedParts.toSet(),
    expiresAt = expiresAt,
    status = status,
)
