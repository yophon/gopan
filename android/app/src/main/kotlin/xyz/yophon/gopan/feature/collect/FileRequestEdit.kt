package xyz.yophon.gopan.feature.collect

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.AppConfig
import xyz.yophon.gopan.core.data.collect.FileRequest
import xyz.yophon.gopan.core.data.collect.FileRequestForm
import xyz.yophon.gopan.core.data.collect.FileRequestsRepository
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.core.ui.exposeTestTags
import xyz.yophon.gopan.core.ui.format.parseTime
import xyz.yophon.gopan.core.ui.rememberSystemActions
import xyz.yophon.gopan.feature.drive.resolve
import xyz.yophon.gopan.feature.shares.ShareCreateViewModel
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

/** 截止选项(与网页端一致);[KEEP] 只在修改时出现,保持原截止时间 */
enum class CollectExpiry(val days: Long?, @StringRes val label: Int) {
    KEEP(null, R.string.collect_expiry_keep),
    DAY_1(1, R.string.collect_expiry_1),
    DAYS_7(7, R.string.collect_expiry_7),
    DAYS_30(30, R.string.collect_expiry_30),
    UNLIMITED(null, R.string.collect_expiry_none),
    ;

    companion object {
        val CREATE_OPTIONS = listOf(DAY_1, DAYS_7, DAYS_30, UNLIMITED)
        val EDIT_OPTIONS = listOf(KEEP) + CREATE_OPTIONS
    }
}

/** 上限的预设档位与文字(与网页端 FileRequestDialog 一致);null = 不限 */
object CollectLimits {
    const val MB = 1024L * 1024
    const val GB = 1024L * MB
    val FILE_SIZE_PRESETS: List<Long?> = listOf(null, 100 * MB, GB, 5 * GB)
    val TOTAL_PRESETS: List<Long?> = listOf(null, GB, 10 * GB, 50 * GB)
    const val DEFAULT_TOTAL = 10 * GB
    const val MAX_TITLE = 100
    const val MAX_NOTE = 1000
    const val MAX_FILES = 100_000

    /** 已有的上限不在预设里(MCP 建的、以后改了档位)时补一个选项,免得修改时被悄悄改掉 */
    fun withCurrent(presets: List<Long?>, current: Long?): List<Long?> =
        if (current == null || current in presets) presets else presets + current

    /** 100 MB / 1 GB / 1.5 GB:GB 起最多两位小数,以下按 MB 一位小数,去掉末尾的 0 */
    fun label(bytes: Long): String =
        if (bytes >= GB) "${trim(bytes.toDouble() / GB, 2)} GB" else "${trim(bytes.toDouble() / MB, 1)} MB"

    private fun trim(value: Double, scale: Int): String =
        BigDecimal(value).setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}

/** 对话框的用途:对某个文件夹新建,或修改已有的链接 */
sealed interface FileRequestTarget {
    data class Create(val folderId: String, val folderName: String) : FileRequestTarget

    data class Edit(val request: FileRequest) : FileRequestTarget
}

@Immutable
data class FileRequestEditState(
    val editing: Boolean = false,
    val folderName: String = "",
    val title: String = "",
    val note: String = "",
    val expiry: CollectExpiry = CollectExpiry.DAYS_7,
    val requireName: Boolean = false,
    val groupByUploader: Boolean = false,
    val maxFileSize: Long? = null,
    val maxTotalBytes: Long? = CollectLimits.DEFAULT_TOTAL,
    /** 输入框里的原文:空 = 不限 */
    val maxFiles: String = "",
    val usePassword: Boolean = false,
    val password: String = "",
    val fileSizeChoices: List<Long?> = CollectLimits.FILE_SIZE_PRESETS,
    val totalChoices: List<Long?> = CollectLimits.TOTAL_PRESETS,
    val saving: Boolean = false,
    val error: String? = null,
    /** 新建好了:链接 + (有的话)密码 */
    val created: FileRequest? = null,
    val url: String? = null,
    /** 修改保存好了:界面据此关掉对话框 */
    val saved: FileRequest? = null,
) {
    /** 按人分文件夹得知道是谁:开着时「要求留名」强制开、不能关 */
    val effectiveRequireName: Boolean get() = requireName || groupByUploader
}

/**
 * 新建 / 修改收集链接(doc/20 二.3)。修改是整表单提交;密码与目标文件夹建好后不能改。
 * [target] 由打开对话框的地方给(assisted 注入),每次打开一个新的 ViewModel。
 */
