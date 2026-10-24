package xyz.yophon.gopan.core.transfer

import xyz.yophon.gopan.core.transfer.db.DownloadTaskEntity

/**
 * 落点是「离线可用」([DownloadDest.OFFLINE],doc/26)的下载:引擎把下完的文件交给它放到位、登记。
 * 接口放在传输这边,引擎不依赖离线模块(离线模块要用引擎入队)。
 */
interface OfflineSink {
    /** 已有同内容的离线副本就直接用(更新一轮时没变的文件不重下);返回它的路径 */
    suspend fun existing(userId: String, nodeId: String, sha256: String, size: Long): String?

    /**
     * 一个文件下完:[location] 是收件箱里的文件或已有副本的路径;[root] 是离线项那一行(单个文件就是任务自己)。
     * 返回放好后的路径;离线项已经移除、或者已经换了一轮时丢掉文件,返回 null。
     */
    suspend fun saved(task: DownloadTaskEntity, root: DownloadTaskEntity, location: String): String?

    /** 文件夹这一轮全部下完:这一轮里没有的文件(云端已经删了)清掉,记下完成时刻 */
    suspend fun roundDone(root: DownloadTaskEntity, nodeIds: Set<String>)
}
