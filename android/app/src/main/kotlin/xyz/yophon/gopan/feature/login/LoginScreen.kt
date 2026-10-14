package xyz.yophon.gopan.feature.login

import android.content.ActivityNotFoundException
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.auth.rememberYophonIdLauncher
import xyz.yophon.gopan.core.ui.theme.GopanTheme

/**
 * 登录页:Yophon ID 主入口;「其他账号使用密码登录」展开 登录 / 注册 两个 tab。
 * 成功后不在这里导航:AuthState 变为 SignedIn 时 RootNavHost 自己切到主界面。
 */
@Composable
fun LoginScreen(
    initialMode: LoginMode = LoginMode.YOPHON_ID,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val launcher = rememberYophonIdLauncher { data -> viewModel.onYophonIdResult(data) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) { viewModel.applyInitialMode(initialMode) }

    // 授权页只在前台时启动(后台启动 Activity 会被系统拦下而没有回调,busy 就卡住了);Channel 会把事件留到前台再投递
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { event ->
                when (event) {
                    is LoginEvent.LaunchAuthorization -> try {
                        launcher.launch(event.intent)
                    } catch (e: ActivityNotFoundException) {
                        viewModel.onYophonIdLaunchFailed(e)
                    }
                }
            }
        }
    }

    // 展开了密码面板时,返回键先收起面板
    BackHandler(enabled = state.passwordPanelVisible && !state.busy) { viewModel.showYophonId() }

    LoginContent(
        state = state,
        onStartYophonId = viewModel::startYophonId,
        onShowYophonId = viewModel::showYophonId,
        onShowPasswordLogin = viewModel::showPasswordLogin,
        onShowRegister = viewModel::showRegister,
        onLogin = viewModel::login,
        onRegister = viewModel::register,
    )
}

@Composable
private fun LoginContent(
    state: LoginUiState,
    onStartYophonId: () -> Unit,
    onShowYophonId: () -> Unit,
    onShowPasswordLogin: () -> Unit,
    onShowRegister: () -> Unit,
    onLogin: (username: String, password: String) -> Unit,
    onRegister: (username: String, password: String, confirm: String) -> Unit,
) {
    // safeDrawing 含状态栏、导航栏、刘海与 IME:键盘弹起时可视区缩小,表单在其中居中并可滚动,不会被遮住
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val viewportHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewportHeight)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Brand()
                Spacer(Modifier.height(36.dp))
                AnimatedContent(targetState = state.passwordPanelVisible, label = "login-panel") { expanded ->
                    if (expanded) {
                        PasswordPanel(
                            state = state,
                            onShowYophonId = onShowYophonId,
                            onShowPasswordLogin = onShowPasswordLogin,
                            onShowRegister = onShowRegister,
                            onLogin = onLogin,
                            onRegister = onRegister,
                        )
                    } else {
                        YophonIdPanel(
                            state = state,
                            onStartYophonId = onStartYophonId,
                            onShowPasswordLogin = onShowPasswordLogin,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Brand() {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(80.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Outlined.Cloud,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(44.dp),
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.login_title),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun YophonIdPanel(
    state: LoginUiState,
    onStartYophonId: () -> Unit,
    onShowPasswordLogin: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.login_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        BusyButton(
            text = stringResource(R.string.login_with_yophon_id),
            busyText = stringResource(R.string.login_connecting_id),
            busy = state.busy,
            onClick = onStartYophonId,
        )
        ErrorBanner(message = state.bannerError)
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onShowPasswordLogin, enabled = !state.busy) {
            Text(stringResource(R.string.login_other_account))
        }
    }
}

@Composable
private fun PasswordPanel(
    state: LoginUiState,
    onShowYophonId: () -> Unit,
    onShowPasswordLogin: () -> Unit,
    onShowRegister: () -> Unit,
    onLogin: (String, String) -> Unit,
    onRegister: (String, String, String) -> Unit,
) {
    val registering = state.mode == LoginMode.REGISTER
    val tabs = if (state.showRegisterTab) listOf(LoginMode.PASSWORD, LoginMode.REGISTER) else listOf(LoginMode.PASSWORD)
    val selectedTab = tabs.indexOf(state.mode).coerceAtLeast(0)

    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }

    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val submit = {
        keyboard?.hide()
        if (registering) onRegister(username, password, confirm) else onLogin(username, password)
    }
    val moveNext = { focusManager.moveFocus(FocusDirection.Down); Unit }

    Column(modifier = Modifier.fillMaxWidth()) {
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            tabs.forEach { tab ->
                Tab(
                    selected = state.mode == tab,
                    onClick = { if (tab == LoginMode.REGISTER) onShowRegister() else onShowPasswordLogin() },
                    enabled = !state.busy,
                    text = {
                        Text(stringResource(if (tab == LoginMode.REGISTER) R.string.login_tab_register else R.string.login_tab_login))
                    },
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentType = if (registering) ContentType.NewUsername else ContentType.Username },
            label = { Text(stringResource(R.string.login_username)) },
            singleLine = true,
            enabled = !state.busy,
            isError = state.errorField == LoginField.USERNAME,
            supportingText = state.fieldError(LoginField.USERNAME)?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onNext = { moveNext() }),
        )
        Spacer(Modifier.height(12.dp))
        PasswordField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(R.string.login_password),
            enabled = !state.busy,
            error = state.fieldError(LoginField.PASSWORD),
            autofillType = if (registering) ContentType.NewPassword else ContentType.Password,
            imeAction = if (registering) ImeAction.Next else ImeAction.Done,
            onNext = moveNext,
            onDone = submit,
        )
        if (registering) {
            Spacer(Modifier.height(12.dp))
            PasswordField(
                value = confirm,
                onValueChange = { confirm = it },
                label = stringResource(R.string.login_confirm_password),
                enabled = !state.busy,
                error = state.fieldError(LoginField.CONFIRM),
                autofillType = ContentType.NewPassword,
                imeAction = ImeAction.Done,
                onNext = moveNext,
                onDone = submit,
            )
        }

        ErrorBanner(message = state.bannerError)
        Spacer(Modifier.height(20.dp))
        BusyButton(
            text = stringResource(if (registering) R.string.login_register_submit else R.string.login_submit),
            busyText = stringResource(R.string.login_working),
            busy = state.busy,
            onClick = submit,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = onShowYophonId,
            enabled = !state.busy,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(stringResource(R.string.login_back_to_id))
        }
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    error: String?,
    autofillType: ContentType,
    imeAction: ImeAction,
    onNext: () -> Unit,
    onDone: () -> Unit,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentType = autofillType },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = stringResource(if (visible) R.string.login_hide_password else R.string.login_show_password),
                )
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = KeyboardActions(onNext = { onNext() }, onDone = { onDone() }),
    )
}

