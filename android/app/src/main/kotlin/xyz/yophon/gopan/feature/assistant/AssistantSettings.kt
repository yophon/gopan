package xyz.yophon.gopan.feature.assistant

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import xyz.yophon.gopan.core.assistant.AiProvider
import xyz.yophon.gopan.core.assistant.AiProviderDraft
import xyz.yophon.gopan.core.assistant.AssistantAccess
import xyz.yophon.gopan.core.assistant.AssistantRepository
import xyz.yophon.gopan.core.prefs.UserPreferencesRepository
import xyz.yophon.gopan.core.ui.components.ConfirmDialog
import xyz.yophon.gopan.feature.drive.NestedScreenInsets
import xyz.yophon.gopan.feature.drive.TopBarInsets
import javax.inject.Inject

@Immutable
data class AssistantSettingsUi(
    val direct: Boolean = false,
    val providers: List<AiProvider>? = null,
    val access: AssistantAccess? = null,
    val message: String? = null,
)

/** 助手设置(doc/33 一):连接方式(按设备记)、自己的模型服务、助手能做什么(三态,与网页「Agent 接入 → 助手」同一份) */
@HiltViewModel
class AssistantSettingsViewModel @Inject constructor(
    private val repo: AssistantRepository,
    private val prefs: UserPreferencesRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow(AssistantSettingsUi())
    val ui: StateFlow<AssistantSettingsUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch { prefs.assistantDirect.collect { d -> _ui.update { it.copy(direct = d) } } }
        load()
    }

    fun load() {
        viewModelScope.launch {
            runCatching { repo.providers() }.onSuccess { p -> _ui.update { it.copy(providers = p) } }.onFailure(::fail)
            runCatching { repo.access() }.onSuccess { a -> _ui.update { it.copy(access = a) } }.onFailure(::fail)
        }
    }

    private fun fail(e: Throwable) = _ui.update { it.copy(message = e.userText()) }

    fun setDirect(direct: Boolean) {
        viewModelScope.launch { prefs.setAssistantDirect(direct) }
    }

    fun save(existing: AiProvider?, draft: AiProviderDraft) {
        viewModelScope.launch {
            runCatching { if (existing == null) repo.createProvider(draft) else repo.updateProvider(existing.id, draft) }
                .onSuccess { load() }
                .onFailure(::fail)
        }
    }

    fun delete(p: AiProvider) {
        viewModelScope.launch { runCatching { repo.deleteProvider(p.id) }.onSuccess { load() }.onFailure(::fail) }
    }

    fun setPermission(key: String, permission: String) {
        val a = _ui.value.access ?: return
        viewModelScope.launch { runCatching { repo.setAccess(a.disabled, mapOf(key to permission)) }.onSuccess { n -> _ui.update { it.copy(access = n) } }.onFailure(::fail) }
    }

    fun setDisabled(disabled: Boolean) {
        viewModelScope.launch { runCatching { repo.setAccess(disabled, emptyMap()) }.onSuccess { n -> _ui.update { it.copy(access = n) } }.onFailure(::fail) }
    }

    fun messageShown() = _ui.update { it.copy(message = null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantSettingsScreen(onBack: () -> Unit, viewModel: AssistantSettingsViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<AiProvider?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<AiProvider?>(null) }
    var outbound by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(ui.message) { ui.message?.let { snackbar.showSnackbar(it); viewModel.messageShown() } }

    Scaffold(
        contentWindowInsets = NestedScreenInsets,
        topBar = {
            TopAppBar(
                windowInsets = TopBarInsets,
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.drive_nav_back)) }
                },
                title = { Text(stringResource(R.string.assistant_settings)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item { Section(stringResource(R.string.assistant_mode_title)) }
            item {
                ModeRow(stringResource(R.string.assistant_mode_proxy), stringResource(R.string.assistant_mode_proxy_hint), !ui.direct) { viewModel.setDirect(false) }
                ModeRow(stringResource(R.string.assistant_mode_direct), stringResource(R.string.assistant_mode_direct_hint), ui.direct) { viewModel.setDirect(true) }
            }

            val site = ui.providers.orEmpty().filter { it.site }
            if (site.isNotEmpty()) {
                item { Section(stringResource(R.string.assistant_site_title)) }
                items(site, key = { "s" + it.id }) { p ->
                    val usage = if (p.dailyTokens > 0) "${p.usedTokens} / ${p.dailyTokens}" else "${p.usedTokens}"
                    ListItem(
                        headlineContent = { Text(p.name) },
                        supportingContent = { Text(p.models.joinToString("、"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = { Text(stringResource(R.string.assistant_usage_today, usage), style = MaterialTheme.typography.bodySmall) },
                    )
                }
            }

            item { Section(stringResource(R.string.assistant_mine_title)) }
            items(ui.providers.orEmpty().filter { !it.site }, key = { "m" + it.id }) { p ->
                ListItem(
                    headlineContent = { Text(if (p.hasKey) p.name else p.name + stringResource(R.string.assistant_no_key)) },
                    supportingContent = { Text("${p.baseUrl} · ${p.models.joinToString("、")}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { IconButton(onClick = { deleting = p }) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.assistant_delete)) } },
                    modifier = Modifier.clickable { editing = p },
                )
            }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.assistant_add_provider)) },
                    modifier = Modifier.clickable { adding = true },
                )
            }

            ui.access?.let { access ->
                item { Section(stringResource(R.string.assistant_access_title)) }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.assistant_enabled)) },
                        trailingContent = { Switch(checked = !access.disabled, onCheckedChange = { viewModel.setDisabled(!it) }) },
                    )
                }
                items(access.groups, key = { "g" + it.key }) { g ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text(if (g.outbound) g.label + stringResource(R.string.assistant_outbound) else g.label, style = MaterialTheme.typography.bodyLarge)
                        val options = listOf("ALLOW" to R.string.assistant_perm_allow, "ASK" to R.string.assistant_perm_ask, "DENY" to R.string.assistant_perm_deny)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            options.forEachIndexed { i, (value, label) ->
                                SegmentedButton(
                                    selected = g.permission == value,
                                    enabled = !access.disabled,
                                    onClick = {
                                        if (value == "ALLOW" && g.outbound && g.permission != "ALLOW") outbound = g.key to value
                                        else viewModel.setPermission(g.key, value)
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                                ) { Text(stringResource(label)) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (adding || editing != null) {
        ProviderDialog(
            existing = editing,
            onDismiss = { adding = false; editing = null },
            onSave = { draft ->
                viewModel.save(editing, draft)
                adding = false
                editing = null
            },
        )
    }
    deleting?.let { p ->
        ConfirmDialog(
            title = stringResource(R.string.assistant_delete_provider_title),
            text = stringResource(R.string.assistant_delete_provider_message, p.name),
            confirmText = stringResource(R.string.assistant_delete),
            destructive = true,
            onConfirm = { viewModel.delete(p); deleting = null },
            onDismiss = { deleting = null },
        )
    }
    outbound?.let { (key, value) ->
        ConfirmDialog(
            title = stringResource(R.string.assistant_outbound_title),
            text = stringResource(R.string.assistant_outbound_message),
            confirmText = stringResource(R.string.assistant_outbound_confirm),
            onConfirm = { viewModel.setPermission(key, value); outbound = null },
            onDismiss = { outbound = null },
        )
    }
}

@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))
}

