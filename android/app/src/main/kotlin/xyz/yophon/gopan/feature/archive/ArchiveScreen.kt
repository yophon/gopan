package xyz.yophon.gopan.feature.archive

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import xyz.yophon.gopan.core.ui.components.FolderGlyph
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.NodeKind
import xyz.yophon.gopan.core.data.archive.ArchiveEntry
import xyz.yophon.gopan.core.data.archive.ArchiveInfo
import xyz.yophon.gopan.core.data.archive.ArchiveStatus
import xyz.yophon.gopan.core.ui.components.EmptyState
import xyz.yophon.gopan.core.ui.components.ErrorState
import xyz.yophon.gopan.core.ui.components.LoadingState
import xyz.yophon.gopan.core.ui.format.formatBytes
import xyz.yophon.gopan.core.ui.format.formatTime
import xyz.yophon.gopan.feature.drive.BreadcrumbBar
import xyz.yophon.gopan.feature.drive.Crumb
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.LoadMoreFooter
import xyz.yophon.gopan.feature.drive.LoadMoreTrigger
import xyz.yophon.gopan.feature.drive.fileCategory
import xyz.yophon.gopan.feature.drive.icon
import xyz.yophon.gopan.feature.drive.isNearEnd
import xyz.yophon.gopan.feature.drive.resolve
import xyz.yophon.gopan.feature.drive.tint

/**
 * 压缩包浏览页(doc/21 四.3):顶栏是压缩包名,下面是包内面包屑(根显示格式,如 ZIP)与当前目录的条目。
 * 点目录进入,点图片 / PDF / 文本在页内看,别的文件取下来交给其他应用;长按多选。
 * 底栏「全部解压 / 解压此目录 / 解压选中的 N 项」解到压缩包旁边,「解压到…」先选文件夹。
 * 系统返回先关查看器、退出多选、回上一级目录,都没有了才离开。
 */
