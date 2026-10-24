package xyz.yophon.gopan.feature.drive

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import xyz.yophon.gopan.core.data.ContentHit
import xyz.yophon.gopan.core.data.ContentHitPage
import xyz.yophon.gopan.core.data.Listing
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.NodeKind
import xyz.yophon.gopan.core.data.NodePage
import xyz.yophon.gopan.core.data.NodesRepository
import xyz.yophon.gopan.core.data.TextRange
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.prefs.DriveSortOrder

/**
 * ViewModel 测试用的假仓库:目录按 parentId 预置若干页(游标就是页号字符串),
 * [networkDown] 时首页抛 NETWORK,若 [cache] 里有该目录则回退缓存(模拟 NodesRepositoryImpl 的行为)。
 */
class FakeNodesRepository : NodesRepository {

    private val _version = MutableStateFlow(0L)
    override val version: StateFlow<Long> = _version

    /** parentId → 页列表 */
    val listings = HashMap<String?, List<NodePage>>()

    /** parentId → 离线时可回退的缓存页 */
    val cache = HashMap<String?, NodePage>()

    /** 关键词 → 页列表 */
    val searchResults = HashMap<String, List<NodePage>>()

    var trashPages: List<NodePage> = emptyList()

    /** 按 id 可查的节点(ancestors / node 用) */
    val nodes = HashMap<String, Node>()

    @Volatile var networkDown = false

    /** 下一次变更抛出的错误(抛一次就清掉) */
    @Volatile var nextMutationError: GopanException? = null

    val calls = ArrayList<String>()
    var firstPageCalls = 0
        private set

    /** 联网成功时 Listing.fetchedAt 的取值;默认"刚刚",测"列表放久了"时改成很早的时刻 */
    var fetchedAtProvider: () -> Long = { System.currentTimeMillis() }
    var lastOrder: Pair<DriveSortOrder, Boolean>? = null
        private set

    /** 非空时 firstPage 联网前等它:测"先显示缓存、联网还在路上"的中间态 */
    @Volatile var firstPageGate: CompletableDeferred<Unit>? = null

    /** 非空时 firstPage 联网抛它(NOT_FOUND 这类不回退缓存的错误) */
    @Volatile var firstPageError: GopanException? = null

    override suspend fun cachedFirstPage(parentId: String?, order: DriveSortOrder, desc: Boolean): Listing? {
        calls += "cachedFirstPage($parentId)"
        val cached = cache[parentId] ?: return null
        return Listing(cached.copy(nextCursor = null), fromCache = true, fetchedAt = 1_000L)
    }

    override suspend fun firstPage(parentId: String?, order: DriveSortOrder, desc: Boolean): Listing {
        firstPageCalls++
        lastOrder = order to desc
        calls += "firstPage($parentId)"
        firstPageGate?.await()
        firstPageError?.let { throw it }
        if (networkDown) {
            val error = GopanException(GopanErrors.NETWORK, "网络连接失败,请检查网络后重试")
            val cached = cache[parentId] ?: throw error
            return Listing(cached.copy(nextCursor = null), fromCache = true, fetchedAt = 1_000L, error = error)
        }
        return Listing(pageOf(parentId, null), fromCache = false, fetchedAt = fetchedAtProvider())
    }

    override suspend fun children(parentId: String?, cursor: String?, order: DriveSortOrder, desc: Boolean): NodePage {
        calls += "children($parentId,$cursor)"
        if (networkDown) throw GopanException(GopanErrors.NETWORK, "网络连接失败,请检查网络后重试")
        return pageOf(parentId, cursor)
    }

    private fun pageOf(parentId: String?, cursor: String?): NodePage {
        val pages = listings[parentId] ?: return NodePage.EMPTY
        val index = cursor?.toInt() ?: 0
        return pages.getOrNull(index) ?: NodePage.EMPTY
    }

    override suspend fun search(q: String, cursor: String?): NodePage {
        calls += "search($q,$cursor)"
        if (networkDown) throw GopanException(GopanErrors.NETWORK, "网络连接失败,请检查网络后重试")
        val pages = searchResults[q] ?: return NodePage.EMPTY
        return pages.getOrNull(cursor?.toInt() ?: 0) ?: NodePage.EMPTY
    }

