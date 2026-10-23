package xyz.yophon.gopan.feature.account.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.security.ApiKey
import xyz.yophon.gopan.core.data.security.AppPassword
import xyz.yophon.gopan.core.data.security.AuditEvent
import xyz.yophon.gopan.core.data.security.CredentialType
import xyz.yophon.gopan.core.data.security.DeviceSession
import xyz.yophon.gopan.core.data.security.OAuthGrant
import xyz.yophon.gopan.core.ui.components.EmptyState
import xyz.yophon.gopan.core.ui.components.ErrorState
import xyz.yophon.gopan.core.ui.components.LoadingState
import xyz.yophon.gopan.core.ui.components.TextInputDialog
import xyz.yophon.gopan.core.ui.format.describeUserAgent
import xyz.yophon.gopan.core.ui.format.formatTime
import xyz.yophon.gopan.core.ui.rememberSystemActions
import xyz.yophon.gopan.feature.drive.FullPage
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.NestedScreenInsets
import xyz.yophon.gopan.feature.drive.resolve
import xyz.yophon.gopan.core.ui.exposeTestTags

// ---------------- 公共部分 ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecurityScaffold(
    title: String,
    onBack: () -> Unit,
    snackbar: SnackbarHostState,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        contentWindowInsets = NestedScreenInsets,
        topBar = {
            TopAppBar(
                windowInsets = NestedScreenInsets,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.preview_back))
                    }
                },
                title = { Text(title) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.padding(padding).fillMaxSize()) {
            content()
        }
    }
}

/** 收 ViewModel 的一次性提示到 Snackbar */
@Composable
private fun CollectMessages(effects: Flow<SecurityEffect>, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    LaunchedEffect(effects) {
        effects.collect { effect ->
            when (effect) {
                is SecurityEffect.Message -> launch { snackbar.showSnackbar(effect.text.resolve(context)) }
            }
        }
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** 地址一行:标签、等宽地址、复制 */
@Composable
private fun CopyRow(label: String, value: String, onCopy: () -> Unit, tag: String) {
    ListItem(
        overlineContent = { Text(label) },
        headlineContent = { SelectionContainer { Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) } },
        trailingContent = {
            IconButton(onClick = onCopy, modifier = Modifier.testTag(tag)) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.security_copy))
            }
        },
    )
}

/** 刚生成的密码 / Key:只显示这一次 */
@Composable
private fun SecretOnce(label: String, secret: String, onCopy: () -> Unit, onDone: () -> Unit, tag: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            SelectionContainer {
                Text(
                    secret,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.testTag(tag),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCopy) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.security_copy))
                }
                OutlinedButton(onClick = onDone) { Text(stringResource(R.string.security_secret_done)) }
            }
        }
    }
}

/** 列表页的加载 / 错误 / 空态;有内容时交给 [content] */
@Composable
private fun <T> ListBody(
    status: ListStatus,
    items: List<T>,
    onRetry: () -> Unit,
    emptyTitle: String,
    content: LazyListScope.() -> Unit,
    header: LazyListScope.() -> Unit = {},
) {
    when (status) {
        ListStatus.Loading -> LoadingState()
        is ListStatus.Error -> FullPage { ErrorState(status.message, onRetry = onRetry) }
        ListStatus.Content -> LazyColumn(Modifier.fillMaxSize()) {
            header()
            if (items.isEmpty()) item { EmptyState(title = emptyTitle, modifier = Modifier.padding(top = 32.dp)) }
            content()
        }
    }
}

