package xyz.yophon.gopan.feature.space

import xyz.yophon.gopan.core.ui.components.FolderGlyph
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.space.DuplicateGroup
import xyz.yophon.gopan.core.data.space.SpaceCategory
import xyz.yophon.gopan.core.data.space.SpaceFile
import xyz.yophon.gopan.core.data.space.SpaceOverview
import xyz.yophon.gopan.core.ui.components.ConfirmDialog
import xyz.yophon.gopan.core.ui.components.EmptyState
import xyz.yophon.gopan.core.ui.components.ErrorState
import xyz.yophon.gopan.core.ui.components.LoadingState
import xyz.yophon.gopan.core.ui.format.formatBytes
import xyz.yophon.gopan.core.ui.format.formatRelativeTime
import xyz.yophon.gopan.core.ui.format.formatTime
import xyz.yophon.gopan.feature.account.UsageLevel
import xyz.yophon.gopan.feature.account.UsageSummary
import xyz.yophon.gopan.feature.drive.FullPage
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.LoadMoreFooter
import xyz.yophon.gopan.feature.drive.LoadMoreTrigger
import xyz.yophon.gopan.feature.drive.NestedScreenInsets
import xyz.yophon.gopan.feature.drive.NodeVisual
import xyz.yophon.gopan.feature.drive.isNearEnd
import xyz.yophon.gopan.feature.drive.resolve
import xyz.yophon.gopan.feature.notifications.NotificationNav

/** 空间页的标签;顺序即显示顺序 */
enum class SpaceTab(@StringRes val label: Int) {
    OVERVIEW(R.string.space_tab_overview),
    FOLDERS(R.string.space_tab_folders),
    LARGE(R.string.space_tab_large),
    DUPLICATES(R.string.space_tab_duplicates),
    STALE(R.string.space_tab_stale),
    EMPTY(R.string.space_tab_empty),
    IDLE(R.string.space_tab_idle),
}

/**
 * 「我的 → 空间」(doc/23 三.1):总览、文件夹逐层、大文件、重复文件、很久没动、空文件夹、分享与直链。
 * 清理都是移进回收站(30 天内能还原,也记进动态);点文件 / 文件夹的「打开」交给 [onOpen](文件页)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceScreen(
    onBack: () -> Unit,
    onOpen: (NotificationNav.Drive) -> Unit,
    onOpenTrash: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(SpaceTab.OVERVIEW) }
    val snackbar = remember { SnackbarHostState() }
    Scaffold(
        contentWindowInsets = NestedScreenInsets,
        topBar = {
            TopAppBar(
                windowInsets = NestedScreenInsets,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.preview_back))
                    }
                },
                title = { Text(stringResource(R.string.space_title)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 8.dp, modifier = Modifier.testTag("space_tabs")) {
                SpaceTab.entries.forEach { t ->
                    Tab(
                        selected = tab == t,
                        onClick = { tab = t },
                        text = { Text(stringResource(t.label)) },
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    SpaceTab.OVERVIEW -> OverviewTab(snackbar, onOpenTrash = onOpenTrash, onShowTab = { tab = it })
                    SpaceTab.FOLDERS -> FoldersTab(snackbar, onOpen)
                    SpaceTab.LARGE -> LargeFilesTab(snackbar, onOpen)
                    SpaceTab.DUPLICATES -> DuplicatesTab(snackbar, onOpen)
                    SpaceTab.STALE -> FilesTab(hiltViewModel<StaleFilesViewModel>(), SpaceTab.STALE, snackbar, onOpen)
                    SpaceTab.EMPTY -> FilesTab(hiltViewModel<EmptyFoldersViewModel>(), SpaceTab.EMPTY, snackbar, onOpen)
                    SpaceTab.IDLE -> IdleTab(snackbar)
                }
            }
        }
    }
}

/** 标签的一次性提示进共用的 Snackbar,「打开」交给导航;标签露出来 / 回到前台时按需静默重查 */
@Composable
private fun TabEffects(viewModel: SpaceTabViewModel, snackbar: SnackbarHostState, onOpen: (NotificationNav.Drive) -> Unit) {
    val context = LocalContext.current
    val currentOnOpen by rememberUpdatedState(onOpen)
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is SpaceEffect.Message -> launch { snackbar.showSnackbar(effect.text.resolve(context)) }
                is SpaceEffect.Open -> currentOnOpen(effect.nav)
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onShown() }
}

