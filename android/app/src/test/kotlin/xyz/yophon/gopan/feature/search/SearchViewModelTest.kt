package xyz.yophon.gopan.feature.search

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.feature.drive.FakeNodesRepository
import xyz.yophon.gopan.feature.drive.FakeTransferActions
import xyz.yophon.gopan.feature.drive.FakeNodesRepository.Companion.file
import xyz.yophon.gopan.feature.drive.FakeNodesRepository.Companion.hit
import xyz.yophon.gopan.feature.drive.FakeNodesRepository.Companion.hits
import xyz.yophon.gopan.feature.drive.FakeNodesRepository.Companion.page
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.MainDispatcherRule
import xyz.yophon.gopan.feature.drive.NodeEffect
import xyz.yophon.gopan.feature.preview.PreviewSessionStore

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repo = FakeNodesRepository()
    private val sessions = PreviewSessionStore()

    private fun viewModel(handle: SavedStateHandle = SavedStateHandle()) = SearchViewModel(repo, FakeTransferActions(), sessions, handle)

    @Test
    fun `debounces typing and searches the trimmed keyword once`() = runTest {
        repo.searchResults["报告"] = listOf(page(file("r1", "报告.pdf"), nextCursor = "1", total = 2), page(file("r2", "报告2.pdf"), total = 2))
        val vm = viewModel()

        vm.onQueryChange("报")
        vm.onQueryChange("报告")
        vm.onQueryChange(" 报告 ")
        advanceTimeBy(SearchViewModel.DEBOUNCE_MS - 1)
        assertThat(repo.calls).isEmpty()

        advanceUntilIdle()
        assertThat(repo.calls).containsExactly("search(报告,null)")
        with(vm.state.value) {
            assertThat(query).isEqualTo("报告")
            assertThat(status).isEqualTo(ListStatus.Content)
            assertThat(items.map { it.id }).containsExactly("r1")
            assertThat(total).isEqualTo(2)
        }

        vm.loadMore()
        advanceUntilIdle()
        assertThat(vm.state.value.items.map { it.id }).containsExactly("r1", "r2").inOrder()
        assertThat(vm.state.value.nextCursor).isNull()
    }

    @Test
    fun `clearing the keyword goes back to idle without searching`() = runTest {
        repo.searchResults["a"] = listOf(page(file("a")))
        val vm = viewModel()
        vm.onQueryChange("a")
        advanceUntilIdle()
        assertThat(vm.state.value.items).hasSize(1)

        vm.onQueryChange("")
        advanceUntilIdle()

        assertThat(vm.state.value.isIdle).isTrue()
        assertThat(vm.state.value.items).isEmpty()
        assertThat(repo.calls).containsExactly("search(a,null)")
    }

    @Test
    fun `no hits is an empty state, network failure is an error state with retry`() = runTest {
        val vm = viewModel()
        vm.onQueryChange("nothing")
        advanceUntilIdle()
        assertThat(vm.state.value.isEmpty).isTrue()

        repo.networkDown = true
        vm.onQueryChange("other")
        advanceUntilIdle()
        assertThat(vm.state.value.status).isInstanceOf(ListStatus.Error::class.java)

        repo.networkDown = false
        repo.searchResults["other"] = listOf(page(file("o")))
        vm.retry()
        advanceUntilIdle()
        assertThat(vm.state.value.status).isEqualTo(ListStatus.Content)
        assertThat(vm.state.value.items.map { it.id }).containsExactly("o")
    }

    @Test
    fun `a successful mutation re-runs the current search silently`() = runTest {
        repo.searchResults["a"] = listOf(page(file("a")))
        val vm = viewModel()
        vm.onQueryChange("a")
        advanceUntilIdle()

        vm.rename("a", "a-renamed")
        advanceUntilIdle()

        assertThat(repo.calls).containsExactly("search(a,null)", "rename(a,a-renamed)", "search(a,null)").inOrder()
        assertThat(vm.state.value.status).isEqualTo(ListStatus.Content)
        assertThat(vm.dialog.value).isNull()
    }

    // ---- 「内容」标签(doc/20) ----

    @Test
    fun `the content tab debounces, searches content only and pages with the cursor`() = runTest {
        val c1 = hit(file("c1", "租房合同.pdf"), page = 3)
        repo.contentResults["合同"] = listOf(
            // 首页里重复的也去掉(结果列表按文件 id 做 key)
            hits(c1, c1, nextCursor = "1"),
            hits(hit(file("c2", "合同.docx")), hit(file("c1", "租房合同.pdf"), page = 3)),
        )
        val vm = viewModel()
        vm.onTabSelected(SearchTab.CONTENT)

        vm.onQueryChange("合")
        vm.onQueryChange(" 合同 ")
        advanceTimeBy(SearchViewModel.DEBOUNCE_MS - 1)
        assertThat(repo.calls).isEmpty()

        advanceUntilIdle()
        assertThat(repo.calls).containsExactly("searchContent(合同,null)")
        with(vm.state.value.content) {
            assertThat(status).isEqualTo(ListStatus.Content)
            assertThat(notice).isNull()
            assertThat(hits.map { it.node.id }).containsExactly("c1")
            assertThat(hits.single().page).isEqualTo(3)
            assertThat(nextCursor).isEqualTo("1")
        }

        vm.loadMoreContent()
        advanceUntilIdle()
        assertThat(repo.calls).containsExactly("searchContent(合同,null)", "searchContent(合同,1)").inOrder()
        // 下一页里重复的文件丢掉(列表 key 不能重复)
        assertThat(vm.state.value.content.hits.map { it.node.id }).containsExactly("c1", "c2").inOrder()
        assertThat(vm.state.value.content.nextCursor).isNull()
    }

    @Test
    fun `the selected tab is kept across keyword edits and clearing`() = runTest {
        val vm = viewModel()
        vm.onTabSelected(SearchTab.CONTENT)
        vm.onQueryChange("合同")
        advanceUntilIdle()
        vm.onQueryChange("")
        advanceUntilIdle()
        assertThat(vm.state.value.isIdle).isTrue()
        assertThat(vm.state.value.tab).isEqualTo(SearchTab.CONTENT)

        vm.onQueryChange("发票")
        advanceUntilIdle()
        assertThat(vm.state.value.tab).isEqualTo(SearchTab.CONTENT)
        assertThat(repo.calls).containsExactly("searchContent(合同,null)", "searchContent(发票,null)").inOrder()
    }

    @Test
    fun `the tab survives process recreation`() = runTest {
        val handle = SavedStateHandle()
        viewModel(handle).onTabSelected(SearchTab.CONTENT)

        val restored = viewModel(handle)
        assertThat(restored.state.value.tab).isEqualTo(SearchTab.CONTENT)
        restored.onQueryChange("合同")
        advanceUntilIdle()
        assertThat(repo.calls).containsExactly("searchContent(合同,null)")
    }

    @Test
    fun `each tab is searched once per keyword, a new keyword invalidates both`() = runTest {
        repo.searchResults["abc"] = listOf(page(file("n1")))
        repo.contentResults["abc"] = listOf(hits(hit(file("c1"))))
        val vm = viewModel()
        vm.onQueryChange("abc")
        advanceUntilIdle()

        vm.onTabSelected(SearchTab.CONTENT)
        advanceUntilIdle()
        vm.onTabSelected(SearchTab.NAME)
        advanceUntilIdle()
        vm.onTabSelected(SearchTab.CONTENT)
        advanceUntilIdle()
        assertThat(repo.calls).containsExactly("search(abc,null)", "searchContent(abc,null)").inOrder()
        assertThat(vm.state.value.items.map { it.id }).containsExactly("n1")
        assertThat(vm.state.value.content.hits.map { it.node.id }).containsExactly("c1")

        // 换关键词:只搜当前标签;另一个标签先作废(切过去不会闪出上个关键词的结果),切过去才搜
        vm.onQueryChange("xyz")
        advanceUntilIdle()
        assertThat(repo.calls.last()).isEqualTo("searchContent(xyz,null)")
        assertThat(vm.state.value.items).isEmpty()
        assertThat(vm.state.value.status).isEqualTo(ListStatus.Loading)

        vm.onTabSelected(SearchTab.NAME)
        advanceUntilIdle()
        assertThat(repo.calls.last()).isEqualTo("search(xyz,null)")
        assertThat(vm.state.value.isEmpty).isTrue()
    }

    @Test
    fun `a short keyword or a site without content search is a notice, not an error`() = runTest {
        repo.contentErrors["合"] = GopanException(GopanErrors.QUERY_TOO_SHORT, "关键词太短:至少两个汉字或三个字母")
        repo.contentErrors["发票"] = GopanException(GopanErrors.CONTENT_SEARCH_DISABLED, "本站没有开启内容搜索")
        val vm = viewModel()
        vm.onTabSelected(SearchTab.CONTENT)

        vm.effects.test {
            vm.onQueryChange("合")
            advanceUntilIdle()
            with(vm.state.value.content) {
                assertThat(notice).isEqualTo(ContentNotice.QUERY_TOO_SHORT)
                assertThat(status).isEqualTo(ListStatus.Content)
                assertThat(isEmpty).isFalse()
            }

            vm.onQueryChange("发票")
            advanceUntilIdle()
            assertThat(vm.state.value.content.notice).isEqualTo(ContentNotice.DISABLED)
            assertThat(vm.state.value.content.status).isEqualTo(ListStatus.Content)
            // 不弹 Snackbar
            expectNoEvents()
        }

        // 提示就是这个关键词的结果:切走再切回来不再问服务端
        vm.onTabSelected(SearchTab.NAME)
        advanceUntilIdle()
        vm.onTabSelected(SearchTab.CONTENT)
        advanceUntilIdle()
        assertThat(repo.calls.count { it == "searchContent(发票,null)" }).isEqualTo(1)
        assertThat(vm.state.value.content.notice).isEqualTo(ContentNotice.DISABLED)
    }

    @Test
    fun `no content hits is an empty state, a network failure is an error with retry`() = runTest {
        val vm = viewModel()
        vm.onTabSelected(SearchTab.CONTENT)
        vm.onQueryChange("nothing")
        advanceUntilIdle()
        assertThat(vm.state.value.content.isEmpty).isTrue()

        repo.networkDown = true
        vm.onQueryChange("合同")
        advanceUntilIdle()
        assertThat(vm.state.value.content.status).isInstanceOf(ListStatus.Error::class.java)
        assertThat(vm.state.value.content.notice).isNull()

        repo.networkDown = false
        repo.contentResults["合同"] = listOf(hits(hit(file("c1"))))
        vm.retry()
        advanceUntilIdle()
        assertThat(vm.state.value.content.status).isEqualTo(ListStatus.Content)
        assertThat(vm.state.value.content.hits.map { it.node.id }).containsExactly("c1")
    }

    @Test
    fun `a new keyword cancels the content search in flight and drops its next page`() = runTest {
        repo.contentResults["aaa"] = listOf(hits(hit(file("a1")), nextCursor = "1"), hits(hit(file("a2"))))
        repo.contentResults["bbb"] = listOf(hits(hit(file("b1"))))
        repo.contentDelayMs = 1_000
        val vm = viewModel()
        vm.onTabSelected(SearchTab.CONTENT)

        // 第一次请求还没回来就换了关键词:旧请求被取消,结果只有新关键词的
        vm.onQueryChange("aaa")
        advanceTimeBy(SearchViewModel.DEBOUNCE_MS + 500)
        vm.onQueryChange("bbb")
        advanceUntilIdle()
        assertThat(repo.calls).containsExactly("searchContent(aaa,null)", "searchContent(bbb,null)").inOrder()
        assertThat(vm.state.value.query).isEqualTo("bbb")
        assertThat(vm.state.value.content.hits.map { it.node.id }).containsExactly("b1")

        // 翻页在途时换关键词:旧关键词的下一页不会拼进新列表
        vm.onQueryChange("aaa")
        advanceUntilIdle()
        vm.loadMoreContent()
        runCurrent()
        assertThat(vm.state.value.content.isLoadingMore).isTrue()
        vm.onQueryChange("bbb")
        advanceUntilIdle()
        assertThat(vm.state.value.content.hits.map { it.node.id }).containsExactly("b1")
        assertThat(vm.state.value.content.isLoadingMore).isFalse()
    }

    @Test
    fun `tapping a hit previews the hit list and pdfs open at the hit page`() = runTest {
        repo.contentResults["合同"] = listOf(
            hits(hit(file("p1", "合同.pdf"), page = 3), hit(file("t1", "合同.txt")), hit(file("o1", "合同.doc"), page = 2)),
        )
        val vm = viewModel()
        vm.onTabSelected(SearchTab.CONTENT)
        vm.onQueryChange("合同")
        advanceUntilIdle()

        vm.effects.test {
            vm.onHitClick(vm.state.value.content.hits[1])
            assertThat(awaitItem()).isEqualTo(NodeEffect.OpenPreview("t1"))
        }
        val items = sessions.itemsFor("t1")
        assertThat(items.map { it.nodeId }).containsExactly("p1", "t1", "o1").inOrder()
        assertThat(items.map { it.pdfPage }).containsExactly(3, null, 2).inOrder()
    }

    @Test
    fun `name results still open the preview without pdf pages`() = runTest {
        repo.searchResults["合同"] = listOf(page(file("p1", "合同.pdf"), file("p2", "合同2.pdf")))
        repo.contentResults["合同"] = listOf(hits(hit(file("p1", "合同.pdf"), page = 3)))
        val vm = viewModel()
        vm.onQueryChange("合同")
        advanceUntilIdle()
        vm.onTabSelected(SearchTab.CONTENT)
        advanceUntilIdle()
        vm.onTabSelected(SearchTab.NAME)
        advanceUntilIdle()

        vm.onItemClick(vm.state.value.items.first())
        val items = sessions.itemsFor("p1")
        assertThat(items.map { it.nodeId }).containsExactly("p1", "p2").inOrder()
        assertThat(items.map { it.pdfPage }).containsExactly(null, null)
    }

    @Test
    fun `a mutation on the content tab re-runs it silently, the name tab refreshes when shown again`() = runTest {
        repo.searchResults["abc"] = listOf(page(file("a")))
        repo.contentResults["abc"] = listOf(hits(hit(file("a"))))
        val vm = viewModel()
        vm.onQueryChange("abc")
        advanceUntilIdle()
        vm.onTabSelected(SearchTab.CONTENT)
        advanceUntilIdle()

        vm.rename("a", "a-renamed")
        advanceUntilIdle()
        assertThat(vm.state.value.content.status).isEqualTo(ListStatus.Content)
        assertThat(vm.state.value.content.hits).hasSize(1)

        vm.onTabSelected(SearchTab.NAME)
        advanceUntilIdle()
        assertThat(repo.calls).containsExactly(
            "search(abc,null)",
            "searchContent(abc,null)",
            "rename(a,a-renamed)",
            "searchContent(abc,null)",
            "search(abc,null)",
        ).inOrder()
        // 静默重搜:切回来时手上的结果一直在,不闪加载中
        assertThat(vm.state.value.status).isEqualTo(ListStatus.Content)
        assertThat(vm.state.value.items).hasSize(1)
    }
}
