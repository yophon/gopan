package xyz.yophon.gopan.core.data.shares

import androidx.compose.runtime.Immutable
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import kotlinx.serialization.Serializable
import xyz.yophon.gopan.core.network.requireData
import xyz.yophon.gopan.core.ui.format.parseTime
import xyz.yophon.gopan.graphql.CreateShareMutation
import xyz.yophon.gopan.graphql.MySharesQuery
import xyz.yophon.gopan.graphql.RevokeShareMutation
import xyz.yophon.gopan.graphql.ShareVisitsQuery
import xyz.yophon.gopan.graphql.type.NodeKind
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** 一个分享链接。访问计数是近 180 天的(服务端定期清理更早的记录) */
@Immutable
@Serializable
data class Share(
    val id: String,
    val token: String,
    val nodeId: String,
    val nodeName: String,
    val isFolder: Boolean,
    val hasPassword: Boolean,
    /** null = 永久有效 */
    val expiresAt: String?,
    val createdAt: String,
    val verifyCount: Long = 0,
    val downloadCount: Long = 0,
    val lastVisitAt: String? = null,
) {
    fun isExpired(now: Instant = Instant.now()): Boolean = parseTime(expiresAt)?.isBefore(now) == true
}

/** 一次访问:verify(访客打开)/ download(单文件下载)/ pack(打包下载) */
@Immutable
data class ShareVisit(val kind: String, val ip: String?, val userAgent: String?, val createdAt: String)

/** 分享(server/internal/service/share.go)。失败抛 GopanException。 */
interface SharesRepository {
    suspend fun myShares(): List<Share>

    /** [expiresAt] 为 null 表示永久;[password] 为空表示不设密码 */
    suspend fun create(nodeId: String, password: String?, expiresAt: Instant?): Share

    suspend fun revoke(id: String)

    suspend fun visits(shareId: String, limit: Int = VISITS_LIMIT): List<ShareVisit>

    companion object {
        const val VISITS_LIMIT = 50
    }
}

@Singleton
class ApolloSharesRepository @Inject constructor(private val apollo: ApolloClient) : SharesRepository {

    override suspend fun myShares(): List<Share> =
        apollo.query(MySharesQuery()).execute().requireData().myShares.map { s ->
                Share(
                    id = s.id,
                    token = s.token,
                    nodeId = s.node.id,
                    nodeName = s.node.name,
                    isFolder = s.node.kind == NodeKind.FOLDER,
                    hasPassword = s.hasPassword,
                    expiresAt = s.expiresAt,
                    createdAt = s.createdAt,
                    verifyCount = s.visitStats.verifyCount,
                    downloadCount = s.visitStats.downloadCount,
                    lastVisitAt = s.visitStats.lastVisitAt,
                )
            }

    override suspend fun create(nodeId: String, password: String?, expiresAt: Instant?): Share {
        val s = apollo.mutation(
            CreateShareMutation(
                nodeId = nodeId,
                password = Optional.presentIfNotNull(password?.takeIf { it.isNotEmpty() }),
                expiresAt = Optional.presentIfNotNull(expiresAt?.toString()),
            ),
        ).execute().requireData().createShare
        return Share(
            id = s.id,
            token = s.token,
            nodeId = s.node.id,
            nodeName = s.node.name,
            isFolder = s.node.kind == NodeKind.FOLDER,
            hasPassword = s.hasPassword,
            expiresAt = s.expiresAt,
            createdAt = s.createdAt,
        )
    }

    override suspend fun revoke(id: String) {
        apollo.mutation(RevokeShareMutation(id)).execute().requireData()
    }

    override suspend fun visits(shareId: String, limit: Int): List<ShareVisit> =
        apollo.query(ShareVisitsQuery(shareId, Optional.present(limit))).execute().requireData().shareVisits.map {
            ShareVisit(kind = it.kind, ip = it.ip, userAgent = it.userAgent, createdAt = it.createdAt)
        }
}
