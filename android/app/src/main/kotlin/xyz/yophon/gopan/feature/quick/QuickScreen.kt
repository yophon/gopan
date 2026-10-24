package xyz.yophon.gopan.feature.quick

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.quick.QuickItem
import xyz.yophon.gopan.core.ui.components.ConfirmDialog
import xyz.yophon.gopan.core.ui.components.EmptyState
import xyz.yophon.gopan.core.ui.components.ErrorState
import xyz.yophon.gopan.core.ui.components.ListSkeleton
import xyz.yophon.gopan.core.ui.format.formatRelativeTime
import xyz.yophon.gopan.core.ui.transfer.rememberTransferPermissions
import xyz.yophon.gopan.feature.drive.FullPage
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.NestedScreenInsets
import xyz.yophon.gopan.feature.drive.NodeAction
import xyz.yophon.gopan.feature.drive.NodeDialogHost
import xyz.yophon.gopan.feature.drive.NodeEffect
import xyz.yophon.gopan.feature.drive.NodeRow
import xyz.yophon.gopan.feature.drive.TopBarInsets
import xyz.yophon.gopan.feature.drive.resolve
import java.time.Instant
import androidx.compose.ui.input.nestedscroll.nestedScroll
import xyz.yophon.gopan.feature.drive.belowCollapsingTopBar
import xyz.yophon.gopan.feature.drive.bottomBarPadding
import xyz.yophon.gopan.feature.drive.collapsingTopBarColors
import xyz.yophon.gopan.feature.drive.rememberSubPageScroll

private val STARRED_ACTIONS = listOf(
    NodeAction.LOCATE,
    NodeAction.DOWNLOAD,
    NodeAction.PACK,
    NodeAction.SHARE,
    NodeAction.STAR,
    NodeAction.VERSIONS,
    NodeAction.DETAILS,
)
private val RECENT_ACTIONS = listOf(NodeAction.FORGET) + STARRED_ACTIONS

/**
 * 「收藏」「最近」(doc/24):点文件夹进去、点文件预览;⋮ / 长按出操作。第二行:收藏写所在位置,最近写打开时间。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickScreen(
    onBack: () -> Unit,
    onOpenFolder: (folderId: String) -> Unit,
    onOpenRoot: () -> Unit,
    onOpenPreview: (nodeId: String) -> Unit,
    viewModel: QuickViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val inputError by viewModel.inputError.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val permissions = rememberTransferPermissions(onStorageResult = viewModel::onStoragePermission)
    val context = LocalContext.current
    val currentOnOpenFolder by rememberUpdatedState(onOpenFolder)
    val currentOnOpenPreview by rememberUpdatedState(onOpenPreview)
    val rootName = stringResource(R.string.drive_title_root)

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is NodeEffect.Message -> launch { snackbarHostState.showSnackbar(effect.text.resolve(context)) }
                is NodeEffect.OpenFolder -> currentOnOpenFolder(effect.folderId)
                is NodeEffect.OpenPreview -> currentOnOpenPreview(effect.nodeId)
                NodeEffect.RequestStoragePermission -> permissions.requestStoragePermission()
                NodeEffect.TransferQueued -> permissions.onTransferQueued()
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResumed() }
    val locate: (Node) -> Unit = { node -> node.parentId?.let(onOpenFolder) ?: onOpenRoot() }

    val topBarScroll = rememberSubPageScroll()
    Scaffold(
        contentWindowInsets = NestedScreenInsets,
        modifier = Modifier.nestedScroll(topBarScroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                windowInsets = TopBarInsets,
                scrollBehavior = topBarScroll,
                colors = collapsingTopBarColors(topBarScroll),
                title = { Text(stringResource(if (state.recent) R.string.quick_recent_title else R.string.quick_starred_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.drive_nav_back))
                    }
                },
                actions = {
                    if (state.recent && state.items.isNotEmpty()) {
                        TextButton(onClick = viewModel::askClear) { Text(stringResource(R.string.quick_clear)) }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .belowCollapsingTopBar(padding, topBarScroll)
                .fillMaxSize()
                .testTag(if (state.recent) "quick_recent" else "quick_starred"),
        ) {
            when (val status = state.status) {
                ListStatus.Loading -> ListSkeleton()
                is ListStatus.Error -> FullPage { ErrorState(status.message, onRetry = viewModel::retry) }
                ListStatus.Content -> if (state.items.isEmpty()) {
                    FullPage {
                        if (state.recent) {
                            EmptyState(
                                title = stringResource(R.string.quick_recent_empty),
                                description = stringResource(R.string.quick_recent_empty_desc),
                                icon = Icons.Outlined.History,
                            )
                        } else {
                            EmptyState(
                                title = stringResource(R.string.quick_starred_empty),
                                description = stringResource(R.string.quick_starred_empty_desc),
                                icon = Icons.Outlined.StarOutline,
                            )
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomBarPadding(24.dp))) {
                        items(state.items, key = { it.node.id }) { item ->
                            NodeRow(
                                node = item.node,
                                secondary = quickSecondary(item, state.recent, state.nowMillis, rootName),
                                onClick = { viewModel.onItemClick(item.node) },
                                onLongClick = { viewModel.showActions(item.node) },
                                onMore = { viewModel.showActions(item.node) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (state.confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.quick_clear_title),
            text = stringResource(R.string.quick_clear_text),
            confirmText = stringResource(R.string.quick_clear),
            onConfirm = viewModel::clearAll,
            onDismiss = viewModel::cancelClear,
        )
    }

    NodeDialogHost(
        dialog = dialog,
        busy = busy,
        inputError = inputError,
        viewModel = viewModel,
        actions = if (state.recent) RECENT_ACTIONS else STARRED_ACTIONS,
        onLocate = locate,
        onForget = viewModel::forget,
    )
}

/** 收藏:所在位置(「我的文件 / 工作」);最近:打开于多久以前 */
@Composable
private fun quickSecondary(item: QuickItem, recent: Boolean, nowMillis: Long, rootName: String): String {
    if (recent) return stringResource(R.string.quick_opened_at, formatRelativeTime(item.openedAt, Instant.ofEpochMilli(nowMillis)))
    val segs = item.path?.split('/')?.filter { it.isNotEmpty() }?.dropLast(1) ?: emptyList()
    return (listOf(rootName) + segs).joinToString(" / ")
}
