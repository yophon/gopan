package xyz.yophon.gopan.core.data.media

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * 播放队列里的地址按需现取(doc/22 二.4):队列只放占位地址 `gopan-node://<nodeId>`,
 * 每首开播(以及拖动进度重新建连)时才换成预签名地址。预签名 15 分钟有效,取到的地址在 [ttlMs] 内复用,
 * 免得每次拖动都查一次;播放器拿到 403 时 [invalidate] 再取。
 * [fetch] 返回 null(没有地址:文件被删、不能预览)时抛 [IOException],播放器按加载失败处理。
 */
class MediaUrls(
    private val fetch: suspend (nodeId: String) -> String?,
    private val now: () -> Long,
    private val ttlMs: Long = TTL_MS,
) {
    private class Entry(val url: String, val at: Long)

    private val entries = HashMap<String, Entry>()
    private val mutex = Mutex()

    /** 同一时刻只查一个(下一首预加载与当前这首重新建连撞在一起时不重复查) */
    suspend fun resolve(nodeId: String): String = mutex.withLock {
        cached(nodeId)?.let { return it }
        val url = fetch(nodeId) ?: throw IOException("没有可播放的地址")
        seed(nodeId, url)
        url
    }

    private fun cached(nodeId: String): String? =
        synchronized(entries) { entries[nodeId]?.takeIf { now() - it.at < ttlMs }?.url }

    /** 预览页刚取到的地址:第一首不用再查一次 */
    fun seed(nodeId: String, url: String) {
        synchronized(entries) { entries[nodeId] = Entry(url, now()) }
    }

    fun invalidate(nodeId: String) {
        synchronized(entries) { entries.remove(nodeId) }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    companion object {
        /** 预签名 15 分钟;留 5 分钟余量 */
        const val TTL_MS = 10 * 60 * 1000L

        const val SCHEME = "gopan-node"
        private const val PREFIX = "$SCHEME://"

        /** 队列里的占位地址 */
        fun placeholder(nodeId: String): String = PREFIX + nodeId

        /** 占位地址里的节点 id;不是占位地址返回 null */
        fun nodeIdOf(uri: String): String? =
            uri.takeIf { it.startsWith(PREFIX) }?.substring(PREFIX.length)?.substringBefore('/')?.takeIf { it.isNotEmpty() }
    }
}
