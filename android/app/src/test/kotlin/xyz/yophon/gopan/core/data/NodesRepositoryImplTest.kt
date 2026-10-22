package xyz.yophon.gopan.core.data

import com.apollographql.apollo.ApolloClient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CookieJar
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.auth.UserSummary
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.buildApolloClient
import xyz.yophon.gopan.core.network.buildOkHttpClient
import xyz.yophon.gopan.core.prefs.DriveSortOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** 协议层:真正的 Apollo 客户端打 MockWebServer,验证变量、映射、缓存回退与面包屑解析 */
class NodesRepositoryImplTest {

    private lateinit var server: MockWebServer
    private lateinit var apollo: ApolloClient
    private lateinit var cache: InMemoryListingCache
    private lateinit var repo: NodesRepositoryImpl

    /** (operationName, variables) */
    private val requests = CopyOnWriteArrayList<Pair<String, JsonObject>>()

    /** operationName → 响应体 */
    private val handlers = ConcurrentHashMap<String, (JsonObject) -> String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path != "/query") return MockResponse().setResponseCode(404)
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val op = body["operationName"]?.jsonPrimitive?.content.orEmpty()
                val variables = body["variables"]?.jsonObject ?: JsonObject(emptyMap())
                requests += op to variables
                val handler = handlers[op] ?: return MockResponse().setResponseCode(400).setBody("""{"errors":[{"message":"no handler for $op"}]}""")
                return MockResponse().setHeader("Content-Type", "application/json").setBody(handler(variables))
            }
        }
        server.start()
        apollo = buildApolloClient(server.url("/query").toString(), buildOkHttpClient(CookieJar.NO_COOKIES))
        cache = InMemoryListingCache()
        val session = SessionStore().apply {
            setSignedIn("token", UserSummary(id = "u1", username = "alice", quotaBytes = 1, usedBytes = 0, isAdmin = false))
        }
        repo = NodesRepositoryImpl(apollo, cache, session)
    }

    @After
    fun tearDown() {
        apollo.close()
        server.shutdown()
    }

    @Test
    fun `firstPage sends the variables, maps the fragment and writes the cache`() = runBlocking {
        handlers["Children"] = {
            page(
                nodeJson("f1", "照片", kind = "FOLDER", subtreeBytes = 2048, subtreeCount = 3, statsStale = true),
                nodeJson("a1", "cat.jpg", size = 1234, mime = "image/jpeg", previewKind = "IMAGE", thumbUrl = "https://s3/thumb?sig=1"),
                nextCursor = "c2",
                total = 42,
            )
        }

        val listing = repo.firstPage(null, DriveSortOrder.SIZE, desc = true)

        val (op, vars) = requests.single()
        assertThat(op).isEqualTo("Children")
        assertThat(vars["parentId"]?.jsonPrimitive?.contentOrNull).isNull()
        assertThat(vars["order"]?.jsonPrimitive?.content).isEqualTo("SIZE")
        assertThat(vars["desc"]?.jsonPrimitive?.boolean).isTrue()

        assertThat(listing.fromCache).isFalse()
        assertThat(listing.page.nextCursor).isEqualTo("c2")
        assertThat(listing.page.total).isEqualTo(42)
        val (folder, image) = listing.page.items
        assertThat(folder.kind).isEqualTo(NodeKind.FOLDER)
        assertThat(folder.isFolder).isTrue()
        assertThat(folder.subtreeBytes).isEqualTo(2048)
        assertThat(folder.subtreeCount).isEqualTo(3)
        assertThat(folder.statsStale).isTrue()
        assertThat(image.size).isEqualTo(1234)
        assertThat(image.mime).isEqualTo("image/jpeg")
        assertThat(image.preview).isEqualTo(NodePreview(PreviewKind.IMAGE, hasThumbnail = true, thumbUrl = "https://s3/thumb?sig=1"))
        assertThat(image.hasThumb).isTrue()
        assertThat(image.imageCacheKey).isEqualTo("a1@2026-10-14T00:00:00Z")

        val cached = cache.get("u1", null)
        assertThat(cached).isNotNull()
        assertThat(cached!!.items).isEqualTo(listing.page.items)
        assertThat(cached.order).isEqualTo(DriveSortOrder.SIZE)
        assertThat(cached.desc).isTrue()
        assertThat(cached.total).isEqualTo(42)
    }

    @Test
    fun `firstPage falls back to the cache when the server is unreachable and re-sorts locally`() = runBlocking {
        val small = node("s", "small.txt", size = 1)
        val big = node("b", "big.bin", size = 999)
        val dir = node("d", "dir", kind = "FOLDER")
        cache.put("u1", "p", CachedListing(listOf(big, dir, small), total = 3, order = DriveSortOrder.NAME, desc = false, fetchedAt = 123L))
        server.shutdown()

        val listing = repo.firstPage("p", DriveSortOrder.SIZE, desc = true)

        assertThat(listing.fromCache).isTrue()
        assertThat(listing.fetchedAt).isEqualTo(123L)
        assertThat(listing.error?.isNetwork).isTrue()
        assertThat(listing.page.nextCursor).isNull()
        assertThat(listing.page.items.map { it.id }).containsExactly("d", "b", "s").inOrder()
    }

    @Test
    fun `cachedFirstPage reads the cache without touching the network`() = runBlocking {
        val small = node("s", "small.txt", size = 1)
        val big = node("b", "big.bin", size = 999)
        cache.put("u1", "p", CachedListing(listOf(small, big), total = 2, order = DriveSortOrder.NAME, desc = false, fetchedAt = 7L))

        val listing = repo.cachedFirstPage("p", DriveSortOrder.SIZE, desc = true)

        assertThat(listing?.fromCache).isTrue()
        assertThat(listing?.error).isNull()
        assertThat(listing?.fetchedAt).isEqualTo(7L)
        assertThat(listing?.page?.items?.map { it.id }).containsExactly("b", "s").inOrder()
        assertThat(repo.cachedFirstPage("never-cached", DriveSortOrder.NAME, false)).isNull()
        assertThat(requests).isEmpty()
    }

    @Test
    fun `firstPage does not fall back for NOT_FOUND or when nothing is cached`() = runBlocking {
        cache.put("u1", "gone", CachedListing(listOf(node("x", "x")), 1, DriveSortOrder.NAME, false, 1L))
        handlers["Children"] = { """{"errors":[{"message":"对象不存在","extensions":{"code":"NOT_FOUND"}}],"data":null}""" }

        val notFound = runCatching { repo.firstPage("gone", DriveSortOrder.NAME, false) }.exceptionOrNull() as GopanException
        assertThat(notFound.code).isEqualTo(GopanErrors.NOT_FOUND)

        server.shutdown()
        val offline = runCatching { repo.firstPage("never-cached", DriveSortOrder.NAME, false) }.exceptionOrNull() as GopanException
        assertThat(offline.isNetwork).isTrue()
    }

    @Test
    fun `ancestors uses folders seen in listings, then the cache, then node queries`() = runBlocking {
        handlers["Children"] = { vars ->
            when (vars["parentId"]?.jsonPrimitive?.contentOrNull) {
                null -> page(nodeJson("a", "A", kind = "FOLDER"))
                "a" -> page(nodeJson("b", "B", kind = "FOLDER", parentId = "a"))
                else -> page()
            }
        }
        repo.firstPage(null, DriveSortOrder.NAME, false)
        repo.firstPage("a", DriveSortOrder.NAME, false)
        val before = requests.size

        assertThat(repo.ancestors("b").map { it.name }).containsExactly("A", "B").inOrder()
        assertThat(requests.size).isEqualTo(before) // 全部来自内存里见过的文件夹

        // c 没见过:查一次 Node,它的父 b 已知
        handlers["Node"] = { vars -> """{"data":{"node":${nodeJson(vars["id"]!!.jsonPrimitive.content, "C", kind = "FOLDER", parentId = "b")}}}""" }
        assertThat(repo.ancestors("c").map { it.name }).containsExactly("A", "B", "C").inOrder()
        assertThat(requests.map { it.first }.count { it == "Node" }).isEqualTo(1)
        // 第二次直接命中内存
        repo.ancestors("c")
        assertThat(requests.map { it.first }.count { it == "Node" }).isEqualTo(1)

        // 只在本地缓存页里出现过的文件夹:断网也能解析
        cache.put("u1", "zz", CachedListing(listOf(node("d", "D", kind = "FOLDER", parentId = "a")), 1, DriveSortOrder.NAME, false, 1L))
        server.shutdown()
        assertThat(repo.ancestors("d").map { it.name }).containsExactly("A", "D").inOrder()
    }

    @Test
    fun `search and trash map pages and forward cursors`() = runBlocking {
        handlers["SearchNodes"] = { page(nodeJson("s1", "report.pdf"), nextCursor = "n") }
        handlers["Trash"] = { page(nodeJson("t1", "old.txt", deletedAt = "2026-10-01T00:00:00Z", purgeAt = "2026-10-31T00:00:00Z")) }

        val search = repo.search("rep", "cur")
        assertThat(search.items.single().name).isEqualTo("report.pdf")
        assertThat(search.nextCursor).isEqualTo("n")
        val (_, searchVars) = requests.last()
        assertThat(searchVars["q"]?.jsonPrimitive?.content).isEqualTo("rep")
        assertThat(searchVars["cursor"]?.jsonPrimitive?.content).isEqualTo("cur")

        val trash = repo.trash(null)
        val item = trash.items.single()
        assertThat(item.deletedAt).isEqualTo("2026-10-01T00:00:00Z")
        assertThat(item.purgeAt).isEqualTo("2026-10-31T00:00:00Z")
    }

    @Test
    fun `searchContent maps hits with snippet, highlights and page and forwards the cursor`() = runBlocking {
        handlers["SearchContent"] = {
            """{"data":{"searchContent":{"__typename":"ContentHitPage","items":[""" +
                """{"__typename":"ContentHit","node":${nodeJson("p1", "合同.pdf", parentId = "dir", mime = "application/pdf", previewKind = "PDF")},""" +
                """"snippet":"…签订的租房合同,合同期","highlights":[{"__typename":"TextRange","start":6,"length":2},{"__typename":"TextRange","start":9,"length":2}],"page":3},""" +
                """{"__typename":"ContentHit","node":${nodeJson("t1", "笔记.txt")},"snippet":"合同","highlights":[{"__typename":"TextRange","start":0,"length":2}],"page":null}""" +
                """],"nextCursor":"n2"}}}"""
        }

        val page = repo.searchContent("合同", "c1")

        val (op, vars) = requests.single()
        assertThat(op).isEqualTo("SearchContent")
        assertThat(vars["q"]?.jsonPrimitive?.content).isEqualTo("合同")
        assertThat(vars["cursor"]?.jsonPrimitive?.content).isEqualTo("c1")
        assertThat(page.nextCursor).isEqualTo("n2")
        val (pdf, txt) = page.items
        assertThat(pdf.node.id).isEqualTo("p1")
        assertThat(pdf.node.parentId).isEqualTo("dir")
        assertThat(pdf.node.preview.kind).isEqualTo(PreviewKind.PDF)
        assertThat(pdf.snippet).isEqualTo("…签订的租房合同,合同期")
        assertThat(pdf.highlights).containsExactly(TextRange(6, 2), TextRange(9, 2)).inOrder()
        assertThat(pdf.page).isEqualTo(3)
        assertThat(txt.page).isNull()
    }

    @Test
    fun `searchContent errors keep the server code`() = runBlocking {
        handlers["SearchContent"] = {
            """{"errors":[{"message":"关键词太短:至少两个汉字或三个字母","path":["searchContent"],"extensions":{"code":"QUERY_TOO_SHORT"}}],"data":null}"""
        }
        val error = runCatching { repo.searchContent("合", null) }.exceptionOrNull() as GopanException
        assertThat(error.code).isEqualTo(GopanErrors.QUERY_TOO_SHORT)

        handlers["SearchContent"] = {
            """{"errors":[{"message":"本站没有开启内容搜索","path":["searchContent"],"extensions":{"code":"CONTENT_SEARCH_DISABLED"}}],"data":null}"""
        }
        val disabled = runCatching { repo.searchContent("合同", null) }.exceptionOrNull() as GopanException
        assertThat(disabled.code).isEqualTo(GopanErrors.CONTENT_SEARCH_DISABLED)
        assertThat(disabled.message).isEqualTo("本站没有开启内容搜索")
    }

    @Test
    fun `mutations forward ids and targets and bump the version`() = runBlocking {
        handlers["CreateFolder"] = { vars -> """{"data":{"createFolder":${nodeJson("nf", vars["name"]!!.jsonPrimitive.content, kind = "FOLDER", parentId = "p")}}}""" }
        handlers["RenameNode"] = { vars -> """{"data":{"renameNode":${nodeJson("nf", vars["name"]!!.jsonPrimitive.content, kind = "FOLDER", parentId = "p")}}}""" }
        handlers["MoveNodes"] = { """{"data":{"moveNodes":[]}}""" }
        handlers["CopyNodes"] = { """{"data":{"copyNodes":[]}}""" }
        handlers["DeleteNodes"] = { """{"data":{"deleteNodes":true}}""" }
        handlers["RestoreNodes"] = { """{"data":{"restoreNodes":[]}}""" }
        handlers["PurgeNodes"] = { """{"data":{"purgeNodes":true}}""" }
        handlers["PurgeTrash"] = { """{"data":{"purgeTrash":true}}""" }

        assertThat(repo.version.value).isEqualTo(0)
        val created = repo.createFolder("p", "新建")
        assertThat(created.name).isEqualTo("新建")
        assertThat(created.parentId).isEqualTo("p")
        assertThat(repo.version.value).isEqualTo(1)

        repo.rename("nf", "改名")
        repo.move(listOf("x", "y"), null)
        repo.copy(listOf("x"), "t")
        repo.delete(listOf("x"))
        repo.restore(listOf("x"))
        repo.purge(listOf("x"))
        repo.purgeTrash()
        assertThat(repo.version.value).isEqualTo(8)

        // 后台任务(离线下载、解压)往网盘里加了东西:不请求服务端,只让列表页回到前台时刷新
        val before = requests.size
        repo.markChanged()
        assertThat(repo.version.value).isEqualTo(9)
        assertThat(requests).hasSize(before)

        val move = requests.first { it.first == "MoveNodes" }.second
        assertThat(move["ids"]?.jsonArray?.map { it.jsonPrimitive.content }).containsExactly("x", "y").inOrder()
        assertThat(move.containsKey("targetParentId")).isTrue()
        assertThat(move["targetParentId"]?.jsonPrimitive?.contentOrNull).isNull()
        val copy = requests.first { it.first == "CopyNodes" }.second
        assertThat(copy["targetParentId"]?.jsonPrimitive?.content).isEqualTo("t")
    }

    @Test
    fun `a GraphQL error on a mutation becomes a GopanException with the server code and message`() = runBlocking {
        handlers["CreateFolder"] = { """{"errors":[{"message":"同名文件或文件夹已存在","path":["createFolder"],"extensions":{"code":"NAME_CONFLICT"}}],"data":null}""" }

        val error = runCatching { repo.createFolder(null, "dup") }.exceptionOrNull() as GopanException

        assertThat(error.code).isEqualTo(GopanErrors.NAME_CONFLICT)
        assertThat(error.message).isEqualTo("同名文件或文件夹已存在")
        assertThat(repo.version.value).isEqualTo(0)
    }

    // ---- helpers ----

    private fun node(id: String, name: String, kind: String = "FILE", size: Long? = 10, parentId: String? = null) =
        Node(id = id, parentId = parentId, name = name, kind = if (kind == "FOLDER") NodeKind.FOLDER else NodeKind.FILE, size = size, updatedAt = "2026-10-14T00:00:00Z")

    private fun page(vararg items: String, nextCursor: String? = null, total: Int = items.size): String =
        """{"data":{"${currentField()}":{"__typename":"NodePage","items":[${items.joinToString(",")}],"nextCursor":${nextCursor.q()},"total":$total}}}"""

    /** 当前请求的根字段名:按最近一次请求的 operationName 推 */
    private fun currentField(): String = when (requests.lastOrNull()?.first) {
        "SearchNodes" -> "searchNodes"
        "Trash" -> "trash"
        else -> "children"
    }

    private fun nodeJson(
        id: String,
        name: String,
        kind: String = "FILE",
        parentId: String? = null,
        size: Long? = 10,
        mime: String? = null,
        previewKind: String = "NONE",
        thumbUrl: String? = null,
        deletedAt: String? = null,
        purgeAt: String? = null,
        subtreeBytes: Long? = null,
        subtreeCount: Long? = null,
        statsStale: Boolean? = null,
    ): String = """{"__typename":"Node","id":"$id","parentId":${parentId.q()},"name":"$name","kind":"$kind","size":${size ?: "null"},""" +
        """"mime":${mime.q()},"createdAt":"2026-10-13T00:00:00Z","updatedAt":"2026-10-14T00:00:00Z","deletedAt":${deletedAt.q()},"purgeAt":${purgeAt.q()},""" +
        """"subtreeBytes":${subtreeBytes ?: "null"},"subtreeCount":${subtreeCount ?: "null"},"statsStale":${statsStale ?: "null"},""" +
        """"preview":{"__typename":"PreviewInfo","kind":"$previewKind","thumbUrl":${thumbUrl.q()}}}"""

    private fun String?.q(): String = if (this == null) "null" else "\"$this\""
}
