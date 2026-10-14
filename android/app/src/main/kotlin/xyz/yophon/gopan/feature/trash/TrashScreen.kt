package xyz.yophon.gopan.feature.trash

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.ui.components.ConfirmDialog
import xyz.yophon.gopan.core.ui.components.EmptyState
import xyz.yophon.gopan.core.ui.components.ErrorState
import xyz.yophon.gopan.core.ui.components.LoadingState
import xyz.yophon.gopan.core.ui.format.formatBytes
import xyz.yophon.gopan.core.ui.format.formatRelativeTime
import xyz.yophon.gopan.feature.drive.FullPage
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.LoadMoreFooter
import xyz.yophon.gopan.feature.drive.LoadMoreTrigger
import xyz.yophon.gopan.feature.drive.NodeAction
import xyz.yophon.gopan.feature.drive.NodeActionSheet
import xyz.yophon.gopan.feature.drive.NodeDetailsSheet
import xyz.yophon.gopan.feature.drive.NestedScreenInsets
import xyz.yophon.gopan.feature.drive.NodeRow
import xyz.yophon.gopan.feature.drive.isNearEnd
import xyz.yophon.gopan.feature.drive.TRASH_ACTIONS
import xyz.yophon.gopan.feature.drive.resolve
import java.time.Instant

/** 回收站页:列表(名称、原大小、删除时间、剩余天数)、还原、彻底删除、清空、多选批量 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onBack: () -> Unit,
    viewModel: TrashViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { text -> launch { snackbarHostState.showSnackbar(text.resolve(context)) } }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResumed() }
    BackHandler(enabled = state.selectionMode) { viewModel.clearSelection() }

    Scaffold(
        contentWindowInsets = NestedScreenInsets,
        topBar = {
            if (state.selectionMode) {
                TrashSelectionTopBar(state, viewModel)
            } else {
                TrashTopBar(onBack = onBack, canPurgeAll = state.items.isNotEmpty(), onPurgeAll = viewModel::requestPurgeAll)
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            when (val status = state.status) {
                ListStatus.Loading -> LoadingState()
                is ListStatus.Error -> FullPage { ErrorState(status.message, onRetry = viewModel::retry) }
                ListStatus.Content -> if (state.items.isEmpty()) {
                    FullPage {
                        EmptyState(
                            title = stringResource(R.string.trash_empty),
                            description = stringResource(R.string.trash_empty_desc),
                            icon = Icons.Outlined.Delete,
                        )
                    }
                } else {
                    TrashList(state, viewModel)
                }
            }
        }
    }

    TrashDialogs(state, viewModel)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrashTopBar(onBack: () -> Unit, canPurgeAll: Boolean, onPurgeAll: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TopAppBar(
        windowInsets = NestedScreenInsets,
        title = { Text(stringResource(R.string.trash_title)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.drive_nav_back))
            }
        },
        actions = {
            Box {
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.drive_action_more))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.trash_action_purge_all)) },
                        enabled = canPurgeAll,
                        onClick = {
                            expanded = false
                            onPurgeAll()
                        },
                        leadingIcon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrashSelectionTopBar(state: TrashUiState, viewModel: TrashViewModel) {
    val hasSelection = state.selection.isNotEmpty()
    TopAppBar(
        windowInsets = NestedScreenInsets,
        title = { Text(stringResource(R.string.drive_selected_count, state.selection.size)) },
        navigationIcon = {
            IconButton(onClick = viewModel::clearSelection) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.drive_exit_selection))
            }
        },
        actions = {
            IconButton(onClick = viewModel::selectAll, enabled = !state.allSelected) {
                Icon(Icons.Outlined.SelectAll, contentDescription = stringResource(R.string.drive_select_all))
            }
            IconButton(onClick = viewModel::restoreSelected, enabled = hasSelection && !state.busy) {
                Icon(Icons.Outlined.RestoreFromTrash, contentDescription = stringResource(R.string.trash_action_restore))
            }
            IconButton(onClick = viewModel::requestPurgeSelected, enabled = hasSelection && !state.busy) {
                Icon(Icons.Outlined.DeleteForever, contentDescription = stringResource(R.string.trash_action_purge))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
}

/** 第二行:原大小 · 删除于 … · 还剩 N 天 / 已到期,待清理(与网页端一致,只认服务端给的 purgeAt) */
@Composable
private fun trashSecondary(node: Node, nowMillis: Long): String {
    val size = formatBytes(node.size ?: node.subtreeBytes)
    val deleted = stringResource(R.string.trash_deleted_at, formatRelativeTime(node.deletedAt))
    val expiry = trashExpiry(node.purgeAt, Instant.ofEpochMilli(nowMillis))
    val text = when {
        expiry.days == null -> "—"
        expiry.level == TrashExpiryLevel.EXPIRED -> stringResource(R.string.trash_today)
        else -> stringResource(R.string.trash_days_left, expiry.days)
    }
    return "$size · $deleted · $text"
}

