package xyz.yophon.gopan.core.transfer.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import xyz.yophon.gopan.core.transfer.TransferState

/**
 * 上传任务。签名 URL 一律不落库(1 小时就过期),只存会话 id;续传时调 uploadSession 对账。
 * 来源是 content:// URI(SAF / 相册选择器,已持久化读权限)或 App 私有目录里的副本(分享接收、拍照、长文本)。
 */
@Entity(tableName = "upload_task", indices = [Index("userId"), Index("state")])
data class UploadTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String,
    /** 目标文件夹;null = 根目录 */
    val parentId: String?,
    /** 发往传输助手:定稿时文件夹失效(被删)会重新 ensureChatFolder 后重传 */
    val toChat: Boolean = false,
    /** content://… 或 file://…(私有副本) */
    val sourceUri: String,
    /** 来源是 App 自己的副本:任务完成 / 取消 / 移除后删掉 */
    val ownsSource: Boolean = false,
    /** 来源是持久化授权的 content URI:任务结束后释放授权 */
    val persistedGrant: Boolean = false,
    val name: String,
    val size: Long,
    /** 选择时来源的修改时间(可能拿不到);与 size 一起判断缓存的 sha256 是否还有效 */
    val lastModified: Long? = null,
    val mime: String? = null,
    val sha256: String? = null,
    val sessionId: String? = null,
    /** 会话创建时刻(epoch 毫秒):超过 24 小时后对象存储可能已清掉分片,连续 INTERNAL 时改为重建 */
    val sessionCreatedAt: Long? = null,
    val state: TransferState = TransferState.QUEUED,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    /** 瞬时错误的任务级重试次数(用户重试时清零) */
    val attempts: Int = 0,
    /** 自动重建会话的次数(定稿撞名、会话过期、文件夹失效等),上限 3 */
    val autoRestarts: Int = 0,
    /** 退避:早于这个时刻(epoch 毫秒)不运行 */
    val nextAttemptAt: Long = 0,
    /** 完成后的节点;秒传为 true 时节点由 initUpload 直接返回 */
    val nodeId: String? = null,
    /** 服务端最终的名字(同名时会自动改成 "名字 (1).ext") */
    val finalName: String? = null,
    val instant: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val finishedAt: Long? = null,
)
