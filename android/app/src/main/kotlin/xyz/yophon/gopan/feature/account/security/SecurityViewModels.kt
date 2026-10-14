package xyz.yophon.gopan.feature.account.security

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
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
import xyz.yophon.gopan.core.AppConfig
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.data.security.AccessRoot
import xyz.yophon.gopan.core.data.security.ApiKey
import xyz.yophon.gopan.core.data.security.AppPassword
import xyz.yophon.gopan.core.data.security.AuditEvent
import xyz.yophon.gopan.core.data.security.CredentialType
import xyz.yophon.gopan.core.data.security.DeviceSession
import xyz.yophon.gopan.core.data.security.OAuthGrant
import xyz.yophon.gopan.core.data.security.SecurityRepository
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.UiText
import javax.inject.Inject

sealed interface SecurityEffect {
    data class Message(val text: UiText) : SecurityEffect
}

/** 列表页的公共部分:加载状态、下拉刷新、一次性提示 */
abstract class SecurityListViewModel : ViewModel() {
    protected val effectsChannel = Channel<SecurityEffect>(Channel.BUFFERED)
    val effects: Flow<SecurityEffect> = effectsChannel.receiveAsFlow()

    protected fun say(text: UiText) {
        effectsChannel.trySend(SecurityEffect.Message(text))
    }

    protected fun sayError(e: Throwable, fallback: String) = say(UiText.Plain(e.message ?: fallback))

    /** 已经不存在(别处吊销过)也当成功 */
    protected fun Result<*>.okOrGone(): Boolean =
        isSuccess || (exceptionOrNull() as? GopanException)?.code == GopanErrors.NOT_FOUND
}

// ---------------- 登录设备 ----------------

@Immutable
data class DevicesUiState(
    val status: ListStatus = ListStatus.Loading,
    val items: List<DeviceSession> = emptyList(),
    val refreshing: Boolean = false,
    val revoking: Set<String> = emptySet(),
)

/** 登录设备:当前账号的会话(一族 refresh token 一行),可吊销本机以外的 */
@HiltViewModel
class DevicesViewModel @Inject constructor(private val repo: SecurityRepository) : SecurityListViewModel() {
    private val _state = MutableStateFlow(DevicesUiState())
    val state: StateFlow<DevicesUiState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        load(silent = false)
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        load(silent = true)
    }

    fun retry() = load(silent = false)

    private fun load(silent: Boolean) {
        job?.cancel()
        if (!silent) _state.update { it.copy(status = ListStatus.Loading) }
        job = viewModelScope.launch {
            gopanResult { repo.sessions() }
                .onSuccess { items -> _state.update { it.copy(status = ListStatus.Content, items = sortSessions(items), refreshing = false) } }
                .onFailure { e ->
                    if (_state.value.status == ListStatus.Content) {
                        _state.update { it.copy(refreshing = false) }
                        sayError(e, "加载失败")
                    } else {
                        _state.update { it.copy(status = ListStatus.Error(e.message ?: "加载失败"), refreshing = false) }
                    }
                }
        }
    }

    fun revoke(session: DeviceSession) {
        if (session.current || !session.active || session.familyId in _state.value.revoking) return
        _state.update { it.copy(revoking = it.revoking + session.familyId) }
        viewModelScope.launch {
            val result = gopanResult { repo.revokeSession(session.familyId) }
            _state.update { it.copy(revoking = it.revoking - session.familyId) }
            if (result.okOrGone()) {
                say(UiText.Res(R.string.devices_msg_revoked))
                load(silent = true)
            } else {
                sayError(result.exceptionOrNull()!!, "吊销失败")
            }
        }
    }

    companion object {
        /** 本机在最前,再是有效的(最近活动倒序),失效的放最后 */
        fun sortSessions(items: List<DeviceSession>): List<DeviceSession> =
            items.sortedWith(compareByDescending<DeviceSession> { it.current }.thenByDescending { it.active }.thenByDescending { it.lastSeenAt })
    }
}

// ---------------- WebDAV 应用密码 ----------------

@Immutable
data class AppPasswordsUiState(
    val status: ListStatus = ListStatus.Loading,
    val items: List<AppPassword> = emptyList(),
    val refreshing: Boolean = false,
    val creating: Boolean = false,
    /** 刚生成的明文:只显示这一次 */
    val created: String? = null,
    val revoking: Set<String> = emptySet(),
)