/** 临期(≤ 1 天)与已到期的条目第二行用警示色,与网页端的 expiry-urgent 一致 */
@Composable
private fun trashSecondaryColor(node: Node, nowMillis: Long): Color? =
    if (trashExpiry(node.purgeAt, Instant.ofEpochMilli(nowMillis)).level == TrashExpiryLevel.NORMAL) null
    else MaterialTheme.colorScheme.error

@Composable
private fun TrashList(state: TrashUiState, viewModel: TrashViewModel) {
    val listState = rememberLazyListState()
    LoadMoreTrigger(
        itemCount = state.items.size,
        isNearEnd = { listState.isNearEnd() },
        onLoadMore = viewModel::loadMore,
    )
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(state.items, key = { it.id }, contentType = { "node" }) { node ->
            NodeRow(
                node = node,
                secondary = trashSecondary(node, state.nowMillis),
                secondaryColor = trashSecondaryColor(node, state.nowMillis),
                selectionMode = state.selectionMode,
                selected = node.id in state.selection,
                onClick = { viewModel.onItemClick(node) },
                onLongClick = { viewModel.onItemLongClick(node) },
                onMore = { viewModel.showActions(node) },
            )
        }
        if (state.nextCursor != null || state.isLoadingMore || state.loadMoreError != null) {
            item(key = "footer", contentType = "footer") {
                LoadMoreFooter(isLoading = state.isLoadingMore, error = state.loadMoreError, onRetry = viewModel::loadMore)
            }
        }
    }
}

@Composable
private fun TrashDialogs(state: TrashUiState, viewModel: TrashViewModel) {
    when (val dialog = state.dialog) {
        null -> Unit
        is TrashDialog.Actions -> NodeActionSheet(
            node = dialog.node,
            secondary = trashSecondary(dialog.node, state.nowMillis),
            actions = TRASH_ACTIONS,
            onAction = { action ->
                when (action) {
                    NodeAction.RESTORE -> viewModel.restore(listOf(dialog.node.id))
                    NodeAction.PURGE -> viewModel.requestPurge(listOf(dialog.node))
                    NodeAction.DETAILS -> viewModel.showDetails(dialog.node)
                    else -> Unit
                }
            },
            onDismiss = viewModel::dismissDialog,
        )
        is TrashDialog.Details -> NodeDetailsSheet(node = dialog.node, onDismiss = viewModel::dismissDialog)
        is TrashDialog.ConfirmPurge -> ConfirmDialog(
            title = stringResource(R.string.trash_purge_title),
            text = dialog.singleName?.let { stringResource(R.string.trash_purge_confirm_one, it) }
                ?: stringResource(R.string.trash_purge_confirm_many, dialog.ids.size),
            confirmText = stringResource(R.string.trash_action_purge),
            destructive = true,
            busy = state.busy,
            onConfirm = { viewModel.confirmPurge(dialog.ids) },
            onDismiss = viewModel::dismissDialog,
        )
        is TrashDialog.ConfirmPurgeAll -> ConfirmDialog(
            title = stringResource(R.string.trash_purge_all_title),
            text = stringResource(if (dialog.step < 2) R.string.trash_purge_all_confirm_1 else R.string.trash_purge_all_confirm_2),
            confirmText = stringResource(if (dialog.step < 2) R.string.action_confirm else R.string.trash_purge_all_button),
            destructive = true,
            busy = state.busy,
            onConfirm = viewModel::confirmPurgeAllStep,
            onDismiss = viewModel::dismissDialog,
        )
    }
}
