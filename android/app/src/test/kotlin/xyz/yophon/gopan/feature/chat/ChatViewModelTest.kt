package xyz.yophon.gopan.feature.chat

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import xyz.yophon.gopan.core.data.chat.ChatMessage
import xyz.yophon.gopan.core.data.chat.ChatRepository
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.prefs.UserPreferencesRepository
import xyz.yophon.gopan.core.share.ShareInbox
import xyz.yophon.gopan.core.share.SharePayload
import xyz.yophon.gopan.core.share.ShareReceiving
import xyz.yophon.gopan.core.transfer.Connectivity
import xyz.yophon.gopan.feature.drive.FakeTransferActions
import xyz.yophon.gopan.feature.drive.InMemoryPreferencesDataStore
import xyz.yophon.gopan.feature.drive.MainDispatcherRule
import java.io.File
import xyz.yophon.gopan.feature.preview.PreviewSessionStore

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val repo = FakeChatRepository()
    private val transfers = FakeTransferActions()
    private val prefs = UserPreferencesRepository(InMemoryPreferencesDataStore())
    private val inbox = FakeShareInbox()
    private val net = object : Connectivity {
        override val online = MutableStateFlow(true)
    }
    private var vm: ChatViewModel? = null

    private fun viewModel() = ChatViewModel(repo, transfers, prefs, inbox, net, PreviewSessionStore()).also { vm = it }

    /**
     * 轮询循环跑在测试调度器上:测试体结束前必须停掉,否则 runTest 收尾时永远等不到空闲。
     * ViewModel 在 init 里启动的协程要等 runCurrent 才执行,所以测试里先建 VM、再准备假数据也一样。
     */
    private fun chatTest(block: suspend TestScope.(ChatViewModel) -> Unit) = runTest {
        val vm = viewModel()
        try {
            block(vm)
        } finally {
            vm.setVisible(false)
            runCurrent()
        }
    }

    @Test
    fun `polls only while visible and merges new messages`() = chatTest { vm ->
        repo.messages += listOf(msg("1"), msg("2"))
        vm.setVisible(true)
        runCurrent()
        assertThat(vm.state.value.messages.map { it.id }).containsExactly("000001", "000002").inOrder()
        assertThat(vm.state.value.loading).isFalse()

        repo.messages += msg("3")
        advanceTimeBy(ChatViewModel.POLL_INTERVAL_MS + 10)
        runCurrent()
        assertThat(vm.state.value.messages.map { it.id }).contains("000003")

        vm.setVisible(false)
        val calls = repo.latestCalls
        advanceTimeBy(10_000)
        runCurrent()
        assertThat(repo.latestCalls).isEqualTo(calls)
    }

    @Test
    fun `two failed refreshes mark the page offline`() = chatTest { vm ->
        repo.failLatest = true
        vm.setVisible(true)
        runCurrent()
        assertThat(vm.state.value.offline).isFalse()
        assertThat(vm.state.value.loadError).isNotNull()
        advanceTimeBy(ChatViewModel.POLL_INTERVAL_MS + 10)
        runCurrent()
        assertThat(vm.state.value.offline).isTrue()

        repo.failLatest = false
        advanceTimeBy(ChatViewModel.POLL_INTERVAL_MS + 10)
        runCurrent()
        assertThat(vm.state.value.offline).isFalse()
        assertThat(vm.state.value.loadError).isNull()
    }

    @Test
    fun `sending shows the text optimistically, then the server copy`() = chatTest { vm ->
        vm.setVisible(true)
        runCurrent()
        repo.holdSend = true
        vm.setDraft("  你好  ")
        vm.send()
        runCurrent()
        assertThat(vm.draft.value).isEmpty()
        assertThat(vm.state.value.pending.single().body).isEqualTo("你好")

        repo.releaseSend()
        runCurrent()
        assertThat(vm.state.value.pending).isEmpty()
        assertThat(vm.state.value.messages.single().body).isEqualTo("你好")
    }

    @Test
    fun `failed send stays as failed pending and can be retried`() = chatTest { vm ->
        vm.setVisible(true)
        runCurrent()
        repo.failSend = true
        vm.setDraft("x")
        vm.send()
        runCurrent()
        val p = vm.state.value.pending.single()
        assertThat(p.failed).isTrue()

        repo.failSend = false
        vm.retryPending(p.id)
        runCurrent()
        assertThat(vm.state.value.pending).isEmpty()
        assertThat(vm.state.value.messages.map { it.body }).containsExactly("x")
    }

    @Test
    fun `long text is sent as a txt file and the draft is cleared only after queueing`() = chatTest { vm ->
        runCurrent()
        val long = "字".repeat(1_400) // 4200 字节
        vm.setDraft(long)
        vm.send()
        runCurrent()
        assertThat(transfers.uploadedTexts.single().first).isEqualTo(long)
        assertThat(transfers.uploadedTexts.single().second).isEqualTo(repo.folder)
        assertThat(transfers.uploadedTexts.single().third).isTrue()
        assertThat(vm.draft.value).isEmpty()
        assertThat(vm.state.value.pending).isEmpty()
    }

    @Test
    fun `deleting keeps the message from coming back with an older poll`() = chatTest { vm ->
        repo.messages += listOf(msg("1"), msg("2"))
        vm.setVisible(true)
        runCurrent()
        repo.deleteLeavesServerCopy = true // 模拟删除前发出的轮询带回旧数据
        vm.delete(vm.state.value.messages.last())
        runCurrent()
        advanceTimeBy(ChatViewModel.POLL_INTERVAL_MS + 10)
        runCurrent()
        assertThat(vm.state.value.messages.map { it.id }).containsExactly("000001")
    }

    @Test
    fun `search then locate builds an anchored window around the hit`() = chatTest { vm ->
        repo.messages += (1..300).map { msg("$it") }
        vm.setVisible(true)
        runCurrent()
        vm.openSearch()
        vm.setSearchQuery("t100")
        advanceTimeBy(ChatViewModel.SEARCH_DEBOUNCE_MS + 10)
        runCurrent()
        val hit = vm.state.value.searchResults!!.first { it.body == "t100" }

        vm.locate(hit)
        runCurrent()
        val s = vm.state.value
        assertThat(s.searchOpen).isFalse()
        assertThat(s.anchored).isTrue()
        assertThat(s.highlightId).isEqualTo(hit.id)
        assertThat(s.messages.first().id).isEqualTo("000050")
        assertThat(s.messages.last().id).isEqualTo("000150")

        // 定位模式下轮询不合并最新页
        advanceTimeBy(ChatViewModel.POLL_INTERVAL_MS + 10)
        runCurrent()
        assertThat(vm.state.value.messages.last().id).isEqualTo("000150")

        vm.backToLatest()
        runCurrent()
        assertThat(vm.state.value.anchored).isFalse()
        assertThat(vm.state.value.messages.last().id).isEqualTo("000300")
    }

    @Test
    fun `shared text goes to the draft and files are sent to the chat folder`() = chatTest { vm ->
        runCurrent()
        vm.setDraft("已有草稿  ")
        val f = File.createTempFile("share", ".txt").apply { writeText("x") }
        inbox.post(SharePayload("分享的链接", listOf(f), failed = 0))
        runCurrent()
        assertThat(vm.draft.value).isEqualTo("已有草稿\n分享的链接")
        assertThat(transfers.uploadedFiles.single().first).containsExactly(f)
        assertThat(transfers.uploadedFiles.single().second).isEqualTo(repo.folder)
        f.delete()
    }

    @Test
    fun `pasted images are copied and sent to the chat folder`() = chatTest { vm ->
        runCurrent()
        // JVM 单测里 Uri.parse 是桩(返回 null);ViewModel 只是把列表交给 TransferActions,不解引用
        val uris = listOf(android.net.Uri.parse("content://keyboard/1"))
        vm.sendPasted(uris)
        runCurrent()
        assertThat(transfers.pasted).hasSize(1)
        assertThat(transfers.pasted.single().second).isEqualTo(repo.folder)
        assertThat(transfers.pasted.single().third).isTrue()
    }

    @Test
    fun `programmatic draft changes are announced so the input field can replace its text`() = chatTest { vm ->
        val replaced = mutableListOf<String>()
        backgroundScope.launch { vm.draftReplace.collect { replaced += it } }
        runCurrent()
        vm.setDraft("typed") // 用户打字:不发替换事件
        inbox.post(SharePayload("shared", emptyList(), failed = 0))
        runCurrent()
        assertThat(replaced).containsExactly("typed" + "\n" + "shared")
        vm.send()
        runCurrent()
        assertThat(replaced.last()).isEmpty()
    }

    @Test
    fun `older history is loaded with before and stops when a short page arrives`() = chatTest { vm ->
        repo.messages += (1..250).map { msg("$it") }
        vm.setVisible(true)
        runCurrent()
        assertThat(vm.state.value.messages).hasSize(200)
        assertThat(vm.state.value.hasMoreOlder).isTrue()
        vm.loadOlder()
        runCurrent()
        assertThat(vm.state.value.messages).hasSize(250)
        assertThat(vm.state.value.hasMoreOlder).isFalse()
    }
}

