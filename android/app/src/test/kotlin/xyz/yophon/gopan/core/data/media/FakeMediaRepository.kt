package xyz.yophon.gopan.core.data.media

import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException

/** 影音单测用:按节点给连播列表,记录上报与清除 */
class FakeMediaRepository : MediaRepository {
    val playlists = mutableMapOf<String, List<Node>>()
    var playlistError: GopanException? = null
    var recent: List<RecentPlay> = emptyList()
    var recentError: GopanException? = null
    var clearError: GopanException? = null
    var saveError: GopanException? = null
    val playlistCalls = mutableListOf<String>()
    val saved = mutableListOf<Triple<String, Double, Double>>()
    val cleared = mutableListOf<String>()

    override suspend fun playlist(nodeId: String): List<Node> {
        playlistCalls += nodeId
        playlistError?.let { throw it }
        return playlists[nodeId] ?: throw GopanException(GopanErrors.NOT_FOUND, "文件不存在")
    }

    override suspend fun recentPlays(limit: Int): List<RecentPlay> {
        recentError?.let { throw it }
        return recent.take(limit)
    }

    override suspend fun saveProgress(nodeId: String, position: Double, duration: Double): PlayProgress? {
        saved += Triple(nodeId, position, duration)
        saveError?.let { throw it }
        return null
    }

    override suspend fun clearProgress(nodeId: String) {
        cleared += nodeId
        clearError?.let { throw it }
    }
}
