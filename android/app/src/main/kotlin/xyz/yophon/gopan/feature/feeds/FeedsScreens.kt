@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.yophon.gopan.feature.feeds

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Unsubscribe
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
import xyz.yophon.gopan.core.data.feeds.Feed
import xyz.yophon.gopan.core.data.feeds.FeedItem
import xyz.yophon.gopan.core.data.feeds.FeedItemState
import xyz.yophon.gopan.core.data.feeds.FeedKind
import xyz.yophon.gopan.core.data.feeds.FeedMode
import xyz.yophon.gopan.core.data.feeds.FeedPreview
import xyz.yophon.gopan.core.ui.components.ConfirmDialog
import xyz.yophon.gopan.core.ui.components.EmptyState
import xyz.yophon.gopan.core.ui.components.ErrorState
import xyz.yophon.gopan.core.ui.components.LoadingState
import xyz.yophon.gopan.core.ui.format.formatBytes
import xyz.yophon.gopan.core.ui.format.formatRelativeTime
import xyz.yophon.gopan.core.ui.format.formatTime
import xyz.yophon.gopan.core.ui.rememberSystemActions
import xyz.yophon.gopan.feature.drive.FullPage
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.LoadMoreFooter
import xyz.yophon.gopan.feature.drive.LoadMoreTrigger
import xyz.yophon.gopan.feature.drive.NestedScreenInsets
import xyz.yophon.gopan.feature.drive.isNearEnd
import xyz.yophon.gopan.feature.drive.resolve

// ---- 共用 ----

/** 小标签:类型(RSS / 播客 / GitHub)、条目状态 */
@Composable
internal fun FeedTag(label: String, container: Color = MaterialTheme.colorScheme.secondaryContainer, content: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.extraSmall) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
    }
}

private fun kindIcon(feed: Feed): ImageVector = when {
    feed.kind == FeedKind.GITHUB -> Icons.Outlined.NewReleases
    feed.mode == FeedMode.DOWNLOAD -> Icons.Outlined.Podcasts
    else -> Icons.Outlined.RssFeed
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NestedTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        windowInsets = NestedScreenInsets,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.preview_back))
            }
        },
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        actions = { actions() },
    )
}

