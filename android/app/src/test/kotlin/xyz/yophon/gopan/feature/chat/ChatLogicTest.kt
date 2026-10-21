package xyz.yophon.gopan.feature.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.NodeKind
import xyz.yophon.gopan.core.data.NodePreview
import xyz.yophon.gopan.core.data.PreviewKind
import xyz.yophon.gopan.core.data.chat.ChatMessage
import xyz.yophon.gopan.core.share.IncomingShareStore
import xyz.yophon.gopan.core.transfer.SourceStore
import xyz.yophon.gopan.core.transfer.TransferState
import xyz.yophon.gopan.core.transfer.db.UploadTaskEntity
import java.time.LocalDate
import java.time.ZoneOffset

/** 消息 id 用可比较的串模拟 uuid v7 的字典序 */
fun msg(id: String, body: String = "t$id", at: Long = id.toLong() * 1_000, node: Node? = null, source: String = "web") =
    ChatMessage(id = id.padStart(6, '0'), body = body, createdAt = "", createdAtMillis = at, source = source, node = node)

fun image(id: String) = Node(
    id = "n$id",
    name = "$id.jpg",
    kind = NodeKind.FILE,
    preview = NodePreview(PreviewKind.IMAGE, hasThumbnail = true),
)

class ChatLogicTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 14)
    private val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `merge replaces local copies with server copies and keeps older history`() {
        val local = listOf(msg("1"), msg("2"), msg("3", body = "old"))
        val latest = listOf(msg("2"), msg("3", body = "new"), msg("4"))

        val (merged, added) = ChatLogic.mergeLatest(local, latest, emptySet(), pageLimit = 3)

        assertThat(merged.map { it.id }).containsExactly("000001", "000002", "000003", "000004").inOrder()
        assertThat(merged.first { it.id == "000003" }.body).isEqualTo("new")
        assertThat(added).isEqualTo(1)
    }

    @Test
    fun `merge drops messages deleted elsewhere inside the latest page range`() {
        val local = listOf(msg("1"), msg("5"), msg("6"), msg("7"))
        // 最新页(满页)覆盖 5..8,6 不在里面:别端删了;1 在范围外,保留
        val latest = listOf(msg("5"), msg("7"), msg("8"))

        val (merged, _) = ChatLogic.mergeLatest(local, latest, emptySet(), pageLimit = 3)

        assertThat(merged.map { it.id }).containsExactly("000001", "000005", "000007", "000008").inOrder()
    }

    @Test
    fun `partial latest page covers the whole conversation`() {
        val local = listOf(msg("1"), msg("2"))
        val (merged, _) = ChatLogic.mergeLatest(local, listOf(msg("2")), emptySet(), pageLimit = 200)
        assertThat(merged.map { it.id }).containsExactly("000002")

        val (cleared, _) = ChatLogic.mergeLatest(local, emptyList(), emptySet(), pageLimit = 200)
        assertThat(cleared).isEmpty()
    }

    @Test
    fun `tombstones keep stale poll results from bringing deleted messages back`() {
        val (merged, added) = ChatLogic.mergeLatest(listOf(msg("1")), listOf(msg("1"), msg("2")), setOf("000002"), pageLimit = 200)
        assertThat(merged.map { it.id }).containsExactly("000001")
        assertThat(added).isEqualTo(0)
    }

    @Test
    fun `media within two minutes on the same day are grouped, nine at most`() {
        val base = todayStart + 3_600_000
        val media = (1..11).map { i -> msg("$i", body = "", at = base + i * 10_000L, node = image("$i")) }
        val later = msg("20", body = "", at = base + 11 * 10_000L + 3 * 60_000L, node = image("20"))
        val text = msg("21", body = "hello", at = later.createdAtMillis + 1_000)

        val items = ChatLogic.buildItems(media + later + text, emptyList(), emptyList(), zone, today)

        assertThat(items[0]).isInstanceOf(ChatItem.DayHeader::class.java)
        assertThat((items[0] as ChatItem.DayHeader).label).isEqualTo("今天")
        assertThat((items[1] as ChatItem.MediaGroup).messages).hasSize(9)
        assertThat((items[2] as ChatItem.MediaGroup).messages).hasSize(2)
        // 隔了 3 分钟:单独一条
        assertThat(items[3]).isInstanceOf(ChatItem.Message::class.java)
        assertThat(items[4]).isInstanceOf(ChatItem.Message::class.java)
    }

    @Test
    fun `day headers use today, yesterday, month-day and full date`() {
        assertThat(ChatLogic.dayLabel(today, today)).isEqualTo("今天")
        assertThat(ChatLogic.dayLabel(today.minusDays(1), today)).isEqualTo("昨天")
        assertThat(ChatLogic.dayLabel(LocalDate.of(2026, 3, 5), today)).isEqualTo("3月5日")
        assertThat(ChatLogic.dayLabel(LocalDate.of(2025, 12, 31), today)).isEqualTo("2025-12-31")
    }

    @Test
    fun `pending texts and upload cards come after messages`() {
        val p = PendingText("pending-1", "hi", todayStart)
        val card = UploadCard(upload(1, toChat = true, state = TransferState.RUNNING), null)
        val items = ChatLogic.buildItems(listOf(msg("1", at = todayStart)), listOf(p), listOf(card), zone, today)
        assertThat(items.map { it::class.simpleName }).containsExactly("DayHeader", "Message", "Pending", "Upload").inOrder()
    }

    @Test
    fun `links stop before trailing punctuation`() {
        val text = "看这个 https://example.com/a?b=1。还有(http://x.io/p)!"
        val links = ChatLogic.findLinks(text).map { text.substring(it.first, it.last + 1) }
        assertThat(links).containsExactly("https://example.com/a?b=1", "http://x.io/p").inOrder()
        assertThat(ChatLogic.findLinks("ftp://no and mailto:a@b")).isEmpty()
    }

    @Test
    fun `utf8 length counts chinese as three bytes`() {
        assertThat(ChatLogic.utf8Length("abc")).isEqualTo(3)
        assertThat(ChatLogic.utf8Length("中文")).isEqualTo(6)
    }

    @Test
    fun `latest text skips files and empty bodies`() {
        val messages = listOf(msg("1", body = "first"), msg("2", body = "", node = image("2")), msg("3", body = ""))
        assertThat(ChatLogic.latestText(messages)).isEqualTo("first")
    }

    @Test
    fun `source labels`() {
        assertThat(ChatLogic.sourceLabel("mcp")).isEqualTo("Agent")
        assertThat(ChatLogic.sourceLabel("webdav")).isEqualTo("WebDAV")
        assertThat(ChatLogic.sourceLabel("fetch")).isEqualTo("离线下载")
        assertThat(ChatLogic.sourceLabel("web")).isNull()
        assertThat(ChatLogic.sourceLabel("android")).isNull()
    }

    @Test
    fun `upload cards show chat uploads and linger until the message appears`() {
        val now = 100_000L
        val running = upload(1, toChat = true, state = TransferState.RUNNING)
        val doneNoMsg = upload(2, toChat = true, state = TransferState.DONE, nodeId = "nA", finishedAt = now - 1_000)
        val doneWithMsg = upload(3, toChat = true, state = TransferState.DONE, nodeId = "nB", finishedAt = now - 1_000)
        val doneOld = upload(4, toChat = true, state = TransferState.DONE, nodeId = "nC", finishedAt = now - 60_000)
        val driveUpload = upload(5, toChat = false, parentId = "drive-folder", state = TransferState.RUNNING)
        val toChatFolderFromDrive = upload(6, toChat = false, parentId = "chat-folder", state = TransferState.QUEUED)

        val cards = ChatLogic.uploadCards(
            listOf(running, doneNoMsg, doneWithMsg, doneOld, driveUpload, toChatFolderFromDrive),
            emptyMap(),
            chatFolderId = "chat-folder",
            messageNodeIds = setOf("nB"),
            now = now,
        )

        assertThat(cards.map { it.task.id }).containsExactly(1L, 2L, 6L).inOrder()
    }

    @Test
    fun `share text drops parts contained in others`() {
        assertThat(IncomingShareStore.shareText("标题", "标题 https://a.b")).isEqualTo("标题 https://a.b")
        assertThat(IncomingShareStore.shareText("同一句", "同一句")).isEqualTo("同一句")
        assertThat(IncomingShareStore.shareText("A", "B")).isEqualTo("A\nB")
        assertThat(IncomingShareStore.shareText(null, "  ")).isNull()
    }

    @Test
    fun `file names are sanitized for local storage`() {
        assertThat(SourceStore.sanitizeFileName("a/b:c*?.txt")).isEqualTo("a_b_c__.txt")
        assertThat(SourceStore.sanitizeFileName("..hidden")).isEqualTo("hidden")
        assertThat(SourceStore.sanitizeFileName("   ")).isEqualTo("未命名")
        val long = "名".repeat(120) + ".pdf"
        val cut = SourceStore.sanitizeFileName(long)
        assertThat(cut.toByteArray().size).isAtMost(200)
        assertThat(cut).endsWith(".pdf")
    }

    private fun upload(
        id: Long,
        toChat: Boolean,
        state: TransferState,
        parentId: String? = "chat-folder",
        nodeId: String? = null,
        finishedAt: Long? = null,
    ) = UploadTaskEntity(
        id = id,
        userId = "u",
        parentId = parentId,
        toChat = toChat,
        sourceUri = "mem://$id",
        name = "f$id.bin",
        size = 10,
        state = state,
        nodeId = nodeId,
        finishedAt = finishedAt,
        createdAt = 0,
        updatedAt = 0,
    )
}