/** 主按钮:busy 时禁用并显示小转圈 + 进行中文案 */
@Composable
private fun BusyButton(
    text: String,
    busyText: String,
    busy: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = !busy,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
            Spacer(Modifier.size(10.dp))
            Text(busyText)
        } else {
            Text(text)
        }
    }
}

/** 表单下方的错误横幅(含上方间距,随横幅一起出现/消失);消失动画期间仍显示最后一条文案 */
@Composable
private fun ErrorBanner(message: String?) {
    var lastMessage by remember { mutableStateOf(message) }
    if (message != null) lastMessage = message
    AnimatedVisibility(visible = message != null, modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    text = lastMessage.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

// ---- 预览 ----

@Preview(name = "Yophon ID", showBackground = true)
@Preview(name = "Yophon ID · 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewYophonId() {
    GopanTheme(dynamicColor = false) {
        LoginContent(
            state = LoginUiState(),
            onStartYophonId = {}, onShowYophonId = {}, onShowPasswordLogin = {}, onShowRegister = {},
            onLogin = { _, _ -> }, onRegister = { _, _, _ -> },
        )
    }
}

@Preview(name = "密码登录 · 出错", showBackground = true)
@Composable
private fun PreviewPasswordError() {
    GopanTheme(dynamicColor = false) {
        LoginContent(
            state = LoginUiState(mode = LoginMode.PASSWORD, error = LoginRules.TEXT_BAD_CREDENTIALS),
            onStartYophonId = {}, onShowYophonId = {}, onShowPasswordLogin = {}, onShowRegister = {},
            onLogin = { _, _ -> }, onRegister = { _, _, _ -> },
        )
    }
}

@Preview(name = "注册 · 进行中", showBackground = true)
@Composable
private fun PreviewRegisterBusy() {
    GopanTheme(dynamicColor = false) {
        LoginContent(
            state = LoginUiState(mode = LoginMode.REGISTER, busy = true),
            onStartYophonId = {}, onShowYophonId = {}, onShowPasswordLogin = {}, onShowRegister = {},
            onLogin = { _, _ -> }, onRegister = { _, _, _ -> },
        )
    }
}
