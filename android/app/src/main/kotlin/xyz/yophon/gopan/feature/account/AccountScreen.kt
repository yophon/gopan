package xyz.yophon.gopan.feature.account

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.yophon.gopan.BuildConfig
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.auth.UserSummary
import xyz.yophon.gopan.core.prefs.DriveViewMode
import xyz.yophon.gopan.core.prefs.ThemeMode
import xyz.yophon.gopan.core.ui.components.ConfirmDialog
import xyz.yophon.gopan.core.ui.components.ErrorState
import xyz.yophon.gopan.core.ui.components.LoadingState

/** 「我的」页入口:导航回调由 AccountGraph 提供,页面本身不认识 NavController */
@Composable
fun AccountScreen(
    onOpenTrash: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenAppPasswords: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenAdmin: () -> Unit,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResumed() }
    AccountContent(
        state = state,
        versionName = BuildConfig.VERSION_NAME,
        onRefresh = viewModel::refresh,
        onOpenTrash = onOpenTrash,
        onOpenSessions = onOpenSessions,
        onOpenAppPasswords = onOpenAppPasswords,
        onOpenAgent = onOpenAgent,
        onOpenAdmin = onOpenAdmin,
        onOpenPasswordDialog = viewModel::openPasswordDialog,
        onClosePasswordDialog = viewModel::closePasswordDialog,
        onPasswordInputChanged = viewModel::clearPasswordErrors,
        onSubmitPassword = viewModel::submitPasswordChange,
        onSetTheme = viewModel::setTheme,
        onSetDriveViewMode = viewModel::setDriveViewMode,
        onComingSoon = viewModel::notifyComingSoon,
        onLogout = viewModel::logout,
        onMessageShown = viewModel::consumeMessage,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountContent(
    state: AccountUiState,
    versionName: String,
    onRefresh: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenAppPasswords: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenAdmin: () -> Unit,
    onOpenPasswordDialog: () -> Unit,
    onClosePasswordDialog: () -> Unit,
    onPasswordInputChanged: () -> Unit,
    onSubmitPassword: (old: String, new: String, confirm: String) -> Unit,
    onSetTheme: (ThemeMode) -> Unit,
    onSetDriveViewMode: (DriveViewMode) -> Unit,
    onComingSoon: () -> Unit,
    onLogout: () -> Unit,
    onMessageShown: (Long) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var showThemeDialog by rememberSaveable { mutableStateOf(false) }
    var showViewModeDialog by rememberSaveable { mutableStateOf(false) }
    var showLogoutConfirm by rememberSaveable { mutableStateOf(false) }

    val message = state.message
    LaunchedEffect(message?.id) {
        if (message != null) {
            onMessageShown(message.id)
            snackbarHostState.showSnackbar(message.text)
        }
    }

    // 外层 MainScaffold 的 innerPadding 已经让出了状态栏,这里的顶栏不再重复加 inset
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.account_title)) },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        val user = state.user
        when {
            user == null && state.loading -> LoadingState(modifier = Modifier.padding(innerPadding))
            user == null -> ErrorState(
                message = state.loadError ?: stringResource(R.string.state_error),
                modifier = Modifier.padding(innerPadding),
                onRetry = onRefresh,
            )
            else -> PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
            ) {
                AccountList(
                    user = user,
                    usage = state.usage ?: UsageSummary(user.usedBytes, user.quotaBytes),
                    theme = state.theme,
                    driveViewMode = state.driveViewMode,
                    versionName = versionName,
                    loggingOut = state.loggingOut,
                    onOpenTrash = onOpenTrash,
                    onOpenSessions = onOpenSessions,
                    onOpenAppPasswords = onOpenAppPasswords,
                    onOpenAgent = onOpenAgent,
                    onOpenAdmin = onOpenAdmin,
                    onOpenPasswordDialog = onOpenPasswordDialog,
                    onOpenThemeDialog = { showThemeDialog = true },
                    onOpenViewModeDialog = { showViewModeDialog = true },
                    onComingSoon = onComingSoon,
                    onLogoutClick = { showLogoutConfirm = true },
                )
            }
        }
    }

    state.passwordDialog?.let { dialog ->
        ChangePasswordDialog(
            state = dialog,
            onInputChanged = onPasswordInputChanged,
            onSubmit = onSubmitPassword,
            onDismiss = onClosePasswordDialog,
        )
    }

    if (showThemeDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.account_item_theme),
            options = ThemeMode.entries.map { it to stringResource(it.labelRes()) },
            selected = state.theme,
            onSelect = { onSetTheme(it); showThemeDialog = false },
            onDismiss = { showThemeDialog = false },
        )
    }

    if (showViewModeDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.account_item_drive_view),
            options = DriveViewMode.entries.map { it to stringResource(it.labelRes()) },
            selected = state.driveViewMode,
            onSelect = { onSetDriveViewMode(it); showViewModeDialog = false },
            onDismiss = { showViewModeDialog = false },
        )
    }

    if (showLogoutConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.account_logout_confirm_title),
            text = stringResource(R.string.account_logout_confirm_text),
            confirmText = stringResource(R.string.account_logout_confirm_action),
            destructive = true,
            busy = state.loggingOut,
            onConfirm = { showLogoutConfirm = false; onLogout() },
            onDismiss = { showLogoutConfirm = false },
        )
    }
}

