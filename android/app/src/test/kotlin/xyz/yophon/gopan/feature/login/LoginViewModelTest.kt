package xyz.yophon.gopan.feature.login

import android.content.Intent
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import java.io.IOException

class LoginViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val gateway = FakeLoginGateway()
    private val vm by lazy { LoginViewModel(gateway) }
    private val state get() = vm.state.value

    private fun serverError(code: String, message: String = "服务端说不行", serverMessage: String? = null) =
        GopanException(code, message, serverMessage = serverMessage)

    // ---- 初始状态与视图切换 ----

    @Test
    fun `starts on the Yophon ID view with the register tab available`() {
        assertThat(state.mode).isEqualTo(LoginMode.YOPHON_ID)
        assertThat(state.busy).isFalse()
        assertThat(state.error).isNull()
        assertThat(state.registerOpen).isNull()
        assertThat(state.showRegisterTab).isTrue()
    }

    @Test
    fun `initial mode from the route is applied once`() {
        vm.applyInitialMode(LoginMode.REGISTER)
        assertThat(state.mode).isEqualTo(LoginMode.REGISTER)

        vm.showPasswordLogin()
        vm.applyInitialMode(LoginMode.REGISTER)
        assertThat(state.mode).isEqualTo(LoginMode.PASSWORD)
    }

    @Test
    fun `switching views clears the error`() = runTest {
        gateway.loginError = serverError(GopanErrors.BAD_CREDENTIALS)
        vm.showPasswordLogin()
        vm.login("alice", "wrong-password")
        assertThat(state.error).isEqualTo(LoginRules.TEXT_BAD_CREDENTIALS)

        vm.showRegister()

        assertThat(state.mode).isEqualTo(LoginMode.REGISTER)
        assertThat(state.error).isNull()
        assertThat(state.errorField).isNull()
    }

    // ---- 密码登录 ----

    @Test
    fun `password login trims the username and clears busy on success`() = runTest {
        vm.showPasswordLogin()

        vm.login("  alice ", "secret-12")

        assertThat(gateway.calls).containsExactly("login:alice:secret-12")
        assertThat(state.busy).isFalse()
        assertThat(state.error).isNull()
    }

    @Test
    fun `busy is set while the request is in flight and taps meanwhile are ignored`() = runTest {
        gateway.gate = CompletableDeferred()
        vm.showPasswordLogin()

        vm.login("alice", "secret-12")
        assertThat(state.busy).isTrue()
        assertThat(state.error).isNull()

        vm.login("bob", "another-1")
        vm.startYophonId()
        vm.showRegister()
        assertThat(gateway.calls).containsExactly("login:alice:secret-12")
        assertThat(state.mode).isEqualTo(LoginMode.PASSWORD)

        gateway.gate!!.complete(Unit)
        assertThat(state.busy).isFalse()
    }

    @Test
    fun `login requires both fields and does not hit the network`() = runTest {
        vm.showPasswordLogin()

        vm.login("   ", "x")
        assertThat(state.error).isEqualTo(LoginRules.TEXT_USERNAME_REQUIRED)
        assertThat(state.errorField).isEqualTo(LoginField.USERNAME)

        vm.login("alice", "")
        assertThat(state.error).isEqualTo(LoginRules.TEXT_PASSWORD_REQUIRED)
        assertThat(state.errorField).isEqualTo(LoginField.PASSWORD)

        assertThat(gateway.calls).isEmpty()
        assertThat(state.busy).isFalse()
    }

    @Test
    fun `server error codes map to the agreed texts`() = runTest {
        val expectations = listOf(
            GopanErrors.BAD_CREDENTIALS to "用户名或密码错误",
            GopanErrors.ACCOUNT_DISABLED to "账号已被禁用",
            GopanErrors.ID_REQUIRED to "请使用 Yophon ID 登录",
            GopanErrors.RATE_LIMITED to "尝试过于频繁",
            GopanErrors.NETWORK to "网络不可用",
        )
        vm.showPasswordLogin()
        for ((code, text) in expectations) {
            gateway.loginError = serverError(code)
            vm.login("alice", "secret-12")
            assertThat(state.busy).isFalse()
            assertThat(state.errorField).isNull()
            assertThat(state.error).isNotNull()
            assertThat(state.error).contains(text)
        }
    }

    @Test
    fun `INVALID_INPUT shows the server message`() = runTest {
        gateway.loginError = serverError(GopanErrors.INVALID_INPUT, "输入无效", serverMessage = "用户名长度需在 2~32 之间")
        vm.showPasswordLogin()

        vm.login("a", "secret-12")

        assertThat(state.error).isEqualTo("用户名长度需在 2~32 之间")
    }

    @Test
    fun `a raw IOException counts as a network error`() = runTest {
        gateway.loginError = IOException("unreachable")
        vm.showPasswordLogin()

        vm.login("alice", "secret-12")

        assertThat(state.error).isEqualTo(LoginRules.TEXT_NETWORK)
        assertThat(state.busy).isFalse()
    }

    // ---- 注册 ----

    @Test
    fun `register with mismatched passwords is rejected locally`() = runTest {
        vm.showRegister()

        vm.register("alice", "secret-12", "secret-13")

        assertThat(gateway.calls).isEmpty()
        assertThat(state.busy).isFalse()
        assertThat(state.error).isEqualTo(LoginRules.TEXT_PASSWORD_MISMATCH)
        assertThat(state.errorField).isEqualTo(LoginField.CONFIRM)
    }

    @Test
    fun `register validates lengths like the server before sending`() = runTest {
        vm.showRegister()

        vm.register("a", "secret-12", "secret-12")
        assertThat(state.error).isEqualTo(LoginRules.TEXT_USERNAME_LENGTH)
        assertThat(state.errorField).isEqualTo(LoginField.USERNAME)

        vm.register("alice", "short", "short")
        assertThat(state.error).isEqualTo(LoginRules.TEXT_PASSWORD_LENGTH)
        assertThat(state.errorField).isEqualTo(LoginField.PASSWORD)

        assertThat(gateway.calls).isEmpty()
    }

    @Test
    fun `register sends the trimmed username and maps USERNAME_TAKEN`() = runTest {
        vm.showRegister()
        vm.register(" alice ", "secret-12", "secret-12")
        assertThat(gateway.calls).containsExactly("register:alice:secret-12")
        assertThat(state.error).isNull()

        gateway.registerError = serverError(GopanErrors.USERNAME_TAKEN)
        vm.register("alice", "secret-12", "secret-12")
        assertThat(state.error).isEqualTo(LoginRules.TEXT_USERNAME_TAKEN)
        assertThat(state.mode).isEqualTo(LoginMode.REGISTER)
        assertThat(state.showRegisterTab).isTrue()
    }

    @Test
    fun `REGISTER_CLOSED hides the register tab and falls back to the login tab`() = runTest {
        gateway.registerError = serverError(GopanErrors.REGISTER_CLOSED, "注册已关闭")
        vm.showRegister()

        vm.register("alice", "secret-12", "secret-12")

        assertThat(state.registerOpen).isFalse()
        assertThat(state.showRegisterTab).isFalse()
        assertThat(state.mode).isEqualTo(LoginMode.PASSWORD)
        assertThat(state.error).isEqualTo(LoginRules.TEXT_REGISTER_CLOSED)
        assertThat(state.busy).isFalse()

        // 之后再想进注册 tab 也只会停在登录 tab
        vm.showRegister()
        assertThat(state.mode).isEqualTo(LoginMode.PASSWORD)
    }

    // ---- Yophon ID ----

    @Test
    fun `yophon id start emits the authorization intent and stays busy until the result arrives`() = runTest {
        vm.events.test {
            vm.startYophonId()

            val event = awaitItem()
            assertThat(event).isInstanceOf(LoginEvent.LaunchAuthorization::class.java)
            assertThat((event as LoginEvent.LaunchAuthorization).intent).isSameInstanceAs(gateway.authorizationIntent)
            assertThat(state.busy).isTrue()

            vm.onYophonIdResult(Intent())
            assertThat(gateway.calls).containsExactly("build", "complete:intent").inOrder()
            assertThat(state.busy).isFalse()
            assertThat(state.error).isNull()
            expectNoEvents()
        }
    }

    @Test
    fun `cancelling the authorization page shows no error`() = runTest {
        gateway.completeError = GopanException(GopanErrors.ID_CANCELLED, "登录已取消")
        vm.startYophonId()

        vm.onYophonIdResult(null)

        assertThat(state.busy).isFalse()
        assertThat(state.error).isNull()
    }

    @Test
    fun `a 403 from the ticket exchange shows the unauthorised text`() = runTest {
        for (code in listOf(GopanErrors.ID_FORBIDDEN, GopanErrors.ID_TICKET_DENIED)) {
            gateway.completeError = GopanException(code, "账号未授权或登录暂不可用")
            vm.startYophonId()
            vm.onYophonIdResult(Intent())

            assertThat(state.busy).isFalse()
            assertThat(state.error).isEqualTo("账号未授权或登录暂不可用,请重试")
        }
    }

    @Test
    fun `discovery failure surfaces its message and clears busy without emitting an event`() = runTest {
        gateway.buildError = GopanException(GopanErrors.ID_DISCOVERY_FAILED, "无法连接账号中心,请检查网络")

        vm.events.test {
            vm.startYophonId()
            assertThat(state.busy).isFalse()
            assertThat(state.error).isEqualTo("无法连接账号中心,请检查网络")
            expectNoEvents()
        }
    }

    @Test
    fun `failing to launch the authorization page resets busy with a readable text`() = runTest {
        vm.startYophonId()
        assertThat(state.busy).isTrue()

        vm.onYophonIdLaunchFailed(IllegalStateException("no browser"))

        assertThat(state.busy).isFalse()
        assertThat(state.error).isNotEmpty()
    }

    @Test
    fun `yophon id errors do not affect the register tab`() = runTest {
        gateway.completeError = GopanException(GopanErrors.ID_SESSION_FAILED, "登录成功但会话建立失败,请重试")
        vm.startYophonId()
        vm.onYophonIdResult(Intent())

        assertThat(state.error).isEqualTo("登录成功但会话建立失败,请重试")
        assertThat(state.registerOpen).isNull()
        assertThat(state.mode).isEqualTo(LoginMode.YOPHON_ID)
    }
}