class FakeChatRepository : ChatRepository {
    val messages = mutableListOf<ChatMessage>()
    var latestCalls = 0
    var failLatest = false
    var failSend = false
    var holdSend = false
    var deleteLeavesServerCopy = false
    val folder = "chat-folder"
    private var counter = 1000
    private var gate: CompletableDeferred<Unit>? = null

    private fun sorted() = messages.sortedBy { it.id }

    override suspend fun latest(limit: Int): List<ChatMessage> {
        latestCalls++
        if (failLatest) throw GopanException(GopanErrors.NETWORK, "网络")
        return sorted().takeLast(limit)
    }

    override suspend fun before(id: String, limit: Int) = sorted().filter { it.id < id }.takeLast(limit)

    override suspend fun after(id: String, limit: Int) = sorted().filter { it.id > id }.take(limit)

    override suspend fun search(query: String, limit: Int) = sorted().filter { it.body.contains(query) }.takeLast(limit)

    override suspend fun sendText(body: String): ChatMessage {
        if (holdSend) {
            val g = CompletableDeferred<Unit>()
            gate = g
            g.await()
        }
        if (failSend) throw GopanException(GopanErrors.NETWORK, "网络")
        val m = msg("${++counter}", body = body)
        messages += m
        return m
    }

    fun releaseSend() {
        holdSend = false
        gate?.complete(Unit)
        gate = null
    }

    override suspend fun sendNode(nodeId: String): ChatMessage = throw UnsupportedOperationException()

    override suspend fun delete(id: String) {
        if (!deleteLeavesServerCopy) messages.removeAll { it.id == id }
    }

    override suspend fun ensureFolder(): String = folder

    override suspend fun folderId(): String? = folder
}

class FakeShareInbox : ShareInbox {
    override val pending = MutableStateFlow<SharePayload?>(null)
    override val receiving: StateFlow<ShareReceiving?> = MutableStateFlow(null)

    fun post(p: SharePayload) {
        pending.value = p
    }

    override fun take(): SharePayload? {
        val p = pending.value
        pending.value = null
        return p
    }
}
