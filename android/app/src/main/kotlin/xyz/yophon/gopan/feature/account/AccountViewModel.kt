package xyz.yophon.gopan.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import xyz.yophon.gopan.core.auth.AuthState
import xyz.yophon.gopan.core.auth.SessionManager
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.auth.UserSummary
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.core.prefs.DriveViewMode
import xyz.yophon.gopan.core.prefs.ThemeMode
import xyz.yophon.gopan.core.prefs.UserPreferencesRepository
import javax.inject.Inject

/** 改密码对话框的状态;null 表示没打开 */
data class PasswordDialogState(
    val busy: Boolean = false,
    /** 本地校验没过(UI 按枚举给文案) */
    val validationError: ChangePasswordError? = null,
    /** 服务端拒绝(旧密码错误等),已是中文 */
    val serverError: String? = null,
)

/** 一次性提示(Snackbar);UI 显示后调 [AccountViewModel.consumeMessage] */
data class AccountMessage(
    val text: String,
    /** 同一文案连续出现时让 UI 能区分 */
    val id: Long,
)

data class AccountUiState(
    /** 当前用户;登录后 SessionStore 里就有缓存,所以通常不为空,只有拉 me 失败且无缓存时才是 null */
    val user: UserSummary? = null,
    /** 没有用户可显示时的整页加载 */
    val loading: Boolean = false,
    /** 下拉刷新中 */
    val refreshing: Boolean = false,
    /** 没有用户可显示时的整页错误;有用户时错误走 [message] */
    val loadError: String? = null,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val driveViewMode: DriveViewMode = DriveViewMode.LIST,
    val passwordDialog: PasswordDialogState? = null,
    val loggingOut: Boolean = false,
    val message: AccountMessage? = null,
) {
    val usage: UsageSummary? get() = user?.let { UsageSummary(usedBytes = it.usedBytes, quotaBytes = it.quotaBytes) }
    val isAdmin: Boolean get() = user?.isAdmin == true
}

/**
 * 「我的」页:进入时拉一次 me(用量会变),下拉刷新;改密码、登出、主题与列表视图偏好。
 * 用户信息以 [SessionStore] 为准(me / refresh 都会写回去),这里只负责触发与错误态。
 */
@HiltViewModel
class AccountViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val sessionStore: SessionStore,
    private val preferences: UserPreferencesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AccountUiState(user = sessionStore.currentUser))
    val state: StateFlow<AccountUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var messageSeq = 0L

    init {
        viewModelScope.launch {
            sessionStore.state.collect { auth ->
                val user = (auth as? AuthState.SignedIn)?.user
                _state.update { it.copy(user = user, loadError = if (user != null) null else it.loadError) }
            }
        }
        viewModelScope.launch {
            preferences.preferences.collect { prefs ->
                _state.update { it.copy(theme = prefs.theme, driveViewMode = prefs.driveViewMode) }
            }
        }
        load()
    }

    /** 下拉刷新 / 错误重试 */
    fun refresh() = load()

    private fun load() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            val hasUser = _state.value.user != null
            _state.update { it.copy(loading = !hasUser, refreshing = hasUser, loadError = null) }
            val result = gopanResult { sessionManager.reloadUser() }
            result.onFailure { e ->
                val ge = e as GopanException
                // UNAUTHENTICATED 到这里说明 refresh 也失败了,SessionManager 已切到登录页,不再提示
                if (!ge.isUnauthenticated) {
                    if (_state.value.user != null) postMessage(ge.message ?: "刷新失败") else _state.update { it.copy(loadError = ge.message) }
                }
            }
            _state.update { it.copy(loading = false, refreshing = false) }
        }
    }

    // ---- 改密码 ----

    fun openPasswordDialog() {
        _state.update { it.copy(passwordDialog = PasswordDialogState()) }
    }

    fun closePasswordDialog() {
        if (_state.value.passwordDialog?.busy == true) return
        _state.update { it.copy(passwordDialog = null) }
    }

    /** 输入变化时清掉上次的错误提示 */
    fun clearPasswordErrors() {
        _state.update { s ->
            val d = s.passwordDialog ?: return@update s
            if (d.validationError == null && d.serverError == null) s else s.copy(passwordDialog = d.copy(validationError = null, serverError = null))
        }
    }

    fun submitPasswordChange(oldPassword: String, newPassword: String, confirm: String) {
        val dialog = _state.value.passwordDialog ?: return
        if (dialog.busy) return
        ChangePasswordForm.validate(oldPassword, newPassword, confirm)?.let { err ->
            _state.update { it.copy(passwordDialog = dialog.copy(validationError = err, serverError = null)) }
            return
        }
        _state.update { it.copy(passwordDialog = dialog.copy(busy = true, validationError = null, serverError = null)) }
        viewModelScope.launch {
            val result = gopanResult { sessionManager.changePassword(oldPassword, newPassword) }
            result.fold(
                onSuccess = {
                    _state.update { it.copy(passwordDialog = null) }
                    postMessage(MESSAGE_PASSWORD_CHANGED)
                },
                onFailure = { e ->
                    val ge = e as GopanException
                    if (ge.isUnauthenticated) {
                        // 登录已失效,会话层已回登录页,对话框跟着关
                        _state.update { it.copy(passwordDialog = null) }
                    } else {
                        _state.update { s ->
                            s.copy(passwordDialog = s.passwordDialog?.copy(busy = false, serverError = ge.message))
                        }
                    }
                },
            )
        }
    }

    // ---- 登出 ----

    fun logout() {
        if (_state.value.loggingOut) return
        _state.update { it.copy(loggingOut = true) }
        viewModelScope.launch {
            try {
                sessionManager.logout()
            } finally {
                _state.update { it.copy(loggingOut = false) }
            }
        }
    }

    // ---- 外观偏好 ----

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch {
            runCatching { preferences.setTheme(mode) }.onFailure { Timber.w(it, "写主题偏好失败") }
        }
    }

    fun setDriveViewMode(mode: DriveViewMode) {
        viewModelScope.launch {
            runCatching { preferences.setDriveViewMode(mode) }.onFailure { Timber.w(it, "写列表视图偏好失败") }
        }
    }

    // ---- 提示 ----

    /** 占位功能点击时的提示 */
    fun notifyComingSoon() = postMessage(MESSAGE_COMING_SOON)

    fun consumeMessage(id: Long) {
        _state.update { if (it.message?.id == id) it.copy(message = null) else it }
    }

    private fun postMessage(text: String) {
        val msg = AccountMessage(text, ++messageSeq)
        _state.update { it.copy(message = msg) }
    }

    companion object {
        const val MESSAGE_PASSWORD_CHANGED = "密码已修改,其它设备已下线"
        const val MESSAGE_COMING_SOON = "该功能即将推出"
    }
}
