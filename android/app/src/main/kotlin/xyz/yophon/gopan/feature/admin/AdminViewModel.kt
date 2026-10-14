package xyz.yophon.gopan.feature.admin

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.data.admin.AdminOverview
import xyz.yophon.gopan.core.data.admin.AdminRepository
import xyz.yophon.gopan.core.data.admin.AdminUser
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.UiText
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToLong

/** 管理端的弹层 */
sealed interface AdminDialog {
    data object Create : AdminDialog

    data class Actions(val user: AdminUser) : AdminDialog

    data class Quota(val user: AdminUser) : AdminDialog

    data class ConfirmDisable(val user: AdminUser) : AdminDialog

    data class ConfirmReset(val user: AdminUser) : AdminDialog

    /** 重置后的新密码:只显示这一次 */
    data class NewPassword(val user: AdminUser, val password: String) : AdminDialog
}

@Immutable
data class AdminUiState(
    val status: ListStatus = ListStatus.Loading,
    val refreshing: Boolean = false,
    val users: List<AdminUser> = emptyList(),
    val overview: AdminOverview? = null,
    val dialog: AdminDialog? = null,
    /** 对话框里的提交中 / 服务端错误 */
    val submitting: Boolean = false,
    val dialogError: String? = null,
    val retrying: Boolean = false,
)

sealed interface AdminEffect {
    data class Message(val text: UiText) : AdminEffect
}

/**
 * 管理端:概览(用户数、用量、物理存储、任务队列,失败任务可重排)、用户列表、建号、调配额、禁用 / 启用、重置密码。
 * 与网页端「管理」页一致;自己不能禁用自己。
 */
@HiltViewModel
class AdminViewModel @Inject constructor(
    private val repo: AdminRepository,
    sessionStore: SessionStore,
) : ViewModel() {

    private val _state = MutableStateFlow(AdminUiState())
    val state: StateFlow<AdminUiState> = _state.asStateFlow()

    private val _effects = Channel<AdminEffect>(Channel.BUFFERED)
    val effects: Flow<AdminEffect> = _effects.receiveAsFlow()

    val selfId: String? = sessionStore.currentUser?.id

    init {
        load(silent = false)
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        load(silent = true)
    }

    fun retry() = load(silent = false)

    private fun load(silent: Boolean) {
        if (!silent) _state.update { it.copy(status = ListStatus.Loading) }
        viewModelScope.launch {
            gopanResult {
                coroutineScope {
                    val users = async { repo.users() }
                    val overview = async { repo.overview() }
                    users.await() to overview.await()
                }
            }.onSuccess { (users, overview) ->
                _state.update { it.copy(status = ListStatus.Content, users = sortUsers(users), overview = overview, refreshing = false) }
            }.onFailure { e ->
                if (_state.value.status == ListStatus.Content) {
                    _state.update { it.copy(refreshing = false) }
                    say(UiText.Plain(e.message ?: "加载失败"))
                } else {
                    _state.update { it.copy(status = ListStatus.Error(e.message ?: "加载失败"), refreshing = false) }
                }
            }
        }
    }

    fun show(dialog: AdminDialog) = _state.update { it.copy(dialog = dialog, dialogError = null) }

    fun dismiss() = _state.update { if (it.submitting) it else it.copy(dialog = null, dialogError = null) }

    fun clearDialogError() = _state.update { it.copy(dialogError = null) }

    // ---- 建号 ----

    fun createUser(username: String, password: String, quotaGb: String) {
        val name = username.trim()
        val gb = parseGb(quotaGb)
        val error = when {
            name.length !in 2..32 -> "用户名 2~32 个字符"
            password.length < 8 -> "密码至少 8 位"
            gb == null -> "配额请输入大于 0 的数字(GB)"
            else -> null
        }
        if (error != null) {
            _state.update { it.copy(dialogError = error) }
            return
        }
        submit(UiText.Res(R.string.admin_msg_created, name)) { repo.createUser(name, password, gbToBytes(gb!!)) }
    }

    // ---- 配额 ----

    fun setQuota(user: AdminUser, quotaGb: String) {
        val gb = parseGb(quotaGb) ?: return _state.update { it.copy(dialogError = "配额请输入大于 0 的数字(GB)") }
        submit(UiText.Res(R.string.admin_msg_quota)) { repo.setQuota(user.id, gbToBytes(gb)) }
    }

    // ---- 禁用 / 启用 ----

    fun toggleDisabled(user: AdminUser) {
        if (user.id == selfId) return
        val text = if (user.disabled) UiText.Res(R.string.admin_msg_enabled, user.username) else UiText.Res(R.string.admin_msg_disabled, user.username)
        submit(text) { repo.setDisabled(user.id, !user.disabled) }
    }

    // ---- 重置密码 ----

    fun resetPassword(user: AdminUser) {
        if (_state.value.submitting) return
        _state.update { it.copy(submitting = true, dialogError = null) }
        viewModelScope.launch {
            gopanResult { repo.resetPassword(user.id) }
                .onSuccess { pwd -> _state.update { it.copy(submitting = false, dialog = AdminDialog.NewPassword(user, pwd)) } }
                .onFailure { e -> _state.update { it.copy(submitting = false, dialogError = e.message ?: "重置失败") } }
        }
    }

    // ---- 失败任务重排 ----

    fun retryFailedTasks() {
        if (_state.value.retrying) return
        _state.update { it.copy(retrying = true) }
        viewModelScope.launch {
            gopanResult { repo.retryFailedTasks() }
                .onSuccess { n ->
                    say(UiText.Res(R.string.admin_msg_retried, n))
                    load(silent = true)
                }
                .onFailure { e -> say(UiText.Plain(e.message ?: "重排失败")) }
            _state.update { it.copy(retrying = false) }
        }
    }

    /** 跑一个修改:成功关弹层、提示、静默刷新;失败把错误贴在弹层里 */
    private fun submit(success: UiText, block: suspend () -> Any) {
        if (_state.value.submitting) return
        _state.update { it.copy(submitting = true, dialogError = null) }
        viewModelScope.launch {
            gopanResult { block() }
                .onSuccess {
                    _state.update { it.copy(submitting = false, dialog = null) }
                    say(success)
                    load(silent = true)
                }
                .onFailure { e -> _state.update { it.copy(submitting = false, dialogError = e.message ?: "操作失败") } }
        }
    }

    private fun say(text: UiText) {
        _effects.trySend(AdminEffect.Message(text))
    }

    companion object {
        const val GB = 1L shl 30

        /** 「3」「2.5」→ GB 数;空、非数字、≤ 0 返回 null */
        fun parseGb(text: String): Double? = text.trim().toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }

        fun gbToBytes(gb: Double): Long = (gb * GB).roundToLong()

        /** 字节 → 输入框里的 GB(整数不带小数点) */
        fun bytesToGbText(bytes: Long): String {
            val gb = bytes.toDouble() / GB
            return if (gb == Math.floor(gb)) gb.toLong().toString() else String.format(Locale.ROOT, "%.2f", gb).trimEnd('0').trimEnd('.')
        }

        /** 管理员在前,再按用户名 */
        fun sortUsers(users: List<AdminUser>): List<AdminUser> =
            users.sortedWith(compareByDescending<AdminUser> { it.isAdmin }.thenBy { it.username.lowercase() })
    }
}
