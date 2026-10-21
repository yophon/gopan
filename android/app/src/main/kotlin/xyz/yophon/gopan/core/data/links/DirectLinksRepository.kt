package xyz.yophon.gopan.core.data.links

import androidx.compose.runtime.Immutable
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import xyz.yophon.gopan.core.AppConfig
import xyz.yophon.gopan.core.auth.SignOutHook
import xyz.yophon.gopan.core.network.requireData
import xyz.yophon.gopan.graphql.CreateLinksMutation
import xyz.yophon.gopan.graphql.DirectLinkQuery
import xyz.yophon.gopan.graphql.LinksEnabledQuery
import xyz.yophon.gopan.graphql.MyLinksQuery
import xyz.yophon.gopan.graphql.PauseLinkMutation
import xyz.yophon.gopan.graphql.RevokeLinkMutation
import xyz.yophon.gopan.graphql.fragment.DirectLinkFields
import javax.inject.Inject
import javax.inject.Singleton
import xyz.yophon.gopan.graphql.type.LinkStatus as GqlLinkStatus

/**
 * PAUSED:手动暂停或超了每天的流量上限(见 pauseReason);EXPIRED:过了有效期;
 * UNAVAILABLE:文件在回收站、属主被禁用,或全站本月流量用完 / 管理员关了直链
 */
enum class LinkStatus { ACTIVE, PAUSED, EXPIRED, UNAVAILABLE }

@Immutable
data class LinkUsage(val hits: Long = 0, val bytes: Long = 0, val blocked: Long = 0)

/** 一条直链(doc/22 四):任意文件的永久公开地址 /x/<token>/<文件名> */
@Immutable
data class DirectLink(
    val id: String,
    val token: String = "",
    /** 完整地址(服务端没配站点地址时已拼上 App 连的服务端地址) */
    val url: String,
    val nodeId: String,
    val name: String,
    val mime: String? = null,
    val size: Long? = null,
    val status: LinkStatus = LinkStatus.ACTIVE,
    /** manual(手动)/ daily_cap(超了当天上限,次日自动恢复)/ anomaly(异常时自动暂停) */
    val pauseReason: String? = null,
    /** web / android / api(图床接口)/ mcp */
    val source: String = "",
    val createdAt: String = "",
    val expiresAt: String? = null,
    /** JPEG 里带拍摄位置 */
    val hasGps: Boolean = false,
    val hitsTotal: Long = 0,
    val bytesTotal: Long = 0,
    val lastHitAt: String? = null,
    val today: LinkUsage = LinkUsage(),
    val last30Days: LinkUsage = LinkUsage(),
)

@Immutable
data class DirectLinkPage(val items: List<DirectLink>, val nextCursor: String?, val total: Int)

/** 直链接口(server/internal/service/link.go)。失败抛 GopanException。 */
interface DirectLinksRepository {
    /** 本站是否开了直链(GOPAN_LINKS);关了时所有入口都隐藏。成功拿到一次就在本次登录期间缓存 */
    suspend fun enabled(): Boolean

    /** 新的在前 */
    suspend fun myLinks(cursor: String?): DirectLinkPage

    suspend fun link(id: String): DirectLink

    /** 每个文件一条;已有有效直链的返回原来那条。超过 [MAX_PER_CALL] 个分批 */
    suspend fun create(nodeIds: List<String>): List<DirectLink>

    suspend fun pause(id: String, paused: Boolean): DirectLink

    suspend fun revoke(id: String)

    companion object {
        const val MAX_PER_CALL = 100
    }
}

@Singleton
class ApolloDirectLinksRepository @Inject constructor(
    private val apollo: ApolloClient,
    private val config: AppConfig,
) : DirectLinksRepository {

    @Volatile private var enabledCache: Boolean? = null

    override suspend fun enabled(): Boolean {
        enabledCache?.let { return it }
        val enabled = apollo.query(LinksEnabledQuery()).execute().requireData().linksEnabled
        enabledCache = enabled
        return enabled
    }

    override suspend fun myLinks(cursor: String?): DirectLinkPage {
        val page = apollo.query(MyLinksQuery(cursor = Optional.present(cursor))).execute().requireData().myLinks
        return DirectLinkPage(page.items.map { it.directLinkFields.toDomain() }, page.nextCursor, page.total)
    }

    override suspend fun link(id: String): DirectLink =
        apollo.query(DirectLinkQuery(id)).execute().requireData().link.directLinkFields.toDomain()

    override suspend fun create(nodeIds: List<String>): List<DirectLink> =
        nodeIds.distinct().chunked(DirectLinksRepository.MAX_PER_CALL).flatMap { batch ->
            apollo.mutation(CreateLinksMutation(batch)).execute().requireData().createLinks.map { it.directLinkFields.toDomain() }
        }

    override suspend fun pause(id: String, paused: Boolean): DirectLink =
        apollo.mutation(PauseLinkMutation(id, paused)).execute().requireData().pauseLink.directLinkFields.toDomain()

    override suspend fun revoke(id: String) {
        apollo.mutation(RevokeLinkMutation(id)).execute().requireData()
    }

    /** 登出:换账号(或换站点)后重新问一次开关 */
    fun clearMemory() {
        enabledCache = null
    }

    private fun DirectLinkFields.toDomain() = DirectLink(
        id = id,
        token = token,
        url = LinkFormats.absoluteUrl(url.ifEmpty { path }, config.baseUrl),
        nodeId = nodeId,
        name = name,
        mime = mime,
        size = size,
        status = when (status) {
            GqlLinkStatus.ACTIVE -> LinkStatus.ACTIVE
            GqlLinkStatus.PAUSED -> LinkStatus.PAUSED
            GqlLinkStatus.EXPIRED -> LinkStatus.EXPIRED
            // UNAVAILABLE 与新版服务端加的状态:都当作不可用(不显示「暂停」)
            else -> LinkStatus.UNAVAILABLE
        },
        pauseReason = pauseReason,
        source = source,
        createdAt = createdAt,
        expiresAt = expiresAt,
        hasGps = hasGps,
        hitsTotal = hitsTotal,
        bytesTotal = bytesTotal,
        lastHitAt = lastHitAt,
        today = LinkUsage(today.hits, today.bytes, today.blocked),
        last30Days = LinkUsage(last30Days.hits, last30Days.bytes, last30Days.blocked),
    )
}

/** 直链自己的 Hilt 模块(不动 DataModule):仓库绑定与登出清理 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DirectLinksModule {
    @Binds
    abstract fun bindDirectLinksRepository(impl: ApolloDirectLinksRepository): DirectLinksRepository

    companion object {
        @Provides
        @IntoSet
        fun provideDirectLinksSignOutHook(repo: ApolloDirectLinksRepository): SignOutHook = SignOutHook { repo.clearMemory() }
    }
}
