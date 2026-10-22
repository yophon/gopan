package xyz.yophon.gopan.feature.activity

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.activity.ActivityAction
import xyz.yophon.gopan.core.data.activity.ActorKind
import xyz.yophon.gopan.core.data.activity.ActorSummary
import xyz.yophon.gopan.core.data.activity.FakeActivityRepository
import xyz.yophon.gopan.core.data.activity.FakeActivityRepository.Companion.ANDROID
import xyz.yophon.gopan.core.data.activity.FakeActivityRepository.Companion.RCLONE
import xyz.yophon.gopan.core.data.activity.FakeActivityRepository.Companion.activity
import xyz.yophon.gopan.core.data.activity.UndoItem
import xyz.yophon.gopan.core.data.activity.UndoOutcome
import xyz.yophon.gopan.core.data.activity.UndoTarget
import xyz.yophon.gopan.feature.drive.FakeNodesRepository
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.MainDispatcherRule
import xyz.yophon.gopan.feature.drive.UiText
import xyz.yophon.gopan.feature.notifications.NotificationNav

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val repo = FakeActivityRepository()
    private val nodes = FakeNodesRepository()

    private fun vmTest(
        args: Map<String, Any?> = emptyMap(),
        block: suspend TestScope.(ActivityViewModel, List<ActivityEffect>) -> Unit,
    ) = runTest {
        val vm = ActivityViewModel(SavedStateHandle(args), repo, nodes)
        val effects = mutableListOf<ActivityEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        block(vm, effects)
    }

    private fun List<ActivityEffect>.texts() = filterIsInstance<ActivityEffect.Message>().map { it.text }

    @Test
    fun `first page and actors load`() {
        repo.items = listOf(activity("3"), activity("2"), activity("1"))
        repo.actors = listOf(ActorSummary(ANDROID, 3, "2026-10-21T10:00:00Z"))
        vmTest { vm, _ ->
            val s = vm.state.value
            assertThat(s.status).isEqualTo(ListStatus.Content)
            assertThat(s.items.map { it.id }).containsExactly("3", "2", "1").inOrder()
            assertThat(s.actors).hasSize(1)
            assertThat(s.query.isEmpty).isTrue()
            // 没按来源筛:不能「全部撤回」
            assertThat(s.canUndoAll).isFalse()
        }
    }

    @Test
    fun `pages are appended until the cursor runs out`() {
        repo.pageSize = 2
        repo.items = (5 downTo 1).map { activity("$it") }
        vmTest { vm, _ ->
            vm.loadMore()
            advanceUntilIdle()
            vm.loadMore()
            advanceUntilIdle()
            assertThat(vm.state.value.items.map { it.id }).containsExactly("5", "4", "3", "2", "1").inOrder()
            assertThat(vm.state.value.nextCursor).isNull()
        }
    }

    @Test
    fun `notification args filter by source and time`() {
        repo.items = listOf(
            activity("3", actor = RCLONE, action = ActivityAction.DELETE, at = "2026-10-21T10:00:00Z"),
            activity("2", actor = ANDROID, at = "2026-10-21T09:00:00Z"),
            activity("1", actor = RCLONE, action = ActivityAction.DELETE, at = "2026-10-20T09:00:00Z"),
        )
        vmTest(mapOf(ActivityViewModel.ACTOR_ARG to "webdav:ap-9", ActivityViewModel.SINCE_ARG to "2026-10-21T00:00:00Z")) { vm, _ ->
            val s = vm.state.value
            assertThat(s.query.actorKind).isEqualTo(ActorKind.WEBDAV)
            assertThat(s.query.actorRef).isEqualTo("ap-9")
            assertThat(s.items.map { it.id }).containsExactly("3")
            assertThat(s.canUndoAll).isTrue()

            // 去掉时间段:同一来源的全部
            vm.clearSince()
            advanceUntilIdle()
            assertThat(vm.state.value.items.map { it.id }).containsExactly("3", "1").inOrder()

            // 换回全部来源
            vm.selectActor(null)
            advanceUntilIdle()
            assertThat(vm.state.value.items).hasSize(3)
            assertThat(repo.calls.filter { it.startsWith("list") })
                .containsExactly("list(WEBDAV,ap-9,2026-10-21T00:00:00Z,null)", "list(WEBDAV,ap-9,null,null)", "list(null,null,null,null)")
                .inOrder()
        }
    }

    @Test
    fun `single undo reports the result and refreshes`() {
        repo.items = listOf(activity("2"), activity("1"))
        vmTest { vm, effects ->
            vm.undo(vm.state.value.items.first())
            advanceUntilIdle()
            assertThat(repo.calls).contains("undo(${UndoTarget.Ids(listOf("2"))})")
            assertThat(effects.texts()).containsExactly(UiText.Plain("已撤回 1 项"))
            // 刷新后这一条标着已撤回
            assertThat(vm.state.value.items.first().undone).isTrue()
            assertThat(vm.state.value.working).isNull()
        }
    }

    @Test
    fun `single undo that cannot be done shows the reason`() {
        repo.items = listOf(activity("1"))
        repo.outcomeFor = { UndoOutcome(0, 1, listOf(UndoItem("1", "a", ActivityAction.MOVE, false, "原文件夹已彻底删除"))) }
        vmTest { vm, effects ->
            vm.undo(vm.state.value.items.first())
            advanceUntilIdle()
            assertThat(effects.texts()).containsExactly(UiText.Plain("撤不了:原文件夹已彻底删除"))
            assertThat(repo.calls.count { it.startsWith("list") }).isEqualTo(1)
        }
    }

    @Test
    fun `rows that cannot be undone are not sent`() {
        repo.items = listOf(activity("2", action = ActivityAction.OVERWRITE, undoable = false), activity("1", undoneAt = "2026-10-21T11:00:00Z"))
        vmTest { vm, _ ->
            vm.state.value.items.forEach(vm::undo)
            advanceUntilIdle()
            assertThat(repo.calls.none { it.startsWith("undo") }).isTrue()
        }
    }

    @Test
    fun `group undo previews first and undoes after confirming`() {
        repo.items = listOf(activity("3", batchId = "b"), activity("2", batchId = "b"), activity("1", batchId = "c"))
        repo.outcomeFor = { target ->
            when (target) {
                is UndoTarget.Batch -> UndoOutcome(
                    1, 1,
                    listOf(UndoItem("3", "a", ActivityAction.MOVE, true), UndoItem("2", "b", ActivityAction.MOVE, false, "文件在回收站里")),
                )
                else -> UndoOutcome(0, 0, emptyList())
            }
        }
        vmTest { vm, effects ->
            vm.toggleGroup("b")
            assertThat(vm.state.value.expanded).containsExactly("b")

            vm.requestUndoGroup("b")
            advanceUntilIdle()
            val confirm = vm.state.value.confirm!!
            assertThat(confirm.target).isEqualTo(UndoTarget.Batch("b"))
            assertThat(confirm.all).isFalse()
            assertThat(ActivityLogic.previewText(confirm.preview)).isEqualTo("会撤回 1 项,1 项撤不了:")
            assertThat(repo.calls.none { it.startsWith("undo") }).isTrue()

            vm.confirmUndo()
            advanceUntilIdle()
            assertThat(repo.calls).contains("undo(${UndoTarget.Batch("b")})")
            assertThat(vm.state.value.confirm).isNull()
            assertThat(effects.texts()).containsExactly(UiText.Plain("已撤回 1 项,1 项没撤成"))
        }
    }

    @Test
    fun `dismissing the preview undoes nothing`() {
        repo.items = listOf(activity("2", batchId = "b"), activity("1", batchId = "b"))
        vmTest { vm, _ ->
            vm.requestUndoGroup("b")
            advanceUntilIdle()
            vm.dismissConfirm()
            assertThat(vm.state.value.confirm).isNull()
            assertThat(repo.calls.none { it.startsWith("undo") }).isTrue()
        }
    }

    @Test
    fun `undo all uses the source filter`() {
        repo.items = listOf(activity("2", actor = RCLONE, action = ActivityAction.DELETE), activity("1", actor = ANDROID))
        vmTest { vm, effects ->
            // 没选来源时什么都不做
            vm.requestUndoAll()
            advanceUntilIdle()
            assertThat(vm.state.value.confirm).isNull()

            vm.selectActor(RCLONE)
            advanceUntilIdle()
            vm.requestUndoAll()
            advanceUntilIdle()
            val confirm = vm.state.value.confirm!!
            assertThat(confirm.all).isTrue()
            assertThat((confirm.target as UndoTarget.Filter).query.actorRef).isEqualTo("ap-9")
            assertThat(confirm.preview.done).isEqualTo(1)
            vm.confirmUndo()
            advanceUntilIdle()
            assertThat(effects.texts().last()).isEqualTo(UiText.Plain("已撤回 1 项"))
        }
    }

    @Test
    fun `opening a row goes to the node, gone nodes only say so`() {
        val file = FakeNodesRepository.file("f1", parentId = "dir")
        val folder = FakeNodesRepository.folder("d1", parentId = null)
        repo.items = listOf(activity("3", node = file), activity("2", node = folder), activity("1"))
        vmTest { vm, effects ->
            val (a3, a2, a1) = vm.state.value.items
            vm.open(a3)
            vm.open(a2)
            vm.open(a1)
            val navs = effects.filterIsInstance<ActivityEffect.Open>().map { it.nav }
            assertThat(navs).containsExactly(
                NotificationNav.Drive(folderId = "dir", previewId = "f1"),
                NotificationNav.Drive(folderId = "d1"),
            ).inOrder()
            assertThat(effects.texts().map { (it as UiText.Res).id }).containsExactly(R.string.activity_msg_node_gone)
        }
    }

    @Test
    fun `load failure shows an error page, refresh failure keeps the list`() {
        repo.failNext = true
        repo.items = listOf(activity("1"))
        vmTest { vm, effects ->
            assertThat(vm.state.value.status).isInstanceOf(ListStatus.Error::class.java)
            vm.retry()
            advanceUntilIdle()
            assertThat(vm.state.value.status).isEqualTo(ListStatus.Content)
            repo.failNext = true
            vm.refresh()
            advanceUntilIdle()
            assertThat(vm.state.value.items).hasSize(1)
            assertThat(effects.texts()).isNotEmpty()
        }
    }
}
