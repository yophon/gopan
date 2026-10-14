package xyz.yophon.gopan.feature.login

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import xyz.yophon.gopan.core.network.GopanException
import javax.inject.Inject

/**
 * 登录页状态机。所有动作都经 [LoginGateway];成功后不导航 —— SessionManager 把 AuthState 置为 SignedIn,
 * RootNavHost 整棵树重建,本 ViewModel 随之销毁。
 *
 * Yophon ID 流程分两段:[startYophonId] 取授权 Intent 并通过 [events] 交给 UI 启动授权页,
 * busy 一直保持到 [onYophonIdResult](或 [onYophonIdLaunchFailed])把流程收尾。
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val gateway: LoginGateway,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private val _events = Channel<LoginEvent>(Channel.BUFFERED)
    val events: Flow<LoginEvent> = _events.receiveAsFlow()

    private var initialModeApplied = false

    /** 路由决定的初始视图(Routes.Register 直接打开注册 tab);只在 ViewModel 新建后生效一次 */
    fun applyInitialMode(mode: LoginMode) {
        if (initialModeApplied) return
        initialModeApplied = true
        switchMode(mode)
    }

    fun showYophonId() = switchMode(LoginMode.YOPHON_ID)

    fun showPasswordLogin() = switchMode(LoginMode.PASSWORD)

    fun showRegister() = switchMode(LoginMode.REGISTER)

    fun dismissError() {
        _state.update { it.copy(error = null, errorField = null) }
    }

    /** 密码登录;用户名两端空白忽略(服务端同样 trim) */
    fun login(username: String, password: String) {
        val name = username.trim()
        LoginRules.validateLogin(name, password)?.let { reject(it); return }
        runAction { gateway.loginWithPassword(name, password) }
    }

    fun register(username: String, password: String, confirm: String) {
        val name = username.trim()
        LoginRules.validateRegister(name, password, confirm)?.let { reject(it); return }
        runAction { gateway.register(name, password) }
    }

    /** 第 1 段:取授权 Intent 交给 UI 启动;成功后仍保持 busy,等授权页返回 */
    fun startYophonId() {
        runAction(keepBusyOnSuccess = true) {
            val intent = gateway.buildYophonIdIntent()
            _events.send(LoginEvent.LaunchAuthorization(intent))
        }
    }

    /** 第 2 段:授权页返回(取消时 data 可能为 null)。进程重建后 busy 可能已是 false,照常处理 */
    fun onYophonIdResult(data: Intent?) {
        _state.update { it.copy(busy = true, error = null, errorField = null) }
        viewModelScope.launch {
            try {
                gateway.completeYophonId(data)
                _state.update { it.copy(busy = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    /** UI 没能启动授权页(没有可用浏览器等) */
    fun onYophonIdLaunchFailed(e: Throwable) = fail(e)

    // ---- 内部 ----

    private fun switchMode(mode: LoginMode) {
        val current = _state.value
        if (current.busy) return
        val target = if (mode == LoginMode.REGISTER && !current.showRegisterTab) LoginMode.PASSWORD else mode
        if (target == current.mode && current.error == null) return
        _state.update { it.copy(mode = target, error = null, errorField = null) }
    }

    private fun runAction(keepBusyOnSuccess: Boolean = false, block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, errorField = null) }
        viewModelScope.launch {
            try {
                block()
                if (!keepBusyOnSuccess) _state.update { it.copy(busy = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    private fun reject(error: FieldError) {
        _state.update { it.copy(error = error.message, errorField = error.field) }
    }

    private fun fail(e: Throwable) {
        val text = LoginRules.errorText(e)
        // 只记错误码,不记异常对象:链路里的异常可能带 URL、描述等,不打印任何可能敏感的内容
        if (text != null) Timber.w("登录失败:%s", (e as? GopanException)?.code ?: e.javaClass.simpleName)
        val closed = LoginRules.closesRegistration(e)
        _state.update { s ->
            s.copy(
                busy = false,
                error = text,
                errorField = null,
                registerOpen = if (closed) false else s.registerOpen,
                mode = if (closed && s.mode == LoginMode.REGISTER) LoginMode.PASSWORD else s.mode,
            )
        }
    }
}