@Composable
private fun ModeRow(title: String, hint: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val KINDS = listOf(
    "OPENAI" to "https://api.deepseek.com/v1",
    "ANTHROPIC" to "https://api.anthropic.com",
    "GOOGLE" to "https://generativelanguage.googleapis.com/v1beta",
    "OPENAI_RESPONSES" to "https://api.openai.com/v1",
)

@Composable
private fun ProviderDialog(existing: AiProvider?, onDismiss: () -> Unit, onSave: (AiProviderDraft) -> Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var kind by remember { mutableStateOf(existing?.kind ?: "OPENAI") }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl.orEmpty()) }
    var models by remember { mutableStateOf(existing?.models?.joinToString("\n").orEmpty()) }
    var key by remember { mutableStateOf("") }
    val kindLabels = mapOf(
        "OPENAI" to stringResource(R.string.assistant_kind_openai),
        "ANTHROPIC" to "Anthropic(Claude)",
        "GOOGLE" to "Google(Gemini)",
        "OPENAI_RESPONSES" to "OpenAI Responses",
    )
    val placeholder = KINDS.first { it.first == kind }.second
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (existing == null) R.string.assistant_add_provider else R.string.assistant_edit_provider)) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.assistant_field_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                KINDS.forEach { (k, _) ->
                    Row(Modifier.fillMaxWidth().selectable(selected = kind == k, onClick = { kind = k }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = kind == k, onClick = null, modifier = Modifier.padding(4.dp))
                        Text(kindLabels.getValue(k), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text(stringResource(R.string.assistant_field_url)) }, placeholder = { Text(placeholder) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(models, { models = it }, label = { Text(stringResource(R.string.assistant_field_models)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    key,
                    { key = it },
                    label = { Text("key") },
                    placeholder = { if (existing?.hasKey == true) Text(stringResource(R.string.assistant_key_keep)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && AssistantLogic.parseModels(models).isNotEmpty(),
                onClick = {
                    onSave(
                        AiProviderDraft(
                            name = name.trim(),
                            kind = kind,
                            baseUrl = baseUrl.trim().ifEmpty { placeholder },
                            models = AssistantLogic.parseModels(models),
                            key = if (existing != null && key.isEmpty()) null else key.trim(),
                        ),
                    )
                },
            ) { Text(stringResource(R.string.assistant_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.assistant_cancel)) } },
    )
}