@HiltViewModel
class AppPasswordsViewModel @Inject constructor(
    private val repo: SecurityRepository,
    private val config: AppConfig,
    sessionStore: SessionStore,
) : SecurityListViewModel() {
    private val _state = MutableStateFlow(AppPasswordsUiState())
    val state: StateFlow<AppPasswordsUiState> = _state.asStateFlow()

    val davUrl: String get() = config.davUrl
    val username: String = sessionStore.currentUser?.username.orEmpty()

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
            gopanResult { repo.appPasswords() }
                .onSuccess { items -> _state.update { it.copy(status = ListStatus.Content, items = items, refreshing = false) } }
                .onFailure { e ->
                    if (_state.value.status == ListStatus.Content) {
                        _state.update { it.copy(refreshing = false) }
                        sayError(e, "加载失败")
                    } else {
                        _state.update { it.copy(status = ListStatus.Error(e.message ?: "加载失败"), refreshing = false) }
                    }
                }
        }
    }

    /** 返回 true 表示已提交(调用方清空输入框) */
    fun create(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty() || _state.value.creating) return false
        _state.update { it.copy(creating = true) }
        viewModelScope.launch {
            gopanResult { repo.createAppPassword(n) }
                .onSuccess { plain ->
                    _state.update { it.copy(creating = false, created = plain) }
                    load(silent = true)
                }
                .onFailure { e ->
                    _state.update { it.copy(creating = false) }
                    sayError(e, "生成失败")
                }
        }
        return true
    }

    fun dismissCreated() = _state.update { it.copy(created = null) }

    fun revoke(item: AppPassword) {
        if (item.id in _state.value.revoking) return
        _state.update { it.copy(revoking = it.revoking + item.id) }
        viewModelScope.launch {
            val result = gopanResult { repo.revokeAppPassword(item.id) }
            _state.update { s -> s.copy(revoking = s.revoking - item.id, items = if (result.okOrGone()) s.items.filterNot { it.id == item.id } else s.items) }
            if (result.okOrGone()) say(UiText.Res(R.string.app_passwords_msg_revoked)) else sayError(result.exceptionOrNull()!!, "吊销失败")
        }
    }
}

// ---------------- Agent 接入 ----------------

enum class AgentTab { API_KEYS, OAUTH, AUDIT }

/** 正在编辑目录范围的凭据 */
@Immutable
data class RootEdit(
    val credentialId: String,
    val type: CredentialType,
    val name: String,
    val saving: Boolean = false,
    val error: String? = null,
    /** [error] 对应的那次提交(改了输入就不再显示) */
    val errorPath: String? = null,
)

@Immutable
data class AgentUiState(
    val status: ListStatus = ListStatus.Loading,
    val refreshing: Boolean = false,
    val tab: AgentTab = AgentTab.OAUTH,
    val keys: List<ApiKey> = emptyList(),
    val grants: List<OAuthGrant> = emptyList(),
    val roots: List<AccessRoot> = emptyList(),
    // 新建 API Key
    val adminKey: Boolean = false,
    val scopes: Set<String> = McpScopes.defaultFor(admin = false),
    val creating: Boolean = false,
    /** 刚创建的 Key 明文:只显示这一次 */
    val createdToken: String? = null,
    val revoking: Set<String> = emptySet(),
    val rootEdit: RootEdit? = null,
    // 操作记录
    val audit: List<AuditEvent> = emptyList(),
    val auditStatus: ListStatus = ListStatus.Loading,
    val auditLoadingMore: Boolean = false,
    /** 最后一页不满 50 条:没有更早的了 */
    val auditEnd: Boolean = false,
) {
    fun rootName(credentialId: String, type: CredentialType): String? =
        roots.firstOrNull { it.credentialId == credentialId && it.type == type }?.rootName
}

/**
 * Agent 接入(MCP):API Key(新建时明文只显示一次)、OAuth 授权、目录范围、操作记录。
 * 与网页端「Agent 接入」对话框一致;管理员可以建管理员专用 Key。
 */
