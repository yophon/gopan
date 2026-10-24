package xyz.yophon.gopan.core.offline

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 离线可用的一项(doc/26):用户手动选的文件或文件夹。只是手机上的一份副本,不同步:
 * 云端之后改了不会自动跟着变,在「离线可用」页里手动「更新」才重新取一轮。
 */
@Entity(tableName = "offline_pin", primaryKeys = ["userId", "nodeId"])
data class OfflinePinEntity(
    val userId: String,
    val nodeId: String,
    val name: String,
    val isFolder: Boolean,
    val mime: String? = null,
    /** 加入 / 上次更新时云端的大小(文件夹是整个子树,可能是估算) */
    val size: Long? = null,
    /** 这一轮的下载任务:文件是 FILE 任务,文件夹是批次行 */
    val taskId: Long? = null,
    val pinnedAt: Long,
    /** 这一轮开始的时刻 */
    val roundAt: Long,
    /** 上一轮全部下完的时刻;null = 还没下完过 */
    val syncedAt: Long? = null,
)

/** 离线项里已经存到手机上的一个文件 */
@Entity(
    tableName = "offline_file",
    primaryKeys = ["userId", "rootId", "nodeId"],
    indices = [Index(value = ["userId", "nodeId"])],
)
data class OfflineFileEntity(
    val userId: String,
    /** 属于哪个离线项([OfflinePinEntity.nodeId]) */
    val rootId: String,
    val nodeId: String,
    val name: String,
    /** 在离线项里的相对目录:文件夹项以文件夹自己的名字开头(「照片/2024」),单个文件是空串 */
    val relDir: String,
    val size: Long,
    val sha256: String?,
    val mime: String?,
    /** 手机上的绝对路径(filesDir/offline/…) */
    val path: String,
    val savedAt: Long,
)

@Dao
interface OfflineDao {
    @Query("SELECT * FROM offline_pin WHERE userId = :userId ORDER BY pinnedAt DESC")
    fun observePins(userId: String): Flow<List<OfflinePinEntity>>

    @Query("SELECT * FROM offline_pin WHERE userId = :userId AND nodeId = :nodeId")
    suspend fun pin(userId: String, nodeId: String): OfflinePinEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPin(pin: OfflinePinEntity)

    @Query("UPDATE offline_pin SET taskId = :taskId WHERE userId = :userId AND nodeId = :nodeId")
    suspend fun setTask(userId: String, nodeId: String, taskId: Long?)

    @Query("UPDATE offline_pin SET syncedAt = :at WHERE userId = :userId AND nodeId = :nodeId")
    suspend fun markSynced(userId: String, nodeId: String, at: Long)

    @Query("DELETE FROM offline_pin WHERE userId = :userId AND nodeId = :nodeId")
    suspend fun deletePin(userId: String, nodeId: String)

    @Query("SELECT * FROM offline_file WHERE userId = :userId")
    fun observeFiles(userId: String): Flow<List<OfflineFileEntity>>

    @Query("SELECT * FROM offline_file WHERE userId = :userId AND rootId = :rootId ORDER BY relDir, name")
    fun observeFilesOf(userId: String, rootId: String): Flow<List<OfflineFileEntity>>

    @Query("SELECT * FROM offline_file WHERE userId = :userId AND rootId = :rootId")
    suspend fun filesOf(userId: String, rootId: String): List<OfflineFileEntity>

    @Query("SELECT * FROM offline_file WHERE userId = :userId AND rootId = :rootId AND nodeId = :nodeId")
    suspend fun file(userId: String, rootId: String, nodeId: String): OfflineFileEntity?

    /** 同一个文件可能在两个离线项里(单独加过、所在文件夹也加了):最近存的在前 */
    @Query("SELECT * FROM offline_file WHERE userId = :userId AND nodeId = :nodeId ORDER BY savedAt DESC")
    suspend fun byNode(userId: String, nodeId: String): List<OfflineFileEntity>

    @Upsert
    suspend fun putFile(file: OfflineFileEntity)

    @Query("DELETE FROM offline_file WHERE userId = :userId AND rootId = :rootId AND nodeId IN (:nodeIds)")
    suspend fun deleteFiles(userId: String, rootId: String, nodeIds: List<String>)

    @Query("DELETE FROM offline_file WHERE userId = :userId AND rootId = :rootId")
    suspend fun deleteFilesOf(userId: String, rootId: String)

    @Query("DELETE FROM offline_pin")
    suspend fun deleteAllPins()

    @Query("DELETE FROM offline_file")
    suspend fun deleteAllFiles()
}
