package xyz.yophon.gopan.core.data.chat

import androidx.compose.runtime.Immutable
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import kotlinx.serialization.Serializable
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.toNode
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.requireData
import xyz.yophon.gopan.core.ui.format.parseTime
import xyz.yophon.gopan.graphql.ChatFolderInfoQuery
import xyz.yophon.gopan.graphql.ChatMessagesQuery
import xyz.yophon.gopan.graphql.DeleteChatMessageMutation
import xyz.yophon.gopan.graphql.EnsureChatFolderMutation
import xyz.yophon.gopan.graphql.SendChatMessageMutation
import xyz.yophon.gopan.graphql.fragment.ChatMessageFields
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 传输助手的一条消息。单用户单会话,不分收发两侧;[source] 只用来显示来源标签。
 * 排序、去重、游标一律用 [id](uuid v7,字典序即时间序);[createdAt] 只用于显示时间与按天分组。
 */
@Immutable
@Serializable
data class ChatMessage(
    val id: String,
    val body: String,
    val createdAt: String,
    /** createdAt 的 epoch 毫秒(解析失败为 0) */
    val createdAtMillis: Long,
    val source: String,
    /** 附件;附件进回收站或被彻删时为 null */
    val node: Node?,
) {
    /** 文件消息的附件已不可用:node 为空、正文也为空(服务端从不写入空正文的文本消息) */
    val isGoneFile: Boolean get() = node == null && body.isEmpty()
    val isText: Boolean get() = node == null && body.isNotEmpty()
}

/** 传输助手的服务端接口(server/internal/service/chat.go)。失败抛 [GopanException]。 */
interface ChatRepository {
    /** 最新 [limit] 条,升序 */
    suspend fun latest(limit: Int = LATEST_LIMIT): List<ChatMessage>

    /** 比 [id] 更旧的 [limit] 条(离锚点最近的),升序 */
    suspend fun before(id: String, limit: Int): List<ChatMessage>

    /** 比 [id] 更新的 [limit] 条,升序 */
    suspend fun after(id: String, limit: Int): List<ChatMessage>

    /** 正文或附件名包含关键词,取最新 [limit] 条命中,升序 */
    suspend fun search(query: String, limit: Int = LATEST_LIMIT): List<ChatMessage>

    suspend fun sendText(body: String): ChatMessage

    /** 把已有节点发进会话;节点已在会话里时服务端返回原来那条 */
    suspend fun sendNode(nodeId: String): ChatMessage

    /** 只删消息不删文件;别端已经删掉(NOT_FOUND)也算成功 */
    suspend fun delete(id: String)

    /** 传输助手文件夹(幂等,失效时服务端重建) */
    suspend fun ensureFolder(): String

    /** 现有的传输助手文件夹(不触发创建;从没建过为 null) */
    suspend fun folderId(): String?

    companion object {
        const val LATEST_LIMIT = 200
        const val HISTORY_PAGE = 100
        const val ANCHOR_PAGE = 50

        /** 服务端按 UTF-8 字节限制正文 */
        const val MAX_TEXT_BYTES = 4000
    }
}

@Singleton
class ApolloChatRepository @Inject constructor(private val apollo: ApolloClient) : ChatRepository {

    override suspend fun latest(limit: Int): List<ChatMessage> = list(limit = limit)

    override suspend fun before(id: String, limit: Int): List<ChatMessage> = list(limit = limit, before = id)

    override suspend fun after(id: String, limit: Int): List<ChatMessage> = list(limit = limit, after = id)

    override suspend fun search(query: String, limit: Int): List<ChatMessage> = list(limit = limit, query = query)

    private suspend fun list(limit: Int, before: String? = null, after: String? = null, query: String? = null): List<ChatMessage> =
        apollo.query(
            ChatMessagesQuery(
                limit = Optional.present(limit),
                before = Optional.presentIfNotNull(before),
                after = Optional.presentIfNotNull(after),
                query = Optional.presentIfNotNull(query),
            ),
        ).execute().requireData().chatMessages.map { it.chatMessageFields.toDomain() }

    override suspend fun sendText(body: String): ChatMessage =
        apollo.mutation(SendChatMessageMutation(body = Optional.present(body), nodeId = Optional.absent()))
            .execute().requireData().sendChatMessage.chatMessageFields.toDomain()

    override suspend fun sendNode(nodeId: String): ChatMessage =
        apollo.mutation(SendChatMessageMutation(body = Optional.absent(), nodeId = Optional.present(nodeId)))
            .execute().requireData().sendChatMessage.chatMessageFields.toDomain()

    override suspend fun delete(id: String) {
        try {
            apollo.mutation(DeleteChatMessageMutation(id)).execute().requireData()
        } catch (e: GopanException) {
            if (e.code != GopanErrors.NOT_FOUND) throw e
        }
    }

    override suspend fun ensureFolder(): String =
        apollo.mutation(EnsureChatFolderMutation()).execute().requireData().ensureChatFolder.id

    override suspend fun folderId(): String? =
        apollo.query(ChatFolderInfoQuery()).execute().requireData().chatFolder?.id
}

fun ChatMessageFields.toDomain() = ChatMessage(
    id = id,
    body = body,
    createdAt = createdAt,
    createdAtMillis = parseTime(createdAt)?.toEpochMilli() ?: 0L,
    source = source,
    node = node?.nodeItem?.toNode(),
)
