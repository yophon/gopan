@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.yophon.gopan.feature.clips

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.clips.Clip
import xyz.yophon.gopan.core.ui.components.ConfirmDialog
import xyz.yophon.gopan.core.ui.components.EmptyState
import xyz.yophon.gopan.core.ui.components.ErrorState
import xyz.yophon.gopan.core.ui.components.ListSkeleton
import xyz.yophon.gopan.core.ui.format.formatRelativeTime
import xyz.yophon.gopan.core.ui.rememberSystemActions
import xyz.yophon.gopan.feature.drive.FullPage
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.LoadMoreFooter
import xyz.yophon.gopan.feature.drive.LoadMoreTrigger
import xyz.yophon.gopan.feature.drive.NestedScreenInsets
import xyz.yophon.gopan.feature.drive.TopBarInsets
import xyz.yophon.gopan.feature.drive.isNearEnd
import xyz.yophon.gopan.feature.drive.resolve
import androidx.compose.ui.input.nestedscroll.nestedScroll
import xyz.yophon.gopan.feature.drive.bottomBarPadding
import xyz.yophon.gopan.feature.drive.pinnedHeaderTopBarColors
import xyz.yophon.gopan.feature.drive.rememberSubPageScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.TopAppBarDefaults
import xyz.yophon.gopan.feature.drive.collapsingTopBarColors

