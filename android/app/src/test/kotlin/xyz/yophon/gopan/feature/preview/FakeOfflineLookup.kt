package xyz.yophon.gopan.feature.preview

import xyz.yophon.gopan.core.offline.LocalCopy
import xyz.yophon.gopan.core.offline.OfflineLookup

/** 预览单测用的离线副本:按节点 id 放副本,[online] 切换有没有网 */
class FakeOfflineLookup : OfflineLookup {
    val copies = mutableMapOf<String, LocalCopy>()
    var online = true

    override suspend fun localCopy(nodeId: String): LocalCopy? = copies[nodeId]

    override fun isOnline(): Boolean = online
}
