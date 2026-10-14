package xyz.yophon.gopan.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface CachedListingDao {
    @Query("SELECT * FROM cached_listing WHERE cacheKey = :key")
    suspend fun get(key: String): CachedListingEntity?

    @Upsert
    suspend fun upsert(entity: CachedListingEntity)

    /** 粗筛:itemsJson 里出现过该 id 的页(调用方再解析确认) */
    @Query("SELECT * FROM cached_listing WHERE userId = :userId AND itemsJson LIKE :pattern")
    suspend fun findByItemPattern(userId: String, pattern: String): List<CachedListingEntity>

    @Query("DELETE FROM cached_listing")
    suspend fun clear()
}
