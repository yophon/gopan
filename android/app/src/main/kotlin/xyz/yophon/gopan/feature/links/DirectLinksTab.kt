package xyz.yophon.gopan.feature.links

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.links.DirectLink
import xyz.yophon.gopan.core.data.links.LinkStatus
import xyz.yophon.gopan.core.data.links.toLinkText
import xyz.yophon.gopan.core.ui.components.EmptyState
import xyz.yophon.gopan.core.ui.components.ErrorState
import xyz.yophon.gopan.core.ui.components.LoadingState
import xyz.yophon.gopan.core.ui.exposeTestTags
import xyz.yophon.gopan.core.ui.rememberSystemActions
import xyz.yophon.gopan.feature.drive.FullPage
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.LoadMoreFooter
import xyz.yophon.gopan.feature.drive.LoadMoreTrigger
import xyz.yophon.gopan.feature.drive.isNearEnd
import xyz.yophon.gopan.feature.drive.resolve

/**
 * 「分享」tab 的「直链」标签:点一行出操作(复制(链接 / Markdown / HTML)/ 打开 / 暂停或恢复 / 吊销)。
 * 统计图表、来源、明细与设置在网页上,列表顶上一句提示。提示借页面的 Snackbar([snackbar])。
 */
@Composable
fun DirectLinksTab(viewModel: DirectLinksViewModel, snackbar: SnackbarHostState) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val system = rememberSystemActions()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var sheetFor by rememberSaveable { mutableStateOf<String?>(null) }
    var copyFor by rememberSaveable { mutableStateOf<String?>(null) }
    var revokeFor by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is DirectLinksEffect.Message -> launch { snackbar.showSnackbar(effect.text.resolve(context)) }
                is DirectLinksEffect.Focus -> {
                    sheetFor = effect.linkId
                    val index = viewModel.state.value.items.indexOfFirst { it.id == effect.linkId }
                    if (index >= 0) listState.animateScrollToItem(index + 1)
                }
            }
        }
    }
    LaunchedEffect(Unit) { viewModel.onShown() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onShown() }
    fun toast(res: Int) = scope.launch { snackbar.showSnackbar(context.getString(res)) }

    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
        when (val status = state.status) {
            ListStatus.Loading -> LoadingState()
            is ListStatus.Error -> FullPage { ErrorState(status.message, onRetry = viewModel::retry) }
            ListStatus.Content -> if (state.items.isEmpty()) {
                FullPage {
                    EmptyState(
                        title = stringResource(R.string.links_empty),
                        description = stringResource(R.string.links_empty_hint),
                        icon = Icons.Outlined.Link,
                    )
                }
            } else {
                LoadMoreTrigger(itemCount = state.items.size, isNearEnd = { listState.isNearEnd() }, onLoadMore = viewModel::loadMore)
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("links_list")) {
                    item("hint") {
                        Text(
                            stringResource(R.string.links_web_hint, state.total),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(state.items, key = { it.id }) { link ->
                        LinkRow(link, busy = link.id in state.busy, onClick = { sheetFor = link.id })
                        HorizontalDivider()
                    }
                    if (state.nextCursor != null || state.loadingMore || state.loadMoreError != null) {
                        item("more") { LoadMoreFooter(isLoading = state.loadingMore, error = state.loadMoreError, onRetry = viewModel::loadMore) }
                    }
                }
            }
        }
    }

    val sheetLink = state.items.firstOrNull { it.id == sheetFor }
    if (sheetLink != null) {
        LinkActionSheet(
            link = sheetLink,
            onDismiss = { sheetFor = null },
            onCopy = { copyFor = sheetLink.id },
            onOpen = { if (!system.openUrl(sheetLink.url)) toast(R.string.shares_no_browser) },
            onPause = { viewModel.setPaused(sheetLink, paused = true) },
            onResume = { viewModel.setPaused(sheetLink, paused = false) },
            onRevoke = { revokeFor = sheetLink.id },
        )
    }
    // 操作表开着时那条没了(别处吊销、刷新后消失):收起
    LaunchedEffect(sheetFor, state.items) {
        if (sheetFor != null && state.status == ListStatus.Content && state.items.none { it.id == sheetFor }) sheetFor = null
    }
    val copyLink = state.items.firstOrNull { it.id == copyFor }
    if (copyLink != null) {
        LinkFormatDialog(
            links = listOf(copyLink.toLinkText()),
            instanceKey = copyLink.id,
            onCopy = { text ->
                copyFor = null
                // Android 13+ 系统自己弹「已复制」;带拍摄位置的照片总要提醒一句
                val needToast = system.copy(text, label = "gopan link")
                when {
                    copyLink.hasGps -> toast(R.string.links_gps_warning)
                    needToast -> toast(R.string.links_msg_copied)
                }
            },
            onDismiss = { copyFor = null },
        )
    }
    val revokeLink = state.items.firstOrNull { it.id == revokeFor }
    if (revokeLink != null) {
        AlertDialog(
            onDismissRequest = { revokeFor = null },
            modifier = Modifier.exposeTestTags(),
            title = { Text(stringResource(R.string.links_revoke_title)) },
            text = { Text(stringResource(R.string.links_revoke_text, revokeLink.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        revokeFor = null
                        viewModel.revoke(revokeLink)
                    },
                    modifier = Modifier.testTag("links_revoke_confirm"),
                ) { Text(stringResource(R.string.links_revoke_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { revokeFor = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun StatusChip(link: DirectLink) {
    val (container, content) = when (link.status) {
        LinkStatus.ACTIVE -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        LinkStatus.PAUSED -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        LinkStatus.EXPIRED -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
        LinkStatus.UNAVAILABLE -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(color = container, shape = MaterialTheme.shapes.small) {
        Text(
            DirectLinkTexts.status(link).resolve(),
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** 第二、三行:今天 / 近 30 天的次数与流量;最后访问(或状态说明) */
@Composable
private fun LinkSecondary(link: DirectLink) {
    Column {
        Text(DirectLinkTexts.usage(link).resolve(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        val note = DirectLinkTexts.note(link)
        Text(
            note?.resolve() ?: DirectLinkTexts.lastHit(link).resolve(),
            style = MaterialTheme.typography.bodySmall,
            color = if (note != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
    }
}

@Composable
private fun LinkRow(link: DirectLink, busy: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(enabled = !busy, onClick = onClick).testTag("link_row"),
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(link.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                StatusChip(link)
            }
        },
        supportingContent = { LinkSecondary(link) },
        trailingContent = {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onClick) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.preview_more)) }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkActionSheet(
    link: DirectLink,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRevoke: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun pick(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        ListItem(
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(link.name, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    StatusChip(link)
                }
            },
            supportingContent = {
                Column {
                    Text(link.url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
                    Spacer(Modifier.height(4.dp))
                    LinkSecondary(link)
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        HorizontalDivider()
        SheetAction(Icons.Outlined.ContentCopy, stringResource(R.string.links_action_copy_formats)) { pick(onCopy) }
        SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.links_action_open)) { pick(onOpen) }
        if (DirectLinkTexts.canPause(link)) SheetAction(Icons.Outlined.Pause, stringResource(R.string.links_action_pause)) { pick(onPause) }
        if (DirectLinkTexts.canResume(link)) SheetAction(Icons.Outlined.PlayArrow, stringResource(R.string.links_action_resume)) { pick(onResume) }
        SheetAction(Icons.Outlined.LinkOff, stringResource(R.string.links_action_revoke), destructive = true) { pick(onRevoke) }
        Text(
            stringResource(R.string.links_sheet_web_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Spacer(Modifier.height(16.dp).navigationBarsPadding())
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, destructive: Boolean = false, onClick: () -> Unit) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
        headlineContent = { Text(label, color = tint) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