    /** 关键词 → 内容搜索的页列表(游标同样是页号字符串) */
    val contentResults = HashMap<String, List<ContentHitPage>>()

    /** 关键词 → 内容搜索抛的错误(QUERY_TOO_SHORT / CONTENT_SEARCH_DISABLED …) */
    val contentErrors = HashMap<String, GopanException>()

    /** 内容搜索每次请求的耗时(虚拟时钟):测"换关键词取消在途请求、丢弃旧页" */
    var contentDelayMs = 0L

    override suspend fun searchContent(q: String, cursor: String?): ContentHitPage {
        calls += "searchContent($q,$cursor)"
        if (contentDelayMs > 0) delay(contentDelayMs)
        if (networkDown) throw GopanException(GopanErrors.NETWORK, "网络连接失败,请检查网络后重试")
        contentErrors[q]?.let { throw it }
        val pages = contentResults[q] ?: return ContentHitPage.EMPTY
        return pages.getOrNull(cursor?.toInt() ?: 0) ?: ContentHitPage.EMPTY
    }

    override suspend fun trash(cursor: String?): NodePage {
        calls += "trash($cursor)"
        if (networkDown) throw GopanException(GopanErrors.NETWORK, "网络连接失败,请检查网络后重试")
        return trashPages.getOrNull(cursor?.toInt() ?: 0) ?: NodePage.EMPTY
    }

    override suspend fun node(id: String): Node =
        nodes[id] ?: throw GopanException(GopanErrors.NOT_FOUND, "对象不存在或已被删除")

    override suspend fun ancestors(folderId: String): List<Node> {
        calls += "ancestors($folderId)"
        val chain = ArrayList<Node>()
        var cur: String? = folderId
        while (cur != null) {
            val n = node(cur)
            chain += n
            cur = n.parentId
        }
        return chain.asReversed()
    }

    override suspend fun createFolder(parentId: String?, name: String): Node {
        mutate("createFolder($parentId,$name)")
        return folder(id = "new-$name", name = name, parentId = parentId)
    }

    override suspend fun rename(id: String, name: String): Node {
        mutate("rename($id,$name)")
        return folder(id = id, name = name)
    }

    override suspend fun move(ids: List<String>, targetParentId: String?) = mutate("move($ids,$targetParentId)")

    override suspend fun copy(ids: List<String>, targetParentId: String?) = mutate("copy($ids,$targetParentId)")

    override suspend fun delete(ids: List<String>) = mutate("delete($ids)")

    override suspend fun restore(ids: List<String>) = mutate("restore($ids)")

    override suspend fun purge(ids: List<String>) = mutate("purge($ids)")

    override suspend fun purgeTrash() = mutate("purgeTrash")

    override suspend fun setStarred(ids: List<String>, starred: Boolean) = mutate("setStarred")

    override suspend fun createTextFile(parentId: String?, name: String): Node {
        mutate("createTextFile($name)")
        return file("new-text", name, parentId)
    }

    private fun mutate(call: String) {
        calls += call
        nextMutationError?.let {
            nextMutationError = null
            throw it
        }
        _version.update { it + 1 }
    }

    companion object {
        fun file(id: String, name: String = id, parentId: String? = null, size: Long = 100, updatedAt: String = "2026-10-14T00:00:00Z") =
            Node(id = id, parentId = parentId, name = name, kind = NodeKind.FILE, size = size, updatedAt = updatedAt)

        fun folder(id: String, name: String = id, parentId: String? = null, updatedAt: String = "2026-10-14T00:00:00Z") =
            Node(id = id, parentId = parentId, name = name, kind = NodeKind.FOLDER, updatedAt = updatedAt)

        fun page(vararg items: Node, nextCursor: String? = null, total: Int = items.size) =
            NodePage(items.toList(), nextCursor, total)

        /** 一条内容命中:片段默认就是文件名,高亮整段 */
        fun hit(node: Node, snippet: String = node.name, page: Int? = null, highlights: List<TextRange> = listOf(TextRange(0, snippet.codePointCount(0, snippet.length)))) =
            ContentHit(node, snippet, highlights, page)

        fun hits(vararg items: ContentHit, nextCursor: String? = null) = ContentHitPage(items.toList(), nextCursor)
    }
}