@HiltViewModel(assistedFactory = FileRequestEditViewModel.Factory::class)
class FileRequestEditViewModel @AssistedInject constructor(
    @Assisted private val target: FileRequestTarget,
    private val repo: FileRequestsRepository,
    private val config: AppConfig,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(target: FileRequestTarget): FileRequestEditViewModel
    }

    private val _state = MutableStateFlow(initialState(target))
    val state: StateFlow<FileRequestEditState> = _state.asStateFlow()

    fun setTitle(value: String) = _state.update { it.copy(title = value.take(CollectLimits.MAX_TITLE), error = null) }

    fun setNote(value: String) = _state.update { it.copy(note = value.take(CollectLimits.MAX_NOTE)) }

    fun setExpiry(expiry: CollectExpiry) = _state.update { it.copy(expiry = expiry) }

    fun setRequireName(on: Boolean) = _state.update { if (it.groupByUploader) it else it.copy(requireName = on) }

    /** 打开按人分文件夹时把「要求留名」一起打开(关掉时留着,与网页端一致) */
    fun setGroupByUploader(on: Boolean) = _state.update { it.copy(groupByUploader = on, requireName = it.requireName || on) }

    fun setMaxFileSize(value: Long?) = _state.update { it.copy(maxFileSize = value) }

    fun setMaxTotalBytes(value: Long?) = _state.update { it.copy(maxTotalBytes = value) }

    fun setMaxFiles(value: String) = _state.update { it.copy(maxFiles = value.filter(Char::isDigit).take(6), error = null) }

    /** 打开密码保护时先填一个随机的 4 位码(与新建分享同一套字符,可改) */
    fun setUsePassword(on: Boolean) = _state.update {
        it.copy(usePassword = on, password = if (on && it.password.isEmpty()) ShareCreateViewModel.randomCode() else it.password, error = null)
    }

    fun setPassword(value: String) = _state.update { it.copy(password = value.take(ShareCreateViewModel.MAX_PASSWORD), error = null) }

    fun save(now: Instant = Instant.now()) {
        val s = _state.value
        if (s.saving || s.created != null || s.saved != null) return
        validate(s)?.let { message ->
            _state.update { it.copy(error = message) }
            return
        }
        val form = buildForm(s, (target as? FileRequestTarget.Edit)?.request?.expiresAt, now)
        val password = s.password.trim().takeIf { s.usePassword && !s.editing }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            when (target) {
                is FileRequestTarget.Create -> gopanResult { repo.create(target.folderId, form, password) }
                    .onSuccess { r ->
                        _state.update { it.copy(saving = false, created = r, url = config.collectUrl(r.token), password = password.orEmpty()) }
                    }
                    .onFailure { e -> _state.update { it.copy(saving = false, error = e.message ?: "创建失败") } }
                is FileRequestTarget.Edit -> gopanResult { repo.update(target.request.id, form) }
                    .onSuccess { r -> _state.update { it.copy(saving = false, saved = r) } }
                    .onFailure { e -> _state.update { it.copy(saving = false, error = e.message ?: "保存失败") } }
            }
        }
    }

    companion object {
        fun initialState(target: FileRequestTarget): FileRequestEditState = when (target) {
            is FileRequestTarget.Create -> FileRequestEditState(
                folderName = target.folderName,
                title = "往「${target.folderName}」传文件".take(CollectLimits.MAX_TITLE),
            )
            is FileRequestTarget.Edit -> target.request.let { r ->
                FileRequestEditState(
                    editing = true,
                    folderName = r.folderName,
                    title = r.title,
                    note = r.note,
                    expiry = CollectExpiry.KEEP,
                    requireName = r.requireName,
                    groupByUploader = r.groupByUploader,
                    maxFileSize = r.maxFileSize,
                    maxTotalBytes = r.maxTotalBytes,
                    maxFiles = r.maxFiles?.toString().orEmpty(),
                    fileSizeChoices = CollectLimits.withCurrent(CollectLimits.FILE_SIZE_PRESETS, r.maxFileSize),
                    totalChoices = CollectLimits.withCurrent(CollectLimits.TOTAL_PRESETS, r.maxTotalBytes),
                )
            }
        }

        /** 本地校验,不合法时返回给用户看的话 */
        fun validate(s: FileRequestEditState): String? {
            if (s.title.isBlank()) return "请填写标题"
            if (s.maxFiles.isNotEmpty()) {
                val n = s.maxFiles.toIntOrNull()
                if (n == null || n < 1 || n > CollectLimits.MAX_FILES) return "个数上限请填 1 到 ${CollectLimits.MAX_FILES} 之间的整数,或留空不限"
            }
            if (!s.editing && s.usePassword && s.password.isBlank()) return "请输入密码,或关闭密码保护"
            return null
        }

        /**
         * 表单 → 提交内容。截止:天数从 [now] 起算;「不变」用原来的截止时间([originalExpiresAt],null 即不限)。
         * 按人分文件夹时要求留名一定为真。
         */
        fun buildForm(s: FileRequestEditState, originalExpiresAt: String?, now: Instant): FileRequestForm = FileRequestForm(
            title = s.title.trim(),
            note = s.note.trim(),
            requireName = s.effectiveRequireName,
            groupByUploader = s.groupByUploader,
            maxFileSize = s.maxFileSize,
            maxTotalBytes = s.maxTotalBytes,
            maxFiles = s.maxFiles.toIntOrNull()?.takeIf { it > 0 },
            expiresAt = when (s.expiry) {
                CollectExpiry.KEEP -> parseTime(originalExpiresAt)
                else -> s.expiry.days?.let { now.plus(Duration.ofDays(it)) }
            },
        )

        /** 发送 / 复制的文本:标题、链接,知道密码时连密码一起(与网页端「复制链接和密码」一致) */
        fun shareText(title: String, url: String, password: String?): String = buildString {
            if (title.isNotBlank()) append(title.trim()).append('\n')
            append("链接:").append(url)
            if (!password.isNullOrEmpty()) append("\n密码:").append(password)
        }
    }
}

