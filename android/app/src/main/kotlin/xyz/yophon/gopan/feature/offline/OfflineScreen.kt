package xyz.yophon.gopan.feature.offline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.NodeKind
import xyz.yophon.gopan.core.offline.OfflineFileEntity
import xyz.yophon.gopan.core.offline.OfflineItem
import xyz.yophon.gopan.core.offline.OfflineStatus
import xyz.yophon.gopan.core.ui.components.ConfirmDialog
import xyz.yophon.gopan.core.ui.components.EmptyState
import xyz.yophon.gopan.core.ui.components.LoadingState
import xyz.yophon.gopan.core.ui.format.formatBytes
import xyz.yophon.gopan.core.ui.format.formatRelativeTime
import xyz.yophon.gopan.core.ui.nav.Routes
import xyz.yophon.gopan.feature.drive.NestedScreenInsets
import xyz.yophon.gopan.feature.drive.NodeVisual
import xyz.yophon.gopan.feature.drive.TopBarInsets
import xyz.yophon.gopan.feature.drive.belowCollapsingTopBar
import xyz.yophon.gopan.feature.drive.bottomBarPadding
import xyz.yophon.gopan.feature.drive.collapsingTopBarColors
import xyz.yophon.gopan.feature.drive.rememberSubPageScroll
import xyz.yophon.gopan.feature.drive.resolve
import java.time.Instant

/** 「离线可用」页(doc/26):加过的文件与文件夹,各自的状态与手机上的占用;点开看,⋮ 里更新、移除 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineScreen(
    onBack: () -> Unit,
    onOpenPreview: (String) -> Unit,
    onOpenFolder: (Routes.OfflineFolder) -> Unit,
    viewModel: OfflineViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val removing by viewModel.removing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    OfflineEffects(viewModel.effects, snackbar, onOpenPreview, onOpenFolder)
    val topBarScroll = rememberSubPageScroll()
    Scaffold(
        contentWindowInsets = NestedScreenInsets,
        modifier = Modifier.nestedScroll(topBarScroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                windowInsets = TopBarInsets,
                scrollBehavior = topBarScroll,
                colors = collapsingTopBarColors(topBarScroll),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.drive_nav_back))
                    }
                },
                title = {
                    Column {
                        Text(stringResource(R.string.offline_title))
                        val total = items.orEmpty().sumOf { it.bytes }
                        if (total > 0) {
                            Text(
                                stringResource(R.string.offline_total, formatBytes(total)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val list = items
        val content = Modifier.belowCollapsingTopBar(padding, topBarScroll)
        when {
            list == null -> LoadingState(modifier = content)
            list.isEmpty() -> EmptyState(
                title = stringResource(R.string.offline_empty),
                description = stringResource(R.string.offline_empty_hint),
                icon = Icons.Outlined.OfflinePin,
                modifier = content,
            )
            else -> LazyColumn(content.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomBarPadding())) {
                items(list, key = { it.nodeId }) { item ->
                    OfflineItemRow(
                        item = item,
                        onClick = { viewModel.open(item) },
                        onUpdate = { viewModel.update(item) },
                        onRemove = { viewModel.requestRemove(item) },
                    )
                }
            }
        }
    }
    removing?.let { item ->
        ConfirmDialog(
            title = stringResource(R.string.offline_remove_title),
            text = stringResource(R.string.offline_remove_text, item.name, formatBytes(item.bytes)),
            confirmText = stringResource(R.string.offline_action_remove),
            destructive = true,
            onConfirm = viewModel::confirmRemove,
            onDismiss = viewModel::dismissRemove,
        )
    }
}

@Composable
private fun OfflineItemRow(item: OfflineItem, onClick: () -> Unit, onUpdate: () -> Unit, onRemove: () -> Unit) {
    val node = Node(id = item.nodeId, name = item.name, kind = if (item.isFolder) NodeKind.FOLDER else NodeKind.FILE, mime = item.mime)
    val status = item.status
    ListItem(
        modifier = Modifier.clickable(onClick = onClick).testTag("offline:${item.name}"),
        leadingContent = { NodeVisual(node, modifier = Modifier.size(44.dp)) },
        headlineContent = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(
                    statusText(item),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (status is OfflineStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (status is OfflineStatus.Downloading) {
                    val fraction = status.fraction
                    val bar = Modifier.fillMaxWidth().padding(top = 6.dp)
                    if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = bar) else LinearProgressIndicator(modifier = bar)
                }
            }
        },
        trailingContent = {
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("offline_more")) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.drive_action_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.offline_action_update)) },
                        leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                        onClick = {
                            menu = false
                            onUpdate()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.offline_action_remove)) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                        onClick = {
                            menu = false
                            onRemove()
                        },
                    )
                }
            }
        },
    )
}

/** 第二行:下好了写占用、文件数、什么时候存的;否则写这一轮的状态 */
@Composable
private fun statusText(item: OfflineItem): String = when (val s = item.status) {
    OfflineStatus.Ready -> buildList {
        add(formatBytes(item.bytes))
        if (item.isFolder) add(stringResource(R.string.offline_files_count, item.files))
        item.syncedAt?.let { add(stringResource(R.string.offline_synced_at, formatRelativeTime(Instant.ofEpochMilli(it).toString()))) }
    }.joinToString(" · ")
    is OfflineStatus.Downloading -> when {
        s.filesTotal != null && s.filesTotal > 0 -> stringResource(R.string.offline_status_files, s.filesDone ?: 0, s.filesTotal)
        s.fraction != null -> stringResource(R.string.offline_status_percent, (s.fraction * 100).toInt())
        else -> stringResource(R.string.offline_status_downloading)
    }
    OfflineStatus.Waiting -> stringResource(R.string.offline_status_waiting)
    is OfflineStatus.Failed -> s.message ?: stringResource(R.string.offline_status_failed)
    OfflineStatus.Incomplete -> stringResource(R.string.offline_status_incomplete)
}