// ---- 列表 ----

@Composable
private fun AccountList(
    user: UserSummary,
    usage: UsageSummary,
    theme: ThemeMode,
    driveViewMode: DriveViewMode,
    versionName: String,
    loggingOut: Boolean,
    onOpenTrash: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenAppPasswords: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenAdmin: () -> Unit,
    onOpenPasswordDialog: () -> Unit,
    onOpenThemeDialog: () -> Unit,
    onOpenViewModeDialog: () -> Unit,
    onComingSoon: () -> Unit,
    onLogoutClick: () -> Unit,
) {
    val comingSoon = stringResource(R.string.account_coming_soon)
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item("header") { AccountHeader(user = user, usage = usage) }

        item("storage") { SectionTitle(stringResource(R.string.account_section_storage)) }
        item("trash") {
            SettingItem(
                icon = Icons.Outlined.DeleteOutline,
                title = stringResource(R.string.account_item_trash),
                supporting = stringResource(R.string.account_item_trash_hint),
                onClick = onOpenTrash,
            )
        }

        item("account") { SectionTitle(stringResource(R.string.account_section_account)) }
        item("password") {
            SettingItem(icon = Icons.Outlined.Lock, title = stringResource(R.string.account_item_change_password), onClick = onOpenPasswordDialog)
        }
        item("sessions") {
            SettingItem(icon = Icons.Outlined.Devices, title = stringResource(R.string.account_item_sessions), value = comingSoon, onClick = onOpenSessions)
        }
        item("apppass") {
            SettingItem(icon = Icons.Outlined.Key, title = stringResource(R.string.account_item_app_passwords), value = comingSoon, onClick = onOpenAppPasswords)
        }
        item("agent") {
            SettingItem(icon = Icons.Outlined.SmartToy, title = stringResource(R.string.account_item_agent), value = comingSoon, onClick = onOpenAgent)
        }

        item("appearance") { SectionTitle(stringResource(R.string.account_section_appearance)) }
        item("theme") {
            SettingItem(
                icon = Icons.Outlined.Palette,
                title = stringResource(R.string.account_item_theme),
                value = stringResource(theme.labelRes()),
                onClick = onOpenThemeDialog,
            )
        }
        item("view") {
            SettingItem(
                icon = if (driveViewMode == DriveViewMode.GRID) Icons.Outlined.GridView else Icons.AutoMirrored.Outlined.ViewList,
                title = stringResource(R.string.account_item_drive_view),
                value = stringResource(driveViewMode.labelRes()),
                onClick = onOpenViewModeDialog,
            )
        }

        if (user.isAdmin) {
            item("admin") { SectionTitle(stringResource(R.string.account_section_admin)) }
            item("admin-entry") {
                SettingItem(icon = Icons.Outlined.AdminPanelSettings, title = stringResource(R.string.account_item_admin), value = comingSoon, onClick = onOpenAdmin)
            }
        }

        item("about") { SectionTitle(stringResource(R.string.account_section_about)) }
        item("version") {
            SettingItem(icon = Icons.Outlined.Info, title = stringResource(R.string.account_item_version), value = versionName, onClick = null)
        }
        item("update") {
            SettingItem(icon = Icons.Outlined.SystemUpdate, title = stringResource(R.string.account_item_check_update), onClick = onComingSoon)
        }
        item("oss") {
            SettingItem(icon = Icons.Outlined.Code, title = stringResource(R.string.account_item_open_source), onClick = onComingSoon)
        }

        item("logout-divider") { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
        item("logout") {
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.account_item_logout), color = MaterialTheme.colorScheme.error)
                },
                leadingContent = {
                    if (loggingOut) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    }
                },
                modifier = Modifier.clickable(enabled = !loggingOut, onClick = onLogoutClick),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AccountHeader(user: UserSummary, usage: UsageSummary) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(56.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = user.username.take(1).uppercase(),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = user.username,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f, fill = false),
                            maxLines = 1,
                        )
                        if (user.isAdmin) {
                            Spacer(Modifier.width(8.dp))
                            AdminBadge()
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = usage.percentText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            val barColor = when (usage.level) {
                UsageLevel.NORMAL -> MaterialTheme.colorScheme.primary
                UsageLevel.WARN -> MaterialTheme.colorScheme.tertiary
                UsageLevel.CRITICAL -> MaterialTheme.colorScheme.error
            }
            LinearProgressIndicator(
                progress = { usage.fraction },
                modifier = Modifier.fillMaxWidth(),
                color = barColor,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = usage.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AdminBadge() {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small) {
        Text(
            text = stringResource(R.string.account_admin_badge),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** 设置项:图标 + 标题(+ 副标题),右侧是当前值或箭头;onClick 为 null 时不可点 */
@Composable
private fun SettingItem(
    icon: ImageVector,
    title: String,
    onClick: (() -> Unit)?,
    supporting: String? = null,
    value: String? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value != null) {
                    Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onClick != null) {
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

// ---- 对话框 ----

@Composable
private fun ChangePasswordDialog(
    state: PasswordDialogState,
    onInputChanged: () -> Unit,
    onSubmit: (old: String, new: String, confirm: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var reveal by rememberSaveable { mutableStateOf(false) }

    val canSubmit = ChangePasswordForm.canSubmit(oldPassword, newPassword, confirm) && !state.busy
    val submit = { if (canSubmit) onSubmit(oldPassword, newPassword, confirm) }
    val errorText = state.serverError ?: state.validationError?.let { stringResource(it.messageRes()) }
    val err = state.validationError

    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text(stringResource(R.string.account_pwd_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PasswordField(
                    value = oldPassword,
                    onValueChange = { oldPassword = it; onInputChanged() },
                    label = stringResource(R.string.account_pwd_old),
                    reveal = reveal,
                    onToggleReveal = { reveal = !reveal },
                    isError = err == ChangePasswordError.OLD_EMPTY || state.serverError != null,
                    enabled = !state.busy,
                    imeAction = ImeAction.Next,
                )
                PasswordField(
                    value = newPassword,
                    onValueChange = { newPassword = it; onInputChanged() },
                    label = stringResource(R.string.account_pwd_new),
                    placeholder = stringResource(R.string.account_pwd_new_hint),
                    reveal = reveal,
                    onToggleReveal = { reveal = !reveal },
                    isError = err == ChangePasswordError.NEW_EMPTY || err == ChangePasswordError.NEW_TOO_SHORT ||
                        err == ChangePasswordError.NEW_TOO_LONG || err == ChangePasswordError.NEW_SAME_AS_OLD,
                    enabled = !state.busy,
                    imeAction = ImeAction.Next,
                )
                PasswordField(
                    value = confirm,
                    onValueChange = { confirm = it; onInputChanged() },
                    label = stringResource(R.string.account_pwd_confirm),
                    reveal = reveal,
                    onToggleReveal = { reveal = !reveal },
                    isError = err == ChangePasswordError.CONFIRM_MISMATCH,
                    enabled = !state.busy,
                    imeAction = ImeAction.Done,
                    onDone = submit,
                )
                if (errorText != null) {
                    Text(errorText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = canSubmit) {
                if (state.busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.account_pwd_submit))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.busy) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    reveal: Boolean,
    onToggleReveal: () -> Unit,
    isError: Boolean,
    enabled: Boolean,
    imeAction: ImeAction,
    placeholder: String? = null,
    onDone: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        isError = isError,
        enabled = enabled,
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone?.invoke() }),
        trailingIcon = {
            IconButton(onClick = onToggleReveal) {
                Icon(
                    imageVector = if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = stringResource(if (reveal) R.string.account_pwd_hide else R.string.account_pwd_show),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 单选对话框(主题 / 列表视图):点选项即生效并关闭 */
@Composable
private fun <T> SingleChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (option, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = option == selected, role = Role.RadioButton, onClick = { onSelect(option) })
                            .padding(vertical = 8.dp),
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

// ---- 文案映射 ----

private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.account_theme_system
    ThemeMode.LIGHT -> R.string.account_theme_light
    ThemeMode.DARK -> R.string.account_theme_dark
}

private fun DriveViewMode.labelRes(): Int = when (this) {
    DriveViewMode.LIST -> R.string.account_view_list
    DriveViewMode.GRID -> R.string.account_view_grid
}

private fun ChangePasswordError.messageRes(): Int = when (this) {
    ChangePasswordError.OLD_EMPTY -> R.string.account_pwd_err_old_empty
    ChangePasswordError.NEW_EMPTY -> R.string.account_pwd_err_new_empty
    ChangePasswordError.NEW_TOO_SHORT -> R.string.account_pwd_err_new_too_short
    ChangePasswordError.NEW_TOO_LONG -> R.string.account_pwd_err_new_too_long
    ChangePasswordError.NEW_SAME_AS_OLD -> R.string.account_pwd_err_same_as_old
    ChangePasswordError.CONFIRM_MISMATCH -> R.string.account_pwd_err_mismatch
}
