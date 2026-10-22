package xyz.yophon.gopan.core.data.feeds

import androidx.compose.runtime.Immutable
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import xyz.yophon.gopan.core.network.requireData
import xyz.yophon.gopan.graphql.CreateFeedMutation
import xyz.yophon.gopan.graphql.DeleteFeedMutation
import xyz.yophon.gopan.graphql.FeedItemsQuery
import xyz.yophon.gopan.graphql.FeedQuery
import xyz.yophon.gopan.graphql.FeedsQuery
import xyz.yophon.gopan.graphql.FetchFeedItemMutation
import xyz.yophon.gopan.graphql.PreviewFeedQuery
import xyz.yophon.gopan.graphql.RefreshFeedMutation
import xyz.yophon.gopan.graphql.UpdateFeedMutation
import xyz.yophon.gopan.graphql.fragment.FeedFields
import xyz.yophon.gopan.graphql.fragment.FeedItemFields
import xyz.yophon.gopan.graphql.type.FeedInput
import javax.inject.Inject
import javax.inject.Singleton
import xyz.yophon.gopan.graphql.type.FeedItemState as GqlItemState
import xyz.yophon.gopan.graphql.type.FeedKind as GqlFeedKind
import xyz.yophon.gopan.graphql.type.FeedMode as GqlFeedMode
import xyz.yophon.gopan.graphql.type.NodeKind as GqlNodeKind

enum class FeedKind { RSS, GITHUB }

/** DOWNLOAD 下载附件(播客音视频、GitHub 资源);CLIP 剪藏正文;NONE 只记录 */
enum class FeedMode { DOWNLOAD, CLIP, NONE }

/** NEW 记下了没处理;SKIPPED 被过滤或是订阅前的旧条目;QUEUED 等着或正在处理;DONE 已完成;FAILED 失败 */
enum class FeedItemState { NEW, SKIPPED, QUEUED, DONE, FAILED }

/** 一个订阅(doc/23 四) */
@Immutable
data class Feed(
    val id: String,
    val kind: FeedKind,
    /** RSS 地址;GitHub 为 owner/repo */
    val url: String,
    val title: String,
    val siteUrl: String = "",
    /** 存到哪;被彻底删除后为 null(订阅已暂停) */
    val folderId: String? = null,
    val folderName: String? = null,
    val mode: FeedMode = FeedMode.NONE,
    val includeWords: String = "",
    val excludeWords: String = "",
    val assetPattern: String = "",
    val prerelease: Boolean = false,
    /** 只留最近 N 条;0 = 不删 */
    val keepLast: Int = 0,
    val intervalMinutes: Int = 360,
    val enabled: Boolean = true,
    val lastCheckedAt: String? = null,
    val lastOkAt: String? = null,
    val lastError: String? = null,
    val failCount: Int = 0,
    val nextCheckAt: String = "",
    val createdAt: String = "",
    val itemCount: Int = 0,
    val newCount: Int = 0,
    /** 最新一条的发布时间 */
    val latestAt: String? = null,
)

/** 条目下好 / 剪好的文件 */
@Immutable
data class FeedItemNode(val id: String, val name: String, val isFolder: Boolean, val parentId: String?)

@Immutable
data class FeedItem(
    val id: String,
    val title: String,
    /** 原文链接 */
    val url: String = "",
    val enclosureUrl: String = "",
    val enclosureType: String = "",
    val enclosureSize: Long? = null,
    val publishedAt: String? = null,
    val state: FeedItemState = FeedItemState.NEW,
    val error: String? = null,
    /** 进了回收站或被「只留最近 N 条」清掉后为 null */
    val node: FeedItemNode? = null,
    val jobId: String? = null,
    val createdAt: String = "",
)

@Immutable
data class FeedItemPage(val items: List<FeedItem>, val nextCursor: String?)

@Immutable
data class FeedPreviewItem(
    val title: String,
    val url: String = "",
    val enclosureUrl: String = "",
    val enclosureSize: Long? = null,
    val publishedAt: String? = null,
)

