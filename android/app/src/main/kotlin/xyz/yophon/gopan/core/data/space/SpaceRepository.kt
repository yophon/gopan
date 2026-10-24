package xyz.yophon.gopan.core.data.space

import androidx.compose.runtime.Immutable
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.toNode
import xyz.yophon.gopan.core.network.requireData
import xyz.yophon.gopan.graphql.RevokeFileRequestMutation
import xyz.yophon.gopan.graphql.RevokeLinkMutation
import xyz.yophon.gopan.graphql.RevokeShareMutation
import xyz.yophon.gopan.graphql.SpaceDuplicatesQuery
import xyz.yophon.gopan.graphql.SpaceEmptyFoldersQuery
import xyz.yophon.gopan.graphql.SpaceFoldersQuery
import xyz.yophon.gopan.graphql.SpaceIdleQuery
import xyz.yophon.gopan.graphql.SpaceLargeFilesQuery
import xyz.yophon.gopan.graphql.PurgeAllFileVersionsMutation
import xyz.yophon.gopan.graphql.SpaceOverviewQuery
import xyz.yophon.gopan.graphql.SpaceStaleFilesQuery
import xyz.yophon.gopan.graphql.fragment.SpaceFileFields
import javax.inject.Inject
import javax.inject.Singleton
import xyz.yophon.gopan.graphql.type.FileCategory as GqlCategory
import xyz.yophon.gopan.graphql.type.NodeKind as GqlNodeKind

/**
 * 空间分析的文件分类(与 GraphQL FileCategory 同名,服务端按 mime / 扩展名算)。
 * 和文件列表图标用的 feature.drive.FileCategory 不是一回事:这里只有七类,PDF 与 Office 都算文档。
 */
enum class SpaceCategory {
    IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, TEXT, OTHER;

    companion object {
        fun of(raw: String?): SpaceCategory? = entries.firstOrNull { it.name == raw }
    }
}

@Immutable
data class CategoryUsage(val category: SpaceCategory, val files: Long, val bytes: Long)

@Immutable
data class SpaceOverview(
    val usedBytes: Long,
    val quotaBytes: Long,
    /** 回收站里的还占着配额,彻底删除或到期后才释放 */
    val trashFiles: Long,
    val trashBytes: Long,
    val byType: List<CategoryUsage>,
    /** 重复文件:组数与删掉多余的能省下的配额 */
    val duplicateGroups: Long,
    val duplicateBytes: Long,
    /** 历史版本(doc/24):同名覆盖时留下的旧内容,计入已用 */
    val versionFiles: Long = 0,
    val versionBytes: Long = 0,
)

/** 文件夹逐层看:[folders] 按子树大小从大到小,[looseFiles] 是直接放在这一层的文件 */
@Immutable
data class SpaceFolderLevel(val path: String, val folders: List<Node>, val looseFiles: Long, val looseBytes: Long)

/** 列出来的一个文件(或空文件夹)和它所在的文件夹 */
@Immutable
data class SpaceFile(val node: Node, val path: String, val category: SpaceCategory? = null)

/** 同一份内容在盘里出现多次:[files] 最早的在前(默认留它) */
@Immutable
data class DuplicateGroup(val sha256: String, val size: Long, val copies: Int, val files: List<SpaceFile>) {
    /** 只留一份能省下的配额 */
    val reclaimable: Long get() = size * (copies - 1).coerceAtLeast(0)
}

@Immutable
data class DuplicatePage(val items: List<DuplicateGroup>, val nextOffset: Int?)

@Immutable
data class IdleShare(
    val id: String,
    val nodeId: String,
    val name: String,
    val isFolder: Boolean,
    val expiresAt: String?,
    val createdAt: String,
    val lastVisitAt: String?,
    val expired: Boolean,
)

@Immutable
data class IdleLink(
    val id: String,
    val nodeId: String,
    val name: String,
    val expiresAt: String?,
    val createdAt: String,
    val lastHitAt: String?,
    val hits: Long,
    val expired: Boolean,
)

@Immutable
data class IdleRequest(val id: String, val title: String, val folderId: String, val expiresAt: String, val createdAt: String)

/** 已过期的、或 90 天没人访问的分享与直链;已过期的收集链接 */
@Immutable
data class SpaceIdle(val shares: List<IdleShare>, val links: List<IdleLink>, val requests: List<IdleRequest>) {
    val isEmpty: Boolean get() = shares.isEmpty() && links.isEmpty() && requests.isEmpty()
}

/**
 * 空间分析接口(server/internal/service/space.go):全是打开页面时现算的只读查询。
 * 清理:文件走 NodesRepository.delete(进回收站、记进动态),回收站走 purgeTrash;分享 / 直链 / 收集链接在这里吊销。
 * 失败抛 GopanException。
 */
interface SpaceRepository {
    suspend fun overview(): SpaceOverview

    /** [parentId] 为 null = 根目录 */
    suspend fun folders(parentId: String?): SpaceFolderLevel

    /** 最大的前 100 个;[category] 为 null = 不限类型 */
    suspend fun largeFiles(category: SpaceCategory?): List<SpaceFile>

