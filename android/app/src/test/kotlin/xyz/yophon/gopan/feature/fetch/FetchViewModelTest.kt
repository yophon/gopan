package xyz.yophon.gopan.feature.fetch

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import xyz.yophon.gopan.core.data.jobs.FakeJobsRepository
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.feature.chat.FakeChatRepository
import xyz.yophon.gopan.feature.drive.MainDispatcherRule
import xyz.yophon.gopan.feature.transfers.TransfersTabRequest

@OptIn(ExperimentalCoroutinesApi::class)
class FetchViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val jobs = FakeJobsRepository()
    private val chat = FakeChatRepository()
    private val tabRequest = TransfersTabRequest()

    /** 建 VM(init 里问开关)并收集它的一次性事件 */
    private fun fetchTest(block: suspend TestScope.(FetchViewModel, List<FetchEffect>) -> Unit) = runTest {
        val vm = FetchViewModel(jobs, chat, tabRequest)
        val effects = mutableListOf<FetchEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        runCurrent()
        block(vm, effects)
    }

    private fun links(n: Int) = (1..n).joinToString("\n") { "https://example.com/$it.zip" }

    // ---- 文件页对话框 ----

    @Test
    fun `entry stays hidden when the site turned fetching off`() {
        jobs.enabled = false
        fetchTest { vm, _ ->
            assertThat(vm.state.value.enabled).isFalse()
            vm.openDialog()
            assertThat(vm.state.value.dialogOpen).isFalse()
        }
    }

    @Test
    fun `switch is asked again when the first try failed, then never again`() {
        jobs.failFetchEnabled = GopanException(GopanErrors.NETWORK, "网络连接失败,请检查网络后重试")
        fetchTest { vm, _ ->
            assertThat(vm.state.value.enabled).isFalse()
            vm.checkEnabled()
            runCurrent()
            assertThat(vm.state.value.enabled).isTrue()
            vm.checkEnabled()
            runCurrent()
            assertThat(jobs.fetchEnabledCalls).isEqualTo(2)
        }
    }

    @Test
    fun `dialog counts links live and only allows 1 to 10`() = fetchTest { vm, _ ->
        assertThat(vm.state.value.enabled).isTrue()
        vm.openDialog()
        assertThat(vm.state.value.dialogOpen).isTrue()
        assertThat(vm.state.value.canSubmit).isFalse()

        vm.setText("【报告】https://example.com/a.pdf,还有 https://example.com/b.zip 和 https://example.com/a.pdf")
        assertThat(vm.state.value.urls).containsExactly("https://example.com/a.pdf", "https://example.com/b.zip").inOrder()
        assertThat(vm.state.value.canSubmit).isTrue()

        vm.setText(links(10))
        assertThat(vm.state.value.tooMany).isFalse()
        assertThat(vm.state.value.canSubmit).isTrue()

        vm.setText(links(11))
        assertThat(vm.state.value.urls).hasSize(11)
        assertThat(vm.state.value.tooMany).isTrue()
        assertThat(vm.state.value.canSubmit).isFalse()
        vm.submit("dir")
        runCurrent()
        assertThat(jobs.fetched).isEmpty()

        vm.setText("ftp://example.com/x 没有链接")
        assertThat(vm.state.value.urls).isEmpty()
        assertThat(vm.state.value.canSubmit).isFalse()
    }

    @Test
    fun `submit sends the links to the current folder and reports success`() = fetchTest { vm, effects ->
        vm.openDialog()
        vm.setText(links(2))
        vm.submit("dir")
        assertThat(vm.state.value.submitting).isTrue()
        // 提交中再点一次不会重复提交
        vm.submit("dir")
        runCurrent()

        assertThat(jobs.fetched).containsExactly(listOf("https://example.com/1.zip", "https://example.com/2.zip") to "dir")
        assertThat(vm.state.value.dialogOpen).isFalse()
        assertThat(vm.state.value.submitting).isFalse()
        assertThat(effects).containsExactly(FetchEffect.Queued(2))
    }

    @Test
    fun `submit at the root sends a null parent`() = fetchTest { vm, _ ->
        vm.openDialog()
        vm.setText(links(1))
        vm.submit(null)
        runCurrent()
        assertThat(jobs.fetched.single().second).isNull()
    }

    @Test
    fun `failed submit keeps the dialog open with a Chinese reason`() = fetchTest { vm, effects ->
        vm.openDialog()
        vm.setText(links(1))
        jobs.nextMutationError = GopanException("TOO_MANY_JOBS", "请求失败(TOO_MANY_JOBS)", serverMessage = "too many")
        vm.submit("dir")
        runCurrent()
        assertThat(vm.state.value.dialogOpen).isTrue()
        assertThat(vm.state.value.submitting).isFalse()
        assertThat(vm.state.value.error).isEqualTo("排队中的后台任务过多,等前面的完成再来")
        assertThat(effects).isEmpty()

        // 服务端给了中文原因(带具体链接)就用它
        jobs.nextMutationError = GopanException("INVALID_URL", "x", serverMessage = "链接格式不对,只支持 http / https:https://a")
        vm.submit("dir")
        runCurrent()
        assertThat(vm.state.value.error).isEqualTo("链接格式不对,只支持 http / https:https://a")

        // 改了输入就清掉错误
        vm.setText(links(1) + "\n")
        assertThat(vm.state.value.error).isNull()
    }

    @Test
    fun `dismiss is ignored while submitting and reopening starts empty`() = fetchTest { vm, _ ->
        vm.openDialog()
        vm.setText(links(1))
        vm.submit("dir")
        vm.dismissDialog()
        assertThat(vm.state.value.dialogOpen).isTrue()
        runCurrent()

        vm.openDialog()
        vm.setText(links(3))
        vm.dismissDialog()
        assertThat(vm.state.value.dialogOpen).isFalse()
        vm.openDialog()
        assertThat(vm.state.value.text).isEmpty()
        assertThat(vm.state.value.urls).isEmpty()
    }

    @Test
    fun `view action asks the transfers screen to open on the jobs tab once`() = fetchTest { vm, _ ->
        vm.showJobs()
        assertThat(tabRequest.consumeJobs()).isTrue()
        assertThat(tabRequest.consumeJobs()).isFalse()
    }

    // ---- 传输助手提示条 ----

    @Test
    fun `chat offer detects shared links in the draft`() {
        assertThat(chatFetchOffer("随便聊聊")).isNull()
        assertThat(chatFetchOffer("")).isNull()
        val one = chatFetchOffer("【安装包】https://example.com/app.apk 分享自浏览器")!!
        assertThat(one.urls).containsExactly("https://example.com/app.apk")
        assertThat(one.capped).isFalse()
        val many = chatFetchOffer(links(12))!!
        assertThat(many.urls).hasSize(10)
        assertThat(many.urls.first()).isEqualTo("https://example.com/1.zip")
        assertThat(many.total).isEqualTo(12)
        assertThat(many.capped).isTrue()
    }

    @Test
    fun `chat banner submits to the chat folder, keeps the draft and hides for those links`() = fetchTest { vm, effects ->
        val draft = "看看这个 https://example.com/report.pdf"
        assertThat(vm.state.value.chatOffer(draft)?.urls).containsExactly("https://example.com/report.pdf")

        vm.submitToChat(draft)
        runCurrent()
        assertThat(jobs.fetched).containsExactly(listOf("https://example.com/report.pdf") to chat.folder)
        assertThat(effects).containsExactly(FetchEffect.QueuedToChat(1))
        // 草稿不动(提示条不碰草稿),但同一组链接不再提示
        assertThat(vm.state.value.chatOffer(draft)).isNull()
        assertThat(vm.state.value.chatOffer("$draft\n补一句话")).isNull()
        // 链接变了重新出现
        assertThat(vm.state.value.chatOffer("$draft https://example.com/b.zip")?.urls).hasSize(2)
    }

    @Test
    fun `chat banner submits at most 10 links`() = fetchTest { vm, _ ->
        vm.submitToChat(links(12))
        runCurrent()
        assertThat(jobs.fetched.single().first).hasSize(10)
    }

    @Test
    fun `chat banner can be dismissed and is hidden when fetching is off`() {
        fetchTest { vm, _ ->
            val draft = "https://example.com/a.zip"
            vm.dismissChatOffer(draft)
            assertThat(vm.state.value.chatOffer(draft)).isNull()
            assertThat(jobs.fetched).isEmpty()
        }
        jobs.enabled = false
        fetchTest { vm, _ ->
            assertThat(vm.state.value.chatOffer("https://example.com/a.zip")).isNull()
            vm.submitToChat("https://example.com/a.zip")
            runCurrent()
            assertThat(jobs.fetched).isEmpty()
        }
    }

    @Test
    fun `chat submit failure is reported and the banner stays`() = fetchTest { vm, effects ->
        val draft = "https://example.com/a.zip"
        jobs.nextMutationError = GopanException("FETCH_DISABLED", "请求失败(FETCH_DISABLED)")
        vm.submitToChat(draft)
        runCurrent()
        assertThat(effects).containsExactly(FetchEffect.Error("本站没有开启离线下载"))
        assertThat(vm.state.value.submitting).isFalse()
        assertThat(vm.state.value.chatOffer(draft)).isNotNull()

        jobs.nextMutationError = GopanException(GopanErrors.NETWORK, "网络连接失败,请检查网络后重试")
        vm.submitToChat(draft)
        runCurrent()
        assertThat(effects.last()).isEqualTo(FetchEffect.Error("网络连接失败,请检查网络后重试"))
    }
}
