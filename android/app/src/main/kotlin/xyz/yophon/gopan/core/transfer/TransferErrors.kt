package xyz.yophon.gopan.core.transfer

import android.os.SystemClock
import xyz.yophon.gopan.core.network.GopanException

/** 时间源:[now] 是墙上时间(落库、退避),[elapsed] 是单调时间(URL 新鲜度,不受改时钟影响) */
interface TransferClock {
    fun now(): Long

    fun elapsed(): Long
}

object SystemTransferClock : TransferClock {
    override fun now(): Long = System.currentTimeMillis()

    override fun elapsed(): Long = SystemClock.elapsedRealtime()
}

/** 传输引擎本地判定的错误码与中文文案(服务端错误码见 GopanErrors) */
object TransferErrors {
    const val SOURCE_GONE = "SOURCE_GONE"
    const val SOURCE_UNREADABLE = "SOURCE_UNREADABLE"
    const val EMPTY_FILE = "EMPTY_FILE"
    const val FILE_TOO_LARGE = "FILE_TOO_LARGE"
    const val STORAGE_SIGNATURE = "STORAGE_SIGNATURE"
    const val STORAGE_REJECTED = "STORAGE_REJECTED"
    const val DELETED_AFTER_UPLOAD = "DELETED_AFTER_UPLOAD"

    const val GONE = "GONE"
    const val NOT_A_FILE = "NOT_A_FILE"
    const val STORAGE_MISSING = "STORAGE_MISSING"
    const val CHECKSUM = "CHECKSUM"
    const val NO_SPACE = "NO_SPACE"
    const val SAVE_FAILED = "SAVE_FAILED"
    const val NEED_STORAGE_PERMISSION = "NEED_STORAGE_PERMISSION"
    const val PACK_TOO_LARGE = "PACK_TOO_LARGE"
    const val PACK_BUSY = "PACK_BUSY"
    const val PACK_CORRUPT = "PACK_CORRUPT"
    const val PACK_REJECTED = "PACK_REJECTED"

    fun sourceGone() = GopanException(SOURCE_GONE, "找不到要上传的文件(可能已被删除、移走,或读取授权已失效)")

    fun sourceUnreadable() = GopanException(SOURCE_UNREADABLE, "没有读取这个文件的权限,请重新选择")

    fun emptyFile() = GopanException(EMPTY_FILE, "不能上传空文件")

    fun fileTooLarge() = GopanException(FILE_TOO_LARGE, "文件太大,超过了对象存储的分片上限")

    fun signature(s3Code: String?) =
        GopanException(STORAGE_SIGNATURE, "对象存储拒绝了签名(${s3Code ?: "403"}),可能是服务器反代配置有误")

    fun storageRejected(status: Int, s3Code: String?) =
        GopanException(STORAGE_REJECTED, "对象存储拒绝了请求(HTTP $status${s3Code?.let { " $it" }.orEmpty()})")

    fun deletedAfterUpload() = GopanException(DELETED_AFTER_UPLOAD, "文件已上传,但随后被删除了")

    fun gone() = GopanException(GONE, "文件已不存在(已删除或移到回收站)")

    fun notAFile() = GopanException(NOT_A_FILE, "这不是可下载的文件")

    fun storageMissing() = GopanException(STORAGE_MISSING, "对象存储里找不到这个文件的内容")

    fun checksum() = GopanException(CHECKSUM, "下载的内容校验失败")

    fun noSpace() = GopanException(NO_SPACE, "手机存储空间不足")

    fun saveFailed(detail: String?) = GopanException(SAVE_FAILED, "保存文件失败" + (detail?.let { ":$it" } ?: ""))

    fun needStoragePermission() = GopanException(NEED_STORAGE_PERMISSION, "需要存储权限才能保存到「下载」目录")

    fun packTooLarge() = GopanException(PACK_TOO_LARGE, "打包内容超过 2GB 上限,请改用「下载」逐个保存")

    fun packCorrupt() = GopanException(PACK_CORRUPT, "打包下载的压缩包不完整")

    fun packRejected(status: Int, message: String?) =
        GopanException(PACK_REJECTED, message?.takeIf { it.isNotBlank() } ?: "打包下载失败(HTTP $status)")
}
