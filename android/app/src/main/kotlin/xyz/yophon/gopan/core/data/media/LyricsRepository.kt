package xyz.yophon.gopan.core.data.media

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.yophon.gopan.core.AppConfig
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** 一行歌词:t 秒(纯文本歌词为 -1) */
@Immutable
@Serializable
data class LyricLine(val t: Double, val text: String)

@Immutable
@Serializable
data class Lyrics(val synced: Boolean, val lines: List<LyricLine>)

/**
 * 歌词(doc/31 二):服务端把歌旁边同名的 .lrc 解析好,`GET /lrc/{nodeId}` 回 JSON,没有歌词 404。
 * 走播放器的客户端(本站 /lrc/ 带 Bearer、401 刷新一次)。按歌缓存,没有歌词也记住,来回翻页不重复查。
 */
@Singleton
class LyricsRepository @Inject constructor(
    @Named(MediaModule.MEDIA_CLIENT) private val client: OkHttpClient,
    config: AppConfig,
) {
    private val base = config.baseUrl.trimEnd('/')
    private val cache = object : LinkedHashMap<String, Lyrics?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics?>?) = size > 64
    }

    /** 没有歌词、没网、出错都回 null(不缓存出错的,下次再试) */
    suspend fun lyrics(nodeId: String): Lyrics? {
        synchronized(cache) { if (cache.containsKey(nodeId)) return cache[nodeId] }
        return withContext(Dispatchers.IO) {
            val req = Request.Builder().url("$base/lrc/$nodeId").get().build()
            try {
                client.newCall(req).execute().use { res ->
                    when {
                        res.code == 404 -> null.also { remember(nodeId, null) }
                        !res.isSuccessful -> null
                        else -> json.decodeFromString<Lyrics>(res.body?.string().orEmpty())
                            .takeIf { it.lines.isNotEmpty() }
                            .also { remember(nodeId, it) }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            }
        }
    }

    private fun remember(nodeId: String, value: Lyrics?) {
        synchronized(cache) { cache[nodeId] = value }
    }

    fun clear() {
        synchronized(cache) { cache.clear() }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