@HiltViewModel
class AgentViewModel @Inject constructor(
    private val repo: SecurityRepository,
    private val config: AppConfig,
    sessionStore: SessionStore,
) : SecurityListViewModel() {
    private val _state = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    val isAdmin: Boolean = sessionStore.currentUser?.isAdmin == true
    val endpoint: String get() = config.mcpUrl
    val adminEndpoint: String get() = config.mcpUrl + "/admin"

    private var auditLoaded = false

    init {
        load(silent = false)
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        load(silent = true)
        if (_state.value.tab == AgentTab.AUDIT) loadAudit(reset = true)
    }

    fun retry() = load(silent = false)

    private fun load(silent: Boolean) {
        if (!silent) _state.update { it.copy(status = ListStatus.Loading) }
        viewModelScope.launch {
            gopanResult {
                coroutineScope {
                    val keys = async { repo.apiKeys() }
                    val grants = async { repo.oauthGrants() }
                    val roots = async { repo.accessRoots() }
                    Triple(keys.await(), grants.await(), roots.await())
                }
            }.onSuccess { (keys, grants, roots) ->
                _state.update { it.copy(status = ListStatus.Content, keys = keys, grants = grants, roots = roots, refreshing = false) }
            }.onFailure { e ->
                if (_state.value.status == ListStatus.Content) {
                    _state.update { it.copy(refreshing = false) }
                    sayError(e, "加载失败")
                } else {
                    _state.update { it.copy(status = ListStatus.Error(e.message ?: "加载失败"), refreshing = false) }
                }
            }
        }
    }

    fun selectTab(tab: AgentTab) {
        _state.update { it.copy(tab = tab) }
        if (tab == AgentTab.AUDIT && !auditLoaded) loadAudit(reset = true)
    }

    // ---- 新建 API Key ----

    fun setAdminKey(on: Boolean) {
        if (!isAdmin) return
        _state.update { it.copy(adminKey = on, scopes = McpScopes.defaultFor(on)) }
    }

    fun toggleScope(scope: String) = _state.update {
        it.copy(scopes = if (scope in it.scopes) it.scopes - scope else it.scopes + scope)
    }

    fun createKey(name: String): Boolean {
        val n = name.trim()
        val s = _state.value
        if (n.isEmpty() || s.scopes.isEmpty() || s.creating) return false
        _state.update { it.copy(creating = true) }
        viewModelScope.launch {
            // 按选项表的顺序提交(与网页端一致)
            val options = if (s.adminKey) McpScopes.admin else McpScopes.user
            val scopes = options.map { it.first }.filter { it in s.scopes }
            gopanResult { repo.createApiKey(n, scopes) }
                .onSuccess { created ->
                    _state.update { it.copy(creating = false, createdToken = created.token, keys = listOf(created.key) + it.keys) }
                }
                .onFailure { e ->
                    _state.update { it.copy(creating = false) }
                    sayError(e, "API Key 创建失败")
                }
        }
        return true
    }

    fun dismissCreated() = _state.update { it.copy(createdToken = null) }

    fun revokeKey(key: ApiKey) = revoke(key.id, R.string.agent_msg_key_revoked) { repo.revokeApiKey(key.id) }

    fun revokeGrant(grant: OAuthGrant) = revoke(grant.id, R.string.agent_msg_grant_revoked) { repo.revokeOAuthGrant(grant.id) }

    private fun revoke(id: String, okText: Int, call: suspend () -> Unit) {
        if (id in _state.value.revoking) return
        _state.update { it.copy(revoking = it.revoking + id) }
        viewModelScope.launch {
            val result = gopanResult { call() }
            val ok = result.okOrGone()
            _state.update { s ->
                s.copy(
                    revoking = s.revoking - id,
                    keys = if (ok) s.keys.filterNot { it.id == id } else s.keys,
                    grants = if (ok) s.grants.filterNot { it.id == id } else s.grants,
                )
            }
            if (ok) say(UiText.Res(okText)) else sayError(result.exceptionOrNull()!!, "操作失败")
        }
    }

    // ---- 目录范围 ----

    fun editRoot(credentialId: String, type: CredentialType, name: String) =
        _state.update { it.copy(rootEdit = RootEdit(credentialId, type, name)) }

    fun dismissRoot() = _state.update { if (it.rootEdit?.saving == true) it else it.copy(rootEdit = null) }

    /** [path] 为空:清除范围(全盘) */
    fun saveRoot(path: String) {
        val edit = _state.value.rootEdit ?: return
        if (edit.saving) return
        _state.update { it.copy(rootEdit = edit.copy(saving = true, error = null)) }
        viewModelScope.launch {
            val p = path.trim().takeIf { it.isNotEmpty() }?.let { if (it.startsWith("/")) it else "/$it" }
            gopanResult {
                repo.setAccessRoot(edit.credentialId, edit.type, p)
                repo.accessRoots()
            }.onSuccess { roots ->
                _state.update { it.copy(roots = roots, rootEdit = null) }
                say(UiText.Res(R.string.agent_msg_root_saved))
            }.onFailure { e ->
                _state.update { it.copy(rootEdit = edit.copy(saving = false, error = e.message ?: "范围更新失败", errorPath = path.trim())) }
            }
        }
    }

    // ---- 操作记录 ----

    fun loadAudit(reset: Boolean) {
        val s = _state.value
        if (!reset && (s.auditLoadingMore || s.auditEnd)) return
        val before = if (reset) null else s.audit.lastOrNull()?.id
        _state.update {
            if (reset) it.copy(auditStatus = if (it.audit.isEmpty()) ListStatus.Loading else it.auditStatus, auditEnd = false)
            else it.copy(auditLoadingMore = true)
        }
        viewModelScope.launch {
            gopanResult { repo.audit(before) }
                .onSuccess { page ->
                    auditLoaded = true
                    _state.update {
                        it.copy(
                            audit = if (reset) page else it.audit + page,
                            auditStatus = ListStatus.Content,
                            auditLoadingMore = false,
                            auditEnd = page.size < SecurityRepository.AUDIT_PAGE,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        if (reset && it.audit.isEmpty()) it.copy(auditStatus = ListStatus.Error(e.message ?: "加载失败"))
                        else it.copy(auditLoadingMore = false)
                    }
                    if (!reset || _state.value.audit.isNotEmpty()) sayError(e, "加载失败")
                }
        }
    }
}
