package xyz.yophon.gopan.feature.search

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import xyz.yophon.gopan.feature.drive.FakeNodesRepository
import xyz.yophon.gopan.feature.drive.FakeTransferActions
import xyz.yophon.gopan.feature.drive.FakeNodesRepository.Companion.file
import xyz.yophon.gopan.feature.drive.FakeNodesRepository.Companion.page
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.MainDispatcherRule

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repo = FakeNodesRepository()

    @Test
    fun `debounces typing and searches the trimmed keyword once`() = runTest {
        repo.searchResults["报告"] = listOf(page(file("r1", "报告.pdf"), nextCursor = "1", total = 2), page(file("r2", "报告2.pdf"), total = 2))
        val vm = SearchViewModel(repo, FakeTransferActions())

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
        val vm = SearchViewModel(repo, FakeTransferActions())
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
        val vm = SearchViewModel(repo, FakeTransferActions())
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
        val vm = SearchViewModel(repo, FakeTransferActions())
        vm.onQueryChange("a")
        advanceUntilIdle()

        vm.rename("a", "a-renamed")
        advanceUntilIdle()

        assertThat(repo.calls).containsExactly("search(a,null)", "rename(a,a-renamed)", "search(a,null)").inOrder()
        assertThat(vm.state.value.status).isEqualTo(ListStatus.Content)
        assertThat(vm.dialog.value).isNull()
    }
}
