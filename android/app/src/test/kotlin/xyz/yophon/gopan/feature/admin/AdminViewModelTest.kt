package xyz.yophon.gopan.feature.admin

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.auth.UserSummary
import xyz.yophon.gopan.core.data.ScreenCache
import xyz.yophon.gopan.core.data.admin.AdminOverview
import xyz.yophon.gopan.core.data.admin.AdminRepository
import xyz.yophon.gopan.core.data.admin.AdminUser
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.MainDispatcherRule
import xyz.yophon.gopan.feature.drive.UiText

class FakeAdminRepository : AdminRepository {
    val users = mutableListOf(
        AdminUser("me", "admin", isAdmin = true, disabled = false, quotaBytes = 100L shl 30, usedBytes = 1, createdAt = "2026-01-01T00:00:00Z"),
        AdminUser("u1", "bob", isAdmin = false, disabled = false, quotaBytes = 10L shl 30, usedBytes = 0, createdAt = "2026-02-01T00:00:00Z"),
    )
    var failed = 3
    val calls = mutableListOf<String>()
    var createError: GopanException? = null

    override suspend fun users() = users.toList()

    override suspend fun overview() = AdminOverview(users.size, 1, 2, 3, listOf("done" to 10, "failed" to failed))

    override suspend fun createUser(username: String, password: String, quotaBytes: Long): AdminUser {
        createError?.let { throw it }
        calls += "create $username $quotaBytes"
        return AdminUser("n${users.size}", username, false, false, quotaBytes, 0, "").also { users += it }
    }

    override suspend fun setQuota(userId: String, quotaBytes: Long) {
        calls += "quota $userId $quotaBytes"
    }

    override suspend fun setDisabled(userId: String, disabled: Boolean) {
        calls += "disabled $userId $disabled"
    }

    override suspend fun resetPassword(userId: String): String {
        calls += "reset $userId"
        return "Np-12345678"
    }

    override suspend fun retryFailedTasks(): Int {
        val n = failed
        failed = 0
        return n
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AdminViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repo = FakeAdminRepository()
    private val store = SessionStore().apply { setSignedIn("t", UserSummary("me", "admin", 0, 0, true)) }

    @Test
    fun `loads users (admins first) and the overview`() = runTest {
        val vm = AdminViewModel(repo, store, ScreenCache(SessionStore()))
        advanceUntilIdle()
        val s = vm.state.value
        assertThat(s.status).isEqualTo(ListStatus.Content)
        assertThat(s.users.map { it.username }).containsExactly("admin", "bob").inOrder()
        assertThat(s.overview?.failedTasks).isEqualTo(3)
    }

    @Test
    fun `coming back shows the cached overview and users at once and then the fresh ones`() = runTest {
        val cache = ScreenCache(store)
        AdminViewModel(repo, store, cache)
        advanceUntilIdle()

        repo.users += AdminUser("u2", "carol", isAdmin = false, disabled = false, quotaBytes = 1L shl 30, usedBytes = 0, createdAt = "")
        val vm = AdminViewModel(repo, store, cache)
        // 联网的那次还没跑:上次的概览与用户已经在了
        assertThat(vm.state.value.status).isEqualTo(ListStatus.Content)
        assertThat(vm.state.value.users.map { it.username }).containsExactly("admin", "bob").inOrder()
        assertThat(vm.state.value.overview?.failedTasks).isEqualTo(3)

        advanceUntilIdle()
        assertThat(vm.state.value.users.map { it.username }).containsExactly("admin", "bob", "carol").inOrder()
    }

    @Test
    fun `creating a user validates input and converts gb to bytes`() = runTest {
        val vm = AdminViewModel(repo, store, ScreenCache(SessionStore()))
        advanceUntilIdle()
        vm.show(AdminDialog.Create)
        vm.createUser("x", "12345678", "1")
        assertThat(vm.state.value.dialogError).contains("用户名")
        vm.createUser("carol", "short", "1")
        assertThat(vm.state.value.dialogError).contains("密码")
        vm.createUser("carol", "12345678", "0")
        assertThat(vm.state.value.dialogError).contains("配额")
        vm.createUser(" carol ", "12345678", "2.5")
        advanceUntilIdle()
        assertThat(repo.calls).containsExactly("create carol ${(2.5 * (1L shl 30)).toLong()}")
        assertThat(vm.state.value.dialog).isNull()
        assertThat(vm.state.value.users.map { it.username }).contains("carol")
    }

    @Test
    fun `a server error stays on the dialog`() = runTest {
        repo.createError = GopanException(GopanErrors.USERNAME_TAKEN, "用户名已被占用")
        val vm = AdminViewModel(repo, store, ScreenCache(SessionStore()))
        advanceUntilIdle()
        vm.show(AdminDialog.Create)
        vm.createUser("bob", "12345678", "1")
        advanceUntilIdle()
        assertThat(vm.state.value.dialog).isEqualTo(AdminDialog.Create)
        assertThat(vm.state.value.dialogError).isEqualTo("用户名已被占用")
    }

    @Test
    fun `quota, disable and reset`() = runTest {
        val vm = AdminViewModel(repo, store, ScreenCache(SessionStore()))
        advanceUntilIdle()
        val bob = vm.state.value.users.first { it.username == "bob" }
        vm.setQuota(bob, "abc")
        assertThat(vm.state.value.dialogError).isNotNull()
        vm.setQuota(bob, "20")
        advanceUntilIdle()
        vm.toggleDisabled(bob)
        advanceUntilIdle()
        // 自己不能禁用自己
        vm.toggleDisabled(vm.state.value.users.first { it.id == "me" })
        advanceUntilIdle()
        vm.resetPassword(bob)
        advanceUntilIdle()
        assertThat(repo.calls).containsExactly("quota u1 ${20L shl 30}", "disabled u1 true", "reset u1").inOrder()
        assertThat(vm.state.value.dialog).isEqualTo(AdminDialog.NewPassword(bob, "Np-12345678"))
    }

    @Test
    fun `retrying failed tasks reports how many`() = runTest {
        val vm = AdminViewModel(repo, store, ScreenCache(SessionStore()))
        advanceUntilIdle()
        vm.effects.test {
            vm.retryFailedTasks()
            val msg = awaitItem() as AdminEffect.Message
            assertThat((msg.text as UiText.Res).id).isEqualTo(R.string.admin_msg_retried)
        }
        advanceUntilIdle()
        assertThat(vm.state.value.overview?.failedTasks).isEqualTo(0)
    }

    @Test
    fun `gb text conversions`() {
        assertThat(AdminViewModel.parseGb("2.5")).isEqualTo(2.5)
        assertThat(AdminViewModel.parseGb(" 10 ")).isEqualTo(10.0)
        assertThat(AdminViewModel.parseGb("-1")).isNull()
        assertThat(AdminViewModel.parseGb("x")).isNull()
        assertThat(AdminViewModel.bytesToGbText(10L shl 30)).isEqualTo("10")
        assertThat(AdminViewModel.bytesToGbText((2.5 * (1L shl 30)).toLong())).isEqualTo("2.5")
    }
}
