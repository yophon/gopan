package xyz.yophon.gopan.core.data

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.StateFlow
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.prefs.DriveSortOrder

/**
 * 文件节点仓库:列表 / 搜索 / 回收站 / 变更。所有方法在失败时抛 [GopanException],
 * 调用方用 core.network 的 gopanResult { } 包一层即可。
 * 生产实现 [NodesRepositoryImpl](Apollo + Room 缓存),ViewModel 测试用假实现。
 */
interface NodesRepository {

    /**
     * 变更计数:每次成功的 createFolder / rename / move / copy / delete / restore / purge 之后 +1。
     * 列表页回到前台时与自己上次加载时的值比较,不同就静默刷新(在别的页面改过东西)。
     */
    val version: StateFlow<Long>

    /**
     * 别处(服务端的后台任务:离线下载、解压)往网盘里加了东西:[version] +1,列表页回到前台时静默刷新。
     * 默认什么都不做(测试用的假仓库不必实现)。
     */
    fun markChanged() {}

    /**
     * 目录首页(无游标)。联网成功 → 写缓存、返回 fresh;
     * 请求失败而本地有这个目录的缓存 → 返回缓存并标记 [Listing.fromCache];
     * 失败且无缓存 → 抛出原错误。UNAUTHENTICATED / NOT_FOUND 这类"内容确实不可用"的错误不回退缓存。
     */
    suspend fun firstPage(parentId: String?, order: DriveSortOrder, desc: Boolean): Listing

    /**
     * 只读本地:这个目录缓存的首页(按 [order] / [desc] 重排),没有就 null。不联网、不抛错。
     * 列表页进来先拿它画出来,再用 [firstPage] 联网刷新(stale-while-revalidate)。
     */
    suspend fun cachedFirstPage(parentId: String?, order: DriveSortOrder, desc: Boolean): Listing? = null

    /** 目录分页(任意游标),不碰缓存 */
    suspend fun children(parentId: String?, cursor: String?, order: DriveSortOrder, desc: Boolean): NodePage

    suspend fun search(q: String, cursor: String?): NodePage

    /**
     * 按文件内容搜索(doc/20)。关键词太短抛 QUERY_TOO_SHORT,本站关了内容索引抛 CONTENT_SEARCH_DISABLED;
     * 游标与文件名搜索一样不绑定关键词,换关键词必须从头搜。
     */
    suspend fun searchContent(q: String, cursor: String?): ContentHitPage

    suspend fun trash(cursor: String?): NodePage

    suspend fun node(id: String): Node

    /**
     * 面包屑:从顶层文件夹到 [folderId] 自身(根不在内,根是 UI 的固定文案)。
     * 优先用最近列表里见过的节点,其次查本地缓存,最后才逐级 node(id)。
     */
    suspend fun ancestors(folderId: String): List<Node>

    suspend fun createFolder(parentId: String?, name: String): Node

    suspend fun rename(id: String, name: String): Node

    suspend fun move(ids: List<String>, targetParentId: String?)

    suspend fun copy(ids: List<String>, targetParentId: String?)

    /** 进回收站(30 天) */
    suspend fun delete(ids: List<String>)

    suspend fun restore(ids: List<String>)

    suspend fun purge(ids: List<String>)

    suspend fun purgeTrash()

    /** 收藏 / 取消收藏(doc/24);回收站里的服务端会跳过。不算内容改动,但列表上的星标要跟着变,所以也 +1 [version] */
    suspend fun setStarred(ids: List<String>, starred: Boolean)

    /** 新建文本文件(doc/25,同名服务端自动改名),内容为空;返回新节点 */
    suspend fun createTextFile(parentId: String?, name: String): Node
}

/** [NodesRepository.firstPage] 的结果:数据 + 来源 */
@Immutable
data class Listing(
    val page: NodePage,
    /** true:来自本地缓存(请求失败回退,或 [NodesRepository.cachedFirstPage]),内容可能过时 */
    val fromCache: Boolean,
    /** 数据的拉取时刻(epoch 毫秒);缓存时是当初写缓存的时刻 */
    val fetchedAt: Long,
    /** fromCache 时:导致回退的那个错误 */
    val error: GopanException? = null,
)
