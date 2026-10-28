package xyz.yophon.gopan.feature.shares

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.AppConfig
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.shares.Share
import xyz.yophon.gopan.core.data.shares.SharesRepository
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.core.ui.format.formatTime
import xyz.yophon.gopan.core.ui.rememberSystemActions
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import xyz.yophon.gopan.core.ui.exposeTestTags

/** 有效期选项(与网页端一致);[days] 为 null = 永久 */
enum class ShareExpiry(val days: Long?, @StringRes val label: Int) {
    DAY_1(1, R.string.share_expiry_1),
    DAYS_7(7, R.string.share_expiry_7),
    DAYS_30(30, R.string.share_expiry_30),
    FOREVER(null, R.string.share_expiry_forever),
    ;

    fun expiresAt(now: Instant): Instant? = days?.let { now.plus(Duration.ofDays(it)) }
}

@Immutable
data class ShareCreateState(
    val expiry: ShareExpiry = ShareExpiry.DAYS_7,
    val usePassword: Boolean = false,
    val password: String = "",
    /** 文件夹分享:对方也能往里传 */
    val allowUpload: Boolean = false,
    val creating: Boolean = false,
    val error: String? = null,
    /** 创建好了:链接 + (有的话)密码 */
    val created: Share? = null,
    val url: String? = null,
)

/** 新建分享:有效期、可选密码;成功后给出链接(复制 / 发送) */
@HiltViewModel
class ShareCreateViewModel @Inject constructor(
    private val repo: SharesRepository,
    private val config: AppConfig,
) : ViewModel() {

    private val _state = MutableStateFlow(ShareCreateState())
    val state: StateFlow<ShareCreateState> = _state.asStateFlow()

    fun setExpiry(expiry: ShareExpiry) = _state.update { it.copy(expiry = expiry) }

    /** 打开密码保护时先填一个随机的 4 位提取码(可改) */
    fun setUsePassword(on: Boolean) = _state.update {
        it.copy(usePassword = on, password = if (on && it.password.isEmpty()) randomCode() else it.password, error = null)
    }

    fun setPassword(value: String) = _state.update { it.copy(password = value.take(MAX_PASSWORD), error = null) }

    fun setAllowUpload(on: Boolean) = _state.update { it.copy(allowUpload = on) }

    fun create(nodeId: String, now: Instant = Instant.now(), isFolder: Boolean = false) {
        val s = _state.value
        if (s.creating || s.created != null) return
        val password = s.password.trim()
        if (s.usePassword && password.isEmpty()) {
            _state.update { it.copy(error = "请输入分享密码,或关闭密码保护") }
            return
        }
        _state.update { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            gopanResult { repo.create(nodeId, password.takeIf { s.usePassword }, s.expiry.expiresAt(now), allowUpload = isFolder && s.allowUpload) }
                .onSuccess { share -> _state.update { it.copy(creating = false, created = share, url = config.shareUrl(share.token), password = password) } }
                .onFailure { e -> _state.update { it.copy(creating = false, error = e.message ?: "创建分享失败") } }
        }
    }

    companion object {
        const val MAX_PASSWORD = 32
        private const val CODE_CHARS = "abcdefghjkmnpqrstuvwxyz23456789"
        private val random = SecureRandom()

        fun randomCode(length: Int = 4): String = buildString { repeat(length) { append(CODE_CHARS[random.nextInt(CODE_CHARS.length)]) } }

        /** 复制 / 发送的文本:有密码时连密码一起 */
        fun shareText(url: String, password: String?): String =
            if (password.isNullOrEmpty()) url else "链接:$url\n密码:$password"
    }
}

/**
 * 新建分享的对话框。[instanceKey] 每次打开不同(新的 ViewModel、干净的表单),同一次打开里旋转屏幕不丢状态。
 */
