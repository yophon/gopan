package xyz.yophon.gopan.core.data.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import xyz.yophon.gopan.core.data.CachedListing
import xyz.yophon.gopan.core.data.ListingCache
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.prefs.DriveSortOrder
import javax.inject.Inject
import javax.inject.Singleton

/** Room 实现:items 存 JSON。坏掉的行当作没有缓存(返回 null),不让旧版本写的数据拖垮列表。 */
@Singleton
class RoomListingCache @Inject constructor(
    private val dao: CachedListingDao,
) : ListingCache {

    override suspend fun get(userId: String, parentId: String?): CachedListing? = withContext(Dispatchers.IO) {
        dao.get(CachedListingEntity.key(userId, parentId))?.toListing()
    }

    override suspend fun put(userId: String, parentId: String?, listing: CachedListing) = withContext(Dispatchers.IO) {
        dao.upsert(
            CachedListingEntity(
                cacheKey = CachedListingEntity.key(userId, parentId),
                userId = userId,
                parentId = parentId,
                itemsJson = json.encodeToString(listing.items),
                total = listing.total,
                sortOrder = listing.order.name,
                sortDesc = listing.desc,
                fetchedAt = listing.fetchedAt,
            ),
        )
    }

    override suspend fun findNode(userId: String, nodeId: String): Node? = withContext(Dispatchers.IO) {
        // LIKE 只是粗筛(节点 id 是 UUID,没有通配符),解析后再精确匹配
        dao.findByItemPattern(userId, "%\"id\":\"$nodeId\"%")
            .asSequence()
            .mapNotNull { it.toListing() }
            .flatMap { it.items.asSequence() }
            .firstOrNull { it.id == nodeId }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) { dao.clear() }

    private fun CachedListingEntity.toListing(): CachedListing? {
        val items = runCatching { json.decodeFromString<List<Node>>(itemsJson) }
            .onFailure { Timber.w(it, "列表缓存解析失败,忽略该条") }
            .getOrNull() ?: return null
        val order = DriveSortOrder.entries.firstOrNull { it.name == sortOrder } ?: DriveSortOrder.NAME
        return CachedListing(items = items, total = total, order = order, desc = sortDesc, fetchedAt = fetchedAt)
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
