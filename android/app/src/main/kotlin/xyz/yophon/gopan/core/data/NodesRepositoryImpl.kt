package xyz.yophon.gopan.core.data

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.requireData
import xyz.yophon.gopan.core.prefs.DriveSortOrder
import xyz.yophon.gopan.graphql.ChildrenQuery
import xyz.yophon.gopan.graphql.CopyNodesMutation
import xyz.yophon.gopan.graphql.CreateFolderMutation
import xyz.yophon.gopan.graphql.DeleteNodesMutation
import xyz.yophon.gopan.graphql.MoveNodesMutation
import xyz.yophon.gopan.graphql.NodeQuery
import xyz.yophon.gopan.graphql.PurgeNodesMutation
import xyz.yophon.gopan.graphql.PurgeTrashMutation
import xyz.yophon.gopan.graphql.RenameNodeMutation
import xyz.yophon.gopan.graphql.RestoreNodesMutation
import xyz.yophon.gopan.graphql.SearchContentQuery
import xyz.yophon.gopan.graphql.SearchNodesQuery
import xyz.yophon.gopan.graphql.TrashQuery
import xyz.yophon.gopan.graphql.type.NodeOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Apollo 实现。缓存策略:只缓存目录首页(无游标),按 (当前用户, parentId) 一条;
 * 联网成功就覆盖写,请求失败且错误属于"暂时拿不到"的那类才回退缓存。
 * 另外在内存里记住最近见过的文件夹(id → Node),面包屑绝大多数时候不用再查服务端。
 */
