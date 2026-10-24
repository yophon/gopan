package xyz.yophon.gopan.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import xyz.yophon.gopan.core.transfer.db.DownloadDao
import xyz.yophon.gopan.core.transfer.db.DownloadTaskEntity
import xyz.yophon.gopan.core.transfer.db.UploadDao
import xyz.yophon.gopan.core.transfer.db.UploadTaskEntity

/**
 * App 本地库:目录首页缓存(P1)与上传 / 下载队列(P2)。
 * 不导出 schema。版本 1 → 2 加了两张队列表,旧库只有可丢的缓存,破坏性重建即可;
 * 之后队列表里有用户的进行中任务,再改表结构必须写 Migration,不能再走破坏性重建。
 * 2 → 3:上传任务加「替换同名」(replaceExisting,doc/24)。
 */
@Database(
    entities = [CachedListingEntity::class, UploadTaskEntity::class, DownloadTaskEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class GopanDatabase : RoomDatabase() {
    abstract fun cachedListingDao(): CachedListingDao

    abstract fun uploadDao(): UploadDao

    abstract fun downloadDao(): DownloadDao

    companion object {
        const val NAME = "gopan.db"

        /** 上传任务加 replaceExisting;已有的任务照旧自动改名 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE upload_task ADD COLUMN replaceExisting INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