/** 文件夹项里的一层:子目录在前,文件在后;都是手机上的副本,没网也能点开 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineFolderScreen(
    onBack: () -> Unit,
    onOpenPreview: (String) -> Unit,
    onOpenFolder: (Routes.OfflineFolder) -> Unit,
    viewModel: OfflineFolderViewModel = hiltViewModel(),
) {
    val listing by viewModel.listing.collectAsStateWithLifecycle()
    val title by viewModel.title.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    OfflineEffects(viewModel.effects, snackbar, onOpenPreview, onOpenFolder)
    val topBarScroll = rememberSubPageScroll()
    Scaffold(
        contentWindowInsets = NestedScreenInsets,
        modifier = Modifier.nestedScroll(topBarScroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                windowInsets = TopBarInsets,
                scrollBehavior = topBarScroll,
                colors = collapsingTopBarColors(topBarScroll),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.drive_nav_back))
                    }
                },
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val current = listing
        val content = Modifier.belowCollapsingTopBar(padding, topBarScroll)
        when {
            current == null -> LoadingState(modifier = content)
            current.folders.isEmpty() && current.files.isEmpty() -> EmptyState(title = stringResource(R.string.offline_folder_empty), modifier = content)
            else -> LazyColumn(content.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomBarPadding())) {
                items(current.folders, key = { "d:" + it.path }) { folder ->
                    val node = Node(id = "d:" + folder.path, name = folder.name, kind = NodeKind.FOLDER)
                    ListItem(
                        modifier = Modifier.clickable { viewModel.openFolder(folder) },
                        leadingContent = { NodeVisual(node, modifier = Modifier.size(44.dp)) },
                        headlineContent = { Text(folder.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(formatBytes(folder.bytes) + " · " + stringResource(R.string.offline_files_count, folder.files))
                        },
                    )
                }
                items(current.files, key = { "f:" + it.nodeId }) { file -> OfflineFileRow(file, onClick = { viewModel.openFile(file) }) }
            }
        }
    }
}

@Composable
private fun OfflineFileRow(file: OfflineFileEntity, onClick: () -> Unit) {
    val node = Node(id = file.nodeId, name = file.name, kind = NodeKind.FILE, size = file.size, mime = file.mime)
    ListItem(
        modifier = Modifier.clickable(onClick = onClick).testTag("offline_file:${file.name}"),
        leadingContent = { NodeVisual(node, modifier = Modifier.size(44.dp)) },
        headlineContent = { Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(formatBytes(file.size)) },
    )
}

@Composable
private fun OfflineEffects(
    effects: kotlinx.coroutines.flow.Flow<OfflineEffect>,
    snackbar: SnackbarHostState,
    onOpenPreview: (String) -> Unit,
    onOpenFolder: (Routes.OfflineFolder) -> Unit,
) {
    val context = LocalContext.current
    val openPreview by rememberUpdatedState(onOpenPreview)
    val openFolder by rememberUpdatedState(onOpenFolder)
    LaunchedEffect(effects) {
        effects.collect { effect ->
            when (effect) {
                is OfflineEffect.Message -> launch { snackbar.showSnackbar(effect.text.resolve(context)) }
                is OfflineEffect.OpenPreview -> openPreview(effect.nodeId)
                is OfflineEffect.OpenFolder -> openFolder(effect.route)
            }
        }
    }
}
