package xyz.yophon.gopan.core.data.media

import android.content.ContextWrapper
import com.apollographql.apollo.ApolloClient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import xyz.yophon.gopan.core.AppConfig
import xyz.yophon.gopan.core.data.PreviewKind
import xyz.yophon.gopan.core.data.preview.DefaultPreviewRepository
import xyz.yophon.gopan.core.network.buildApolloClient
import xyz.yophon.gopan.core.network.buildOkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** 协议层:真正的 Apollo 客户端打 MockWebServer,验证影音的 operations 与 schema 对得上、变量与映射 */
class ApolloMediaRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var apollo: ApolloClient
    private lateinit var repo: ApolloMediaRepository

    private val requests = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    private val handlers = ConcurrentHashMap<String, (JsonObject) -> String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val op = body["operationName"]?.jsonPrimitive?.content.orEmpty()
                val variables = body["variables"]?.jsonObject ?: JsonObject(emptyMap())
                requests += op to variables
                val handler = handlers[op] ?: return MockResponse().setResponseCode(400)
                return MockResponse().setHeader("Content-Type", "application/json").setBody(handler(variables))
            }
        }
        server.start()
        apollo = buildApolloClient(server.url("/query").toString(), buildOkHttpClient(CookieJar.NO_COOKIES))
        repo = ApolloMediaRepository(apollo)
    }

    @After
    fun tearDown() {
        apollo.close()
        server.shutdown()
    }

    @Test
    fun `playlist maps the folder's files in order`() = runBlocking<Unit> {
        handlers["Playlist"] = {
            """{"data":{"playlist":[${nodeJson("e2", "第2集.mp4", "VIDEO")},${nodeJson("e10", "第10集.mp4", "VIDEO")}]}}"""
        }
        val list = repo.playlist("e2")
        assertThat(requests.single().second["nodeId"]?.jsonPrimitive?.content).isEqualTo("e2")
        assertThat(list.map { it.id }).containsExactly("e2", "e10").inOrder()
        assertThat(list[0].name).isEqualTo("第2集.mp4")
        assertThat(list[0].preview.kind).isEqualTo(PreviewKind.VIDEO)
    }

    @Test
    fun `recent plays carry the node and its progress`() = runBlocking<Unit> {
        handlers["RecentPlays"] = {
            """{"data":{"recentPlays":[{"__typename":"RecentPlay","node":${nodeJson("v", "电影.mkv", "VIDEO")},""" +
                """"progress":${progressJson(1395.0, 6120.0)}}]}}"""
        }
        val list = repo.recentPlays()
        assertThat(requests.single().second["limit"]?.jsonPrimitive?.content).isEqualTo("20")
        assertThat(list.single().node.id).isEqualTo("v")
        assertThat(list.single().progress).isEqualTo(PlayProgress(1395.0, 6120.0, false, "2026-10-20T08:00:00Z"))
    }

    @Test
    fun `save progress sends seconds and accepts a null answer`() = runBlocking<Unit> {
        handlers["SaveProgress"] = { vars ->
            if (vars["nodeId"]?.jsonPrimitive?.content == "short") """{"data":{"saveProgress":null}}"""
            else """{"data":{"saveProgress":${progressJson(30.5, 600.0)}}}"""
        }
        assertThat(repo.saveProgress("v", 30.5, 600.0)).isEqualTo(PlayProgress(30.5, 600.0, false, "2026-10-20T08:00:00Z"))
        val vars = requests.first().second
        assertThat(vars["position"]?.jsonPrimitive?.content).isEqualTo("30.5")
        assertThat(vars["duration"]?.jsonPrimitive?.content).isEqualTo("600.0")
        assertThat(repo.saveProgress("short", 3.0, 120.0)).isNull()
    }

    @Test
    fun `clear progress calls the mutation`() = runBlocking<Unit> {
        handlers["ClearProgress"] = { """{"data":{"clearProgress":true}}""" }
        repo.clearProgress("v")
        assertThat(requests.single()).isEqualTo("ClearProgress" to JsonObject(mapOf("nodeId" to kotlinx.serialization.json.JsonPrimitive("v"))))
    }

    @Test
    fun `the single node preview brings subtitles and progress`() = runBlocking<Unit> {
        handlers["NodePreview"] = {
            """{"data":{"node":{"__typename":"Node","id":"v","parentId":"p","name":"电影.mkv","kind":"FILE","size":10,"mime":"video/x-matroska",""" +
                """"sha256":"abc","updatedAt":"2026-10-14T00:00:00Z","preview":{"__typename":"PreviewInfo","kind":"VIDEO","thumbUrl":null,""" +
                """"largeUrl":"https://s3/poster","contentUrl":"https://s3/v","status":null,"durationSec":6120,"subtitles":[""" +
                """{"__typename":"SubtitleTrack","nodeId":"s1","name":"电影.zh.srt","label":"简体中文","lang":"zh-Hans","default":true},""" +
                """{"__typename":"SubtitleTrack","nodeId":"s2","name":"电影.en.srt","label":"English","lang":"en","default":false}]},""" +
                """"playProgress":${progressJson(1395.0, 6120.0)}}}}"""
        }
        val previews = DefaultPreviewRepository(
            ContextWrapper(null),
            apollo,
            OkHttpClient(),
            AppConfig(baseUrl = "https://drive.example.com", idIssuer = "https://id.example.com", idClientId = "c", idRedirectUri = "x:/cb"),
        )
        val info = previews.preview("v")
        assertThat(info.subtitles).containsExactly(
            SubtitleTrack("s1", "电影.zh.srt", "简体中文", "zh-Hans", true, "https://drive.example.com/sub/s1"),
            SubtitleTrack("s2", "电影.en.srt", "English", "en", false, "https://drive.example.com/sub/s2"),
        ).inOrder()
        assertThat(info.progress).isEqualTo(PlayProgress(1395.0, 6120.0, false, "2026-10-20T08:00:00Z"))
    }

    private fun progressJson(position: Double, duration: Double, finished: Boolean = false) =
        """{"__typename":"PlayProgress","position":$position,"duration":$duration,"finished":$finished,"updatedAt":"2026-10-20T08:00:00Z"}"""

    private fun nodeJson(id: String, name: String, previewKind: String): String =
        """{"__typename":"Node","id":"$id","parentId":"p","name":"$name","kind":"FILE","size":10,"mime":null,""" +
            """"createdAt":"2026-10-13T00:00:00Z","updatedAt":"2026-10-14T00:00:00Z","deletedAt":null,"purgeAt":null,""" +
            """"subtreeBytes":null,"subtreeCount":null,"statsStale":null,"preview":{"__typename":"PreviewInfo","kind":"$previewKind","thumbUrl":null}}"""
}