@Composable
private fun ConfirmRevoke(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.exposeTestTags(),
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("security_revoke_confirm")) {
                Text(confirm, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun RevokeButton(busy: Boolean, label: String, onClick: () -> Unit, tag: String) {
    if (busy) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    } else {
        TextButton(onClick = onClick, modifier = Modifier.testTag(tag)) { Text(label, color = MaterialTheme.colorScheme.error) }
    }
}

// ---------------- 登录设备 ----------------

/** 登录设备:本机标「本机」(用「退出登录」退出),失效的标「已失效」,其余可吊销 */
@Composable
fun DevicesScreen(onBack: () -> Unit, viewModel: DevicesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirm by remember { mutableStateOf<DeviceSession?>(null) }
    CollectMessages(viewModel.effects, snackbar)

    SecurityScaffold(stringResource(R.string.devices_title), onBack, snackbar, state.refreshing, viewModel::refresh) {
        ListBody(
            status = state.status,
            items = state.items,
            onRetry = viewModel::retry,
            emptyTitle = stringResource(R.string.devices_empty),
            header = { item { Hint(stringResource(R.string.devices_hint)) } },
            content = {
                items(state.items, key = { it.familyId }) { s ->
                    DeviceRow(s, busy = s.familyId in state.revoking, onRevoke = { confirm = s })
                }
            },
        )
    }
    confirm?.let { s ->
        ConfirmRevoke(
            title = stringResource(R.string.devices_revoke_title),
            text = stringResource(R.string.devices_revoke_text, describeUserAgent(s.userAgent)),
            confirm = stringResource(R.string.security_revoke),
            onConfirm = {
                confirm = null
                viewModel.revoke(s)
            },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun DeviceRow(s: DeviceSession, busy: Boolean, onRevoke: () -> Unit) {
    ListItem(
        modifier = Modifier.testTag("device_row"),
        leadingContent = { Icon(Icons.Outlined.Devices, contentDescription = null) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(describeUserAgent(s.userAgent), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                when {
                    s.current -> Tag(stringResource(R.string.devices_current), MaterialTheme.colorScheme.primary)
                    !s.active -> Tag(stringResource(R.string.devices_inactive), MaterialTheme.colorScheme.outline)
                }
            }
        },
        supportingContent = {
            Text(
                stringResource(R.string.devices_secondary, s.ip, formatTime(s.lastSeenAt), formatTime(s.createdAt)),
                maxLines = 2,
            )
        },
        trailingContent = if (s.active && !s.current) {
            { RevokeButton(busy, stringResource(R.string.security_revoke), onRevoke, "device_revoke") }
        } else {
            null
        },
    )
}

@Composable
private fun Tag(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

// ---------------- WebDAV 应用密码 ----------------

@Composable
fun AppPasswordsScreen(onBack: () -> Unit, viewModel: AppPasswordsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val system = rememberSystemActions()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf<AppPassword?>(null) }
    CollectMessages(viewModel.effects, snackbar)
    fun copy(text: String) {
        if (system.copy(text)) scope.launch { snackbar.showSnackbar(context.getString(R.string.security_copied)) }
    }

    SecurityScaffold(stringResource(R.string.app_passwords_title), onBack, snackbar, state.refreshing, viewModel::refresh) {
        ListBody(
            status = state.status,
            items = state.items,
            onRetry = viewModel::retry,
            emptyTitle = stringResource(R.string.app_passwords_empty),
            header = {
                item { CopyRow(stringResource(R.string.app_passwords_dav), viewModel.davUrl, { copy(viewModel.davUrl) }, "dav_copy") }
                item { Hint(stringResource(R.string.app_passwords_hint, viewModel.username)) }
                state.created?.let { secret ->
                    item {
                        SecretOnce(stringResource(R.string.app_passwords_created), secret, { copy(secret) }, viewModel::dismissCreated, "app_password_secret")
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(64) },
                            label = { Text(stringResource(R.string.app_passwords_name_hint)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("app_password_name"),
                        )
                        Button(
                            onClick = { if (viewModel.create(name)) name = "" },
                            enabled = name.isNotBlank() && !state.creating,
                            modifier = Modifier.testTag("app_password_create"),
                        ) { Text(stringResource(R.string.app_passwords_create)) }
                    }
                }
                item { HorizontalDivider(Modifier.padding(top = 8.dp)) }
            },
            content = {
                items(state.items, key = { it.id }) { p ->
                    ListItem(
                        modifier = Modifier.testTag("app_password_row"),
                        leadingContent = { Icon(Icons.Outlined.Key, contentDescription = null) },
                        headlineContent = { Text(p.name) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    R.string.app_passwords_secondary,
                                    formatTime(p.createdAt),
                                    p.lastUsedAt?.let { formatTime(it) } ?: stringResource(R.string.security_never),
                                ),
                            )
                        },
                        trailingContent = { RevokeButton(p.id in state.revoking, stringResource(R.string.security_revoke), { confirm = p }, "app_password_revoke") },
                    )
                }
            },
        )
    }
    confirm?.let { p ->
        ConfirmRevoke(
            title = stringResource(R.string.app_passwords_revoke_title),
            text = stringResource(R.string.app_passwords_revoke_text, p.name),
            confirm = stringResource(R.string.security_revoke),
            onConfirm = {
                confirm = null
                viewModel.revoke(p)
            },
            onDismiss = { confirm = null },
        )
    }
}

// ---------------- Agent 接入 ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentScreen(onBack: () -> Unit, viewModel: AgentViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val system = rememberSystemActions()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var keyName by rememberSaveable { mutableStateOf("") }
    var confirmKey by remember { mutableStateOf<ApiKey?>(null) }
    var confirmGrant by remember { mutableStateOf<OAuthGrant?>(null) }
    CollectMessages(viewModel.effects, snackbar)
    fun copy(text: String) {
        if (system.copy(text)) scope.launch { snackbar.showSnackbar(context.getString(R.string.security_copied)) }
    }

    SecurityScaffold(stringResource(R.string.agent_title), onBack, snackbar, state.refreshing, viewModel::refresh) {
        when (val status = state.status) {
            ListStatus.Loading -> LoadingState()
            is ListStatus.Error -> FullPage { ErrorState(status.message, onRetry = viewModel::retry) }
            ListStatus.Content -> LazyColumn(Modifier.fillMaxSize().testTag("agent_list")) {
                item { CopyRow(stringResource(R.string.agent_endpoint), viewModel.endpoint, { copy(viewModel.endpoint) }, "mcp_copy") }
                if (viewModel.isAdmin) {
                    item { CopyRow(stringResource(R.string.agent_admin_endpoint), viewModel.adminEndpoint, { copy(viewModel.adminEndpoint) }, "mcp_admin_copy") }
                }
                item { Hint(stringResource(R.string.agent_help)) }
                item {
                    PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
                        AgentTab.entries.forEach { tab ->
                            Tab(
                                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                selected = state.tab == tab,
                                onClick = { viewModel.selectTab(tab) },
                                text = { Text(stringResource(tab.label())) },
                                modifier = Modifier.testTag("agent_tab_${tab.name}"),
                            )
                        }
                    }
                }
                when (state.tab) {
                    AgentTab.API_KEYS -> {
                        item { Hint(stringResource(R.string.agent_keys_help)) }
                        if (viewModel.isAdmin) {
                            item {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.agent_admin_key), modifier = Modifier.weight(1f))
                                    Switch(checked = state.adminKey, onCheckedChange = viewModel::setAdminKey, modifier = Modifier.testTag("agent_admin_switch"))
                                }
                            }
                        }
                        state.createdToken?.let { token ->
                            item { SecretOnce(stringResource(R.string.agent_key_created), token, { copy(token) }, viewModel::dismissCreated, "agent_key_secret") }
                        }
                        item {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = keyName,
                                        onValueChange = { keyName = it.take(64) },
                                        label = { Text(stringResource(R.string.agent_key_name_hint)) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f).testTag("agent_key_name"),
                                    )
                                    Button(
                                        onClick = { if (viewModel.createKey(keyName)) keyName = "" },
                                        enabled = keyName.isNotBlank() && state.scopes.isNotEmpty() && !state.creating,
                                        modifier = Modifier.testTag("agent_key_create"),
                                    ) { Text(stringResource(R.string.agent_key_create)) }
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    (if (state.adminKey) McpScopes.admin else McpScopes.user).forEach { (scope, label) ->
                                        FilterChip(
                                            selected = scope in state.scopes,
                                            onClick = { viewModel.toggleScope(scope) },
                                            label = { Text(label) },
                                            modifier = Modifier.testTag("scope_$scope"),
                                        )
                                    }
                                }
                            }
                        }
                        item { HorizontalDivider() }
                        if (state.keys.isEmpty()) item { EmptyState(title = stringResource(R.string.agent_keys_empty), modifier = Modifier.padding(top = 24.dp)) }
                        items(state.keys, key = { it.id }) { key ->
                            CredentialRow(
                                icon = Icons.Outlined.Key,
                                title = key.name,
                                scopes = key.scopes,
                                time = stringResource(R.string.agent_last_used, key.lastUsedAt?.let { formatTime(it) } ?: stringResource(R.string.security_never)),
                                root = if (key.isAdmin) null else state.rootName(key.id, CredentialType.API_KEY) ?: stringResource(R.string.agent_root_all),
                                onEditRoot = if (key.isAdmin) null else ({ viewModel.editRoot(key.id, CredentialType.API_KEY, key.name) }),
                                revokeLabel = stringResource(R.string.security_revoke),
                                busy = key.id in state.revoking,
                                onRevoke = { confirmKey = key },
                                tag = "agent_key_row",
                            )
                        }
                    }
                    AgentTab.OAUTH -> {
                        item { Hint(stringResource(R.string.agent_oauth_help)) }
                        if (state.grants.isEmpty()) item { EmptyState(title = stringResource(R.string.agent_oauth_empty), modifier = Modifier.padding(top = 24.dp)) }
                        items(state.grants, key = { it.id }) { grant ->
                            CredentialRow(
                                icon = Icons.Outlined.SmartToy,
                                title = grant.clientName,
                                scopes = grant.scopes,
                                time = stringResource(R.string.agent_granted_at, formatTime(grant.updatedAt)),
                                root = if (grant.isAdmin) null else state.rootName(grant.id, CredentialType.OAUTH) ?: stringResource(R.string.agent_root_all),
                                onEditRoot = if (grant.isAdmin) null else ({ viewModel.editRoot(grant.id, CredentialType.OAUTH, grant.clientName) }),
                                revokeLabel = stringResource(R.string.agent_oauth_revoke),
                                busy = grant.id in state.revoking,
                                onRevoke = { confirmGrant = grant },
                                tag = "agent_grant_row",
                            )
                        }
                    }
                    AgentTab.AUDIT -> auditItems(state, viewModel)
                }
            }
        }
    }

    confirmKey?.let { key ->
        ConfirmRevoke(
            title = stringResource(R.string.agent_key_revoke_title),
            text = stringResource(R.string.agent_key_revoke_text, key.name),
            confirm = stringResource(R.string.security_revoke),
            onConfirm = {
                confirmKey = null
                viewModel.revokeKey(key)
            },
            onDismiss = { confirmKey = null },
        )
    }
    confirmGrant?.let { grant ->
        ConfirmRevoke(
            title = stringResource(R.string.agent_oauth_revoke_title),
            text = stringResource(R.string.agent_oauth_revoke_text, grant.clientName),
            confirm = stringResource(R.string.agent_oauth_revoke),
            onConfirm = {
                confirmGrant = null
                viewModel.revokeGrant(grant)
            },
            onDismiss = { confirmGrant = null },
        )
    }
    state.rootEdit?.let { edit ->
        TextInputDialog(
            title = stringResource(R.string.agent_root_title, edit.name),
            label = stringResource(R.string.agent_root_hint),
            initialValue = "",
            confirmText = stringResource(R.string.agent_root_save),
            busy = edit.saving,
            validate = { text -> edit.error?.takeIf { text.trim() == edit.errorPath } },
            onConfirm = viewModel::saveRoot,
            onDismiss = viewModel::dismissRoot,
            allowEmpty = true,
            showErrorUntouched = true,
        )
    }
}