/**
 * 新建 / 修改收集链接的对话框。[instanceKey] 每次打开不同(新的 ViewModel、干净的表单),同一次打开里旋转屏幕不丢状态。
 * 修改:保存后提示「已保存」并关闭,[onSaved] 拿到服务端回显(含收件统计);新建:原地换成链接与复制 / 发送按钮。
 */
@Composable
fun FileRequestDialog(
    target: FileRequestTarget,
    instanceKey: String,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
    onSaved: (FileRequest) -> Unit = {},
) {
    val viewModel = hiltViewModel<FileRequestEditViewModel, FileRequestEditViewModel.Factory>(key = "file-request-$instanceKey") { factory ->
        factory.create(target)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val system = rememberSystemActions()
    val context = LocalContext.current
    val created = state.created
    val url = state.url
    val currentOnSaved by rememberUpdatedState(onSaved)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnMessage by rememberUpdatedState(onMessage)

    LaunchedEffect(state.saved) {
        state.saved?.let {
            currentOnSaved(it)
            currentOnMessage(context.getString(R.string.collect_msg_saved))
            currentOnDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        modifier = Modifier.exposeTestTags(),
        // 表单长,点到外面就丢了填好的内容:只允许返回键和按钮关闭
        properties = DialogProperties(dismissOnClickOutside = false),
        title = {
            Text(
                when {
                    created != null -> stringResource(R.string.collect_created_title)
                    state.editing -> stringResource(R.string.collect_edit_title)
                    else -> stringResource(R.string.collect_create_title, state.folderName)
                },
                maxLines = 2,
            )
        },
        text = {
            if (created == null || url == null) {
                FileRequestFormFields(state, viewModel)
            } else {
                FileRequestResult(created, url, state.password)
            }
        },
        confirmButton = {
            if (created == null || url == null) {
                TextButton(onClick = { viewModel.save() }, enabled = !state.saving, modifier = Modifier.testTag("collect_save")) {
                    if (state.saving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(if (state.editing) R.string.collect_save else R.string.collect_create))
                    }
                }
            } else {
                val text = FileRequestEditViewModel.shareText(created.title, url, state.password.takeIf { created.hasPassword })
                TextButton(
                    onClick = {
                        if (!system.sendText(text, context.getString(R.string.collect_send_chooser))) onMessage(context.getString(R.string.share_no_app))
                    },
                    modifier = Modifier.testTag("collect_send"),
                ) { Text(stringResource(R.string.collect_send)) }
            }
        },
        dismissButton = {
            if (created == null || url == null) {
                TextButton(onClick = onDismiss, enabled = !state.saving) { Text(stringResource(R.string.action_cancel)) }
            } else {
                Row {
                    TextButton(
                        onClick = {
                            val text = FileRequestEditViewModel.shareText(created.title, url, state.password.takeIf { created.hasPassword })
                            if (system.copy(text)) {
                                onMessage(context.getString(if (created.hasPassword) R.string.collect_msg_copied_all else R.string.collect_msg_copied))
                            }
                        },
                        modifier = Modifier.testTag("collect_copy"),
                    ) { Text(stringResource(R.string.collect_copy)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.collect_close)) }
                }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FileRequestFormFields(state: FileRequestEditState, viewModel: FileRequestEditViewModel) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = state.title,
            onValueChange = viewModel::setTitle,
            label = { Text(stringResource(R.string.collect_field_title)) },
            placeholder = { Text(stringResource(R.string.collect_field_title_hint)) },
            singleLine = true,
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth().testTag("collect_title"),
        )
        OutlinedTextField(
            value = state.note,
            onValueChange = viewModel::setNote,
            label = { Text(stringResource(R.string.collect_field_note)) },
            placeholder = { Text(stringResource(R.string.collect_field_note_hint)) },
            minLines = 2,
            maxLines = 4,
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth().testTag("collect_note"),
        )

        FieldLabel(R.string.collect_field_expiry)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (if (state.editing) CollectExpiry.EDIT_OPTIONS else CollectExpiry.CREATE_OPTIONS).forEach { option ->
                FilterChip(
                    selected = state.expiry == option,
                    onClick = { viewModel.setExpiry(option) },
                    label = { Text(stringResource(option.label)) },
                    modifier = Modifier.testTag("collect_expiry_${option.name}"),
                )
            }
        }

        SwitchRow(
            label = stringResource(R.string.collect_field_require_name),
            checked = state.effectiveRequireName,
            enabled = !state.groupByUploader,
            onChange = viewModel::setRequireName,
            tag = "collect_require_name",
        )
        SwitchRow(
            label = stringResource(R.string.collect_field_group),
            hint = stringResource(R.string.collect_field_group_hint),
            checked = state.groupByUploader,
            onChange = viewModel::setGroupByUploader,
            tag = "collect_group",
        )

        FieldLabel(R.string.collect_field_file_size)
        LimitChips(state.fileSizeChoices, state.maxFileSize, viewModel::setMaxFileSize, "collect_file_size")
        FieldLabel(R.string.collect_field_total, hint = R.string.collect_field_total_hint)
        LimitChips(state.totalChoices, state.maxTotalBytes, viewModel::setMaxTotalBytes, "collect_total")

        OutlinedTextField(
            value = state.maxFiles,
            onValueChange = viewModel::setMaxFiles,
            label = { Text(stringResource(R.string.collect_field_max_files)) },
            placeholder = { Text(stringResource(R.string.collect_limit_none)) },
            singleLine = true,
            enabled = !state.saving,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag("collect_max_files"),
        )

        if (!state.editing) {
            SwitchRow(
                label = stringResource(R.string.collect_field_password),
                checked = state.usePassword,
                onChange = viewModel::setUsePassword,
                tag = "collect_password_switch",
            )
            if (state.usePassword) {
                var visible by remember { mutableStateOf(true) }
                OutlinedTextField(
                    value = state.password,
                    onValueChange = viewModel::setPassword,
                    label = { Text(stringResource(R.string.collect_field_password_hint)) },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("collect_password"),
                )
            }
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("collect_error"))
        }
    }
}

@Composable
private fun FieldLabel(@StringRes label: Int, @StringRes hint: Int? = null) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
        if (hint != null) {
            Spacer(Modifier.size(8.dp))
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LimitChips(choices: List<Long?>, selected: Long?, onSelect: (Long?) -> Unit, tag: String) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { value ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(value?.let(CollectLimits::label) ?: stringResource(R.string.collect_limit_none)) },
                modifier = Modifier.testTag("${tag}_${value ?: 0}"),
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String, hint: String? = null, enabled: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun FileRequestResult(request: FileRequest, url: String, password: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                SelectionContainer {
                    Text(url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("collect_url"))
                }
            }
        }
        if (request.hasPassword) {
            Text(
                stringResource(R.string.collect_password_line, password),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("collect_password_value"),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            FileRequestTexts.deadline(request).resolve() + " · " + stringResource(R.string.collect_result_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
