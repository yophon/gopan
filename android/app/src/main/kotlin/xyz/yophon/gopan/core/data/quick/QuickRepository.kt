package xyz.yophon.gopan.core.data.quick

import androidx.compose.runtime.Immutable
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import timber.log.Timber
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.NodesRepository
import xyz.yophon.gopan.core.data.ScreenCache
import xyz.yophon.gopan.core.data.ScreenKey
import xyz.yophon.gopan.core.data.media.PlayProgress
import xyz.yophon.gopan.core.data.media.toDomain
import xyz.yophon.gopan.core.data.toNode
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.core.network.requireData
import xyz.yophon.gopan.di.ApplicationScope
import xyz.yophon.gopan.graphql.ClearRecentMutation
import xyz.yophon.gopan.graphql.DeleteFileVersionsMutation
import xyz.yophon.gopan.graphql.FileVersionsQuery
import xyz.yophon.gopan.graphql.MarkOpenedMutation
import xyz.yophon.gopan.graphql.PurgeFileVersionsMutation
import xyz.yophon.gopan.graphql.RecentFilesQuery
import xyz.yophon.gopan.graphql.RestoreFileVersionMutation
import xyz.yophon.gopan.graphql.StarredNodesQuery
import javax.inject.Inject
import javax.inject.Singleton

/** 收藏 / 最近列表的一项:节点 + 所在路径(显示「位置」)+ 最后打开的时刻与看到哪(只有「最近」有) */
@Immutable
@Serializable
data class QuickItem(
    val node: Node,
    /** 从根开始的完整路径,如 /工作/报价.pdf;取不到为 null */
    val path: String?,
    val openedAt: String? = null,
    val progress: PlayProgress? = null,
)

/** 文件被同名覆盖时留下的一个旧版本 */
@Immutable
data class FileVersion(
    val id: String,
    val size: Long,
    /** 这份内容当初写进来的时刻 */
    val savedAt: String,
    /** 被换下来的时刻 */
    val replacedAt: String,
    /** 预签名下载地址,15 分钟有效:即取即用,不缓存 */
    val downloadUrl: String,
)

@Immutable
data class FileVersionList(
    val items: List<FileVersion>,
    /** 本站的保留策略:每个文件最多留几个(0 = 不留),换下来后留几天 */
    val keep: Int,
    val keepDays: Int,
)

/** 收藏、最近打开与历史版本(doc/24)。失败时抛 GopanException,调用方用 gopanResult 包。 */
interface QuickRepository {
    suspend fun starred(): List<QuickItem>

    suspend fun recent(): List<QuickItem>

    /** 打开(预览、下载)过一个文件:进「最近」 */
    suspend fun markOpened(nodeId: String)

    /** 从「最近」里拿掉(连看到哪一起);ids 为 null = 全部清掉 */
    suspend fun clearRecent(ids: List<String>?)

    suspend fun versions(nodeId: String): FileVersionList

    /** 恢复成某个版本:现在的内容留成一个新版本 */
    suspend fun restoreVersion(id: String)

    suspend fun deleteVersions(ids: List<String>)

    /** 清空一个文件的全部版本,返回释放的字节 */
    suspend fun purgeVersions(nodeId: String): Long

    /** 把收藏与最近两页的首屏取进 [ScreenCache](先读手机上存的上次那份,再联网),第一次点进去就有数据 */
    fun prefetch() {}

    companion object {
        /** 两页的快照(落盘):页面进来先画它,同时静默刷新 */
        val STARRED_KEY = ScreenKey("quick.starred", ListSerializer(QuickItem.serializer()))
        val RECENT_KEY = ScreenKey("quick.recent", ListSerializer(QuickItem.serializer()))
    }
}

@Singleton
class ApolloQuickRepository @Inject constructor(
    private val apollo: ApolloClient,
    private val nodes: NodesRepository,
    private val cache: ScreenCache,
    private val session: SessionStore,
    @ApplicationScope private val scope: CoroutineScope,
) : QuickRepository {

    init {
        // 别处收藏、改名、移动、删除过:两页的快照跟着在后台刷新,下次点进去直接是对的。
        // 只刷已经取过的(没进过主界面、没预取过的不白跑)
        @OptIn(FlowPreview::class)
        scope.launch {
            nodes.version.drop(1).debounce(WARM_DEBOUNCE_MS).collect {
                if (cache[QuickRepository.STARRED_KEY] != null) warm(QuickRepository.STARRED_KEY) { starred() }
                if (cache[QuickRepository.RECENT_KEY] != null) warm(QuickRepository.RECENT_KEY) { recent() }
            }
        }
    }

    override fun prefetch() {
        warm(QuickRepository.STARRED_KEY) { starred() }
        warm(QuickRepository.RECENT_KEY) { recent() }
    }

    private fun warm(key: ScreenKey<List<QuickItem>>, fetch: suspend () -> List<QuickItem>) {
        val uid = session.currentUser?.id ?: return
        scope.launch {
            cache.load(key)
            gopanResult { fetch() }
                // 取的途中换了账号:这份不属于现在的用户
                .onSuccess { if (session.currentUser?.id == uid) cache.put(key, it) }
                .onFailure { Timber.d("预取 %s 失败:%s", key, it.message) }
        }
    }

    override suspend fun starred(): List<QuickItem> =
        apollo.query(StarredNodesQuery()).execute().requireData().starredNodes.map {
            QuickItem(node = it.nodeItem.toNode(), path = it.path)
        }

    override suspend fun recent(): List<QuickItem> =
        apollo.query(RecentFilesQuery()).execute().requireData().recentFiles.map {
            QuickItem(node = it.nodeItem.toNode(), path = it.path, openedAt = it.openedAt, progress = it.playProgress?.playProgressFields?.toDomain())
        }

    override suspend fun markOpened(nodeId: String) {
        apollo.mutation(MarkOpenedMutation(nodeId)).execute().requireData()
        // 刚打开的文件排到「最近」最前面:趁着用户还在预览,把快照换成新的
        warm(QuickRepository.RECENT_KEY) { recent() }
    }

    override suspend fun clearRecent(ids: List<String>?) {
        apollo.mutation(ClearRecentMutation(Optional.present(ids))).execute().requireData()
    }

    override suspend fun versions(nodeId: String): FileVersionList {
        val data = apollo.query(FileVersionsQuery(nodeId)).execute().requireData().fileVersions
        return FileVersionList(
            items = data.items.map { FileVersion(it.id, it.size, it.savedAt, it.replacedAt, it.downloadUrl) },
            keep = data.keep,
            keepDays = data.keepDays,
        )
    }

    override suspend fun restoreVersion(id: String) {
        apollo.mutation(RestoreFileVersionMutation(id)).execute().requireData()
        // 文件的大小与修改时间变了:列表页回到前台时刷新
        nodes.markChanged()
    }

    override suspend fun deleteVersions(ids: List<String>) {
        apollo.mutation(DeleteFileVersionsMutation(ids)).execute().requireData()
    }

    override suspend fun purgeVersions(nodeId: String): Long =
        apollo.mutation(PurgeFileVersionsMutation(Optional.present(nodeId))).execute().requireData().purgeFileVersions

    private companion object {
        const val WARM_DEBOUNCE_MS = 500L
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class QuickModule {
    @Binds
    abstract fun bindQuickRepository(impl: ApolloQuickRepository): QuickRepository
}
