package xyz.yophon.gopan.feature.links

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.links.DirectLinksRepository
import xyz.yophon.gopan.core.data.links.LinkFormat
import xyz.yophon.gopan.core.data.links.LinkFormats
import xyz.yophon.gopan.core.data.links.LinkText
import xyz.yophon.gopan.core.data.links.toLinkText
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.core.ui.exposeTestTags
import xyz.yophon.gopan.core.ui.rememberSystemActions
import xyz.yophon.gopan.feature.drive.UiText
import javax.inject.Inject

/** 生成好的直链,等用户挑格式复制;[gpsCount] 张照片带拍摄位置。[token] 区分两次打开(每次默认回到「链接」) */
@Immutable
data class PendingLinkCopy(val links: List<LinkText>, val gpsCount: Int, val token: Long = System.nanoTime())

@Immutable
data class CopyLinkState(
    /** 本站开了直链(GOPAN_LINKS);没问到之前当作关,入口不出现 */
    val enabled: Boolean = false,
    val busy: Boolean = false,
    val pending: PendingLinkCopy? = null,
)

/**
 * 文件页 / 搜索页的「复制直链」(doc/22 四.4):一个或多个文件 → createLinks → 选格式(链接 / Markdown / HTML)复制,
 * 多个文件一行一条。文件夹不能生成直链,混在多选里的跳过。带 GPS 的照片复制后提醒一句。
 * 提示走调用方的 Snackbar([onMessage] 由 CopyLinkHost 接上)。
 */
@HiltViewModel
class CopyLinkViewModel @Inject constructor(private val repo: DirectLinksRepository) : ViewModel() {

    private val _state = MutableStateFlow(CopyLinkState())
    val state: StateFlow<CopyLinkState> = _state.asStateFlow()

    private val _messages = Channel<UiText>(Channel.BUFFERED)
    val messages: Flow<UiText> = _messages.receiveAsFlow()

    init {
        checkEnabled()
    }

    /** 开关没问到(断网)时,打开菜单前可以再问一次;问到了就缓存在仓库里 */
    fun checkEnabled() {
        if (_state.value.enabled) return
        viewModelScope.launch {
            gopanResult { repo.enabled() }.onSuccess { on -> _state.update { it.copy(enabled = on) } }
        }
    }

    fun copy(nodes: List<Node>) {
        if (_state.value.busy) return
        val files = nodes.filter { !it.isFolder }
        if (files.isEmpty()) {
            _messages.trySend(UiText.Res(R.string.links_msg_folder))
            return
        }
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            gopanResult { repo.create(files.map { it.id }) }
                .onSuccess { links ->
                    // 按用户选的顺序排(服务端返回的顺序不作保证)
                    val order = files.withIndex().associate { (i, n) -> n.id to i }
                    val sorted = links.sortedBy { order[it.nodeId] ?: Int.MAX_VALUE }
                    _state.update {
                        it.copy(busy = false, pending = PendingLinkCopy(sorted.map { l -> l.toLinkText() }, sorted.count { l -> l.hasGps }))
                    }
                    if (files.size < nodes.size) _messages.trySend(UiText.Res(R.string.links_msg_skipped_folders, nodes.size - files.size))
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false) }
                    _messages.trySend(UiText.Plain(e.message ?: "生成直链失败"))
                }
        }
    }

    fun dismiss() {
        _state.update { it.copy(pending = null) }
    }

    /** 复制完了:[systemToast] 为 true 时系统没弹「已复制」(Android 12 及以下),这里补一句;带 GPS 的照片优先提醒 */
    fun onCopied(systemToast: Boolean) {
        val pending = _state.value.pending ?: return
        _state.update { it.copy(pending = null) }
        when {
            pending.gpsCount == 1 && pending.links.size == 1 -> _messages.trySend(UiText.Res(R.string.links_gps_warning))
            pending.gpsCount > 0 -> _messages.trySend(UiText.Res(R.string.links_gps_warning_many, pending.gpsCount))
            systemToast -> _messages.trySend(
                if (pending.links.size == 1) UiText.Res(R.string.links_msg_copied) else UiText.Res(R.string.links_msg_copied_many, pending.links.size),
            )
        }
    }
}

/**
 * 「复制直链」的格式对话框(放在页面的弹层宿主里,文件页与搜索页共用一个 [viewModel])。
 * [onMessage]:页面的 Snackbar。
 */
@Composable
fun CopyLinkHost(viewModel: CopyLinkViewModel, onMessage: (UiText) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.messages.collect { onMessage(it) } }
    val pending = state.pending ?: return
    val system = rememberSystemActions()
    LinkFormatDialog(
        links = pending.links,
        instanceKey = pending.token.toString(),
        onCopy = { text -> viewModel.onCopied(systemToast = system.copy(text, label = "gopan link")) },
        onDismiss = viewModel::dismiss,
    )
}

/**
 * 选格式复制:链接(默认)/ Markdown / HTML,下面预览将要复制的内容。直链列表的「复制」也用它。
 * [instanceKey] 变了(又打开一次)回到默认格式。
 */
@Composable
fun LinkFormatDialog(links: List<LinkText>, instanceKey: String, onCopy: (String) -> Unit, onDismiss: () -> Unit) {
    var format by rememberSaveable(instanceKey) { mutableStateOf(LinkFormat.URL) }
    val text = LinkFormats.format(links, format)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.exposeTestTags(),
        title = {
            Text(
                if (links.size == 1) stringResource(R.string.links_copy_title) else stringResource(R.string.links_copy_title_many, links.size),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinkFormat.entries.forEach { f ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = f == format, onClick = { format = f }, role = Role.RadioButton)
                            .testTag("link_format_${f.name.lowercase()}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = f == format, onClick = null)
                        Text(stringResource(f.labelRes()), modifier = Modifier.padding(start = 12.dp))
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable { onCopy(text) },
                ) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()).padding(10.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCopy(text) }, modifier = Modifier.testTag("link_copy_confirm")) {
                Text(stringResource(R.string.links_action_copy))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

fun LinkFormat.labelRes(): Int = when (this) {
    LinkFormat.URL -> R.string.links_format_url
    LinkFormat.MARKDOWN -> R.string.links_format_markdown
    LinkFormat.HTML -> R.string.links_format_html
}