    /** 一年以上没改过、不小于 100 MB 的 */
    suspend fun staleFiles(): List<SpaceFile>

    suspend fun duplicates(offset: Int): DuplicatePage

    /** 子树里一个文件都没有的文件夹(只列最上面一层) */
    suspend fun emptyFolders(): List<SpaceFile>

    suspend fun idle(): SpaceIdle

    suspend fun revokeShare(id: String)

    suspend fun revokeLink(id: String)

    suspend fun revokeRequest(id: String)

    /** 清空自己所有文件的历史版本,返回释放的字节 */
    suspend fun purgeVersions(): Long
}

@Singleton
class ApolloSpaceRepository @Inject constructor(private val apollo: ApolloClient) : SpaceRepository {

    override suspend fun overview(): SpaceOverview {
        val o = apollo.query(SpaceOverviewQuery()).execute().requireData().spaceOverview
        return SpaceOverview(
            usedBytes = o.usedBytes,
            quotaBytes = o.quotaBytes,
            trashFiles = o.trashFiles,
            trashBytes = o.trashBytes,
            byType = o.byType.mapNotNull { c -> c.category.toDomain()?.let { CategoryUsage(it, c.files, c.bytes) } },
            duplicateGroups = o.duplicateGroups,
            duplicateBytes = o.duplicateBytes,
            versionFiles = o.versionFiles,
            versionBytes = o.versionBytes,
        )
    }

    override suspend fun folders(parentId: String?): SpaceFolderLevel {
        val l = apollo.query(SpaceFoldersQuery(parentId = Optional.present(parentId))).execute().requireData().spaceFolders
        return SpaceFolderLevel(l.path, l.folders.map { it.nodeItem.toNode() }, l.looseFiles, l.looseBytes)
    }

    override suspend fun largeFiles(category: SpaceCategory?): List<SpaceFile> =
        apollo.query(
            SpaceLargeFilesQuery(
                category = Optional.presentIfNotNull(category?.let { GqlCategory.safeValueOf(it.name) }),
                limit = Optional.present(LIST_LIMIT),
            ),
        ).execute().requireData().spaceLargeFiles.map { it.spaceFileFields.toDomain() }

    override suspend fun staleFiles(): List<SpaceFile> =
        apollo.query(SpaceStaleFilesQuery(limit = Optional.present(LIST_LIMIT))).execute().requireData()
            .spaceStaleFiles.map { it.spaceFileFields.toDomain() }

    override suspend fun duplicates(offset: Int): DuplicatePage {
        val p = apollo.query(SpaceDuplicatesQuery(offset = Optional.present(offset), limit = Optional.present(DUPLICATES_PAGE)))
            .execute().requireData().spaceDuplicates
        return DuplicatePage(
            items = p.items.map { g -> DuplicateGroup(g.sha256, g.size, g.copies, g.files.map { it.spaceFileFields.toDomain() }) },
            nextOffset = p.nextOffset,
        )
    }

    override suspend fun emptyFolders(): List<SpaceFile> =
        apollo.query(SpaceEmptyFoldersQuery()).execute().requireData().spaceEmptyFolders.map { it.spaceFileFields.toDomain() }

    override suspend fun idle(): SpaceIdle {
        val i = apollo.query(SpaceIdleQuery()).execute().requireData().spaceIdle
        return SpaceIdle(
            shares = i.shares.map {
                IdleShare(it.id, it.nodeId, it.name, it.kind == GqlNodeKind.FOLDER, it.expiresAt, it.createdAt, it.lastVisitAt, it.expired)
            },
            links = i.links.map { IdleLink(it.id, it.nodeId, it.name, it.expiresAt, it.createdAt, it.lastHitAt, it.hits, it.expired) },
            requests = i.requests.map { IdleRequest(it.id, it.title, it.folderId, it.expiresAt, it.createdAt) },
        )
    }

    override suspend fun revokeShare(id: String) {
        apollo.mutation(RevokeShareMutation(id)).execute().requireData()
    }

    override suspend fun revokeLink(id: String) {
        apollo.mutation(RevokeLinkMutation(id)).execute().requireData()
    }

    override suspend fun revokeRequest(id: String) {
        apollo.mutation(RevokeFileRequestMutation(id)).execute().requireData()
    }

    override suspend fun purgeVersions(): Long =
        apollo.mutation(PurgeAllFileVersionsMutation()).execute().requireData().purgeFileVersions

    companion object {
        const val LIST_LIMIT = 100
        const val DUPLICATES_PAGE = 50
    }
}

/** 空间分析自己的 Hilt 模块(不动 DataModule) */
@Module
@InstallIn(SingletonComponent::class)
abstract class SpaceModule {
    @Binds
    abstract fun bindSpaceRepository(impl: ApolloSpaceRepository): SpaceRepository
}

private fun GqlCategory.toDomain(): SpaceCategory? = SpaceCategory.of(rawValue)

internal fun SpaceFileFields.toDomain(): SpaceFile = SpaceFile(node.nodeItem.toNode(), path.ifEmpty { "/" }, category?.toDomain())
