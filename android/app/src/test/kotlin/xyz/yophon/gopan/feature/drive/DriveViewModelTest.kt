package xyz.yophon.gopan.feature.drive

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.prefs.DriveSort
import xyz.yophon.gopan.core.prefs.DriveSortOrder
import xyz.yophon.gopan.core.prefs.DriveViewMode
import xyz.yophon.gopan.core.prefs.UserPreferencesRepository
import xyz.yophon.gopan.feature.drive.FakeNodesRepository.Companion.file
import xyz.yophon.gopan.feature.drive.FakeNodesRepository.Companion.folder
import xyz.yophon.gopan.feature.drive.FakeNodesRepository.Companion.page

@OptIn(ExperimentalCoroutinesApi::class)
class DriveViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repo = FakeNodesRepository()
    private val prefs = UserPreferencesRepository(InMemoryPreferencesDataStore())

    private fun viewModel(folderId: String? = null) = DriveViewModel(
        savedStateHandle = if (folderId == null) SavedStateHandle() else SavedStateHandle(mapOf(DriveViewModel.FOLDER_ID_ARG to folderId)),
        repo = repo,
        prefs = prefs,
    )

    @Test
    fun `loads the first page with the stored sort and pages until the cursor runs out`() = runTest {
        repo.listings[null] = listOf(
            page(folder("d"), file("a"), nextCursor = "1", total = 4),
            page(file("a"), file("b"), nextCursor = "2", total = 4),
            page(file("c"), total = 4),
        )
        prefs.setDriveSort(DriveSort(DriveSortOrder.SIZE, desc = true))

        val vm = viewModel()
        advanceUntilIdle()

        with(vm.state.value) {
            assertThat(status).isEqualTo(ListStatus.Content)
            assertThat(items.map { it.id }).containsExactly("d", "a").inOrder()
            assertThat(nextCursor).isEqualTo("1")
            assertThat(total).isEqualTo(4)
            assertThat(fromCache).isFalse()
            assertThat(sort).isEqualTo(DriveSort(DriveSortOrder.SIZE, desc = true))
        }
        assertThat(repo.lastOrder).isEqualTo(DriveSortOrder.SIZE to true)

        vm.loadMore()
        advanceUntilIdle()
        assertThat(vm.state.value.items.map { it.id }).containsExactly("d", "a", "b").inOrder() // 重复的 a 去掉
        assertThat(vm.state.value.nextCursor).isEqualTo("2")

        vm.loadMore()
        advanceUntilIdle()
        assertThat(vm.state.value.items.map { it.id }).containsExactly("d", "a", "b", "c").inOrder()
        assertThat(vm.state.value.nextCursor).isNull()

        val callsBefore = repo.calls.size
        vm.loadMore()
        advanceUntilIdle()
        assertThat(repo.calls.size).isEqualTo(callsBefore)
    }

    @Test
    fun `falls back to the cached listing when offline and keeps the error for the banner`() = runTest {
        repo.cache[null] = page(file("cached"), nextCursor = "1")
        repo.networkDown = true

        val vm = viewModel()
        advanceUntilIdle()

        with(vm.state.value) {
            assertThat(status).isEqualTo(ListStatus.Content)
            assertThat(fromCache).isTrue()
            assertThat(items.map { it.id }).containsExactly("cached")
            assertThat(nextCursor).isNull()
            assertThat(fetchedAt).isEqualTo(1_000L)
            assertThat(offlineMessage).contains("网络")
        }
        // 缓存页不能翻页
        vm.loadMore()
        advanceUntilIdle()
        assertThat(repo.calls.filter { it.startsWith("children") }).isEmpty()
    }

    @Test
    fun `offline without cache is an error state and retry recovers`() = runTest {
        repo.networkDown = true

        val vm = viewModel()
        advanceUntilIdle()
        assertThat(vm.state.value.status).isInstanceOf(ListStatus.Error::class.java)

        repo.networkDown = false
        repo.listings[null] = listOf(page(file("a")))
        vm.retry()
        advanceUntilIdle()

        assertThat(vm.state.value.status).isEqualTo(ListStatus.Content)
        assertThat(vm.state.value.items.map { it.id }).containsExactly("a")
    }

    @Test
    fun `a failed pull-to-refresh keeps the old items and reports the error`() = runTest {
        repo.listings[null] = listOf(page(file("a")))
        val vm = viewModel()
        advanceUntilIdle()

        repo.networkDown = true
        vm.effects.test {
            vm.refresh()
            advanceUntilIdle()
            val effect = awaitItem()
            assertThat(effect).isInstanceOf(NodeEffect.Message::class.java)
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(vm.state.value.status).isEqualTo(ListStatus.Content)
        assertThat(vm.state.value.items.map { it.id }).containsExactly("a")
        assertThat(vm.state.value.isRefreshing).isFalse()
    }

    @Test
    fun `changing the sort reloads with the new order and persists it, view mode does not reload`() = runTest {
        repo.listings[null] = listOf(page(file("a")))
        val vm = viewModel()
        advanceUntilIdle()
        assertThat(repo.firstPageCalls).isEqualTo(1)

        vm.selectSort(DriveSortOrder.UPDATED_AT)
        advanceUntilIdle()
        assertThat(repo.firstPageCalls).isEqualTo(2)
        assertThat(repo.lastOrder).isEqualTo(DriveSortOrder.UPDATED_AT to true)
        assertThat(prefs.preferences.first().driveSort).isEqualTo(DriveSort(DriveSortOrder.UPDATED_AT, desc = true))

        vm.selectSort(DriveSortOrder.UPDATED_AT) // 再点一次翻转方向
        advanceUntilIdle()
        assertThat(repo.lastOrder).isEqualTo(DriveSortOrder.UPDATED_AT to false)

        vm.toggleViewMode()
        advanceUntilIdle()
        assertThat(vm.state.value.viewMode).isEqualTo(DriveViewMode.GRID)
        assertThat(repo.firstPageCalls).isEqualTo(3)
    }

    @Test
    fun `breadcrumb resolves the ancestor chain of a folder route`() = runTest {
        repo.nodes["a"] = folder("a", name = "顶层")
        repo.nodes["b"] = folder("b", name = "二级", parentId = "a")
        repo.listings["b"] = listOf(page(file("x", parentId = "b")))

        val vm = viewModel("b")
        advanceUntilIdle()

        assertThat(vm.parentId).isEqualTo("b")
        assertThat(vm.state.value.crumbs).containsExactly(Crumb("a", "顶层"), Crumb("b", "二级")).inOrder()
        assertThat(vm.state.value.items.map { it.id }).containsExactly("x")
        assertThat(repo.calls).contains("firstPage(b)")
    }

    @Test
    fun `breadcrumb degrades to a nameless crumb when the chain cannot be resolved`() = runTest {
        repo.listings["zzz"] = listOf(page())

        val vm = viewModel("zzz")
        advanceUntilIdle()

        assertThat(vm.state.value.crumbs).containsExactly(Crumb("zzz", ""))
        assertThat(vm.state.value.isEmpty).isTrue()
    }

    @Test
    fun `long press enters selection mode and batch delete clears it and reloads`() = runTest {
        val a = file("a")
        val b = file("b")
        val c = folder("c")
        repo.listings[null] = listOf(page(a, b, c))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onItemLongClick(a)
        assertThat(vm.state.value.selectionMode).isTrue()
        assertThat(vm.state.value.selection).containsExactly("a")
        vm.onItemClick(b)
        assertThat(vm.state.value.selection).containsExactly("a", "b")
        vm.onItemClick(a)
        assertThat(vm.state.value.selection).containsExactly("b")
        vm.selectAll()
        assertThat(vm.state.value.allSelected).isTrue()

        vm.deleteSelected()
        val dialog = vm.dialog.value as NodeDialog.ConfirmDelete
        assertThat(dialog.ids).containsExactly("a", "b", "c").inOrder()
        assertThat(dialog.singleName).isNull()

        vm.effects.test {
            vm.confirmDelete(dialog.ids)
            advanceUntilIdle()
            val message = awaitItem() as NodeEffect.Message
            assertThat((message.text as UiText.Res).id).isEqualTo(R.string.drive_msg_deleted)
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(repo.calls).contains("delete([a, b, c])")
        assertThat(vm.dialog.value).isNull()
        assertThat(vm.state.value.selectionMode).isFalse()
        assertThat(vm.state.value.selection).isEmpty()
        assertThat(repo.firstPageCalls).isEqualTo(2)
    }

    @Test
    fun `tapping a folder opens it, tapping a file shows details`() = runTest {
        val a = file("a")
        val c = folder("c")
        repo.listings[null] = listOf(page(a, c))
        val vm = viewModel()
        advanceUntilIdle()

        vm.effects.test {
            vm.onItemClick(c)
            assertThat(awaitItem()).isEqualTo(NodeEffect.OpenFolder("c"))
            cancelAndIgnoreRemainingEvents()
        }
        vm.onItemClick(a)
        assertThat(vm.dialog.value).isEqualTo(NodeDialog.Details(a))
    }

    @Test
    fun `a name conflict keeps the create dialog open with an inline error`() = runTest {
        repo.listings[null] = listOf(page())
        val vm = viewModel()
        advanceUntilIdle()

        vm.requestCreateFolder()
        assertThat(vm.dialog.value).isEqualTo(DriveDialog.CreateFolder)
        repo.nextMutationError = GopanException(GopanErrors.NAME_CONFLICT, "同名文件或文件夹已存在")

        vm.createFolder("docs")
        advanceUntilIdle()

        assertThat(vm.dialog.value).isEqualTo(DriveDialog.CreateFolder)
        val error = vm.inputError.value
        assertThat(error?.name).isEqualTo("docs")
        assertThat((error?.message as UiText.Res).id).isEqualTo(R.string.drive_name_conflict)
        assertThat(repo.firstPageCalls).isEqualTo(1)

        vm.createFolder("docs2")
        advanceUntilIdle()

        assertThat(vm.dialog.value).isNull()
        assertThat(vm.inputError.value).isNull()
        assertThat(repo.calls).contains("createFolder(null,docs2)")
        assertThat(repo.firstPageCalls).isEqualTo(2)
    }

    @Test
    fun `other errors close nothing but surface as a message`() = runTest {
        repo.listings[null] = listOf(page(file("a")))
        val vm = viewModel()
        advanceUntilIdle()
        repo.nextMutationError = GopanException(GopanErrors.NETWORK, "网络连接失败,请检查网络后重试")

        vm.effects.test {
            vm.moveTo(listOf("a"), Crumb("t", "目标"), rootName = "文件")
            advanceUntilIdle()
            val message = awaitItem() as NodeEffect.Message
            assertThat((message.text as UiText.Plain).text).contains("网络")
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(repo.firstPageCalls).isEqualTo(1)
    }

    @Test
    fun `move and copy send the picked target, root as null`() = runTest {
        repo.listings[null] = listOf(page(file("a")))
        val vm = viewModel()
        advanceUntilIdle()

        vm.requestMove(listOf("a"))
        assertThat(vm.dialog.value).isInstanceOf(NodeDialog.Picker::class.java)
        assertThat((vm.dialog.value as NodeDialog.Picker).mode).isEqualTo(PickerMode.MOVE)

        vm.moveTo(listOf("a"), Crumb("t", "目标"), rootName = "文件")
        advanceUntilIdle()
        assertThat(repo.calls).contains("move([a],t)")
        assertThat(vm.dialog.value).isNull()

        vm.copyTo(listOf("a"), Crumb(null, ""), rootName = "文件")
        advanceUntilIdle()
        assertThat(repo.calls).contains("copy([a],null)")
    }

    @Test
    fun `onResumed reloads only when something changed elsewhere`() = runTest {
        repo.listings[null] = listOf(page(file("a")))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onResumed()
        advanceUntilIdle()
        assertThat(repo.firstPageCalls).isEqualTo(1)

        repo.delete(listOf("a")) // 别的页面(回收站/子目录)改了东西
        vm.onResumed()
        advanceUntilIdle()
        assertThat(repo.firstPageCalls).isEqualTo(2)

        vm.onResumed()
        advanceUntilIdle()
        assertThat(repo.firstPageCalls).isEqualTo(2)
    }

    @Test
    fun `onResumed reloads a list older than ten minutes so thumbnail urls are fresh`() = runTest {
        repo.listings[null] = listOf(page(file("a")))
        repo.fetchedAtProvider = { System.currentTimeMillis() - DriveViewModel.STALE_AFTER_MS - 1_000 }
        val vm = viewModel()
        advanceUntilIdle()
        assertThat(repo.firstPageCalls).isEqualTo(1)

        repo.fetchedAtProvider = { System.currentTimeMillis() }
        vm.onResumed()
        advanceUntilIdle()
        assertThat(repo.firstPageCalls).isEqualTo(2)

        // 刚刷新过:不再重复
        vm.onResumed()
        advanceUntilIdle()
        assertThat(repo.firstPageCalls).isEqualTo(2)
    }

    @Test
    fun `failed refresh keeps the loaded pages instead of the cached first page`() = runTest {
        repo.listings[null] = listOf(page(file("a"), nextCursor = "1"), page(file("b")))
        val vm = viewModel()
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        assertThat(vm.state.value.items.map { it.id }).containsExactly("a", "b").inOrder()

        // 断网刷新:仓库会回退到缓存的首页(只有 a),页面不该拿它覆盖已加载的两页
        repo.cache[null] = page(file("a"))
        repo.networkDown = true
        vm.refresh()
        advanceUntilIdle()

        val s = vm.state.value
        assertThat(s.items.map { it.id }).containsExactly("a", "b").inOrder()
        assertThat(s.fromCache).isFalse()
        assertThat(s.isRefreshing).isFalse()
    }
}