private fun AgentTab.label(): Int = when (this) {
    AgentTab.API_KEYS -> R.string.agent_tab_keys
    AgentTab.OAUTH -> R.string.agent_tab_oauth
    AgentTab.AUDIT -> R.string.agent_tab_audit
}

@Composable
private fun CredentialRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    scopes: List<String>,
    time: String,
    root: String?,
    onEditRoot: (() -> Unit)?,
    revokeLabel: String,
    busy: Boolean,
    onRevoke: () -> Unit,
    tag: String,
) {
    ListItem(
        modifier = Modifier.testTag(tag),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(McpScopes.sorted(scopes).joinToString("、") { McpScopes.label(it) }, style = MaterialTheme.typography.bodySmall)
                Text(time, style = MaterialTheme.typography.bodySmall)
                if (root == null) {
                    Text(stringResource(R.string.agent_root_admin), style = MaterialTheme.typography.bodySmall)
                } else {
                    TextButton(onClick = { onEditRoot?.invoke() }, enabled = onEditRoot != null, modifier = Modifier.testTag("agent_root_edit")) {
                        Text(stringResource(R.string.agent_root_label, root))
                    }
                }
            }
        },
        trailingContent = { RevokeButton(busy, revokeLabel, onRevoke, "${tag}_revoke") },
    )
}

