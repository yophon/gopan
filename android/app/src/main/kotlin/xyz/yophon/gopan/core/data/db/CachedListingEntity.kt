package xyz.yophon.gopan.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 目录首页缓存。主键 cacheKey = "userId/parentId"(根目录 parentId 为空串):
 * 根目录是每个用户各一个,所以必须带用户;其它节点 id 全局唯一,带用户只是为了一次清掉。
 */
@Entity(tableName = "cached_listing")
data class CachedListingEntity(
    @PrimaryKey val cacheKey: String,
    val userId: String,
    val parentId: String?,
    /** List<Node> 的 JSON(kotlinx.serialization) */
    val itemsJson: String,
    val total: Int,
    /** DriveSortOrder.name */
    val sortOrder: String,
    val sortDesc: Boolean,
    /** 写入时刻(epoch 毫秒) */
    val fetchedAt: Long,
) {
    companion object {
        fun key(userId: String, parentId: String?) = "$userId/${parentId.orEmpty()}"
    }
}
