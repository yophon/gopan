package xyz.yophon.gopan.core.offline

import xyz.yophon.gopan.core.transfer.NetworkMonitor
import javax.inject.Inject
import javax.inject.Singleton

/** 预览与播放用(doc/26):某个文件在手机上的副本、现在有没有网。接口是为了预览页单测能换成假实现 */
interface OfflineLookup {
    suspend fun localCopy(nodeId: String): LocalCopy?

    fun isOnline(): Boolean
}

/** 副本的 file: 地址(java.net.URI 的写法,路径里的空格等已转义):ExoPlayer(DefaultDataSource)、文本与 PDF 预览按它读,图片转回 File 交给 Coil */
val LocalCopy.uri: String get() = file.toURI().toString()

@Singleton
class DefaultOfflineLookup @Inject constructor(
    private val offline: OfflineRepository,
    private val network: NetworkMonitor,
) : OfflineLookup {
    override suspend fun localCopy(nodeId: String): LocalCopy? = offline.localCopy(nodeId)

    override fun isOnline(): Boolean = network.isOnline()
}
