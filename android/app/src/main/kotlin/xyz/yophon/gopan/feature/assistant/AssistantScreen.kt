package xyz.yophon.gopan.feature.assistant

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.assistant.AssistantApproval
import xyz.yophon.gopan.core.assistant.AssistantItem
import xyz.yophon.gopan.core.assistant.ChatImages
import xyz.yophon.gopan.core.assistant.RefNode
import xyz.yophon.gopan.core.ui.format.formatRelativeTime
import xyz.yophon.gopan.feature.drive.NestedScreenInsets
import xyz.yophon.gopan.feature.drive.TopBarInsets

/**
 * 助手(doc/33 八之 2):对话、确认卡、对话记录。对话在整个 App 一份,离开这页这一轮也接着跑完。
 * 能随消息发图(相册选,doc/35);回答里引用的文件能点:文件夹进去看,文件开预览。
 * 样子跟着传输助手走:浮起的胶囊输入框(发图、在服务器上跑、输入、发送在一起),用户的话是同样的气泡。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenPreview: (String) -> Unit,
    viewModel: AssistantViewModel = hiltViewModel(),
) {
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var historyOpen by remember { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val openRef: (RefNode) -> Unit = { node -> if (node.folder) onOpenFolder(node.id) else onOpenPreview(node.id) }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(ChatImages.MAX)) { uris ->
        if (uris.isNotEmpty()) viewModel.addImages(uris)
    }
    val waiting = chat.running && chat.live.isEmpty() && chat.approval == null

    LaunchedEffect(ui.message) {
        ui.message?.let { snackbar.showSnackbar(it); viewModel.messageShown() }
    }
    LaunchedEffect(chat.items.size, chat.live, chat.approval, waiting) {
        val last = chat.items.size + (if (chat.live.isNotEmpty() || waiting) 1 else 0) + (if (chat.approval != null) 1 else 0) + 1
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
                title = { TitleWithModel(ui, enabled = !chat.running, onPick = viewModel::pick) },
                actions = {
                    IconButton(onClick = { historyOpen = true; viewModel.loadHistory() }, modifier = Modifier.testTag("assistant_history")) {
                        Icon(Icons.Outlined.History, contentDescription = stringResource(R.string.assistant_history))
                    }
                    IconButton(onClick = viewModel::newChat, enabled = !chat.running) {
                        Icon(Icons.Outlined.EditNote, contentDescription = stringResource(R.string.assistant_new))
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
                verticalArrangement = Arrangement.spacedBy(10.dp),
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
                items(chat.items, key = { it.key }) { ItemRow(it, ui.refs, openRef) }
                if (chat.live.isNotEmpty()) {
                    item("live") { AssistantReply(chat.live, ui.refs, openRef) }
                } else if (waiting) {
                    item("waiting") { ThinkingDots() }
                }
                chat.approval?.let { a -> item("approval") { ApprovalCard(a, onAnswer = viewModel::answer) } }
                if (chat.conflict || chat.error != null) {
                    item("error") { ErrorBubble(if (chat.conflict) stringResource(R.string.assistant_conflict) else chat.error.orEmpty()) }
                }
            }
            Composer(
                input = input,
                onInput = { input = it },
                selectionCount = viewModel.selectionCount,
                serverAvailable = ui.serverAvailable,
                onServer = chat.onServer,
                onToggleServer = { viewModel.setOnServer(!chat.onServer) },
                running = chat.running,
                canSend = ui.current != null && !chat.loading && !chat.conflict,
                pendingImages = ui.pendingImages,
                onPickImages = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onRemoveImage = viewModel::removeImage,
                onSend = {
                    if (viewModel.send(input)) input = ""
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
                        supportingContent = {
                            val badge = when (row.runStatus) {
                                "WAITING" -> stringResource(R.string.assistant_run_waiting)
                                "RUNNING", "QUEUED" -> stringResource(R.string.assistant_run_running)
                                else -> null
                            }
                            Text(if (badge != null) "$badge · ${formatRelativeTime(row.updatedAt)}" else formatRelativeTime(row.updatedAt))
                        },
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

/** 标题:大字「助手」,下面一行小字是模型名,点开换 */
@Composable
private fun TitleWithModel(ui: AssistantUi, enabled: Boolean, onPick: (xyz.yophon.gopan.core.assistant.AiProvider, String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = ui.current
    Column {
        Text(stringResource(R.string.assistant_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (!ui.providers.isNullOrEmpty()) {
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(enabled = enabled) { open = true }.testTag("assistant_model"),
                ) {
                    Text(
                        current?.second.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 200.dp),
                    )
                    Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    ui.providers.forEach { p ->
                        Text(
                            if (p.site) "${p.name}(${stringResource(R.string.assistant_site)})" else p.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
                        )
                        p.models.forEach { m ->
                            val on = current?.first?.id == p.id && current.second == m
                            DropdownMenuItem(
                                text = { Text(m, fontWeight = if (on) FontWeight.SemiBold else null) },
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
    }
}

@Composable
private fun Hints(onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(R.string.assistant_hint_1, R.string.assistant_hint_2, R.string.assistant_hint_4, R.string.assistant_hint_3).forEach { res ->
            val text = stringResource(res)
            Surface(
                onClick = { onPick(text) },
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
            }
        }
    }
}

@Composable
private fun ItemRow(item: AssistantItem, refs: Map<String, RefNode?>, onOpen: (RefNode) -> Unit) {
    when (item.kind) {
        // 用户的话:和传输助手同样的气泡,靠右
        "user" -> Column(Modifier.fillMaxWidth().padding(start = 48.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!item.images.isNullOrEmpty()) {
                ChatImageRow(item.images, size = 88)
            } else if ((item.imageCount ?: 0) > 0) {
                Text(stringResource(R.string.assistant_image_count, item.imageCount!!), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (item.text.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp)) {
                    Text(item.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp))
                }
            }
        }
        "assistant" -> AssistantReply(item.text, refs, onOpen)
        "tool" -> {
            val (mark, tint) = when (item.status) {
                "ok" -> "✓" to MaterialTheme.colorScheme.primary
                "error" -> "✕" to MaterialTheme.colorScheme.error
                "denied" -> "⊘" to MaterialTheme.colorScheme.onSurfaceVariant
                else -> "…" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            val suffix = if (item.status == "denied") stringResource(R.string.assistant_not_done) else ""
            Row {
                Text(mark, style = MaterialTheme.typography.labelMedium, color = tint, modifier = Modifier.widthIn(min = 18.dp))
                Text("${item.text}$suffix", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        else -> ErrorBubble(item.text)
    }
}

/** 等模型开口:三个点 */
@Composable
private fun ThinkingDots() {
    val t = rememberInfiniteTransition(label = "thinking")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(600, delayMillis = i * 200), RepeatMode.Reverse), label = "dot$i")
            Box(Modifier.size(6.dp).alpha(a).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
        }
    }
}

@Composable
private fun ErrorBubble(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(14.dp)).padding(12.dp),
    )
}

/** 确认卡:和文件卡片同一种样子(白底细边),不用警告色 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApprovalCard(a: AssistantApproval, onAnswer: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().testTag("assistant_approval"),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(a.title, style = MaterialTheme.typography.titleSmall)
            a.names.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
            if (a.more > 0) Text(stringResource(R.string.assistant_more, a.more), style = MaterialTheme.typography.bodySmall)
            a.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)) }
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onAnswer("once") }) { Text(stringResource(R.string.assistant_allow)) }
                if (a.canAllowSession) OutlinedButton(onClick = { onAnswer("session") }) { Text(stringResource(R.string.assistant_allow_session)) }
                TextButton(onClick = { onAnswer("deny") }) { Text(stringResource(R.string.assistant_deny)) }
            }
        }
    }
}

/** 输入框:传输助手那样浮起的胶囊,发图、在服务器上跑、输入、发送都在里面 */
@Composable
private fun Composer(
    input: String,
    onInput: (String) -> Unit,
    selectionCount: Int,
    serverAvailable: Boolean,
    onServer: Boolean,
    onToggleServer: () -> Unit,
    running: Boolean,
    canSend: Boolean,
    pendingImages: List<String>,
    onPickImages: () -> Unit,
    onRemoveImage: (Int) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    // 页面的 Scaffold(NestedScreenInsets)已经让出了底部导航,这里不再加
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
    ) {
        Column(Modifier.padding(4.dp)) {
            if (pendingImages.isNotEmpty() || selectionCount > 0) {
                Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (selectionCount > 0) {
                        Text(
                            stringResource(R.string.assistant_selection, selectionCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape).padding(horizontal = 10.dp, vertical = 3.dp),
                        )
                    }
                    if (pendingImages.isNotEmpty()) ChatImageRow(pendingImages, size = 56, onRemove = onRemoveImage)
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = onPickImages, enabled = pendingImages.size < ChatImages.MAX, modifier = Modifier.testTag("assistant_add_image")) {
                    Icon(Icons.Outlined.Image, contentDescription = stringResource(R.string.assistant_add_image))
                }
                if (serverAvailable) {
                    // 在服务器上运行(doc/34):亮着时这一句交给服务器跑,关掉 App 也照样跑完
                    IconButton(onClick = onToggleServer, enabled = !running, modifier = Modifier.testTag("assistant_on_server")) {
                        Icon(
                            Icons.Outlined.Cloud,
                            contentDescription = stringResource(R.string.assistant_on_server),
                            tint = if (onServer) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val placeholder = stringResource(if (onServer) R.string.assistant_placeholder_server else R.string.assistant_placeholder)
                BasicTextField(
                    value = input,
                    onValueChange = onInput,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 6,
                    decorationBox = { inner ->
                        Box(Modifier.padding(horizontal = 4.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                            if (input.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            inner()
                        }
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("assistant_input"),
                )
                if (running) {
                    FilledIconButton(onClick = onStop, modifier = Modifier.testTag("assistant_stop")) {
                        Icon(Icons.Outlined.Stop, contentDescription = stringResource(R.string.assistant_stop))
                    }
                } else {
                    FilledIconButton(
                        onClick = onSend,
                        enabled = canSend && (input.isNotBlank() || pendingImages.isNotEmpty()),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            disabledContainerColor = Color.Transparent,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        ),
                        modifier = Modifier.testTag("assistant_send"),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.assistant_send))
                    }
                }
            }
        }
    }
}