/** 「我的 → 剪藏」:标题、站点、摘要、日期、未读圆点;全部 / 未读;点一篇进阅读页 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipsScreen(onBack: () -> Unit, onOpen: (Clip) -> Unit, viewModel: ClipsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.messages.collect { launch { snackbar.showSnackbar(it.resolve(context)) } } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResumed() }

    val topBarScroll = rememberSubPageScroll()
    Scaffold(
        contentWindowInsets = NestedScreenInsets,
        modifier = Modifier.nestedScroll(topBarScroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                windowInsets = TopBarInsets,
                scrollBehavior = topBarScroll,
                colors = pinnedHeaderTopBarColors(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.preview_back))
                    }
                },
                title = { Text(stringResource(R.string.clips_title)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // 顶栏收起时筛选顶到最上面;列表能滚到底部导航后面
        Column(modifier = Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !state.unreadOnly,
                    onClick = { viewModel.setUnreadOnly(false) },
                    label = { Text(stringResource(R.string.clips_filter_all)) },
                    modifier = Modifier.testTag("clips_filter_all"),
                )
                FilterChip(
                    selected = state.unreadOnly,
                    onClick = { viewModel.setUnreadOnly(true) },
                    label = { Text(stringResource(R.string.clips_filter_unread)) },
                    modifier = Modifier.testTag("clips_filter_unread"),
                )
            }
            PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
                when (val status = state.status) {
                    ListStatus.Loading -> ListSkeleton()
                    is ListStatus.Error -> FullPage { ErrorState(status.message, onRetry = viewModel::retry) }
                    ListStatus.Content -> if (state.items.isEmpty()) {
                        FullPage {
                            EmptyState(
                                title = stringResource(if (state.unreadOnly) R.string.clips_empty_unread else R.string.clips_empty),
                                description = stringResource(R.string.clips_empty_hint),
                                icon = Icons.Outlined.ContentCut,
                            )
                        }
                    } else {
                        ClipList(state, viewModel, onOpen)
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipList(state: ClipsUiState, viewModel: ClipsViewModel, onOpen: (Clip) -> Unit) {
    val listState = rememberLazyListState()
    LoadMoreTrigger(itemCount = state.items.size, isNearEnd = { listState.isNearEnd() }, onLoadMore = viewModel::loadMore)
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("clips_list"), contentPadding = PaddingValues(bottom = bottomBarPadding())) {
        items(state.items, key = { it.id }) { clip ->
            ClipRow(clip, onClick = { onOpen(clip) })
            HorizontalDivider()
        }
        if (state.nextCursor != null || state.loadingMore || state.loadMoreError != null) {
            item("more") { LoadMoreFooter(isLoading = state.loadingMore, error = state.loadMoreError, onRetry = viewModel::loadMore) }
        }
    }
}

@Composable
private fun ClipRow(clip: Clip, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = clip.nodeId != null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("clip_row"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                clip.title.ifEmpty { clip.url },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (clip.unread) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (clip.unread) {
                Spacer(Modifier.width(8.dp))
                val description = stringResource(R.string.clips_unread)
                Badge(modifier = Modifier.testTag("clip_unread_dot").semantics { contentDescription = description })
            }
        }
        val meta = listOf(ClipLogic.siteLabel(clip), formatRelativeTime(clip.createdAt)).filter { it.isNotEmpty() }
        Text(
            meta.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (clip.excerpt.isNotBlank()) {
            Text(
                clip.excerpt,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---- 阅读页 ----

/**
 * 剪藏的阅读页:渲染好的 Markdown(跟随 App 的深浅);顶栏「标记已读 / 未读」「打开原网页」「删除」。
 * 停留 3 秒自动标为已读。[title] 是数据回来前的顶栏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipReaderScreen(title: String, onBack: () -> Unit, viewModel: ClipReaderViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val actions = rememberSystemActions()
    val scope = rememberCoroutineScope()
    val currentOnBack by rememberUpdatedState(onBack)
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    LaunchedEffect(dark) { viewModel.load(dark) }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ClipReaderEffect.Message -> launch { snackbar.showSnackbar(effect.text.resolve(context)) }
                ClipReaderEffect.Closed -> currentOnBack()
            }
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.setVisible(true)
                Lifecycle.Event.ON_PAUSE -> viewModel.setVisible(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            viewModel.setVisible(false)
        }
    }

    val clip = state.clip
    // 正文铺满整屏,顶栏浮在上面、底部导航浮在下面(页面前后留出它们的高度);顶栏跟着正文的滚动收放
    val topBarScroll = rememberSubPageScroll()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val padTop = remember { statusTop + TopAppBarDefaults.TopAppBarExpandedHeight }
    val bottomInset = bottomBarPadding(16.dp)
    val padBottom = remember { bottomInset }
    Scaffold(
        contentWindowInsets = NestedScreenInsets,
        topBar = {
            TopAppBar(
                windowInsets = TopBarInsets,
                scrollBehavior = topBarScroll,
                colors = collapsingTopBarColors(topBarScroll),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.preview_back))
                    }
                },
                title = {
                    Column {
                        Text(clip?.title?.ifEmpty { null } ?: title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (clip != null) {
                            val site = ClipLogic.siteLabel(clip)
                            if (site.isNotEmpty()) {
                                Text(site, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                },
                actions = {
                    if (clip != null) {
                        IconButton(onClick = { viewModel.setRead(clip.unread) }, enabled = !state.busy, modifier = Modifier.testTag("clip_toggle_read")) {
                            if (clip.unread) {
                                Icon(Icons.Outlined.MarkEmailRead, contentDescription = stringResource(R.string.clips_mark_read))
                            } else {
                                Icon(Icons.Outlined.MarkEmailUnread, contentDescription = stringResource(R.string.clips_mark_unread))
                            }
                        }
                        IconButton(
                            onClick = {
                                if (!actions.openUrl(clip.url)) scope.launch { snackbar.showSnackbar(context.getString(R.string.clips_msg_no_app)) }
                            },
                            modifier = Modifier.testTag("clip_open_original"),
                        ) {
                            Icon(Icons.Outlined.OpenInBrowser, contentDescription = stringResource(R.string.clips_open_original))
                        }
                    }
                    IconButton(onClick = { confirmDelete = true }, enabled = !state.busy, modifier = Modifier.testTag("clip_delete")) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.clips_delete))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val error = state.error
        if (error != null) {
            ErrorState(error, modifier = Modifier.padding(padding), onRetry = viewModel::retry)
        } else {
            MarkdownView(
                url = state.url,
                baseUrl = viewModel.baseUrl,
                retoken = viewModel::retoken,
                modifier = Modifier.fillMaxSize(),
                padTop = padTop,
                padBottom = padBottom,
                onScrollDelta = { dy ->
                    val bar = topBarScroll.state
                    bar.heightOffset = (bar.heightOffset - dy).coerceIn(bar.heightOffsetLimit, 0f)
                },
            )
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.clips_delete_title),
            text = stringResource(R.string.clips_delete_text, clip?.title?.ifEmpty { null } ?: title),
            confirmText = stringResource(R.string.clips_delete),
            destructive = true,
            busy = state.busy,
            onConfirm = {
                confirmDelete = false
                viewModel.delete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