/** 加载中 / 出错 / 内容,外面套下拉刷新 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabBody(
    status: ListStatus,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    content: @Composable () -> Unit,
) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        when (status) {
            ListStatus.Loading -> LoadingState()
            is ListStatus.Error -> FullPage { ErrorState(status.message, onRetry = onRetry) }
            ListStatus.Content -> content()
        }
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SpaceCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

// ---------- 总览 ----------

@Composable
private fun OverviewTab(snackbar: SnackbarHostState, onOpenTrash: () -> Unit, onShowTab: (SpaceTab) -> Unit) {
    val viewModel: SpaceOverviewViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    TabEffects(viewModel, snackbar, onOpen = {})
    TabBody(state.status, state.refreshing, viewModel::refresh, viewModel::retry) {
        val overview = state.overview ?: return@TabBody
        LazyColumn(modifier = Modifier.fillMaxSize().testTag("space_overview"), contentPadding = PaddingValues(vertical = 8.dp)) {
            item("usage") { UsageCard(overview) }
            item("types-title") { SectionTitle(stringResource(R.string.space_by_type)) }
            item("types") { CategoryCard(overview) }
            item("trash") { TrashCard(overview, purging = state.purging, onOpenTrash = onOpenTrash, onPurge = viewModel::requestPurgeTrash) }
            item("dups") { DuplicatesSummaryCard(overview, onShow = { onShowTab(SpaceTab.DUPLICATES) }) }
        }
    }
    if (state.confirmPurge) {
        val o = state.overview
        ConfirmDialog(
            title = stringResource(R.string.trash_purge_all_title),
            text = stringResource(R.string.space_trash_purge_text, o?.trashFiles ?: 0, formatBytes(o?.trashBytes ?: 0)),
            confirmText = stringResource(R.string.trash_purge_all_button),
            destructive = true,
            busy = state.purging,
            onConfirm = viewModel::confirmPurgeTrash,
            onDismiss = viewModel::dismissPurge,
        )
    }
}

@Composable
private fun UsageCard(overview: SpaceOverview) {
    val usage = UsageSummary(overview.usedBytes, overview.quotaBytes)
    SpaceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(usage.text, style = MaterialTheme.typography.titleMedium)
                Text(usage.percentText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { usage.fraction },
            modifier = Modifier.fillMaxWidth(),
            color = when (usage.level) {
                UsageLevel.NORMAL -> MaterialTheme.colorScheme.primary
                UsageLevel.WARN -> MaterialTheme.colorScheme.tertiary
                UsageLevel.CRITICAL -> MaterialTheme.colorScheme.error
            },
        )
    }
}

@Composable
private fun CategoryCard(overview: SpaceOverview) {
    val bars = remember(overview) { SpaceLogic.categoryBars(overview) }
    SpaceCard {
        bars.forEachIndexed { i, bar ->
            if (i > 0) Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(SpaceLogic.categoryLabel(bar.category), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.space_category_value, bar.files, formatBytes(bar.bytes), bar.percent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(progress = { bar.fraction }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun TrashCard(overview: SpaceOverview, purging: Boolean, onOpenTrash: () -> Unit, onPurge: () -> Unit) {
    SpaceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.space_trash), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.space_trash_desc, overview.trashFiles, formatBytes(overview.trashBytes), SpaceLogic.trashPercent(overview)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onOpenTrash) { Text(stringResource(R.string.space_trash_open)) }
            OutlinedButton(
                onClick = onPurge,
                enabled = !purging && (overview.trashFiles > 0 || overview.trashBytes > 0),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("space_purge_trash"),
            ) { Text(stringResource(R.string.space_trash_purge)) }
        }
    }
}

@Composable
private fun DuplicatesSummaryCard(overview: SpaceOverview, onShow: () -> Unit) {
    SpaceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.space_tab_duplicates), style = MaterialTheme.typography.titleSmall)
                Text(
                    if (overview.duplicateGroups > 0) {
                        stringResource(R.string.space_duplicates_desc, overview.duplicateGroups, formatBytes(overview.duplicateBytes))
                    } else {
                        stringResource(R.string.space_duplicates_none)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (overview.duplicateGroups > 0) TextButton(onClick = onShow) { Text(stringResource(R.string.space_duplicates_show)) }
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.space_dedupe_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------- 文件夹 ----------

@Composable
private fun FoldersTab(snackbar: SnackbarHostState, onOpen: (NotificationNav.Drive) -> Unit) {
    val viewModel: SpaceFoldersViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    TabEffects(viewModel, snackbar, onOpen)
    BackHandler(enabled = state.canGoUp) { viewModel.up() }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp)) {
            Text(
                // 服务端给的完整路径;还没查到时先按下钻的名字拼
                state.level?.path ?: ("/" + state.stack.drop(1).joinToString("/") { it.name }),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (state.canGoUp) {
                TextButton(onClick = { viewModel.up() }, modifier = Modifier.testTag("space_folders_up")) {
                    Icon(Icons.Outlined.ArrowUpward, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.space_folders_up))
                }
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            TabBody(state.status, state.refreshing, viewModel::refresh, viewModel::retry) {
                val level = state.level ?: return@TabBody
                val shares = remember(level) { SpaceLogic.folderShares(level) }
                if (shares.isEmpty()) {
                    FullPage { EmptyState(title = stringResource(R.string.space_folders_empty), icon = Icons.Outlined.Folder) }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize().testTag("space_folders"), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(shares, key = { it.node?.id ?: "loose" }) { share ->
                            FolderShareRow(
                                share = share,
                                looseFiles = level.looseFiles,
                                onEnter = { share.node?.let(viewModel::enter) },
                                onOpen = { share.node?.let(viewModel::open) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderShareRow(share: FolderShare, looseFiles: Long, onEnter: () -> Unit, onOpen: () -> Unit) {
    val node = share.node
    ListItem(
        modifier = if (node != null) Modifier.clickable(onClick = onEnter).testTag("space_folder_row") else Modifier,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            if (node != null) {
                FolderGlyph(Modifier.size(26.dp))
            } else {
                Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        headlineContent = {
            Text(
                node?.name ?: stringResource(R.string.space_folders_loose),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (node != null) FontWeight.Normal else FontWeight.Medium,
            )
        },
        supportingContent = {
            Column {
                val count = node?.subtreeCount ?: looseFiles
                val text = stringResource(R.string.space_folder_value, formatBytes(share.bytes), share.percent, count)
                Text(
                    if (node?.statsStale == true) stringResource(R.string.space_folder_stale, text) else text,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(progress = { share.fraction }, modifier = Modifier.fillMaxWidth())
            }
        },
        trailingContent = if (node != null) {
            {
                IconButton(onClick = onOpen) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.space_open_in_drive))
                }
            }
        } else {
            null
        },
    )
}

// ---------- 可勾选的清单 ----------

@Composable
private fun LargeFilesTab(snackbar: SnackbarHostState, onOpen: (NotificationNav.Drive) -> Unit) {
    val viewModel: LargeFilesViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    FilesTab(viewModel, SpaceTab.LARGE, snackbar, onOpen) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().testTag("space_large_categories"),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item("all") {
                FilterChip(
                    selected = state.category == null,
                    onClick = { viewModel.setCategory(null) },
                    label = { Text(stringResource(R.string.space_large_all)) },
                )
            }
            items(SpaceCategory.entries, key = { it.name }) { c ->
                FilterChip(selected = state.category == c, onClick = { viewModel.setCategory(c) }, label = { Text(SpaceLogic.categoryLabel(c)) })
            }
        }
    }
}

@Composable
private fun FilesTab(
    viewModel: SpaceFilesViewModel,
    tab: SpaceTab,
    snackbar: SnackbarHostState,
    onOpen: (NotificationNav.Drive) -> Unit,
    header: @Composable () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TabEffects(viewModel, snackbar, onOpen)
    BackHandler(enabled = state.selection.isNotEmpty()) { viewModel.clearSelection() }
    Column(modifier = Modifier.fillMaxSize()) {
        header()
        when (tab) {
            SpaceTab.LARGE -> Hint(stringResource(R.string.space_large_hint))
            SpaceTab.STALE -> Hint(stringResource(R.string.space_stale_hint))
            SpaceTab.EMPTY -> Hint(stringResource(R.string.space_empty_hint))
            else -> Unit
        }
        Box(modifier = Modifier.weight(1f)) {
            TabBody(state.status, state.refreshing, viewModel::refresh, viewModel::retry) {
                if (state.items.isEmpty()) {
                    FullPage {
                        EmptyState(
                            title = stringResource(
                                when (tab) {
                                    SpaceTab.STALE -> R.string.space_stale_none
                                    SpaceTab.EMPTY -> R.string.space_empty_none
                                    else -> R.string.space_large_none
                                },
                            ),
                            icon = if (tab == SpaceTab.EMPTY) Icons.Outlined.FolderOff else Icons.Outlined.CleaningServices,
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize().testTag("space_files"), contentPadding = PaddingValues(bottom = 24.dp)) {
                        item("select-all") {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                Text(
                                    stringResource(R.string.space_count, state.items.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = viewModel::selectAll) {
                                    Text(stringResource(if (state.allSelected) R.string.space_select_none else R.string.drive_select_all))
                                }
                            }
                        }
                        items(state.items, key = { it.node.id }) { file ->
                            SpaceFileRow(
                                file = file,
                                secondary = fileSecondary(file, tab),
                                selected = file.node.id in state.selection,
                                onToggle = { viewModel.toggle(file.node.id) },
                                onOpen = { viewModel.open(file.node) },
                            )
                        }
                    }
                }
            }
        }
        if (state.selection.isNotEmpty()) {
            SelectionBar(
                text = stringResource(R.string.space_selected, state.selection.size, formatBytes(state.selectedBytes)),
                busy = state.deleting,
                onClear = viewModel::clearSelection,
                onDelete = viewModel::requestDelete,
            )
        }
    }
    if (state.confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.space_delete_title),
            text = stringResource(R.string.space_delete_text, state.selection.size, formatBytes(state.selectedBytes)),
            confirmText = stringResource(R.string.space_action_delete),
            destructive = true,
            busy = state.deleting,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDelete,
        )
    }
}

/** 第二行:大小 · 所在文件夹;很久没动的再带上次修改时间;空文件夹只有位置 */
@Composable
private fun fileSecondary(file: SpaceFile, tab: SpaceTab): String = when (tab) {
    SpaceTab.EMPTY -> file.path
    SpaceTab.STALE -> stringResource(R.string.space_stale_value, formatBytes(file.node.size), formatTime(file.node.updatedAt).substringBefore(' '), file.path)
    else -> "${formatBytes(file.node.size)} · ${file.path}"
}

