package xyz.yophon.gopan.feature.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import xyz.yophon.gopan.core.auth.AuthState
import xyz.yophon.gopan.core.prefs.DriveViewMode
import xyz.yophon.gopan.core.prefs.ThemeMode
import xyz.yophon.gopan.core.prefs.UserPreferencesRepository
import xyz.yophon.gopan.testutil.FakeGopanServer
import xyz.yophon.gopan.testutil.TestHarness

/** 纯内存的 DataStore<Preferences>,单测里替代磁盘文件 */
private class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val flow = MutableStateFlow<Preferences>(emptyPreferences())
    private val mutex = Mutex()
    override val data: Flow<Preferences> = flow
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = mutex.withLock {
        transform(flow.value).also { flow.value = it }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelTest {
    private lateinit var server: FakeGopanServer
    private lateinit var h: TestHarness
    private lateinit var prefs: UserPreferencesRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = FakeGopanServer()
        h = TestHarness(server)
        prefs = UserPreferencesRepository(InMemoryPreferencesDataStore())
    }

    @After
    fun tearDown() {
        h.close()
        server.close()
        Dispatchers.resetMain()
    }

    private fun newViewModel() = AccountViewModel(h.sessionManager, h.store, prefs)

    private suspend fun signIn() {
        h.sessionManager.loginWithPassword("alice", "secret")
        server.server.takeRequest()
    }

    private suspend fun AccountViewModel.awaitState(predicate: (AccountUiState) -> Boolean): AccountUiState =
        withTimeout(5_000) { state.first(predicate) }

    private suspend fun AccountViewModel.awaitIdle(): AccountUiState = awaitState { !it.loading && !it.refreshing }

    @Test
    fun `loads me on creation and exposes user and usage`() = runBlocking {
        signIn()
        val vm = newViewModel()
        // 构造时就有 SessionStore 里的缓存用户,不会出现整页加载
        assertThat(vm.state.value.user?.username).isEqualTo("alice")
        assertThat(vm.state.value.loading).isFalse()

        val s = vm.awaitIdle()
        assertThat(server.meCount.get()).isEqualTo(1)
        assertThat(s.user?.username).isEqualTo("alice")
        assertThat(s.usage?.quotaBytes).isEqualTo(1073741824L)
        assertThat(s.usage?.usedBytes).isEqualTo(12345L)
        assertThat(s.usage?.percent).isEqualTo(0)
        assertThat(s.isAdmin).isFalse()
        assertThat(s.loadError).isNull()
        assertThat(s.message).isNull()
        assertThat(s.theme).isEqualTo(ThemeMode.SYSTEM)
        assertThat(s.driveViewMode).isEqualTo(DriveViewMode.LIST)
    }

    @Test
    fun `refresh pulls me again and shows refreshing while in flight`() = runBlocking {
        signIn()
        val vm = newViewModel()
        vm.awaitIdle()
        vm.refresh()
        vm.awaitIdle()
        assertThat(server.meCount.get()).isEqualTo(2)
    }

    @Test
    fun `expired token is refreshed transparently and me still succeeds`() = runBlocking {
        signIn()
        server.validToken = "t2"
        server.nextRefreshToken = "t2"
        val vm = newViewModel()
        val s = vm.awaitIdle()
        assertThat(s.user?.username).isEqualTo("alice")
        assertThat(s.loadError).isNull()
        assertThat(server.refreshCount.get()).isEqualTo(1)
        assertThat(h.store.accessToken).isEqualTo("t2")
    }

    @Test
    fun `revoked session signs out without an error message`() = runBlocking {
        signIn()
        server.validToken = "gone"
        server.refreshOk = false
        val vm = newViewModel()
        val s = vm.awaitState { it.user == null }
        assertThat(h.store.state.value).isEqualTo(AuthState.SignedOut)
        assertThat(s.loadError).isNull()
        assertThat(s.message).isNull()
    }

    @Test
    fun `network failure keeps the cached user and posts a message`() = runBlocking {
        signIn()
        server.server.shutdown()
        val vm = newViewModel()
        val s = vm.awaitState { it.message != null }
        assertThat(s.user?.username).isEqualTo("alice")
        assertThat(s.loadError).isNull()
        assertThat(h.store.state.value).isInstanceOf(AuthState.SignedIn::class.java)
        val id = s.message!!.id
        vm.consumeMessage(id)
        assertThat(vm.state.value.message).isNull()
    }

    @Test
    fun `password dialog validates locally before hitting the server`() = runBlocking {
        signIn()
        val vm = newViewModel()
        vm.awaitIdle()
        val before = server.server.requestCount

        vm.openPasswordDialog()
        assertThat(vm.state.value.passwordDialog).isEqualTo(PasswordDialogState())

        vm.submitPasswordChange("oldpass123", "short", "short")
        assertThat(vm.state.value.passwordDialog?.validationError).isEqualTo(ChangePasswordError.NEW_TOO_SHORT)
        assertThat(vm.state.value.passwordDialog?.busy).isFalse()
        assertThat(server.server.requestCount).isEqualTo(before)

        vm.clearPasswordErrors()
        assertThat(vm.state.value.passwordDialog?.validationError).isNull()

        vm.closePasswordDialog()
        assertThat(vm.state.value.passwordDialog).isNull()
    }

    @Test
    fun `successful password change adopts the new token, closes the dialog and notifies`() = runBlocking {
        signIn()
        server.password = "oldpass123"
        val vm = newViewModel()
        vm.awaitIdle()

        vm.openPasswordDialog()
        vm.submitPasswordChange("oldpass123", "newpass456", "newpass456")
        val s = vm.awaitState { it.passwordDialog == null && it.message != null }
        assertThat(s.message?.text).isEqualTo(AccountViewModel.MESSAGE_PASSWORD_CHANGED)
        assertThat(server.changePasswordCount.get()).isEqualTo(1)
        assertThat(server.password).isEqualTo("newpass456")
        // 服务端重发的凭证直接采用,当前设备不需要重新登录
        assertThat(h.store.accessToken).isEqualTo(server.validToken)
        assertThat(h.store.accessToken).isNotEqualTo("t1")
        assertThat(h.store.state.value).isInstanceOf(AuthState.SignedIn::class.java)
    }

    @Test
    fun `server rejection keeps the dialog open with the server message`() = runBlocking {
        signIn()
        server.password = "oldpass123"
        val vm = newViewModel()
        vm.awaitIdle()

        vm.openPasswordDialog()
        vm.submitPasswordChange("wrongold1", "newpass456", "newpass456")
        val s = vm.awaitState { it.passwordDialog?.serverError != null }
        assertThat(s.passwordDialog?.busy).isFalse()
        assertThat(s.passwordDialog?.serverError).isEqualTo("旧密码错误")
        assertThat(s.message).isNull()
        assertThat(h.store.accessToken).isEqualTo("t1")
        assertThat(server.password).isEqualTo("oldpass123")
    }

    @Test
    fun `logout clears the session`() = runBlocking {
        signIn()
        val vm = newViewModel()
        vm.awaitIdle()
        vm.logout()
        vm.awaitState { it.user == null && !it.loggingOut }
        assertThat(h.store.state.value).isEqualTo(AuthState.SignedOut)
        assertThat(h.cookieJar.hasCookie("gopan_rt")).isFalse()
    }

    @Test
    fun `theme and drive view preferences round-trip through the repository`() = runBlocking {
        signIn()
        val vm = newViewModel()
        vm.awaitIdle()

        vm.setTheme(ThemeMode.DARK)
        assertThat(vm.awaitState { it.theme == ThemeMode.DARK }.theme).isEqualTo(ThemeMode.DARK)
        assertThat(prefs.preferences.first().theme).isEqualTo(ThemeMode.DARK)

        vm.setDriveViewMode(DriveViewMode.GRID)
        assertThat(vm.awaitState { it.driveViewMode == DriveViewMode.GRID }.driveViewMode).isEqualTo(DriveViewMode.GRID)
        assertThat(prefs.preferences.first().driveViewMode).isEqualTo(DriveViewMode.GRID)
    }

    @Test
    fun `coming soon posts a message with a fresh id each time`() = runBlocking {
        signIn()
        val vm = newViewModel()
        vm.awaitIdle()
        vm.notifyComingSoon()
        val first = vm.state.value.message!!
        assertThat(first.text).isEqualTo(AccountViewModel.MESSAGE_COMING_SOON)
        vm.notifyComingSoon()
        val second = vm.state.value.message!!
        assertThat(second.id).isNotEqualTo(first.id)
        // 消费旧 id 不会吞掉新消息
        vm.consumeMessage(first.id)
        assertThat(vm.state.value.message).isEqualTo(second)
    }
}
