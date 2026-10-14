package xyz.yophon.gopan.core.transfer.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 上传队列。状态迁移一律用带前置状态的 UPDATE(WHERE state = …),返回受影响行数:
 * 运行中的任务写结果时,用户可能刚点了暂停 / 取消,条件不满足就不覆盖。
 */
@Dao
interface UploadDao {
    @Query("SELECT * FROM upload_task WHERE userId = :userId AND state != 'CANCELED' ORDER BY id")
    fun observe(userId: String): Flow<List<UploadTaskEntity>>

    @Query("SELECT * FROM upload_task WHERE id = :id")
    suspend fun get(id: Long): UploadTaskEntity?

    @Query("SELECT * FROM upload_task WHERE userId = :userId AND state = 'QUEUED' ORDER BY id")
    suspend fun queued(userId: String): List<UploadTaskEntity>

    @Query("SELECT * FROM upload_task WHERE userId = :userId AND state = :state")
    suspend fun withState(userId: String, state: String): List<UploadTaskEntity>

    @Query("SELECT * FROM upload_task")
    suspend fun all(): List<UploadTaskEntity>

    @Insert
    suspend fun insert(tasks: List<UploadTaskEntity>): List<Long>

    /** 进程被杀 / Worker 被停:跑到一半的任务回到队列 */
    @Query("UPDATE upload_task SET state = 'QUEUED' WHERE state = 'RUNNING'")
    suspend fun resetRunning()

    @Query("UPDATE upload_task SET state = 'RUNNING', updatedAt = :now WHERE id = :id AND state = 'QUEUED'")
    suspend fun markRunning(id: Long, now: Long): Int

    @Query(
        "UPDATE upload_task SET state = 'DONE', nodeId = :nodeId, finalName = :finalName, instant = :instant, " +
            "sessionId = NULL, errorCode = NULL, errorMessage = NULL, updatedAt = :now, finishedAt = :now " +
            "WHERE id = :id AND state = 'RUNNING'",
    )
    suspend fun markDone(id: Long, nodeId: String, finalName: String, instant: Boolean, now: Long): Int

    @Query(
        "UPDATE upload_task SET state = 'QUEUED', attempts = :attempts, nextAttemptAt = :nextAt, " +
            "errorCode = :code, errorMessage = :message, updatedAt = :now WHERE id = :id AND state = 'RUNNING'",
    )
    suspend fun markRetry(id: Long, attempts: Int, nextAt: Long, code: String, message: String?, now: Long): Int

    @Query(
        "UPDATE upload_task SET state = 'FAILED', attempts = :attempts, errorCode = :code, errorMessage = :message, " +
            "updatedAt = :now, finishedAt = :now WHERE id = :id AND state = 'RUNNING'",
    )
    suspend fun markFailed(id: Long, attempts: Int, code: String, message: String?, now: Long): Int

    @Query("UPDATE upload_task SET state = 'PAUSED', updatedAt = :now WHERE id = :id AND state IN ('QUEUED', 'RUNNING')")
    suspend fun pause(id: Long, now: Long): Int

    @Query("UPDATE upload_task SET state = 'QUEUED', nextAttemptAt = 0, updatedAt = :now WHERE id = :id AND state = 'PAUSED'")
    suspend fun resume(id: Long, now: Long): Int

    @Query(
        "UPDATE upload_task SET state = 'QUEUED', attempts = 0, autoRestarts = 0, nextAttemptAt = 0, " +
            "errorCode = NULL, errorMessage = NULL, finishedAt = NULL, updatedAt = :now WHERE id = :id AND state = 'FAILED'",
    )
    suspend fun retry(id: Long, now: Long): Int

    @Query("UPDATE upload_task SET state = 'CANCELED', updatedAt = :now WHERE id = :id AND state NOT IN ('DONE', 'CANCELED')")
    suspend fun cancel(id: Long, now: Long): Int

    // ---- runner 的字段级写回(不碰 state) ----

    @Query(
        "UPDATE upload_task SET sourceUri = :uri, ownsSource = :owns, size = :size, lastModified = :lastModified, " +
            "sha256 = :sha256 WHERE id = :id",
    )
    suspend fun setSource(id: Long, uri: String, owns: Boolean, size: Long, lastModified: Long?, sha256: String)

    @Query("UPDATE upload_task SET sessionId = :sessionId, sessionCreatedAt = :createdAt WHERE id = :id")
    suspend fun setSession(id: Long, sessionId: String?, createdAt: Long?)

    @Query("UPDATE upload_task SET parentId = :parentId WHERE id = :id")
    suspend fun setParent(id: Long, parentId: String?)

    @Query("UPDATE upload_task SET autoRestarts = :n WHERE id = :id")
    suspend fun setRestarts(id: Long, n: Int)

    @Query("DELETE FROM upload_task WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM upload_task")
    suspend fun deleteAll()
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM download_task WHERE userId = :userId AND state != 'CANCELED' ORDER BY id")
    fun observe(userId: String): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_task WHERE id = :id")
    suspend fun get(id: Long): DownloadTaskEntity?

