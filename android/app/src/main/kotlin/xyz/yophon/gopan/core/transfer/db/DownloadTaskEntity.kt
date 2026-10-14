package xyz.yophon.gopan.core.transfer.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import xyz.yophon.gopan.core.transfer.DownloadDest
import xyz.yophon.gopan.core.transfer.DownloadKind
import xyz.yophon.gopan.core.transfer.TransferState

/**
 * 下载任务。预签名 URL 不落库;续传起点以 .part 的实际长度为准(本表不存"已下字节")。
 * 文件夹下载是一行 FOLDER(批次)加若干行 FILE(batchId = 批次行 id,relDir 是批次内的相对目录)。
 */
@Entity(tableName = "download_task", indices = [Index("userId"), Index("state"), Index("batchId")])
data class DownloadTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String,
    val kind: DownloadKind,
    /** 属于哪个文件夹批次;顶层任务为 null */
    val batchId: Long? = null,
    /** FILE / FOLDER:节点 id */
    val nodeId: String? = null,
    /** PACK:规范化(去重、去子孙)后的节点 id,逗号分隔 */
    val packNodeIds: String? = null,
    /** 落盘名(已清洗) */
    val name: String,
    /** 相对目标根的目录(已清洗,"/" 分隔);顶层为空串 */
    val relDir: String = "",
    /** FILE:节点大小;FOLDER:展开后的总字节;PACK:估算值(可能为 null) */
    val size: Long? = null,
    /** FILE:任务开始时节点的内容哈希,用来发现"内容被覆盖写" */
    val sha256: String? = null,
    val mime: String? = null,
    val dest: DownloadDest,
    /** dest = TREE 时的目录树 URI */
    val treeUri: String? = null,
    val state: TransferState = TransferState.QUEUED,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
    /** FOLDER:已展开出子任务 */
    val expanded: Boolean = false,
    /** 完成后的位置:content:// URI(MediaStore / SAF)或文件路径(缓存) */
    val finalUri: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val finishedAt: Long? = null,
)
