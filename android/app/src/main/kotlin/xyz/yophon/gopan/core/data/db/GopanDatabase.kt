package xyz.yophon.gopan.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import xyz.yophon.gopan.core.offline.OfflineDao
import xyz.yophon.gopan.core.offline.OfflineFileEntity
import xyz.yophon.gopan.core.offline.OfflinePinEntity
import xyz.yophon.gopan.core.transfer.db.DownloadDao
import xyz.yophon.gopan.core.transfer.db.DownloadTaskEntity
import xyz.yophon.gopan.core.transfer.db.UploadDao
import xyz.yophon.gopan.core.transfer.db.UploadTaskEntity

/**
 * App 本地库:目录首页缓存(P1)与上传 / 下载队列(P2)。
 * 不导出 schema。版本 1 → 2 加了两张队列表,旧库只有可丢的缓存,破坏性重建即可;
 * 之后队列表里有用户的进行中任务,再改表结构必须写 Migration,不能再走破坏性重建。
 * 2 → 3:上传任务加「替换同名」(replaceExisting,doc/24)。
 * 3 → 4:离线可用(doc/26)的两张表 offline_pin、offline_file。
 */
@Database(
    entities = [
        CachedListingEntity::class,
        UploadTaskEntity::class,
        DownloadTaskEntity::class,
        OfflinePinEntity::class,
        OfflineFileEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class GopanDatabase : RoomDatabase() {
    abstract fun cachedListingDao(): CachedListingDao

    abstract fun uploadDao(): UploadDao

    abstract fun downloadDao(): DownloadDao

    abstract fun offlineDao(): OfflineDao

    companion object {
        const val NAME = "gopan.db"

        /** 上传任务加 replaceExisting;已有的任务照旧自动改名 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE upload_task ADD COLUMN replaceExisting INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** 离线可用的两张新表(与 Room 生成的建表语句一致) */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `offline_pin` (`userId` TEXT NOT NULL, `nodeId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`isFolder` INTEGER NOT NULL, `mime` TEXT, `size` INTEGER, `taskId` INTEGER, `pinnedAt` INTEGER NOT NULL, " +
                        "`roundAt` INTEGER NOT NULL, `syncedAt` INTEGER, PRIMARY KEY(`userId`, `nodeId`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `offline_file` (`userId` TEXT NOT NULL, `rootId` TEXT NOT NULL, `nodeId` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, `relDir` TEXT NOT NULL, `size` INTEGER NOT NULL, `sha256` TEXT, `mime` TEXT, " +
                        "`path` TEXT NOT NULL, `savedAt` INTEGER NOT NULL, PRIMARY KEY(`userId`, `rootId`, `nodeId`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_offline_file_userId_nodeId` ON `offline_file` (`userId`, `nodeId`)")
            }
        }
    }
}