private fun LazyListScope.auditItems(state: AgentUiState, viewModel: AgentViewModel) {
    item {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            TextButton(onClick = { viewModel.loadAudit(reset = true) }) { Text(stringResource(R.string.agent_audit_refresh)) }
        }
    }
    when (val status = state.auditStatus) {
        ListStatus.Loading -> item {
            Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
        }
        is ListStatus.Error -> item { ErrorState(status.message, onRetry = { viewModel.loadAudit(reset = true) }) }
        ListStatus.Content -> {
            if (state.audit.isEmpty()) item { EmptyState(title = stringResource(R.string.agent_audit_empty), modifier = Modifier.padding(top = 24.dp)) }
            items(state.audit, key = { it.id }) { AuditRow(it) }
            if (state.audit.isNotEmpty() && !state.auditEnd) {
                item {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                        if (state.auditLoadingMore) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        else OutlinedButton(onClick = { viewModel.loadAudit(reset = false) }) { Text(stringResource(R.string.agent_audit_more)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditRow(e: AuditEvent) {
    val failed = e.status != "ok" && e.status != "success"
    ListItem(
        modifier = Modifier.testTag("audit_row"),
        headlineContent = { Text(e.tool, fontFamily = FontFamily.Monospace) },
        supportingContent = {
            Text(
                listOfNotNull(formatTime(e.createdAt), e.endpoint, e.status, e.errorCode, e.credentialId.take(8)).joinToString(" · "),
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}
