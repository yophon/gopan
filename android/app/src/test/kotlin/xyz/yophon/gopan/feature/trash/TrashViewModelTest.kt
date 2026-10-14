package xyz.yophon.gopan.feature.trash

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import xyz.yophon.gopan.feature.drive.FakeNodesRepository
import xyz.yophon.gopan.feature.drive.FakeNodesRepository.Companion.file
import xyz.yophon.gopan.feature.drive.FakeNodesRepository.Companion.page
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.MainDispatcherRule

@OptIn(ExperimentalCoroutinesApi::class)
class TrashViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repo = FakeNodesRepository()

    @Test
    fun `loads and pages the trash`() = runTest {
        repo.trashPages = listOf(page(file("a"), nextCursor = "1", total = 2), page(file("b"), total = 2))
        val vm = TrashViewModel(repo)
        advanceUntilIdle()

        assertThat(vm.state.value.status).isEqualTo(ListStatus.Content)
        assertThat(vm.state.value.items.map { it.id }).containsExactly("a")
        assertThat(vm.state.value.nowMillis).isGreaterThan(0L)

        vm.loadMore()
        advanceUntilIdle()
        assertThat(vm.state.value.items.map { it.id }).containsExactly("a", "b").inOrder()
        assertThat(vm.state.value.nextCursor).isNull()
    }

    @Test
    fun `restore and purge go through the repository and reload`() = runTest {
        val a = file("a")
        val b = file("b")
        repo.trashPages = listOf(page(a, b))
        val vm = TrashViewModel(repo)
        advanceUntilIdle()

        vm.restore(listOf("a"))
        advanceUntilIdle()
        assertThat(repo.calls).contains("restore([a])")

        vm.onItemLongClick(b)
        vm.requestPurgeSelected()
        val dialog = vm.state.value.dialog as TrashDialog.ConfirmPurge
        assertThat(dialog.ids).containsExactly("b")
        assertThat(dialog.singleName).isEqualTo("b")
        vm.confirmPurge(dialog.ids)
        advanceUntilIdle()

        assertThat(repo.calls).contains("purge([b])")
        assertThat(vm.state.value.dialog).isNull()
        assertThat(vm.state.value.selectionMode).isFalse()
        assertThat(repo.calls.count { it.startsWith("trash(") }).isEqualTo(3)
    }

    @Test
    fun `emptying the trash needs two confirmations`() = runTest {
        repo.trashPages = listOf(page(file("a")))
        val vm = TrashViewModel(repo)
        advanceUntilIdle()

        vm.requestPurgeAll()
        assertThat(vm.state.value.dialog).isEqualTo(TrashDialog.ConfirmPurgeAll(step = 1))
        vm.confirmPurgeAllStep()
        assertThat(vm.state.value.dialog).isEqualTo(TrashDialog.ConfirmPurgeAll(step = 2))
        assertThat(repo.calls).doesNotContain("purgeTrash")

        vm.confirmPurgeAllStep()
        advanceUntilIdle()
        assertThat(repo.calls).contains("purgeTrash")
        assertThat(vm.state.value.dialog).isNull()
    }
}
