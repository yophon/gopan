package xyz.yophon.gopan.feature.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.assistant.AssistantApproval
import xyz.yophon.gopan.core.assistant.AssistantItem
import xyz.yophon.gopan.core.ui.format.formatRelativeTime
import xyz.yophon.gopan.feature.drive.NestedScreenInsets
import xyz.yophon.gopan.feature.drive.TopBarInsets

/**
 * 助手(doc/33 八之 2):对话、确认卡、对话记录。对话在整个 App 一份,离开这页这一轮也接着跑完。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: AssistantViewModel = hiltViewModel(),
) {
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var historyOpen by remember { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(ui.message) {
        ui.message?.let { snackbar.showSnackbar(it); viewModel.messageShown() }
    }
    LaunchedEffect(chat.items.size, chat.live, chat.approval) {
        val last = chat.items.size + (if (chat.live.isNotEmpty()) 1 else 0) + (if (chat.approval != null) 1 else 0) + 1
        if (last > 0) listState.animateScrollToItem(last - 1)
    }

    Scaffold(
        contentWindowInsets = NestedScreenInsets,
        topBar = {
            TopAppBar(
                windowInsets = TopBarInsets,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.drive_nav_back))
                    }
                },
                title = { ModelPicker(ui, enabled = !chat.running, onPick = viewModel::pick) },
                actions = {
                    IconButton(onClick = { historyOpen = true; viewModel.loadHistory() }, modifier = Modifier.testTag("assistant_history")) {
                        Icon(Icons.Outlined.History, contentDescription = stringResource(R.string.assistant_history))
                    }
                    IconButton(onClick = viewModel::newChat, enabled = !chat.running) {
                        Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.assistant_new))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.assistant_settings))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("assistant_list"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (ui.providers?.isEmpty() == true) {
                    item("empty") {
                        Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.assistant_no_provider), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = onOpenSettings, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.assistant_add_provider)) }
                        }
                    }
                } else if (chat.items.isEmpty() && !chat.loading) {
                    item("hints") { Hints(onPick = { input = it }) }
                }
                items(chat.items, key = { it.key }) { ItemRow(it) }
                if (chat.live.isNotEmpty()) item("live") { Text(miniMarkdown(chat.live), style = MaterialTheme.typography.bodyLarge) }
                chat.approval?.let { a -> item("approval") { ApprovalCard(a, onAnswer = viewModel::answer) } }
                if (chat.conflict || chat.error != null) {
                    item("error") { ErrorBubble(if (chat.conflict) stringResource(R.string.assistant_conflict) else chat.error.orEmpty()) }
                }
            }
            Composer(
                input = input,
                onInput = { input = it },
                contextLabel = viewModel.contextLabel,
                running = chat.running,
                canSend = ui.current != null && !chat.loading && !chat.conflict,
                onSend = {
                    viewModel.send(input)
                    input = ""
                },
                onStop = viewModel::stop,
            )
        }
    }

    if (historyOpen) {
        ModalBottomSheet(onDismissRequest = { historyOpen = false }) {
            val rows = ui.history
            if (rows != null && rows.isEmpty()) {
                Text(stringResource(R.string.assistant_history_empty), modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(rows.orEmpty(), key = { it.id }) { row ->
                    ListItem(
                        headlineContent = { Text(row.title.ifBlank { stringResource(R.string.assistant_untitled) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(formatRelativeTime(row.updatedAt)) },
                        trailingContent = {
                            IconButton(onClick = { viewModel.delete(row) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.assistant_delete))
                            }
                        },
                        modifier = Modifier.clickable {
                            historyOpen = false
                            viewModel.open(row)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelPicker(ui: AssistantUi, enabled: Boolean, onPick: (xyz.yophon.gopan.core.assistant.AiProvider, String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = ui.current
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(enabled = enabled && !ui.providers.isNullOrEmpty()) { open = true },
        ) {
            Text(
                current?.let { "${it.first.name} · ${it.second}" } ?: stringResource(R.string.assistant_title),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp),
            )
            if (!ui.providers.isNullOrEmpty()) Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ui.providers.orEmpty().forEach { p ->
                p.models.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(if (p.site) "${p.name} · $m(${stringResource(R.string.assistant_site)})" else "${p.name} · $m") },
                        onClick = {
                            open = false
                            onPick(p, m)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Hints(onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.assistant_hint_title), color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf(R.string.assistant_hint_1, R.string.assistant_hint_2, R.string.assistant_hint_3).forEach { res ->
            val text = stringResource(res)
            OutlinedButton(onClick = { onPick(text) }) { Text(text) }
        }
    }
}

@Composable
private fun ItemRow(item: AssistantItem) {
    when (item.kind) {
        "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                item.text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .padding(start = 48.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        "assistant" -> Text(miniMarkdown(item.text), style = MaterialTheme.typography.bodyLarge)
        "tool" -> {
            val mark = when (item.status) {
                "ok" -> "✓"
                "error" -> "✕"
                "denied" -> "⊘"
                else -> "…"
            }
            val suffix = if (item.status == "denied") stringResource(R.string.assistant_not_done) else ""
            Text(
                "$mark  ${item.text}$suffix",
                style = MaterialTheme.typography.bodyMedium,
                color = if (item.status == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> ErrorBubble(item.text)
    }
}

@Composable
private fun ErrorBubble(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(10.dp)).padding(12.dp),
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ApprovalCard(a: AssistantApproval, onAnswer: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.tertiary, RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(14.dp)
            .testTag("assistant_approval"),
    ) {
        Text(a.title, style = MaterialTheme.typography.titleSmall)
        a.names.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
        if (a.more > 0) Text(stringResource(R.string.assistant_more, a.more), style = MaterialTheme.typography.bodySmall)
        a.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)) }
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onAnswer("once") }) { Text(stringResource(R.string.assistant_allow)) }
            if (a.canAllowSession) OutlinedButton(onClick = { onAnswer("session") }) { Text(stringResource(R.string.assistant_allow_session)) }
            TextButton(onClick = { onAnswer("deny") }) { Text(stringResource(R.string.assistant_deny)) }
        }
    }
}

@Composable
private fun Composer(
    input: String,
    onInput: (String) -> Unit,
    contextLabel: String?,
    running: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    // 页面的 Scaffold(NestedScreenInsets)已经让出了底部导航,这里不再加
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp)) {
        if (contextLabel != null) {
            Text(contextLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = input,
                onValueChange = onInput,
                modifier = Modifier.weight(1f).testTag("assistant_input"),
                placeholder = { Text(stringResource(R.string.assistant_placeholder)) },
                maxLines = 6,
            )
            if (running) {
                IconButton(onClick = onStop, modifier = Modifier.testTag("assistant_stop")) {
                    Icon(Icons.Outlined.Stop, contentDescription = stringResource(R.string.assistant_stop))
                }
            } else {
                IconButton(onClick = onSend, enabled = canSend && input.isNotBlank(), modifier = Modifier.testTag("assistant_send")) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.assistant_send))
                }
            }
        }
    }
}
