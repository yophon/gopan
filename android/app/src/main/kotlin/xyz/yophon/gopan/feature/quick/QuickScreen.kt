package xyz.yophon.gopan.feature.quick

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import xyz.yophon.gopan.core.data.media.PlayProgress
import xyz.yophon.gopan.core.data.media.RecentPlay
import xyz.yophon.gopan.feature.drive.NodeVisual
import xyz.yophon.gopan.feature.media.ProgressText
import xyz.yophon.gopan.feature.media.progressFraction
import xyz.yophon.gopan.feature.media.progressText
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
 * 「收藏」「最近」(doc/24):点文件夹进去、点文件预览;⋮ / 长按出操作。第二行:收藏写所在位置,最近写打开时间与看到哪。
 * 最近的顶上是一排「接着看」(没看完的音视频与 PDF)和类型筛选(doc/31 五)。
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
                is NodeEffect.OpenTextEditor -> Unit
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
                ListStatus.Content -> if (state.items.isEmpty() && state.plays.isEmpty()) {
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
                        if (state.recent && state.plays.isNotEmpty()) {
                            item(key = "resume") {
                                ResumeStrip(state.plays, onOpen = viewModel::resume, onRemove = viewModel::forgetProgress)
                            }
                        }
                        if (state.recent && state.items.isNotEmpty()) {
                            item(key = "filter") {
                                FilterRow(state.filter, onSelect = viewModel::setFilter)
                            }
                            if (state.shown.isEmpty()) {
                                item(key = "filter_empty") {
                                    Text(
                                        stringResource(R.string.quick_filter_empty),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                                    )
                                }
                            }
                        }
                        items(state.shown, key = { it.node.id }) { item ->
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

/** 收藏:所在位置(「我的文件 / 工作」);最近:打开于多久以前,有进度的再写看到哪 */
@Composable
private fun quickSecondary(item: QuickItem, recent: Boolean, nowMillis: Long, rootName: String): String {
    if (recent) {
        val opened = stringResource(R.string.quick_opened_at, formatRelativeTime(item.openedAt, Instant.ofEpochMilli(nowMillis)))
        val progress = item.progress ?: return opened
        return "$opened · ${progressLabel(item.node.preview.kind, progress)}"
    }
    val segs = item.path?.split('/')?.filter { it.isNotEmpty() }?.dropLast(1) ?: emptyList()
    return (listOf(rootName) + segs).joinToString(" / ")
}

/** 看到 23:15 / 1:42:00、第 12 / 80 页,看完了写「已看完」 */
@Composable
private fun progressLabel(kind: xyz.yophon.gopan.core.data.PreviewKind, progress: PlayProgress): String {
    if (progress.finished) return stringResource(R.string.quick_progress_finished)
    return when (val t = progressText(kind, progress)) {
        is ProgressText.Time -> stringResource(R.string.media_recent_position, t.position, t.duration)
        is ProgressText.Pages -> stringResource(R.string.media_recent_page, t.page, t.count)
    }
}

/** 接着看:横着一排卡片,点了从上次的地方接着放;右上角 × 不再接着看 */
@Composable
private fun ResumeStrip(plays: List<RecentPlay>, onOpen: (RecentPlay) -> Unit, onRemove: (RecentPlay) -> Unit) {
    Column(modifier = Modifier.padding(top = 4.dp, bottom = 8.dp).testTag("quick_resume")) {
        Text(
            stringResource(R.string.quick_resume_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(plays, key = { it.node.id }) { play ->
                Column(
                    modifier = Modifier
                        .width(156.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onOpen(play) }
                        .testTag("resume:${play.node.name}"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        NodeVisual(play.node, modifier = Modifier.fillMaxSize(), iconSize = 32.dp, shape = RoundedCornerShape(10.dp))
                        // 半透明黑底:叠在彩色缩略图上也看得清
                        IconButton(onClick = { onRemove(play) }, modifier = Modifier.align(Alignment.TopEnd).size(40.dp)) {
                            Box(
                                modifier = Modifier.size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.quick_resume_remove),
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                    Text(play.node.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { progressFraction(play.progress) }, modifier = Modifier.fillMaxWidth().height(3.dp))
                    Text(
                        progressLabel(play.node.preview.kind, play.progress),
                        maxLines = 1,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 全部 / 影音 / 文档 / 图片 */
@Composable
private fun FilterRow(selected: RecentFilter, onSelect: (RecentFilter) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(RecentFilter.entries.toList(), key = { it.name }) { f ->
            FilterChip(
                selected = f == selected,
                onClick = { onSelect(f) },
                label = { Text(stringResource(f.label)) },
                modifier = Modifier.testTag("quick_filter:${f.name}"),
            )
        }
    }
}