@Composable
private fun WebHint(modifier: Modifier = Modifier) {
    Row(modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.feeds_web_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- 列表 ----

/** 「我的 → 订阅」:名字、类型、最后更新、新条目数、出错原因;右下角「添加订阅」 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedsScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (Feed) -> Unit,
    viewModel: FeedsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.messages.collect { launch { snackbar.showSnackbar(it.resolve(context)) } } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResumed() }

    Scaffold(
        contentWindowInsets = NestedScreenInsets,
        topBar = { NestedTopBar(stringResource(R.string.feeds_title), onBack) },
        floatingActionButton = {
            if (state.status == ListStatus.Content && state.feeds.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onAdd,
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.feeds_add)) },
                    modifier = Modifier.testTag("feeds_add"),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = viewModel::refresh, modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val status = state.status) {
                ListStatus.Loading -> LoadingState()
                is ListStatus.Error -> FullPage { ErrorState(status.message, onRetry = viewModel::retry) }
                ListStatus.Content -> if (state.feeds.isEmpty()) {
                    FullPage {
                        EmptyState(
                            title = stringResource(R.string.feeds_empty),
                            description = stringResource(R.string.feeds_empty_hint),
                            icon = Icons.Outlined.RssFeed,
                            action = {
                                Button(onClick = onAdd, modifier = Modifier.testTag("feeds_add_empty")) {
                                    Text(stringResource(R.string.feeds_add))
                                }
                            },
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag("feeds_list"),
                        contentPadding = PaddingValues(bottom = 96.dp),
                    ) {
                        items(state.feeds, key = { it.id }) { feed ->
                            FeedRow(feed, onClick = { onOpen(feed) })
                            HorizontalDivider()
                        }
                        item("hint") { WebHint() }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedRow(feed: Feed, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick).testTag("feed_row"),
        leadingContent = { Icon(kindIcon(feed), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(feed.title.ifEmpty { feed.url }, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                FeedTag(stringResource(FeedsLogic.kindLabel(feed)))
            }
        },
        supportingContent = {
            Column {
                val updated = feed.latestAt?.let { stringResource(R.string.feeds_updated, formatRelativeTime(it)) }
                    ?: stringResource(R.string.feeds_never_updated)
                val parts = listOfNotNull(updated, if (!feed.enabled) stringResource(R.string.feeds_paused) else null)
                Text(parts.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                feed.lastError?.let {
                    Text(
                        stringResource(R.string.feeds_error, it),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("feed_error"),
                    )
                }
            }
        },
        trailingContent = {
            if (feed.newCount > 0) {
                val description = stringResource(R.string.feeds_new_count, feed.newCount)
                Badge(modifier = Modifier.testTag("feed_new_count").semantics { contentDescription = description }) {
                    Text(feed.newCount.toString())
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

// ---- 添加 ----

/** 添加订阅:贴地址 → 预览 → 选方式 → 订阅(文件夹、间隔、第一次处理几条用默认值) */
@Composable
fun FeedAddScreen(onBack: () -> Unit, onCreated: (Feed) -> Unit, viewModel: FeedAddViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnCreated by rememberUpdatedState(onCreated)
    LaunchedEffect(viewModel) { viewModel.created.collect { currentOnCreated(it) } }

    Scaffold(
        contentWindowInsets = NestedScreenInsets,
        topBar = { NestedTopBar(stringResource(R.string.feeds_add), onBack) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = viewModel::create,
                    enabled = state.canCreate,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).imePadding().testTag("feed_add_submit"),
                ) {
                    if (state.creating) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.feeds_add_submit))
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("url") {
                OutlinedTextField(
                    value = state.url,
                    onValueChange = viewModel::setUrl,
                    label = { Text(stringResource(R.string.feeds_add_url)) },
                    placeholder = { Text(stringResource(R.string.feeds_add_url_hint)) },
                    singleLine = true,
                    enabled = !state.creating,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { viewModel.preview() }),
                    trailingIcon = {
                        if (state.previewing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            TextButton(onClick = viewModel::preview, enabled = state.canPreview, modifier = Modifier.testTag("feed_add_preview")) {
                                Text(stringResource(R.string.feeds_add_preview))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("feed_add_url"),
                )
            }
            state.error?.let { error ->
                item("error") { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
            if (state.previewStale) {
                item("stale") {
                    Text(stringResource(R.string.feeds_add_url_changed), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                }
            }
            state.preview?.let { preview -> previewSection(preview, state, viewModel) }
            if (state.preview == null) item("hint") { WebHint() }
        }
    }
}

private fun LazyListScope.previewSection(preview: FeedPreview, state: FeedAddUiState, viewModel: FeedAddViewModel) {
    item("preview-head") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    preview.title.ifEmpty { preview.url },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).testTag("feed_add_title"),
                )
                Spacer(Modifier.width(6.dp))
                FeedTag(stringResource(FeedsLogic.kindLabel(preview)))
            }
            val site = preview.siteUrl.ifEmpty { preview.url }
            Text(site, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (preview.podcast) {
                Text(stringResource(R.string.feeds_add_podcast), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    item("mode-title") { Text(stringResource(R.string.feeds_add_mode), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
    items(FeedsLogic.modesFor(preview.kind), key = { "mode-${it.name}" }) { mode ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = state.mode == mode, enabled = !state.creating, role = Role.RadioButton, onClick = { viewModel.setMode(mode) })
                .testTag("feed_mode_${mode.name.lowercase()}"),
        ) {
            RadioButton(selected = state.mode == mode, onClick = null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(FeedsLogic.modeLabel(mode)), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(FeedsLogic.modeDescription(mode)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (preview.kind == FeedKind.GITHUB) {
        item("pattern") {
            OutlinedTextField(
                value = state.assetPattern,
                onValueChange = viewModel::setAssetPattern,
                label = { Text(stringResource(R.string.feeds_add_asset_pattern)) },
                supportingText = { Text(stringResource(R.string.feeds_add_asset_pattern_hint)) },
                singleLine = true,
                enabled = !state.creating,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { viewModel.preview() }),
                trailingIcon = {
                    IconButton(onClick = viewModel::preview, enabled = state.canPreview) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.feeds_add_preview))
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("feed_add_pattern"),
            )
        }
    }
    item("defaults") {
        Text(
            stringResource(R.string.feeds_add_defaults, FeedsLogic.defaultFolderName(preview)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    item("latest-title") { Text(stringResource(R.string.feeds_add_latest), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
    if (preview.items.isEmpty()) {
        item("latest-empty") { Text(stringResource(R.string.feeds_add_no_items), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    items(preview.items.size, key = { "item-$it" }) { i ->
        val entry = preview.items[i]
        Column {
            Text(entry.title.ifEmpty { entry.url }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(entry.publishedAt?.let { formatTime(it) }, entry.enclosureSize?.takeIf { it > 0 }?.let { formatBytes(it) })
            if (meta.isNotEmpty()) {
                Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    item("end-hint") { WebHint() }
}

// ---- 详情 ----

/**
 * 一个订阅:顶上是地址、方式、存放位置、上次检查与出错原因;下面是条目(状态、单条下载 / 剪藏 / 打开)。
 * 顶栏:立即检查;更多:暂停 / 恢复、打开存放的文件夹、退订。编辑与 OPML 在网页上。
 */
@Composable
fun FeedDetailScreen(
    title: String,
    onBack: () -> Unit,
    onOpenNode: (nodeId: String) -> Unit,
    onOpenFolder: (folderId: String) -> Unit,
    viewModel: FeedDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val currentOnBack by rememberUpdatedState(onBack)
    var confirmUnsubscribe by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is FeedDetailEffect.Message -> launch { snackbar.showSnackbar(effect.text.resolve(context)) }
                FeedDetailEffect.Closed -> currentOnBack()
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

    val feed = state.feed
    Scaffold(
        contentWindowInsets = NestedScreenInsets,
        topBar = {
            NestedTopBar(feed?.title?.ifEmpty { feed.url } ?: title, onBack) {
                if (feed != null) {
                    if (state.checking) {
                        Box48 { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
                    } else {
                        IconButton(onClick = viewModel::checkNow, modifier = Modifier.testTag("feed_check")) {
                            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.feeds_detail_check))
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("feed_more")) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.preview_more))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(if (feed.enabled) R.string.feeds_detail_pause else R.string.feeds_detail_resume)) },
                                leadingIcon = { Icon(if (feed.enabled) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, contentDescription = null) },
                                enabled = !state.busy,
                                onClick = {
                                    menu = false
                                    viewModel.toggleEnabled()
                                },
                                modifier = Modifier.testTag("feed_toggle"),
                            )
                            feed.folderId?.let { folderId ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.feeds_detail_open_folder)) },
                                    leadingIcon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                                    onClick = {
                                        menu = false
                                        onOpenFolder(folderId)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.feeds_detail_unsubscribe), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Outlined.Unsubscribe, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                enabled = !state.busy,
                                onClick = {
                                    menu = false
                                    confirmUnsubscribe = true
                                },
                                modifier = Modifier.testTag("feed_unsubscribe"),
                            )
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = viewModel::refresh, modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val status = state.status) {
                ListStatus.Loading -> LoadingState()
                is ListStatus.Error -> FullPage { ErrorState(status.message, onRetry = viewModel::retry) }
                ListStatus.Content -> if (feed != null) FeedDetailList(feed, state, viewModel, onOpenNode)
            }
        }
    }

    if (confirmUnsubscribe && feed != null) {
        ConfirmDialog(
            title = stringResource(R.string.feeds_unsubscribe_title, feed.title.ifEmpty { feed.url }),
            text = stringResource(R.string.feeds_unsubscribe_text),
            confirmText = stringResource(R.string.feeds_unsubscribe_confirm),
            destructive = true,
            busy = state.busy,
            onConfirm = {
                confirmUnsubscribe = false
                viewModel.unsubscribe()
            },
            onDismiss = { confirmUnsubscribe = false },
        )
    }
}

/** 顶栏里与图标按钮同样大小的占位(检查中的转圈) */
@Composable
private fun Box48(content: @Composable () -> Unit) {
    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun FeedDetailList(feed: Feed, state: FeedDetailUiState, viewModel: FeedDetailViewModel, onOpenNode: (String) -> Unit) {
    val listState = rememberLazyListState()
    LoadMoreTrigger(itemCount = state.items.size, isNearEnd = { listState.isNearEnd() }, onLoadMore = viewModel::loadMore)
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("feed_items"), contentPadding = PaddingValues(bottom = 24.dp)) {
        item("header") { FeedHeader(feed) }
        item("items-title") {
            Text(
                stringResource(R.string.feeds_detail_items),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            )
        }
        if (state.items.isEmpty()) {
            item("empty") {
                Text(
                    stringResource(R.string.feeds_detail_no_items),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        items(state.items, key = { it.id }) { item ->
            FeedItemRow(
                item = item,
                feedKind = feed.kind,
                submitting = item.id in state.submitting,
                onFetch = { mode -> viewModel.fetch(item, mode) },
                onOpen = { item.node?.let { onOpenNode(it.id) } },
            )
            HorizontalDivider()
        }
        if (state.nextCursor != null || state.loadingMore || state.loadMoreError != null) {
            item("more") { LoadMoreFooter(isLoading = state.loadingMore, error = state.loadMoreError, onRetry = viewModel::loadMore) }
        }
        item("hint") { WebHint() }
    }
}

@Composable
private fun FeedHeader(feed: Feed) {
    val actions = rememberSystemActions()
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                FeedTag(stringResource(FeedsLogic.kindLabel(feed)))
                FeedTag(stringResource(FeedsLogic.modeLabel(feed.mode)))
                if (!feed.enabled) {
                    FeedTag(
                        stringResource(R.string.feeds_paused),
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            val link = feed.siteUrl.ifEmpty { feed.url }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    feed.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (link.startsWith("http")) {
                    IconButton(onClick = { actions.openUrl(link) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Text(
                feed.folderName?.let { stringResource(R.string.feeds_detail_folder, it) } ?: stringResource(R.string.feeds_detail_folder_missing),
                style = MaterialTheme.typography.bodyMedium,
                color = if (feed.folderId == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                feed.lastCheckedAt?.let { stringResource(R.string.feeds_detail_checked, formatRelativeTime(it)) }
                    ?: stringResource(R.string.feeds_detail_never_checked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            feed.lastError?.let {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.feeds_error, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun FeedItemRow(
    item: FeedItem,
    feedKind: FeedKind,
    submitting: Boolean,
    onFetch: (FeedMode) -> Unit,
    onOpen: () -> Unit,
) {
    val actions = FeedsLogic.actionsFor(item, feedKind)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = actions.open, onClick = onOpen)
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 6.dp)
            .testTag("feed_item_row"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateTag(item.state)
            Spacer(Modifier.width(6.dp))
            Text(
                item.title.ifEmpty { item.url },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (item.state == FeedItemState.NEW) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val meta = listOfNotNull(
            item.publishedAt?.let { formatTime(it) },
            item.enclosureSize?.takeIf { it > 0 }?.let { formatBytes(it) },
            item.node?.name,
        )
        if (meta.isNotEmpty()) {
            Text(
                meta.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        item.error?.takeIf { item.state == FeedItemState.FAILED }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 2.dp))
        }
        if (actions.download || actions.clip || actions.open || submitting) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                if (submitting) {
                    CircularProgressIndicator(modifier = Modifier.padding(8.dp).size(18.dp), strokeWidth = 2.dp)
                } else {
                    if (actions.open) {
                        OutlinedButton(onClick = onOpen, modifier = Modifier.height(36.dp).testTag("feed_item_open")) {
                            Text(stringResource(R.string.feeds_item_open))
                        }
                    }
                    if (actions.download) {
                        TextButton(onClick = { onFetch(FeedMode.DOWNLOAD) }, modifier = Modifier.testTag("feed_item_download")) {
                            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.feeds_item_download))
                        }
                    }
                    if (actions.clip) {
                        TextButton(onClick = { onFetch(FeedMode.CLIP) }, modifier = Modifier.testTag("feed_item_clip")) {
                            Icon(Icons.Outlined.ContentCut, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.feeds_item_clip))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StateTag(state: FeedItemState) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (state) {
        FeedItemState.NEW -> scheme.primaryContainer to scheme.onPrimaryContainer
        FeedItemState.QUEUED -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        FeedItemState.DONE -> scheme.secondaryContainer to scheme.onSecondaryContainer
        FeedItemState.FAILED -> scheme.errorContainer to scheme.onErrorContainer
        FeedItemState.SKIPPED -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
    FeedTag(stringResource(FeedsLogic.stateLabel(state)), container, content)
}