@Composable
fun ShareCreateDialog(node: Node, instanceKey: String, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val viewModel: ShareCreateViewModel = hiltViewModel(key = "share-create-$instanceKey")
    val state by viewModel.state.collectAsStateWithLifecycle()
    val system = rememberSystemActions()
    val context = LocalContext.current
    val created = state.created
    val url = state.url

    AlertDialog(
        onDismissRequest = { if (!state.creating) onDismiss() },
        modifier = Modifier.exposeTestTags(),
        properties = DialogProperties(dismissOnClickOutside = created == null),
        title = {
            Text(
                if (created == null) stringResource(R.string.share_create_title, node.name) else stringResource(R.string.share_created_title),
                maxLines = 2,
            )
        },
        text = {
            if (created == null || url == null) {
                ShareForm(state, viewModel, node.isFolder)
            } else {
                ShareResult(created, url, state.password)
            }
        },
        confirmButton = {
            if (created == null || url == null) {
                TextButton(onClick = { viewModel.create(node.id, isFolder = node.isFolder) }, enabled = !state.creating, modifier = Modifier.testTag("share_create")) {
                    if (state.creating) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.share_create))
                }
            } else {
                val text = ShareCreateViewModel.shareText(url, state.password.takeIf { created.hasPassword })
                TextButton(
                    onClick = {
                        if (!system.sendText(text, context.getString(R.string.share_send_chooser))) onMessage(context.getString(R.string.share_no_app))
                    },
                    modifier = Modifier.testTag("share_send"),
                ) { Text(stringResource(R.string.share_send)) }
            }
        },
        dismissButton = {
            if (created == null || url == null) {
                TextButton(onClick = onDismiss, enabled = !state.creating) { Text(stringResource(R.string.action_cancel)) }
            } else {
                Row {
                    TextButton(
                        onClick = {
                            val text = ShareCreateViewModel.shareText(url, state.password.takeIf { created.hasPassword })
                            if (system.copy(text)) onMessage(context.getString(R.string.shares_msg_copied))
                        },
                        modifier = Modifier.testTag("share_copy"),
                    ) { Text(stringResource(if (created.hasPassword) R.string.share_copy_all else R.string.share_copy)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.share_close)) }
                }
            }
        },
    )
}

@Composable
private fun ShareForm(state: ShareCreateState, viewModel: ShareCreateViewModel, isFolder: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.share_expiry), style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ShareExpiry.entries.forEachIndexed { i, option ->
                SegmentedButton(
                    selected = state.expiry == option,
                    onClick = { viewModel.setExpiry(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = ShareExpiry.entries.size),
                    modifier = Modifier.testTag("share_expiry_${option.name}"),
                    icon = {},
                ) { Text(stringResource(option.label), maxLines = 1) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.share_password_toggle), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = state.usePassword, onCheckedChange = viewModel::setUsePassword, modifier = Modifier.testTag("share_password_switch"))
        }
        if (state.usePassword) {
            var visible by remember { mutableStateOf(true) }
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::setPassword,
                label = { Text(stringResource(R.string.share_password_hint)) },
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = null)
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("share_password"),
            )
        }
        if (isFolder) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.share_upload_toggle), style = MaterialTheme.typography.bodyLarge)
                    if (state.allowUpload) {
                        Text(
                            stringResource(R.string.share_upload_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(checked = state.allowUpload, onCheckedChange = viewModel::setAllowUpload, modifier = Modifier.testTag("share_upload_switch"))
            }
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("share_error"))
        }
    }
}

@Composable
private fun ShareResult(share: Share, url: String, password: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                SelectionContainer {
                    Text(url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("share_url"))
                }
            }
        }
        if (share.hasPassword) {
            Text(stringResource(R.string.share_password_line, password), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("share_password_value"))
        }
        Spacer(Modifier.height(2.dp))
        Text(
            (share.expiresAt?.let { stringResource(R.string.share_expires_at, formatTime(it)) } ?: stringResource(R.string.shares_forever)) +
                (if (share.allowUpload) stringResource(R.string.share_upload_on_suffix) else ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