/** 贴地址后先看一眼(抓一次,不保存) */
@Immutable
data class FeedPreview(
    val kind: FeedKind,
    val url: String,
    val title: String,
    val siteUrl: String = "",
    /** 条目大多带音视频附件 */
    val podcast: Boolean = false,
    val suggestedMode: FeedMode = FeedMode.NONE,
    /** 最近 10 条 */
    val items: List<FeedPreviewItem> = emptyList(),
)

/** 订阅接口(server/internal/service/feeds.go)。失败抛 GopanException(服务端消息是中文,直接给人看)。 */
interface FeedsRepository {
    suspend fun feeds(): List<Feed>

    suspend fun feed(id: String): Feed

    /** 新的在前 */
    suspend fun items(feedId: String, cursor: String?): FeedItemPage

    /** [assetPattern] 只对 GitHub 有用 */
    suspend fun preview(url: String, assetPattern: String?): FeedPreview

    /** 新建:文件夹默认 /订阅/<标题>、每 6 小时检查、第一次只处理最新 1 条(服务端默认值) */
    suspend fun create(url: String, mode: FeedMode, assetPattern: String?): Feed

    /** 暂停 / 恢复;其余设置原样带上(服务端把没给的字段当零值) */
    suspend fun setEnabled(feed: Feed, enabled: Boolean): Feed

    /** 退订,已下好的文件不动 */
    suspend fun delete(id: String)

    /** 立即检查一次(同步,最多 20 秒) */
    suspend fun refresh(id: String): Feed

    /** 手动处理一个条目:DOWNLOAD 下附件 / CLIP 剪藏正文 */
    suspend fun fetchItem(itemId: String, mode: FeedMode): FeedItem
}

@Singleton
class ApolloFeedsRepository @Inject constructor(private val apollo: ApolloClient) : FeedsRepository {

    override suspend fun feeds(): List<Feed> =
        apollo.query(FeedsQuery()).execute().requireData().feeds.map { it.feedFields.toDomain() }

    override suspend fun feed(id: String): Feed =
        apollo.query(FeedQuery(id)).execute().requireData().feed.feedFields.toDomain()

    override suspend fun items(feedId: String, cursor: String?): FeedItemPage {
        val page = apollo.query(FeedItemsQuery(feedId, Optional.present(cursor))).execute().requireData().feedItems
        return FeedItemPage(page.items.map { it.feedItemFields.toDomain() }, page.nextCursor)
    }

    override suspend fun preview(url: String, assetPattern: String?): FeedPreview {
        val p = apollo.query(PreviewFeedQuery(url, Optional.present(assetPattern?.takeIf { it.isNotBlank() })))
            .execute().requireData().previewFeed
        return FeedPreview(
            kind = p.kind.toDomain(),
            url = p.url,
            title = p.title,
            siteUrl = p.siteUrl,
            podcast = p.podcast,
            suggestedMode = p.suggestedMode.toDomain(),
            items = p.items.map { FeedPreviewItem(it.title, it.url, it.enclosureUrl, it.enclosureSize, it.publishedAt) },
        )
    }

    override suspend fun create(url: String, mode: FeedMode, assetPattern: String?): Feed {
        val input = FeedInput(
            url = Optional.present(url),
            mode = Optional.present(mode.toGql()),
            assetPattern = Optional.presentIfNotNull(assetPattern?.trim()?.takeIf { it.isNotEmpty() }),
        )
        return apollo.mutation(CreateFeedMutation(input)).execute().requireData().createFeed.feedFields.toDomain()
    }

    override suspend fun setEnabled(feed: Feed, enabled: Boolean): Feed =
        apollo.mutation(UpdateFeedMutation(feed.id, FeedUpdates.update(feed, enabled))).execute().requireData().updateFeed.feedFields.toDomain()

    override suspend fun delete(id: String) {
        apollo.mutation(DeleteFeedMutation(id)).execute().requireData()
    }

    override suspend fun refresh(id: String): Feed =
        apollo.mutation(RefreshFeedMutation(id)).execute().requireData().refreshFeed.feedFields.toDomain()

