package xyz.yophon.gopan.feature.transfers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import xyz.yophon.gopan.core.transfer.DownloadDest
import xyz.yophon.gopan.core.transfer.DownloadKind
import xyz.yophon.gopan.core.transfer.DownloadPhase
import xyz.yophon.gopan.core.transfer.LiveProgress
import xyz.yophon.gopan.core.transfer.TransferState
import xyz.yophon.gopan.core.transfer.UploadPhase
import xyz.yophon.gopan.core.ui.components.EmptyState
import xyz.yophon.gopan.core.ui.format.formatBytes
import xyz.yophon.gopan.feature.drive.FileCategory
import xyz.yophon.gopan.feature.drive.NestedScreenInsets
import xyz.yophon.gopan.feature.drive.fileCategory
import xyz.yophon.gopan.feature.drive.icon
import xyz.yophon.gopan.feature.drive.tint

/** 传输列表(上传 / 下载);从文件页菜单、「我的」、传输助手、通知点按进入 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen(
    onBack: () -> Unit,
    onOpenFolder: (String?) -> Unit,
    viewModel: TransfersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val currentOpenFolder by rememberUpdatedState(onOpenFolder)
    // 第一次有数据时选栏:正在跑的只有下载(或根本没有上传)就停在下载栏
    var tabChosen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.loaded) {
        if (state.loaded && !tabChosen) {
            tabChosen = true
            val preferDownloads = (state.activeDownloads > 0 && state.activeUploads == 0) ||
                (state.uploads.isEmpty() && state.downloads.isNotEmpty())
            if (preferDownloads) tab = 1
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                TransfersEffect.NoAppToOpen -> launch { snackbar.showSnackbar(context.getString(R.string.transfer_msg_no_app)) }
                is TransfersEffect.OpenFolder -> currentOpenFolder(effect.folderId)
            }
        }
    }

    Scaffold(
        contentWindowInsets = NestedScreenInsets,
        topBar = {
            TopAppBar(
                windowInsets = NestedScreenInsets,
                title = { Text(stringResource(R.string.transfers_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.drive_nav_back))
                    }
                },
                actions = {
                    if (state.hasDone) {
                        IconButton(onClick = viewModel::clearFinished) {
                            Icon(Icons.Outlined.CleaningServices, contentDescription = stringResource(R.string.transfers_clear_done))
                        }
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.transfers_settings))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text(tabLabel(stringResource(R.string.transfers_tab_uploads), state.activeUploads)) },
                    modifier = Modifier.testTag("transfers_tab_uploads"),
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text(tabLabel(stringResource(R.string.transfers_tab_downloads), state.activeDownloads)) },
                    modifier = Modifier.testTag("transfers_tab_downloads"),
                )
            }
            if (tab == 0) {
                if (state.loaded && state.uploads.isEmpty()) {
                    EmptyState(title = stringResource(R.string.transfers_empty_uploads), icon = Icons.Outlined.CloudUpload)
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
                        items(state.uploads, key = { it.task.id }) { row ->
                            UploadItem(row, viewModel)
                            HorizontalDivider()
                        }
                    }
                }
            } else {
                if (state.loaded && state.downloads.isEmpty()) {
                    EmptyState(title = stringResource(R.string.transfers_empty_downloads), icon = Icons.Outlined.CloudDownload)
                } else {
                    val chooser = stringResource(R.string.drive_action_open)
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
                        items(state.downloads, key = { it.task.id }) { row ->
                            DownloadItem(row, viewModel, chooser)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
    if (showSettings) TransferSettingsDialog(onDismiss = { showSettings = false })
}

private fun tabLabel(label: String, active: Int) = if (active > 0) "$label ($active)" else label

@Composable
private fun UploadItem(row: UploadRow, viewModel: TransfersViewModel) {
    val t = row.task
    val p = row.progress
    val category = fileCategory(NodeKind.FILE, t.finalName ?: t.name, t.mime)
    val status = when (t.state) {
        TransferState.RUNNING -> uploadPhaseText(p, t.size)
        TransferState.QUEUED -> if (t.nextAttemptAt > System.currentTimeMillis() && t.errorMessage != null) {
            stringResource(R.string.transfer_state_waiting_retry) + " · " + t.errorMessage
        } else {
            stringResource(R.string.transfer_state_queued)
        }
        TransferState.PAUSED -> stringResource(R.string.transfer_state_paused)
        TransferState.FAILED -> t.errorMessage ?: stringResource(R.string.transfer_state_failed)
        TransferState.DONE -> stringResource(if (t.instant) R.string.transfer_state_instant else R.string.transfer_state_done)
        TransferState.CANCELED -> ""
    }
    TransferRow(
        icon = category.icon,
        category = category,
        title = t.finalName ?: t.name,
        subtitle = buildString {
            append(formatBytes(t.size.takeIf { it >= 0 }))
            if (t.toChat) append(" · ").append(stringResource(R.string.transfer_to_chat))
            if (status.isNotEmpty()) append(" · ").append(status)
        },
        error = t.state == TransferState.FAILED,
        progress = when (t.state) {
            TransferState.RUNNING -> p?.takeIf { it.phase != UploadPhase.PREPARING.name && it.phase != UploadPhase.VERIFYING.name }?.fraction ?: -1f
            else -> null
        },
        testTag = "upload_row_${t.name}",
    ) {
        when (t.state) {
            TransferState.QUEUED, TransferState.RUNNING -> {
                ActionIcon(Icons.Outlined.Pause, R.string.transfer_action_pause) { viewModel.pause(row.key) }
                ActionIcon(Icons.Outlined.Close, R.string.transfer_action_cancel) { viewModel.cancel(row.key) }
            }
            TransferState.PAUSED -> {
                ActionIcon(Icons.Outlined.PlayArrow, R.string.transfer_action_resume) { viewModel.resume(row.key) }
                ActionIcon(Icons.Outlined.Close, R.string.transfer_action_cancel) { viewModel.cancel(row.key) }
            }
            TransferState.FAILED -> {
                ActionIcon(Icons.Outlined.Refresh, R.string.transfer_action_retry) { viewModel.retry(row.key) }
                ActionIcon(Icons.Outlined.DeleteOutline, R.string.transfer_action_remove) { viewModel.remove(row.key) }
            }
            TransferState.DONE -> {
                if (!t.toChat) ActionIcon(Icons.Outlined.FolderOpen, R.string.transfer_action_locate) { viewModel.locate(t) }
                ActionIcon(Icons.Outlined.DeleteOutline, R.string.transfer_action_remove) { viewModel.remove(row.key) }
            }
            TransferState.CANCELED -> Unit
        }
    }
}

@Composable
private fun DownloadItem(row: DownloadRow, viewModel: TransfersViewModel, chooserTitle: String) {
    val t = row.task
    val p = row.progress
    val isFolder = t.kind == DownloadKind.FOLDER
    val category = when {
        isFolder -> FileCategory.FOLDER
        t.kind == DownloadKind.PACK -> FileCategory.ARCHIVE
        else -> fileCategory(NodeKind.FILE, t.name, t.mime)
    }
    val total = t.size
    val status = when (t.state) {
        TransferState.RUNNING -> when {
            isFolder && t.expanded -> stringResource(R.string.transfer_folder_progress, row.filesDone, row.filesTotal)
            else -> downloadPhaseText(p)
        }
        TransferState.QUEUED -> if (t.nextAttemptAt > System.currentTimeMillis() && t.errorMessage != null) {
            stringResource(R.string.transfer_state_waiting_retry) + " · " + t.errorMessage
        } else {
            stringResource(R.string.transfer_state_queued)
        }
        TransferState.PAUSED -> stringResource(R.string.transfer_state_paused)
        TransferState.FAILED -> t.errorMessage ?: stringResource(R.string.transfer_state_failed)
        TransferState.DONE -> when (t.dest) {
            DownloadDest.DOWNLOADS -> stringResource(R.string.transfer_saved_to_downloads)
            DownloadDest.TREE -> stringResource(R.string.transfer_saved_to_folder)
            DownloadDest.OPEN_WITH -> stringResource(R.string.transfer_state_done)
        }
        TransferState.CANCELED -> ""
    }
    val fraction: Float? = when {
        t.state != TransferState.RUNNING -> null
        isFolder && t.expanded -> if ((total ?: 0) > 0) (row.bytesDone.toFloat() / total!!).coerceIn(0f, 1f) else -1f
        p != null && p.phase == DownloadPhase.DOWNLOADING.name && p.totalBytes > 0 -> p.fraction
        else -> -1f
    }
    TransferRow(
        icon = category.icon,
        category = category,
        title = t.name,
        subtitle = buildString {
            if (total != null && total > 0) append(formatBytes(total)).append(" · ")
            if (t.kind == DownloadKind.PACK) append(stringResource(R.string.transfer_pack_label)).append(" · ")
            append(status)
        },
        error = t.state == TransferState.FAILED,
        progress = fraction,
        testTag = "download_row_${t.name}",
    ) {
        when (t.state) {
            TransferState.QUEUED, TransferState.RUNNING -> {
                ActionIcon(Icons.Outlined.Pause, R.string.transfer_action_pause) { viewModel.pause(row.key) }
                ActionIcon(Icons.Outlined.Close, R.string.transfer_action_cancel) { viewModel.cancel(row.key) }
            }
            TransferState.PAUSED -> {
                ActionIcon(Icons.Outlined.PlayArrow, R.string.transfer_action_resume) { viewModel.resume(row.key) }
                ActionIcon(Icons.Outlined.Close, R.string.transfer_action_cancel) { viewModel.cancel(row.key) }
            }
            TransferState.FAILED -> {
                ActionIcon(Icons.Outlined.Refresh, R.string.transfer_action_retry) { viewModel.retry(row.key) }
                ActionIcon(Icons.Outlined.DeleteOutline, R.string.transfer_action_remove) { viewModel.remove(row.key) }
            }
            TransferState.DONE -> {
                if (!isFolder) ActionIcon(Icons.Outlined.OpenInNew, R.string.transfer_action_open) { viewModel.open(t, chooserTitle) }
                ActionIcon(Icons.Outlined.DeleteOutline, R.string.transfer_action_remove) { viewModel.remove(row.key) }
            }
            TransferState.CANCELED -> Unit
        }
    }
}

@Composable
private fun uploadPhaseText(p: LiveProgress?, size: Long): String = when (p?.phase) {
    UploadPhase.HASHING.name -> stringResource(R.string.transfer_phase_hashing, percent(p.doneBytes, maxOf(size, p.totalBytes)))
    UploadPhase.UPLOADING.name -> stringResource(R.string.transfer_phase_uploading, percent(p.doneBytes, p.totalBytes), formatBytes(p.bytesPerSecond))
    UploadPhase.VERIFYING.name -> stringResource(R.string.transfer_phase_verifying)
    else -> stringResource(R.string.transfer_phase_preparing)
}

@Composable
private fun downloadPhaseText(p: LiveProgress?): String = when (p?.phase) {
    DownloadPhase.EXPANDING.name -> stringResource(R.string.transfer_phase_expanding, p.doneBytes.toInt())
    DownloadPhase.DOWNLOADING.name -> stringResource(R.string.transfer_phase_downloading, percent(p.doneBytes, p.totalBytes), formatBytes(p.bytesPerSecond))
    DownloadPhase.VERIFYING.name -> stringResource(R.string.transfer_phase_dl_verifying)
    DownloadPhase.SAVING.name -> stringResource(R.string.transfer_phase_saving)
    else -> stringResource(R.string.transfer_phase_resolving)
}

private fun percent(done: Long, total: Long): Int = if (total <= 0) 0 else (done * 100 / total).toInt().coerceIn(0, 100)

/**
 * 一行:图标 + 名字 + 状态 + 操作;[progress] null 不画进度条,负数画不确定进度条。
 */
@Composable
private fun TransferRow(
    icon: ImageVector,
    category: FileCategory,
    title: String,
    subtitle: String,
    error: Boolean,
    progress: Float?,
    testTag: String,
    actions: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().testTag(testTag).padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = category.tint(), modifier = Modifier.size(32.dp))
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.End) { actions() }
        }
        if (progress != null) {
            Spacer(Modifier.height(8.dp))
            if (progress < 0) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(end = 12.dp))
            } else {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(end = 12.dp))
            }
        }
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, label: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, contentDescription = stringResource(label)) }
}