/** 一行:点整行勾选 / 取消,右边「打开」去文件页 */
@Composable
private fun SpaceFileRow(file: SpaceFile, secondary: String, selected: Boolean, onToggle: () -> Unit, onOpen: () -> Unit, badge: String? = null) {
    ListItem(
        modifier = Modifier.clickable(onClick = onToggle).testTag("space_file_row"),
        colors = ListItemDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
        leadingContent = { NodeVisual(file.node, modifier = Modifier.size(40.dp)) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(file.node.name, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (badge != null) {
                    Spacer(Modifier.width(6.dp))
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small) {
                        Text(
                            badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        },
        supportingContent = {
            Text(secondary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOpen) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.space_open_in_drive))
                }
                Checkbox(checked = selected, onCheckedChange = { onToggle() })
            }
        },
    )
}

/** 底部:已选多少 + 「移进回收站」 */
@Composable
private fun SelectionBar(text: String, busy: Boolean, onClear: () -> Unit, onDelete: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("space_selection_bar"),
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onClear, enabled = !busy) { Text(stringResource(R.string.action_cancel)) }
            Button(
                onClick = onDelete,
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                modifier = Modifier.testTag("space_delete_selected"),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onError)
                } else {
                    Text(stringResource(R.string.space_action_delete))
                }
            }
        }
    }
}

