package xyz.yophon.gopan.feature.space

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.auth.UserSummary
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.NodeKind
import xyz.yophon.gopan.core.data.ScreenCache
import xyz.yophon.gopan.core.data.space.FakeSpaceRepository
import xyz.yophon.gopan.core.data.space.FakeSpaceRepository.Companion.group
import xyz.yophon.gopan.core.data.space.FakeSpaceRepository.Companion.spaceFile
import xyz.yophon.gopan.core.data.space.IdleLink
import xyz.yophon.gopan.core.data.space.IdleRequest
import xyz.yophon.gopan.core.data.space.IdleShare
import xyz.yophon.gopan.core.data.space.SpaceCategory
import xyz.yophon.gopan.core.data.space.SpaceFolderLevel
import xyz.yophon.gopan.core.data.space.SpaceIdle
import xyz.yophon.gopan.feature.drive.FakeNodesRepository
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.MainDispatcherRule
import xyz.yophon.gopan.feature.drive.UiText
import xyz.yophon.gopan.feature.notifications.NotificationNav

@OptIn(ExperimentalCoroutinesApi::class)
class SpaceViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val repo = FakeSpaceRepository()
    private val nodes = FakeNodesRepository()

    /** 没登录:缓存读不到也写不进,各标签按原来的方式加载 */
    private val noCache = ScreenCache(SessionStore())

    private fun signedInCache() = ScreenCache(SessionStore().apply { setSignedIn("t", UserSummary("u1", "alice", 0, 0, false)) })

    private fun <T : SpaceTabViewModel> vmTest(create: () -> T, block: suspend TestScope.(T, List<SpaceEffect>) -> Unit) = runTest {
        val vm = create()
        val effects = mutableListOf<SpaceEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        block(vm, effects)
    }

    private fun List<SpaceEffect>.resIds() = filterIsInstance<SpaceEffect.Message>().mapNotNull { (it.text as? UiText.Res)?.id }

    // ---- 总览 ----

    @Test
    fun `overview loads and purging the trash confirms first`() = vmTest({ SpaceOverviewViewModel(repo, nodes, noCache) }) { vm, effects ->
        assertThat(vm.state.value.status).isEqualTo(ListStatus.Content)
        assertThat(vm.state.value.overview?.trashBytes).isEqualTo(100)

        vm.requestPurgeTrash()
        assertThat(vm.state.value.confirmPurge).isTrue()
        assertThat(nodes.calls).doesNotContain("purgeTrash")
        vm.confirmPurgeTrash()
        advanceUntilIdle()
        assertThat(nodes.calls).contains("purgeTrash")
        assertThat(vm.state.value.confirmPurge).isFalse()
        assertThat(effects.resIds()).containsExactly(R.string.trash_msg_purged_all)
        // 清空后重查一次
        assertThat(repo.calls.count { it == "overview" }).isEqualTo(2)
    }

    @Test
    fun `purging all file versions confirms first and reloads`() = vmTest({ SpaceOverviewViewModel(repo, nodes, noCache) }) { vm, effects ->
        repo.overview = repo.overview.copy(versionFiles = 3, versionBytes = 300)
        vm.refresh()
        advanceUntilIdle()
        vm.requestPurgeVersions()
        assertThat(vm.state.value.confirmPurgeVersions).isTrue()
        assertThat(repo.calls).doesNotContain("purgeVersions")
        vm.confirmPurgeVersions()
        advanceUntilIdle()
        assertThat(repo.calls).contains("purgeVersions")
        assertThat(vm.state.value.confirmPurgeVersions).isFalse()
        assertThat(effects.resIds()).contains(R.string.space_versions_purged)
    }

    @Test
    fun `overview reloads when the drive changed elsewhere`() = vmTest({ SpaceOverviewViewModel(repo, nodes, noCache) }) { vm, _ ->
        vm.onShown()
        advanceUntilIdle()
        assertThat(repo.calls.count { it == "overview" }).isEqualTo(1)
        nodes.delete(listOf("x"))
        vm.onShown()
        advanceUntilIdle()
        assertThat(repo.calls.count { it == "overview" }).isEqualTo(2)
    }

    @Test
    fun `overview seen before shows at once while it reloads`() = runTest {
        val cache = signedInCache()
        SpaceOverviewViewModel(repo, nodes, cache)
        advanceUntilIdle()

        repo.overview = repo.overview.copy(trashFiles = 0, trashBytes = 0)
        val gate = CompletableDeferred<Unit>()
        repo.gate = gate
        val vm = SpaceOverviewViewModel(repo, nodes, cache)
        advanceUntilIdle()
        // 服务端还没回:上次的总览已经画出来
        assertThat(vm.state.value.status).isEqualTo(ListStatus.Content)
        assertThat(vm.state.value.overview?.trashBytes).isEqualTo(100)

        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(vm.state.value.overview?.trashBytes).isEqualTo(0)
    }

    // ---- 文件夹 ----

    private fun folder(id: String, parentId: String? = null) =
        Node(id = id, parentId = parentId, name = "名$id", kind = NodeKind.FOLDER, subtreeBytes = 10, subtreeCount = 1)

    @Test
    fun `folders drill down and back up`() {
        repo.levels[null] = SpaceFolderLevel("/", listOf(folder("a")), 0, 0)
        repo.levels["a"] = SpaceFolderLevel("/名a", listOf(folder("b", "a")), 2, 20)
        vmTest({ SpaceFoldersViewModel(repo, nodes, noCache) }) { vm, effects ->
            assertThat(vm.state.value.canGoUp).isFalse()
            vm.enter(vm.state.value.level!!.folders.single())
            advanceUntilIdle()
            assertThat(vm.state.value.level?.path).isEqualTo("/名a")
            assertThat(vm.state.value.canGoUp).isTrue()

            vm.open(vm.state.value.level!!.folders.single())
            assertThat(effects.filterIsInstance<SpaceEffect.Open>().single().nav).isEqualTo(NotificationNav.Drive(folderId = "b"))

            assertThat(vm.up()).isTrue()
            advanceUntilIdle()
            assertThat(vm.state.value.level?.path).isEqualTo("/")
            assertThat(vm.up()).isFalse()
            assertThat(repo.calls).containsExactly("folders(null)", "folders(a)", "folders(null)").inOrder()
        }
    }

    @Test
    fun `a folder level seen before shows at once when going back up`() = runTest {
        repo.levels[null] = SpaceFolderLevel("/", listOf(folder("a")), 0, 0)
        repo.levels["a"] = SpaceFolderLevel("/名a", listOf(folder("b", "a")), 2, 20)
        val vm = SpaceFoldersViewModel(repo, nodes, signedInCache())
        advanceUntilIdle()
        vm.enter(vm.state.value.level!!.folders.single())
        advanceUntilIdle()

        val gate = CompletableDeferred<Unit>()
        repo.gate = gate
        assertThat(vm.up()).isTrue()
        advanceUntilIdle()
        // 返回上一层不出整页加载:先画缓存,重查还在路上
        assertThat(vm.state.value.status).isEqualTo(ListStatus.Content)
        assertThat(vm.state.value.level?.path).isEqualTo("/")

        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(vm.state.value.level?.path).isEqualTo("/")
        assertThat(repo.calls).containsExactly("folders(null)", "folders(a)", "folders(null)").inOrder()
    }

    // ---- 大文件 ----

    @Test
    fun `large files filter by category and delete the selected`() {
        repo.large = listOf(
            spaceFile("v1", 900, category = SpaceCategory.VIDEO),
            spaceFile("i1", 500, category = SpaceCategory.IMAGE),
            spaceFile("v2", 300, category = SpaceCategory.VIDEO),
        )
        vmTest({ LargeFilesViewModel(repo, nodes, noCache) }) { vm, effects ->
            assertThat(vm.state.value.items).hasSize(3)
            vm.setCategory(SpaceCategory.VIDEO)
            advanceUntilIdle()
            assertThat(vm.state.value.items.map { it.node.id }).containsExactly("v1", "v2").inOrder()
            assertThat(repo.calls).containsExactly("large(null)", "large(VIDEO)").inOrder()

            vm.toggle("v1")
            vm.toggle("v2")
            vm.toggle("v2")
            assertThat(vm.state.value.selection).containsExactly("v1")
            assertThat(vm.state.value.selectedBytes).isEqualTo(900)

            vm.requestDelete()
            assertThat(vm.state.value.confirmDelete).isTrue()
            vm.confirmDelete()
            advanceUntilIdle()
            assertThat(nodes.calls).contains("delete([v1])")
            assertThat(vm.state.value.items.map { it.node.id }).containsExactly("v2")
            assertThat(vm.state.value.selection).isEmpty()
            assertThat(effects.resIds()).containsExactly(R.string.space_msg_deleted)
            // 自己删的不用回来再查
            vm.onShown()
            advanceUntilIdle()
            assertThat(repo.calls.count { it.startsWith("large") }).isEqualTo(2)
        }
    }

    @Test
    fun `select all toggles and a failed delete keeps the list`() {
        repo.empty = listOf(spaceFile("d1", folder = true), spaceFile("d2", folder = true))
        vmTest({ EmptyFoldersViewModel(repo, nodes, noCache) }) { vm, effects ->
            vm.selectAll()
            assertThat(vm.state.value.allSelected).isTrue()
            nodes.nextMutationError = xyz.yophon.gopan.core.network.GopanException("NETWORK", "网络连接失败")
            vm.requestDelete()
            vm.confirmDelete()
            advanceUntilIdle()
            assertThat(vm.state.value.items).hasSize(2)
            assertThat(vm.state.value.deleting).isFalse()
            assertThat(effects.filterIsInstance<SpaceEffect.Message>().single().text).isEqualTo(UiText.Plain("网络连接失败"))
            vm.selectAll()
            assertThat(vm.state.value.selection).isEmpty()
        }
    }

    // ---- 重复文件 ----

    @Test
    fun `duplicates default to keeping the earliest, page and delete`() {
        repo.duplicatePageSize = 1
        repo.duplicates = listOf(group("s1", 100, "a1", "a2", "a3"), group("s2", 10, "b1", "b2"))
        vmTest({ DuplicatesViewModel(repo, nodes, noCache) }) { vm, effects ->
            assertThat(vm.state.value.groups.map { it.sha256 }).containsExactly("s1")
            assertThat(vm.state.value.selection).containsExactly("a2", "a3")
            assertThat(vm.state.value.nextOffset).isEqualTo(1)

            vm.loadMore()
            advanceUntilIdle()
            assertThat(vm.state.value.groups.map { it.sha256 }).containsExactly("s1", "s2").inOrder()
            assertThat(vm.state.value.selection).containsExactly("a2", "a3", "b2")
            assertThat(vm.state.value.selectedBytes).isEqualTo(210)
            assertThat(vm.state.value.nextOffset).isNull()

            // 把最早的也勾上:整组都会删,确认时提醒
            vm.toggle("b1")
            assertThat(vm.state.value.fullySelectedGroups).isEqualTo(1)
            vm.toggle("b1")

            vm.requestDelete()
            vm.confirmDelete()
            advanceUntilIdle()
            assertThat(nodes.calls).contains("delete([a2, a3, b2])")
            assertThat(effects.resIds()).containsExactly(R.string.space_msg_deleted)
            // 删完从第一页重查
            assertThat(repo.calls.last()).isEqualTo("duplicates(0)")
        }
    }

    @Test
    fun `clearing duplicate selection deletes nothing`() {
        repo.duplicates = listOf(group("s1", 100, "a1", "a2"))
        vmTest({ DuplicatesViewModel(repo, nodes, noCache) }) { vm, _ ->
            vm.clearSelection()
            vm.requestDelete()
            assertThat(vm.state.value.confirmDelete).isFalse()
        }
    }

    // ---- 分享与直链 ----

    @Test
    fun `idle shares, links and requests can be revoked after confirming`() {
        repo.idle = SpaceIdle(
            shares = listOf(IdleShare("s1", "n1", "旅行照片", true, "2026-01-01T00:00:00Z", "2025-12-01T00:00:00Z", null, expired = true)),
            links = listOf(IdleLink("l1", "n2", "a.png", null, "2025-01-01T00:00:00Z", "2025-02-01T00:00:00Z", 7, expired = false)),
            requests = listOf(IdleRequest("r1", "收作业", "f1", "2026-09-01T00:00:00Z", "2026-08-01T00:00:00Z")),
        )
        vmTest({ SpaceIdleViewModel(repo, nodes, noCache) }) { vm, effects ->
            vm.requestRevoke(IdleRevoke.Link("l1", "a.png"))
            assertThat(vm.state.value.confirm).isEqualTo(IdleRevoke.Link("l1", "a.png"))
            vm.dismissRevoke()
            assertThat(repo.calls).doesNotContain("revokeLink(l1)")

            vm.requestRevoke(IdleRevoke.Link("l1", "a.png"))
            vm.confirmRevoke()
            advanceUntilIdle()
            vm.requestRevoke(IdleRevoke.Share("s1", "旅行照片"))
            vm.confirmRevoke()
            advanceUntilIdle()
            vm.requestRevoke(IdleRevoke.Request("r1", "收作业"))
            vm.confirmRevoke()
            advanceUntilIdle()
            assertThat(repo.calls).containsAtLeast("revokeLink(l1)", "revokeShare(s1)", "revokeRequest(r1)").inOrder()
            assertThat(vm.state.value.idle?.isEmpty).isTrue()
            assertThat(effects.resIds())
                .containsExactly(R.string.links_msg_revoked, R.string.shares_msg_revoked, R.string.collect_msg_revoked)
                .inOrder()
        }
    }

    @Test
    fun `load failure shows an error and retry recovers`() {
        repo.failNext = true
        vmTest({ StaleFilesViewModel(repo, nodes, noCache) }) { vm, _ ->
            assertThat(vm.state.value.status).isInstanceOf(ListStatus.Error::class.java)
            vm.retry()
            advanceUntilIdle()
            assertThat(vm.state.value.status).isEqualTo(ListStatus.Content)
        }
    }
}