    override suspend fun fetchItem(itemId: String, mode: FeedMode): FeedItem =
        apollo.mutation(FetchFeedItemMutation(itemId, mode.toGql())).execute().requireData().fetchFeedItem.feedItemFields.toDomain()
}

/** updateFeed 的输入:服务端把没给的字段当零值(模式变「只记录」、间隔回到默认…),所以每一项都带上订阅现在的值 */
internal object FeedUpdates {
    fun update(feed: Feed, enabled: Boolean): FeedInput = FeedInput(
        title = Optional.present(feed.title),
        folderId = Optional.presentIfNotNull(feed.folderId),
        mode = Optional.present(feed.mode.toGql()),
        includeWords = Optional.present(feed.includeWords),
        excludeWords = Optional.present(feed.excludeWords),
        assetPattern = Optional.present(feed.assetPattern),
        prerelease = Optional.present(feed.prerelease),
        keepLast = Optional.present(feed.keepLast),
        intervalMinutes = Optional.present(feed.intervalMinutes),
        enabled = Optional.present(enabled),
    )
}

internal fun FeedMode.toGql(): GqlFeedMode = when (this) {
    FeedMode.DOWNLOAD -> GqlFeedMode.DOWNLOAD
    FeedMode.CLIP -> GqlFeedMode.CLIP
    FeedMode.NONE -> GqlFeedMode.NONE
}

private fun GqlFeedMode.toDomain(): FeedMode = when (this) {
    GqlFeedMode.DOWNLOAD -> FeedMode.DOWNLOAD
    GqlFeedMode.CLIP -> FeedMode.CLIP
    // NONE 与新版服务端加的方式:当作只记录(不会误触发下载)
    else -> FeedMode.NONE
}

private fun GqlFeedKind.toDomain(): FeedKind = if (this == GqlFeedKind.GITHUB) FeedKind.GITHUB else FeedKind.RSS

internal fun FeedFields.toDomain() = Feed(
    id = id,
    kind = kind.toDomain(),
    url = url,
    title = title,
    siteUrl = siteUrl,
    folderId = folder?.id,
    folderName = folder?.name,
    mode = mode.toDomain(),
    includeWords = includeWords,
    excludeWords = excludeWords,
    assetPattern = assetPattern,
    prerelease = prerelease,
    keepLast = keepLast,
    intervalMinutes = intervalMinutes,
    enabled = enabled,
    lastCheckedAt = lastCheckedAt,
    lastOkAt = lastOkAt,
    lastError = lastError?.takeIf { it.isNotBlank() },
    failCount = failCount,
    nextCheckAt = nextCheckAt,
    createdAt = createdAt,
    itemCount = itemCount,
    newCount = newCount,
    latestAt = latestAt,
)

internal fun FeedItemFields.toDomain() = FeedItem(
    id = id,
    title = title,
    url = url,
    enclosureUrl = enclosureUrl,
    enclosureType = enclosureType,
    enclosureSize = enclosureSize,
    publishedAt = publishedAt,
    state = when (state) {
        GqlItemState.NEW -> FeedItemState.NEW
        GqlItemState.SKIPPED -> FeedItemState.SKIPPED
        GqlItemState.QUEUED -> FeedItemState.QUEUED
        GqlItemState.DONE -> FeedItemState.DONE
        // FAILED 与新版服务端加的状态:当作失败(不会一直显示处理中、触发轮询)
        else -> FeedItemState.FAILED
    },
    error = error?.takeIf { it.isNotBlank() },
    node = node?.let { FeedItemNode(it.id, it.name, it.kind == GqlNodeKind.FOLDER, it.parentId) },
    jobId = jobId,
    createdAt = createdAt,
)

/** 订阅自己的 Hilt 模块(不动 DataModule) */
@Module
@InstallIn(SingletonComponent::class)
abstract class FeedsModule {
    @Binds
    abstract fun bindFeedsRepository(impl: ApolloFeedsRepository): FeedsRepository
}
