package xyz.yophon.gopan.core.data

import xyz.yophon.gopan.core.prefs.DriveSortOrder
import java.util.concurrent.ConcurrentHashMap

/** 一个目录的缓存首页(只缓存无游标的第一页) */
data class CachedListing(
    val items: List<Node>,
    val total: Int,
    val order: DriveSortOrder,
    val desc: Boolean,
    /** 写入时刻(epoch 毫秒) */
    val fetchedAt: Long,
)

/**
 * 目录首页缓存,按 (userId, parentId) 存取;parentId 为 null 表示根目录。
 * 生产实现 [xyz.yophon.gopan.core.data.db.RoomListingCache],测试用 [InMemoryListingCache]。
 * 所有方法可能做 IO,实现负责切线程。
 */
interface ListingCache {
    suspend fun get(userId: String, parentId: String?): CachedListing?

    suspend fun put(userId: String, parentId: String?, listing: CachedListing)

    /** 在该用户的全部缓存页里找某个节点(面包屑离线兜底) */
    suspend fun findNode(userId: String, nodeId: String): Node?

    suspend fun clear()
}

class InMemoryListingCache : ListingCache {
    private val map = ConcurrentHashMap<String, CachedListing>()

    override suspend fun get(userId: String, parentId: String?): CachedListing? = map[key(userId, parentId)]

    override suspend fun put(userId: String, parentId: String?, listing: CachedListing) {
        map[key(userId, parentId)] = listing
    }

    override suspend fun findNode(userId: String, nodeId: String): Node? =
        map.entries.asSequence()
            .filter { it.key.startsWith("$userId/") }
            .flatMap { it.value.items.asSequence() }
            .firstOrNull { it.id == nodeId }

    override suspend fun clear() = map.clear()

    val size: Int get() = map.size

    private fun key(userId: String, parentId: String?) = "$userId/${parentId.orEmpty()}"
}
