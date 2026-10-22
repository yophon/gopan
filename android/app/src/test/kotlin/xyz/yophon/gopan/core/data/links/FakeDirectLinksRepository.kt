package xyz.yophon.gopan.core.data.links

import kotlinx.coroutines.CompletableDeferred
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException

/** 内存版直链仓库:[links] 新的在前,按 [pageSize] 分页(游标是下一页起始下标);[failNext] 让下一次调用抛 NETWORK */
class FakeDirectLinksRepository : DirectLinksRepository {
    var enabled = true
    var links: List<DirectLink> = emptyList()
    var pageSize = 30
    var failNext = false
    val calls = mutableListOf<String>()

    /** 非空时 myLinks() 等它再回:测"先显示快照、联网还在路上"的中间态 */
    var linksGate: CompletableDeferred<Unit>? = null

    /** 已经删掉(查不到)的直链 id:link() 抛 NOT_FOUND */
    val missing = mutableSetOf<String>()

    /** 带拍摄位置的节点 id:create 返回 hasGps = true */
    val gpsNodes = mutableSetOf<String>()

    private fun maybeFail() {
        if (failNext) {
            failNext = false
            throw GopanException(GopanErrors.NETWORK, "网络连接失败,请检查网络后重试")
        }
    }

    override suspend fun enabled(): Boolean {
        calls += "enabled"
        maybeFail()
        return enabled
    }

    override suspend fun myLinks(cursor: String?): DirectLinkPage {
        calls += "myLinks($cursor)"
        linksGate?.await()
        maybeFail()
        val start = cursor?.toInt() ?: 0
        val end = minOf(links.size, start + pageSize)
        return DirectLinkPage(links.subList(start, end), if (end < links.size) end.toString() else null, links.size)
    }

    override suspend fun link(id: String): DirectLink {
        calls += "link($id)"
        maybeFail()
        if (id in missing) throw GopanException(GopanErrors.NOT_FOUND, "对象不存在或已被删除")
        return links.firstOrNull { it.id == id } ?: link(id, "hidden-$id")
    }

    override suspend fun create(nodeIds: List<String>): List<DirectLink> {
        calls += "create(${nodeIds.joinToString(",")})"
        maybeFail()
        // 服务端返回的顺序不保证:倒过来给,测试调用方会不会按原顺序排
        return nodeIds.reversed().map { id -> link("L$id", "$id.jpg", nodeId = id, hasGps = id in gpsNodes) }
    }

    override suspend fun pause(id: String, paused: Boolean): DirectLink {
        calls += "pause($id,$paused)"
        maybeFail()
        val updated = (links.firstOrNull { it.id == id } ?: link(id, id)).copy(
            status = if (paused) LinkStatus.PAUSED else LinkStatus.ACTIVE,
            pauseReason = if (paused) "manual" else null,
        )
        links = links.map { if (it.id == id) updated else it }
        return updated
    }

    override suspend fun revoke(id: String) {
        calls += "revoke($id)"
        maybeFail()
        if (id in missing) throw GopanException(GopanErrors.NOT_FOUND, "对象不存在或已被删除")
        links = links.filterNot { it.id == id }
    }

    companion object {
        fun link(
            id: String,
            name: String = "$id.png",
            nodeId: String = "node-$id",
            status: LinkStatus = LinkStatus.ACTIVE,
            hasGps: Boolean = false,
        ) = DirectLink(
            id = id,
            token = "T$id",
            url = "https://drive.example.com/x/T$id/$name",
            nodeId = nodeId,
            name = name,
            mime = if (name.endsWith(".pdf")) "application/pdf" else "image/jpeg",
            status = status,
            hasGps = hasGps,
        )
    }
}