// ---------- 重复文件 ----------

@Composable
private fun DuplicatesTab(snackbar: SnackbarHostState, onOpen: (NotificationNav.Drive) -> Unit) {
    val viewModel: DuplicatesViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    TabEffects(viewModel, snackbar, onOpen)
    Column(modifier = Modifier.fillMaxSize()) {
        Hint(stringResource(R.string.space_dedupe_note) + "\n" + stringResource(R.string.space_dup_hint))
        Box(modifier = Modifier.weight(1f)) {
            TabBody(state.status, state.refreshing, viewModel::refresh, viewModel::retry) {
                if (state.groups.isEmpty()) {
                    FullPage { EmptyState(title = stringResource(R.string.space_duplicates_none), icon = Icons.Outlined.ContentCopy) }
                } else {
                    val listState = rememberLazyListState()
                    LoadMoreTrigger(itemCount = state.groups.size, isNearEnd = { listState.isNearEnd() }, onLoadMore = viewModel::loadMore)
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("space_duplicates"), contentPadding = PaddingValues(bottom = 24.dp)) {
                        state.groups.forEach { group -> duplicateGroup(group, state.selection, viewModel) }
                        if (state.nextOffset != null || state.loadingMore || state.loadMoreError != null) {
                            item("more") { LoadMoreFooter(isLoading = state.loadingMore, error = state.loadMoreError, onRetry = viewModel::loadMore) }
                        }
                    }
                }
            }
        }
        if (state.selectedCount > 0) {
            SelectionBar(
                text = stringResource(R.string.space_dup_selected, state.selectedCount, formatBytes(state.selectedBytes)),
                busy = state.deleting,
                onClear = viewModel::clearSelection,
                onDelete = viewModel::requestDelete,
            )
        }
    }
    if (state.confirmDelete) {
        val text = stringResource(R.string.space_delete_text, state.selectedCount, formatBytes(state.selectedBytes))
        val warning = if (state.fullySelectedGroups > 0) "\n\n" + stringResource(R.string.space_dup_all_copies, state.fullySelectedGroups) else ""
        ConfirmDialog(
            title = stringResource(R.string.space_delete_title),
            text = text + warning,
            confirmText = stringResource(R.string.space_action_delete),
            destructive = true,
            busy = state.deleting,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDelete,
        )
    }
}