    /** 可运行的:排队中的单文件 / 打包,以及还没展开的文件夹 */
    @Query(
        "SELECT * FROM download_task WHERE userId = :userId AND state = 'QUEUED' " +
            "AND NOT (kind = 'FOLDER' AND expanded = 1) ORDER BY id",
    )
    suspend fun queued(userId: String): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_task WHERE batchId = :batchId ORDER BY id")
    suspend fun children(batchId: Long): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_task WHERE userId = :userId AND state = :state")
    suspend fun withState(userId: String, state: String): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_task")
    suspend fun all(): List<DownloadTaskEntity>

    @Insert
    suspend fun insert(task: DownloadTaskEntity): Long

    @Insert
    suspend fun insertAll(tasks: List<DownloadTaskEntity>): List<Long>

    /** 展开过的文件夹行不是可执行任务(状态由子任务汇总),重启时不动它 */
    @Query("UPDATE download_task SET state = 'QUEUED' WHERE state = 'RUNNING' AND NOT (kind = 'FOLDER' AND expanded = 1)")
    suspend fun resetRunning()

    @Query("UPDATE download_task SET state = 'RUNNING', updatedAt = :now WHERE id = :id AND state = 'QUEUED'")
    suspend fun markRunning(id: Long, now: Long): Int

    @Query(
        "UPDATE download_task SET state = 'DONE', finalUri = :location, errorCode = NULL, errorMessage = NULL, " +
            "updatedAt = :now, finishedAt = :now WHERE id = :id AND state = 'RUNNING'",
    )
    suspend fun markDone(id: Long, location: String, now: Long): Int

    @Query(
        "UPDATE download_task SET state = 'QUEUED', attempts = :attempts, nextAttemptAt = :nextAt, " +
            "errorCode = :code, errorMessage = :message, updatedAt = :now WHERE id = :id AND state = 'RUNNING'",
    )
    suspend fun markRetry(id: Long, attempts: Int, nextAt: Long, code: String, message: String?, now: Long): Int

    @Query(
        "UPDATE download_task SET state = 'FAILED', attempts = :attempts, errorCode = :code, errorMessage = :message, " +
            "updatedAt = :now, finishedAt = :now WHERE id = :id AND state = 'RUNNING'",
    )
    suspend fun markFailed(id: Long, attempts: Int, code: String, message: String?, now: Long): Int

    /** 文件夹展开完成:批次行保持 RUNNING(由子任务汇总状态),记下总字节 */
    @Query("UPDATE download_task SET expanded = 1, size = :total, updatedAt = :now WHERE id = :id")
    suspend fun markExpanded(id: Long, total: Long, now: Long): Int

    /** 批次行的汇总状态(子任务全部终态后写) */
    @Query(
        "UPDATE download_task SET state = :state, errorCode = :code, errorMessage = :message, updatedAt = :now, " +
            "finishedAt = :finishedAt WHERE id = :id AND state NOT IN ('CANCELED')",
    )
    suspend fun setBatchState(id: Long, state: String, code: String?, message: String?, now: Long, finishedAt: Long?): Int

    @Query("UPDATE download_task SET state = 'PAUSED', updatedAt = :now WHERE (id = :id OR batchId = :id) AND state IN ('QUEUED', 'RUNNING')")
    suspend fun pause(id: Long, now: Long): Int

    @Query("UPDATE download_task SET state = 'QUEUED', nextAttemptAt = 0, updatedAt = :now WHERE (id = :id OR batchId = :id) AND state = 'PAUSED'")
    suspend fun resume(id: Long, now: Long): Int

    @Query(
        "UPDATE download_task SET state = 'QUEUED', attempts = 0, nextAttemptAt = 0, errorCode = NULL, errorMessage = NULL, " +
            "finishedAt = NULL, updatedAt = :now WHERE (id = :id OR batchId = :id) AND state = 'FAILED'",
    )
    suspend fun retry(id: Long, now: Long): Int

    @Query("UPDATE download_task SET state = 'CANCELED', updatedAt = :now WHERE (id = :id OR batchId = :id) AND state NOT IN ('DONE', 'CANCELED')")
    suspend fun cancel(id: Long, now: Long): Int

    /** 展开过的批次行被暂停后恢复 / 重试:批次行本身回到 RUNNING(它不是可执行任务) */
    @Query("UPDATE download_task SET state = 'RUNNING', finishedAt = NULL, updatedAt = :now WHERE id = :id AND kind = 'FOLDER' AND expanded = 1")
    suspend fun reviveBatch(id: Long, now: Long): Int

    @Query("UPDATE download_task SET sha256 = :sha256, size = :size, mime = :mime WHERE id = :id")
    suspend fun setExpected(id: Long, sha256: String?, size: Long, mime: String?)

    @Query("DELETE FROM download_task WHERE id = :id OR batchId = :id")
    suspend fun deleteWithChildren(id: Long)

    @Query("DELETE FROM download_task WHERE userId = :userId AND state = 'DONE' AND batchId IS NULL")
    suspend fun deleteDoneTopLevel(userId: String)

    @Query("DELETE FROM download_task WHERE batchId IN (SELECT id FROM download_task WHERE userId = :userId AND state = 'DONE' AND batchId IS NULL)")
    suspend fun deleteChildrenOfDone(userId: String)

    @Query("DELETE FROM download_task")
    suspend fun deleteAll()
}