@Composable
fun ArchiveScreen(
    onBack: () -> Unit,
    /** 「查看」解压进度:退回主界面(主界面接着打开传输列表) */
    onLeaveToMain: () -> Unit,
    viewModel: ArchiveViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val extracting by viewModel.extract.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val currentOnLeave by rememberUpdatedState(onLeaveToMain)

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ArchiveEffect.Message -> launch { snackbar.showSnackbar(effect.text.resolve(context)) }
                ArchiveEffect.ExtractStarted -> launch {
                    snackbar.showExtractStarted(context) {
                        viewModel.showTransfers()
                        currentOnLeave()
                    }
                }
                is ArchiveEffect.LaunchFile -> if (!launchArchiveFile(context, effect.file, effect.share)) {
                    launch { snackbar.showSnackbar(context.getString(R.string.transfer_msg_no_app)) }
                }
            }
        }
    }
    BackHandler(enabled = state.viewer != null || state.selectionMode || (state.canGoUp && state.readyInfo != null)) {
        if (!viewModel.onBack()) onBack()
    }

    val info = state.readyInfo
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                if (state.selectionMode) {
                    SelectionTopBar(state, viewModel)
                } else {
                    ArchiveTopBar(state.name, info, onBack)
                }
            },
            bottomBar = {
                if (info != null) {
                    ExtractBar(
                        label = state.extractLabel.text.resolve(),
                        busy = extracting.busy,
                        onExtract = viewModel::extractHere,
                        onExtractTo = viewModel::extractTo,
                    )
                }
            },
            // 查看器盖在上面时 Snackbar 由查看器自己显示(同一个 hostState 只挂一处)
            snackbarHost = { if (state.viewer == null) SnackbarHost(snackbar) },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                when (val s = state.info) {
                    ArchiveInfoState.Loading -> LoadingState()
                    ArchiveInfoState.Pending -> LoadingState(message = stringResource(R.string.archive_pending))
                    is ArchiveInfoState.Error -> ErrorState(s.message, onRetry = viewModel::retry)
                    is ArchiveInfoState.Unavailable -> EmptyState(
                        title = s.message ?: stringResource(R.string.archive_unavailable),
                        icon = Icons.Outlined.FolderZip,
                    )
                    is ArchiveInfoState.Ready -> ArchiveBody(state, s.info, viewModel)
                }
            }
        }
        state.viewer?.let { viewer ->
            ArchiveEntryViewer(
                viewer = viewer,
                snackbar = snackbar,
                onClose = viewModel::closeViewer,
                onRetry = viewModel::retryViewer,
                onLaunch = viewModel::launchViewerFile,
                onLoadMoreText = viewModel::loadMoreText,
            )
        }
    }
    state.fileMenu?.let { entry ->
        FileMenuSheet(entry, onPick = { share -> viewModel.fetchForApp(entry, share) }, onDismiss = viewModel::dismissFileMenu)
    }
    state.fetching?.let { FetchingDialog(it, onCancel = viewModel::cancelFetch) }
    ExtractPickerHost(viewModel.extract)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArchiveTopBar(name: String, info: ArchiveInfo?, onBack: () -> Unit) {
    TopAppBar(
        title = {
            Column {
                Text(name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.testTag("archive_title"))
                if (info != null) {
                    Text(
                        stringResource(R.string.archive_summary, info.fileCount, formatBytes(info.totalSize)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.testTag("archive_back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.archive_back))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(state: ArchiveUiState, viewModel: ArchiveViewModel) {
    TopAppBar(
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
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
}

/** 底栏:主按钮解到压缩包旁边(说法随勾选与所在目录变),「解压到…」先选文件夹 */
@Composable
private fun ExtractBar(label: String, busy: Boolean, onExtract: () -> Unit, onExtractTo: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onExtract, enabled = !busy, modifier = Modifier.weight(1f).testTag("archive_extract")) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Unarchive, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = onExtractTo, enabled = !busy, modifier = Modifier.testTag("archive_extract_to")) {
                Text(stringResource(R.string.archive_extract_to), maxLines = 1)
            }
        }
    }
}

@Composable
private fun ArchiveBody(state: ArchiveUiState, info: ArchiveInfo, viewModel: ArchiveViewModel) {
    // 面包屑根是格式名(ZIP);各级的 id 放包内路径,根是 ""
    val crumbs = remember(state.dir, info.format) {
        listOf(Crumb("", formatLabel(info.format))) + state.crumbs.map { Crumb(it.path, it.name) }
    }
    BreadcrumbBar(crumbs = crumbs, onClick = { index -> crumbs[index].id?.let(viewModel::openDir) })
    if (info.status == ArchiveStatus.TRUNCATED) {
        Notice(Icons.Outlined.Info, stringResource(R.string.archive_truncated))
    }
    if (info.encrypted) Notice(Icons.Outlined.Lock, stringResource(R.string.archive_encrypted))
    HorizontalDivider()
    when (val status = state.listStatus) {
        ListStatus.Loading -> LoadingState()
        is ListStatus.Error -> ErrorState(status.message, onRetry = viewModel::retry)
        ListStatus.Content -> if (state.entries.isEmpty()) {
            EmptyState(title = stringResource(R.string.archive_empty_dir), icon = Icons.Outlined.FolderOff)
        } else {
            EntryList(state, viewModel)
        }
    }
}

@Composable
private fun Notice(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EntryList(state: ArchiveUiState, viewModel: ArchiveViewModel) {
    // 换目录从顶上看起
    val listState = remember(state.dir) { LazyListState() }
    LoadMoreTrigger(itemCount = state.entries.size, isNearEnd = { listState.isNearEnd() }, onLoadMore = viewModel::loadMore)
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("archive_list")) {
        items(state.entries, key = { it.idx }, contentType = { "entry" }) { entry ->
            EntryRow(
                entry = entry,
                selectionMode = state.selectionMode,
                selected = entry.path in state.selection,
                onClick = { viewModel.onEntryClick(entry) },
                onLongClick = { viewModel.onEntryLongClick(entry) },
            )
        }
        if (state.nextCursor != null || state.isLoadingMore || state.loadMoreError != null) {
            item(key = "footer", contentType = "footer") {
                LoadMoreFooter(isLoading = state.isLoadingMore, error = state.loadMoreError, onRetry = viewModel::loadMore)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    entry: ArchiveEntry,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val block = entry.block()
    val container = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    ListItem(
        modifier = Modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .testTag("archive_entry:${entry.name}"),
        colors = ListItemDefaults.colors(containerColor = container),
        leadingContent = {
            if (entry.isDir) {
                val label = stringResource(R.string.archive_type_folder)
                FolderGlyph(Modifier.size(30.dp).semantics { contentDescription = label })
            } else {
                val category = fileCategory(NodeKind.FILE, entry.name, null)
                Icon(
                    category.icon,
                    contentDescription = null,
                    tint = if (block != null) dim else category.tint(),
                    modifier = Modifier.size(28.dp),
                )
            }
        },
        headlineContent = {
            Text(
                entry.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (block != null) dim else Color.Unspecified,
            )
        },
        supportingContent = {
            val parts = listOfNotNull(
                formatBytes(entry.size).takeIf { !entry.isDir },
                entry.modifiedAt?.let { formatTime(it) },
            )
            Column {
                if (parts.isNotEmpty()) {
                    Text(parts.joinToString(" · "), maxLines = 1, style = MaterialTheme.typography.bodySmall, color = dim)
                }
                // 太大 / 太深:说明为什么点不开(带标记的看行尾的小标签)
                if (block == EntryBlock.TOO_LARGE || block == EntryBlock.TOO_DEEP) {
                    entry.blockMessage()?.let {
                        Text(it.resolve(), maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                flagLabel(entry.flag)?.let { FlagChip(it.resolve()) }
                if (selectionMode) {
                    Checkbox(checked = selected, onCheckedChange = { onClick() }, enabled = entry.selectable)
                }
            }
        },
    )
}

@Composable
private fun FlagChip(text: String) {
    Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** 不能预览的文件:取下来后「用其他应用打开」还是「分享」 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileMenuSheet(entry: ArchiveEntry, onPick: (share: Boolean) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        val category = fileCategory(NodeKind.FILE, entry.name, null)
        ListItem(
            leadingContent = { Icon(category.icon, contentDescription = null, tint = category.tint(), modifier = Modifier.size(36.dp)) },
            headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(formatBytes(entry.size)) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        HorizontalDivider()
        ListItem(
            modifier = Modifier.clickable { onPick(false) }.testTag("archive_file_open_with"),
            leadingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
            headlineContent = { Text(stringResource(R.string.archive_open_with)) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        ListItem(
            modifier = Modifier.clickable { onPick(true) }.testTag("archive_file_share"),
            leadingContent = { Icon(Icons.Outlined.Share, contentDescription = null) },
            headlineContent = { Text(stringResource(R.string.archive_share)) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        Spacer(Modifier.height(16.dp).navigationBarsPadding())
    }
}

/** 正在把文件取到本地(交给其他应用之前) */
@Composable
private fun FetchingDialog(fetch: EntryFetch, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.archive_fetching, fetch.entry.name), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val fraction = fetch.fraction
                if (fraction == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.archive_fetching_percent, (fraction * 100).toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
        modifier = Modifier.testTag("archive_fetching"),
    )
}
