package xyz.yophon.gopan.feature.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.LockReset
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.admin.AdminOverview
import xyz.yophon.gopan.core.data.admin.AdminUser
import xyz.yophon.gopan.core.ui.components.ErrorState
import xyz.yophon.gopan.core.ui.components.LoadingState
import xyz.yophon.gopan.core.ui.exposeTestTags
import xyz.yophon.gopan.core.ui.format.formatBytes
import xyz.yophon.gopan.core.ui.format.formatTime
import xyz.yophon.gopan.core.ui.rememberSystemActions
import xyz.yophon.gopan.feature.drive.FullPage
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.NestedScreenInsets
import xyz.yophon.gopan.feature.drive.TopBarInsets
import xyz.yophon.gopan.feature.drive.resolve

/** 管理端(仅管理员可见):概览卡片、用户列表、建号;点用户出操作(配额 / 重置密码 / 禁用启用) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(onBack: () -> Unit, viewModel: AdminViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is AdminEffect.Message -> launch { snackbar.showSnackbar(effect.text.resolve(context)) }
            }
        }
    }

    Scaffold(
        contentWindowInsets = NestedScreenInsets,
        topBar = {
            TopAppBar(
                windowInsets = TopBarInsets,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.preview_back))
                    }
                },
                title = { Text(stringResource(R.string.admin_title)) },
            )
        },
        floatingActionButton = {
            if (state.status == ListStatus.Content) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.show(AdminDialog.Create) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.admin_create)) },
                    modifier = Modifier.testTag("admin_create"),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            when (val status = state.status) {
                ListStatus.Loading -> LoadingState()
                is ListStatus.Error -> FullPage { ErrorState(status.message, onRetry = viewModel::retry) }
                ListStatus.Content -> LazyColumn(Modifier.fillMaxSize().testTag("admin_list")) {
                    state.overview?.let { o ->
                        item { OverviewCards(o, retrying = state.retrying, onRetryFailed = viewModel::retryFailedTasks) }
                    }
                    item {
                        Text(
                            stringResource(R.string.admin_users, state.users.size),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(state.users, key = { it.id }) { user ->
                        UserRow(user, isSelf = user.id == viewModel.selfId, onClick = { viewModel.show(AdminDialog.Actions(user)) })
                    }
                    item { Spacer(Modifier.height(88.dp)) }
                }
            }
        }
    }

    AdminDialogs(state, viewModel)
}

@Composable
private fun OverviewCards(o: AdminOverview, retrying: Boolean, onRetryFailed: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard(stringResource(R.string.admin_stat_users), o.userCount.toString(), Modifier.weight(1f))
            StatCard(stringResource(R.string.admin_stat_used), formatBytes(o.totalUsedBytes), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard(stringResource(R.string.admin_stat_storage, o.blobCount), formatBytes(o.blobBytes), Modifier.weight(1f))
            StatCard(
                stringResource(R.string.admin_stat_tasks),
                o.taskCounts.joinToString(" / ") { "${it.first} ${it.second}" }.ifEmpty { stringResource(R.string.admin_tasks_empty) },
                Modifier.weight(1f),
                valueStyleSmall = true,
            )
        }
        if (o.failedTasks > 0) {
            FilledTonalButton(onClick = onRetryFailed, enabled = !retrying, modifier = Modifier.testTag("admin_retry_failed")) {
                if (retrying) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.admin_retry_failed, o.failedTasks))
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier, valueStyleSmall: Boolean = false) {
    Card(modifier = modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(
                value,
                style = if (valueStyleSmall) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun UserRow(user: AdminUser, isSelf: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick).testTag("admin_user_row"),
        leadingContent = { Icon(Icons.Outlined.Person, contentDescription = null) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(user.username, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (user.isAdmin) Tag(stringResource(R.string.admin_tag_admin), MaterialTheme.colorScheme.tertiary)
                if (isSelf) Tag(stringResource(R.string.admin_tag_self), MaterialTheme.colorScheme.primary)
                if (user.disabled) Tag(stringResource(R.string.admin_tag_disabled), MaterialTheme.colorScheme.error)
            }
        },
        supportingContent = {
            val usage = stringResource(R.string.admin_usage, formatBytes(user.usedBytes), formatBytes(user.quotaBytes))
            Text(if (user.createdAt.isNotEmpty()) "$usage · ${formatTime(user.createdAt)}" else usage)
        },
        trailingContent = {
            IconButton(onClick = onClick) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.admin_user_actions)) }
        },
    )
}

@Composable
private fun Tag(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.12f), contentColor = color, shape = MaterialTheme.shapes.extraSmall) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

// ---- 弹层 ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminDialogs(state: AdminUiState, viewModel: AdminViewModel) {
    when (val d = state.dialog) {
        null -> Unit
        AdminDialog.Create -> CreateUserDialog(state, viewModel)
        is AdminDialog.Actions -> UserActionSheet(d.user, isSelf = d.user.id == viewModel.selfId, viewModel = viewModel)
        is AdminDialog.Quota -> QuotaDialog(d.user, state, viewModel)
        is AdminDialog.ConfirmDisable -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            modifier = Modifier.exposeTestTags(),
            title = { Text(stringResource(if (d.user.disabled) R.string.admin_enable_title else R.string.admin_disable_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (d.user.disabled) R.string.admin_enable_text else R.string.admin_disable_text, d.user.username))
                    DialogError(state.dialogError)
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.toggleDisabled(d.user) }, enabled = !state.submitting, modifier = Modifier.testTag("admin_confirm")) {
                    Text(
                        stringResource(if (d.user.disabled) R.string.admin_action_enable else R.string.admin_action_disable),
                        color = if (d.user.disabled) Color.Unspecified else MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismiss, enabled = !state.submitting) { Text(stringResource(R.string.action_cancel)) } },
        )
        is AdminDialog.ConfirmReset -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            modifier = Modifier.exposeTestTags(),
            title = { Text(stringResource(R.string.admin_reset_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.admin_reset_text, d.user.username))
                    DialogError(state.dialogError)
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.resetPassword(d.user) }, enabled = !state.submitting, modifier = Modifier.testTag("admin_confirm")) {
                    Text(stringResource(R.string.admin_action_reset), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismiss, enabled = !state.submitting) { Text(stringResource(R.string.action_cancel)) } },
        )
        is AdminDialog.NewPassword -> NewPasswordDialog(d, viewModel)
    }
}

@Composable
private fun DialogError(error: String?) {
    if (error != null) {
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("admin_dialog_error"))
    }
}

@Composable
private fun CreateUserDialog(state: AdminUiState, viewModel: AdminViewModel) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var quota by rememberSaveable { mutableStateOf("10") }
    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        modifier = Modifier.exposeTestTags(),
        title = { Text(stringResource(R.string.admin_create_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.take(32); viewModel.clearDialogError() },
                    label = { Text(stringResource(R.string.admin_field_username)) },
                    placeholder = { Text(stringResource(R.string.admin_field_username_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("admin_new_username"),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; viewModel.clearDialogError() },
                    label = { Text(stringResource(R.string.admin_field_password)) },
                    placeholder = { Text(stringResource(R.string.admin_field_password_hint)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("admin_new_password"),
                )
                OutlinedTextField(
                    value = quota,
                    onValueChange = { quota = it.take(10); viewModel.clearDialogError() },
                    label = { Text(stringResource(R.string.admin_field_quota)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("admin_new_quota"),
                )
                DialogError(state.dialogError)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { viewModel.createUser(username, password, quota) },
                enabled = !state.submitting && username.isNotBlank() && password.length >= 8,
                modifier = Modifier.testTag("admin_create_confirm"),
            ) { Text(stringResource(R.string.admin_action_create)) }
        },
        dismissButton = { TextButton(onClick = viewModel::dismiss, enabled = !state.submitting) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun QuotaDialog(user: AdminUser, state: AdminUiState, viewModel: AdminViewModel) {
    var quota by rememberSaveable(user.id) { mutableStateOf(AdminViewModel.bytesToGbText(user.quotaBytes)) }
    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        modifier = Modifier.exposeTestTags(),
        title = { Text(stringResource(R.string.admin_quota_title, user.username)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.admin_quota_current, formatBytes(user.quotaBytes), formatBytes(user.usedBytes)), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = quota,
                    onValueChange = { quota = it.take(10); viewModel.clearDialogError() },
                    label = { Text(stringResource(R.string.admin_field_quota)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("admin_quota_input"),
                )
                DialogError(state.dialogError)
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.setQuota(user, quota) }, enabled = !state.submitting, modifier = Modifier.testTag("admin_quota_save")) {
                Text(stringResource(R.string.admin_action_save))
            }
        },
        dismissButton = { TextButton(onClick = viewModel::dismiss, enabled = !state.submitting) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun NewPasswordDialog(d: AdminDialog.NewPassword, viewModel: AdminViewModel) {
    val system = rememberSystemActions()
    // 「已复制」直接写在按钮上(Android 13 以下系统不提示)
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        modifier = Modifier.exposeTestTags(),
        title = { Text(stringResource(R.string.admin_new_password_title, d.user.username)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionContainer {
                    Text(
                        d.password,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("admin_new_password_value"),
                    )
                }
                Text(stringResource(R.string.admin_new_password_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                system.copy(d.password)
                copied = true
            }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(if (copied) R.string.admin_copied else R.string.admin_copy))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismiss, modifier = Modifier.testTag("admin_new_password_done")) { Text(stringResource(R.string.admin_done)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserActionSheet(user: AdminUser, isSelf: Boolean, viewModel: AdminViewModel) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun pick(next: AdminDialog) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { viewModel.show(next) }
    }
    ModalBottomSheet(onDismissRequest = viewModel::dismiss, sheetState = sheetState) {
        ListItem(
            headlineContent = { Text(user.username, style = MaterialTheme.typography.titleMedium) },
            supportingContent = { Text(stringResource(R.string.admin_usage, formatBytes(user.usedBytes), formatBytes(user.quotaBytes))) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        HorizontalDivider()
        SheetItem(Icons.Outlined.Storage, stringResource(R.string.admin_action_quota)) { pick(AdminDialog.Quota(user)) }
        SheetItem(Icons.Outlined.LockReset, stringResource(R.string.admin_action_reset_password)) { pick(AdminDialog.ConfirmReset(user)) }
        if (!isSelf) {
            if (user.disabled) {
                SheetItem(Icons.Outlined.CheckCircle, stringResource(R.string.admin_action_enable_account)) { pick(AdminDialog.ConfirmDisable(user)) }
            } else {
                SheetItem(Icons.Outlined.Block, stringResource(R.string.admin_action_disable_account), destructive = true) { pick(AdminDialog.ConfirmDisable(user)) }
            }
        }
        Spacer(Modifier.height(16.dp).navigationBarsPadding())
    }
}

@Composable
private fun SheetItem(icon: ImageVector, label: String, destructive: Boolean = false, onClick: () -> Unit) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
        headlineContent = { Text(label, color = tint) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