private fun LazyListScope.duplicateGroup(group: DuplicateGroup, selection: Set<String>, viewModel: DuplicatesViewModel) {
    item(key = "g-${group.sha256}", contentType = "dup-header") {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
            Text(
                group.files.firstOrNull()?.node?.name.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.space_dup_group, group.copies, formatBytes(group.size), formatBytes(group.reclaimable)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    group.files.forEachIndexed { i, file ->
        item(key = "f-${group.sha256}-${file.node.id}", contentType = "dup-file") {
            SpaceFileRow(
                file = file,
                secondary = "${file.path} · ${formatRelativeTime(file.node.createdAt ?: file.node.updatedAt)}",
                selected = file.node.id in selection,
                onToggle = { viewModel.toggle(file.node.id) },
                onOpen = { viewModel.open(file.node) },
                badge = if (i == 0) stringResource(R.string.space_dup_earliest) else null,
            )
        }
    }
    item(key = "d-${group.sha256}", contentType = "divider") { HorizontalDivider(modifier = Modifier.padding(top = 4.dp)) }
}

// ---------- 分享与直链 ----------

@Composable
private fun IdleTab(snackbar: SnackbarHostState) {
    val viewModel: SpaceIdleViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    TabEffects(viewModel, snackbar, onOpen = {})
    Column(modifier = Modifier.fillMaxSize()) {
        Hint(stringResource(R.string.space_idle_hint))
        Box(modifier = Modifier.weight(1f)) {
            TabBody(state.status, state.refreshing, viewModel::refresh, viewModel::retry) {
                val idle = state.idle ?: return@TabBody
                if (idle.isEmpty) {
                    FullPage { EmptyState(title = stringResource(R.string.space_idle_none), icon = Icons.Outlined.Link) }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize().testTag("space_idle"), contentPadding = PaddingValues(bottom = 24.dp)) {
                        if (idle.shares.isNotEmpty()) {
                            item("shares") { SectionTitle(stringResource(R.string.space_idle_shares)) }
                            items(idle.shares, key = { "s-${it.id}" }) { s ->
                                IdleRow(
                                    icon = Icons.Outlined.Share,
                                    title = s.name,
                                    secondary = when {
                                        s.expired -> stringResource(R.string.space_idle_expired, formatTime(s.expiresAt))
                                        s.lastVisitAt != null -> stringResource(R.string.space_idle_last_visit, formatRelativeTime(s.lastVisitAt))
                                        else -> stringResource(R.string.space_idle_never, formatRelativeTime(s.createdAt))
                                    },
                                    action = stringResource(R.string.shares_action_revoke),
                                    enabled = !state.revoking,
                                    onAction = { viewModel.requestRevoke(IdleRevoke.Share(s.id, s.name)) },
                                )
                            }
                        }
                        if (idle.links.isNotEmpty()) {
                            item("links") { SectionTitle(stringResource(R.string.space_idle_links)) }
                            items(idle.links, key = { "l-${it.id}" }) { l ->
                                IdleRow(
                                    icon = Icons.Outlined.Link,
                                    title = l.name,
                                    secondary = when {
                                        l.expired -> stringResource(R.string.space_idle_expired, formatTime(l.expiresAt))
                                        l.lastHitAt != null -> stringResource(R.string.space_idle_link_hits, l.hits, formatRelativeTime(l.lastHitAt))
                                        else -> stringResource(R.string.space_idle_never, formatRelativeTime(l.createdAt))
                                    },
                                    action = stringResource(R.string.links_action_revoke),
                                    enabled = !state.revoking,
                                    onAction = { viewModel.requestRevoke(IdleRevoke.Link(l.id, l.name)) },
                                )
                            }
                        }
                        if (idle.requests.isNotEmpty()) {
                            item("requests") { SectionTitle(stringResource(R.string.space_idle_requests)) }
                            items(idle.requests, key = { "r-${it.id}" }) { r ->
                                IdleRow(
                                    icon = Icons.Outlined.MoveToInbox,
                                    title = r.title,
                                    secondary = stringResource(R.string.space_idle_expired, formatTime(r.expiresAt)),
                                    action = stringResource(R.string.collect_action_revoke),
                                    enabled = !state.revoking,
                                    onAction = { viewModel.requestRevoke(IdleRevoke.Request(r.id, r.title)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    state.confirm?.let { target ->
        ConfirmDialog(
            title = when (target) {
                is IdleRevoke.Share -> stringResource(R.string.shares_revoke_title)
                is IdleRevoke.Link -> stringResource(R.string.links_revoke_title)
                is IdleRevoke.Request -> stringResource(R.string.collect_revoke_title, target.name)
            },
            text = when (target) {
                is IdleRevoke.Share -> stringResource(R.string.shares_revoke_text, target.name)
                is IdleRevoke.Link -> stringResource(R.string.links_revoke_text, target.name)
                is IdleRevoke.Request -> stringResource(R.string.collect_revoke_text)
            },
            confirmText = when (target) {
                is IdleRevoke.Share -> stringResource(R.string.shares_revoke_confirm)
                is IdleRevoke.Link -> stringResource(R.string.links_revoke_confirm)
                is IdleRevoke.Request -> stringResource(R.string.collect_revoke_confirm)
            },
            destructive = true,
            busy = state.revoking,
            onConfirm = viewModel::confirmRevoke,
            onDismiss = viewModel::dismissRevoke,
        )
    }
}

@Composable
private fun IdleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    secondary: String,
    action: String,
    enabled: Boolean,
    onAction: () -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        headlineContent = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(secondary, style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
            TextButton(
                onClick = onAction,
                enabled = enabled,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("space_idle_revoke"),
            ) { Text(action) }
        },
    )
}