@Singleton
class NodesRepositoryImpl @Inject constructor(
    private val apollo: ApolloClient,
    private val cache: ListingCache,
    private val session: SessionStore,
) : NodesRepository {

    private val _version = MutableStateFlow(0L)
    override val version: StateFlow<Long> = _version.asStateFlow()

    /** 最近见过的文件夹,LRU 上限 [KNOWN_FOLDERS_MAX];只存文件夹(面包屑只需要它们) */
    private val knownFolders = object : LinkedHashMap<String, Node>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Node>?): Boolean = size > KNOWN_FOLDERS_MAX
    }

    private val userId: String? get() = session.currentUser?.id

    override suspend fun firstPage(parentId: String?, order: DriveSortOrder, desc: Boolean): Listing {
        val uid = userId
        val page = try {
            children(parentId, null, order, desc)
        } catch (e: GopanException) {
            if (uid == null || !e.allowsCacheFallback) throw e
            val cached = runCatching { cache.get(uid, parentId) }
                .onFailure { Timber.w(it, "读列表缓存失败") }
                .getOrNull() ?: throw e
            cached.items.forEach(::remember)
            val items = if (cached.order == order && cached.desc == desc) cached.items else sortNodes(cached.items, order, desc)
            return Listing(
                page = NodePage(items = items, nextCursor = null, total = cached.total),
                fromCache = true,
                fetchedAt = cached.fetchedAt,
                error = e,
            )
        }
        val now = System.currentTimeMillis()
        if (uid != null) {
            runCatching { cache.put(uid, parentId, CachedListing(page.items, page.total, order, desc, now)) }
                .onFailure { Timber.w(it, "写列表缓存失败") }
        }
        return Listing(page = page, fromCache = false, fetchedAt = now)
    }

    override suspend fun children(parentId: String?, cursor: String?, order: DriveSortOrder, desc: Boolean): NodePage {
        val data = apollo.query(
            ChildrenQuery(
                parentId = Optional.present(parentId),
                cursor = Optional.present(cursor),
                order = Optional.present(order.toGql()),
                desc = Optional.present(desc),
            ),
        ).execute().requireData()
        val p = data.children
        return NodePage(items = p.items.map { it.nodeItem.toNode().also(::remember) }, nextCursor = p.nextCursor, total = p.total)
    }

    override suspend fun search(q: String, cursor: String?): NodePage {
        val data = apollo.query(SearchNodesQuery(q = q, cursor = Optional.present(cursor))).execute().requireData()
        val p = data.searchNodes
        return NodePage(items = p.items.map { it.nodeItem.toNode().also(::remember) }, nextCursor = p.nextCursor, total = p.total)
    }

    override suspend fun searchContent(q: String, cursor: String?): ContentHitPage {
        val data = apollo.query(SearchContentQuery(q = q, cursor = Optional.present(cursor))).execute().requireData()
        val p = data.searchContent
        // 命中的都是文件,不进 knownFolders
        return ContentHitPage(items = p.items.map { it.toContentHit() }, nextCursor = p.nextCursor)
    }

    override suspend fun trash(cursor: String?): NodePage {
        val data = apollo.query(TrashQuery(cursor = Optional.present(cursor))).execute().requireData()
        val p = data.trash
        // 回收站里的节点不进 knownFolders:它们不在目录树上
        return NodePage(items = p.items.map { it.nodeItem.toNode() }, nextCursor = p.nextCursor, total = p.total)
    }

    override suspend fun node(id: String): Node =
        apollo.query(NodeQuery(id = id)).execute().requireData().node.nodeItem.toNode().also(::remember)

    override suspend fun ancestors(folderId: String): List<Node> {
        repeat(MAX_CHAIN_FETCHES) {
            when (val r = resolveChain(folderId) { knownFolder(it) }) {
                is ChainResult.Complete -> return r.chain
                is ChainResult.Missing -> {
                    // 联网优先:本地缓存没有时效,节点被移动或改名后会给出错误的路径;只有网络不通时才退回缓存
                    val found = try {
                        node(r.missingId)
                    } catch (e: GopanException) {
                        if (!e.allowsCacheFallback) throw e
                        userId?.let { uid ->
                            runCatching { cache.findNode(uid, r.missingId) }.getOrNull()?.takeIf { it.isFolder }
                        } ?: throw e
                    }
                    if (!found.isFolder) throw GopanException(GopanErrors.NOT_FOUND, GopanErrors.messageFor(GopanErrors.NOT_FOUND, null))
                    remember(found)
                }
            }
        }
        // 链太深或数据异常:退回"只知道自己"
        return listOfNotNull(knownFolder(folderId))
    }

    override suspend fun createFolder(parentId: String?, name: String): Node {
        val data = apollo.mutation(CreateFolderMutation(parentId = Optional.present(parentId), name = name))
            .execute().requireData()
        return data.createFolder.nodeItem.toNode().also(::remember).also { bump() }
    }

    override suspend fun rename(id: String, name: String): Node {
        val data = apollo.mutation(RenameNodeMutation(id = id, name = name)).execute().requireData()
        return data.renameNode.nodeItem.toNode().also(::remember).also { bump() }
    }

    override suspend fun move(ids: List<String>, targetParentId: String?) {
        apollo.mutation(MoveNodesMutation(ids = ids, targetParentId = Optional.present(targetParentId)))
            .execute().requireData()
        forget(ids)
        bump()
    }

    override suspend fun copy(ids: List<String>, targetParentId: String?) {
        apollo.mutation(CopyNodesMutation(ids = ids, targetParentId = Optional.present(targetParentId)))
            .execute().requireData()
        bump()
    }

    override suspend fun delete(ids: List<String>) {
        apollo.mutation(DeleteNodesMutation(ids = ids)).execute().requireData()
        forget(ids)
        bump()
    }

    override suspend fun restore(ids: List<String>) {
        apollo.mutation(RestoreNodesMutation(ids = ids)).execute().requireData()
        forget(ids)
        bump()
    }

    override suspend fun purge(ids: List<String>) {
        apollo.mutation(PurgeNodesMutation(ids = ids)).execute().requireData()
        forget(ids)
        bump()
    }

    override suspend fun purgeTrash() {
        apollo.mutation(PurgeTrashMutation()).execute().requireData()
        bump()
    }

    /** 登出时清掉内存里记住的节点(Room 缓存由 [ListingCache.clear] 清) */
    fun clearMemory() {
        synchronized(knownFolders) { knownFolders.clear() }
    }

    // ---- 内部 ----

    private fun bump() = _version.update { it + 1 }

    private fun remember(node: Node) {
        if (!node.isFolder) return
        synchronized(knownFolders) { knownFolders[node.id] = node }
    }

    private fun forget(ids: Collection<String>) {
        synchronized(knownFolders) { ids.forEach { knownFolders.remove(it) } }
    }

    private fun knownFolder(id: String): Node? = synchronized(knownFolders) { knownFolders[id] }

    private fun DriveSortOrder.toGql(): NodeOrder = when (this) {
        DriveSortOrder.NAME -> NodeOrder.NAME
        DriveSortOrder.SIZE -> NodeOrder.SIZE
        DriveSortOrder.UPDATED_AT -> NodeOrder.UPDATED_AT
    }

    /** 哪些错误值得回退缓存:网络/服务端暂时不可用。登录失效、对象不存在、输入无效都不是 */
    private val GopanException.allowsCacheFallback: Boolean
        get() = code in CACHE_FALLBACK_CODES

    private companion object {
        const val KNOWN_FOLDERS_MAX = 4096
        const val MAX_CHAIN_FETCHES = 64
        val CACHE_FALLBACK_CODES = setOf(
            GopanErrors.NETWORK,
            GopanErrors.HTTP,
            GopanErrors.INTERNAL,
            GopanErrors.EMPTY_RESPONSE,
            GopanErrors.RATE_LIMITED,
            GopanErrors.UNKNOWN,
        )
    }
}
