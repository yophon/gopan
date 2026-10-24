package xyz.yophon.gopan.core.transfer

import androidx.compose.runtime.Immutable

/**
 * 上传 / 下载任务的持久化状态(Room 按名字存)。
 * - QUEUED:等槽位、等网络或等退避结束([nextAttemptAt]);
 * - RUNNING:正在跑。进程被杀后重启时一律回到 QUEUED(续传靠服务端会话 / .part 实际长度对账,不信本地进度);
 * - PAUSED:用户暂停,保留上传会话与 .part;
 * - FAILED:需要用户处理(确定性错误,或瞬时错误重试次数用完);
 * - DONE / CANCELED:终态。CANCELED 的行在尽力 abort 之后删除。
 */
enum class TransferState { QUEUED, RUNNING, PAUSED, FAILED, DONE, CANCELED }

/** 上传在 RUNNING 里的细分阶段,只在内存里(给进度条和文案用) */
enum class UploadPhase { HASHING, PREPARING, UPLOADING, VERIFYING }

/** 下载在 RUNNING 里的细分阶段,只在内存里 */
enum class DownloadPhase { RESOLVING, EXPANDING, DOWNLOADING, VERIFYING, SAVING }

enum class DownloadKind {
    /** 单个文件:预签名 GET + Range 续传 */
    FILE,

    /** 文件夹:客户端展开目录树,子文件各是一个 FILE 任务(batchId 指向本行) */
    FOLDER,

    /** 打包下载:GET /pack 流式 zip(不能续传,≤ 2 GiB) */
    PACK,
}

enum class DownloadDest {
    /** 系统「下载」目录下的 gopan/(Android 10+ 走 MediaStore,8–9 写公共目录) */
    DOWNLOADS,

    /** 用户在设置里选的文件夹(SAF 目录树) */
    TREE,

    /** 下到缓存,完成后交给其它应用打开 */
    OPEN_WITH,

    /** 离线可用(doc/26):存进 App 自己的目录,由离线模块登记 */
    OFFLINE,
}

/** 任务的统一标识:上传与下载各自一张表,id 只在表内唯一 */
sealed interface TaskKey {
    val id: Long

    data class Upload(override val id: Long) : TaskKey

    data class Download(override val id: Long) : TaskKey
}

/** 运行中任务的实时进度(内存,节流后发射) */
@Immutable
data class LiveProgress(
    /** UploadPhase / DownloadPhase 的名字 */
    val phase: String,
    val doneBytes: Long,
    val totalBytes: Long,
    /** 最近 3 秒的平均速度;不在传字节的阶段为 0 */
    val bytesPerSecond: Long = 0,
) {
    val fraction: Float
        get() = if (totalBytes <= 0) 0f else (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
}
