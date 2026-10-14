package xyz.yophon.gopan.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * App 本地库。P1 只有目录缓存;P2 的上传/下载队列表加在这里(entities 追加、version +1)。
 * 缓存可丢,所以不导出 schema、迁移失败直接重建。
 */
@Database(entities = [CachedListingEntity::class], version = 1, exportSchema = false)
abstract class GopanDatabase : RoomDatabase() {
    abstract fun cachedListingDao(): CachedListingDao

    companion object {
        const val NAME = "gopan.db"
    }
}
