package xyz.yophon.gopan.core.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 登录态的内存部分:access token(15 分钟,只在内存)与 [AuthState]。
 * 写入只来自 [SessionManager];UI 与 ViewModel 只读 [state]。
 */
@Singleton
class SessionStore @Inject constructor() {

    @Volatile
    var accessToken: String? = null
        private set

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    val currentUser: UserSummary? get() = (_state.value as? AuthState.SignedIn)?.user

    /** token 可为 null:离线启动时只知道上次的用户,首个业务请求会触发 refresh */
    fun setSignedIn(accessToken: String?, user: UserSummary) {
        this.accessToken = accessToken
        _state.value = AuthState.SignedIn(user)
    }

    /** 只更新用户信息(用量变化等),不动 token */
    fun updateUser(user: UserSummary) {
        _state.update { if (it is AuthState.SignedIn) AuthState.SignedIn(user) else it }
    }

    fun setSignedOut() {
        accessToken = null
        _state.value = AuthState.SignedOut
    }
}
