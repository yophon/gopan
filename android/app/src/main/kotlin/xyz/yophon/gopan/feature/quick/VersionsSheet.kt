package xyz.yophon.gopan.feature.quick

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.quick.FileVersion
import xyz.yophon.gopan.core.ui.components.ConfirmDialog
import xyz.yophon.gopan.core.ui.format.formatBytes
import xyz.yophon.gopan.core.ui.format.formatRelativeTime
import xyz.yophon.gopan.core.ui.format.formatTime
import xyz.yophon.gopan.core.ui.rememberSystemActions
import xyz.yophon.gopan.feature.drive.UiText

/**
 * 文件的历史版本(doc/24):同名覆盖时留下的旧内容,最近换下来的在前。
 * 「恢复」把文件换回那一版(现在的内容也留成版本);下载交给浏览器(预签名地址,15 分钟有效)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionsSheet(
    node: Node,
    token: Long,
    onChanged: () -> Unit,
    onMessage: (UiText) -> Unit,
    onDismiss: () -> Unit,
    viewModel: VersionsViewModel = hiltViewModel(key = "versions:$token"),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnChanged by rememberUpdatedState(onChanged)
    val currentOnMessage by rememberUpdatedState(onMessage)
    val system = rememberSystemActions()
    LaunchedEffect(viewModel) { viewModel.load(node.id) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is VersionsEvent.Changed -> {
                    currentOnChanged()
                    currentOnMessage(event.message)
                }
                is VersionsEvent.Failed -> currentOnMessage(event.message)
            }
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.testTag("versions_sheet")) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.versions_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        node.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (state.items.isNotEmpty()) {
                    TextButton(onClick = viewModel::askPurge, enabled = !state.busy) {
                        Text(stringResource(R.string.versions_purge))
                    }
                }
            }
            state.policy?.let { (keep, days) ->
                Text(
                    if (keep > 0) stringResource(R.string.versions_policy, keep, days) else stringResource(R.string.versions_policy_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                )
            }
            HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
                when {
                    state.loading -> CircularProgressIndicator(modifier = Modifier.padding(24.dp))
                    state.error != null -> Text(
                        state.error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                    state.items.isEmpty() -> Text(
                        stringResource(R.string.versions_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                    else -> Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        state.items.forEach { v ->
                            VersionRow(
                                version = v,
                                busy = state.busy,
                                onRestore = { viewModel.askRestore(v) },
                                onDownload = { if (!system.openUrl(v.downloadUrl)) onMessage(UiText.Res(R.string.versions_no_browser)) },
                                onDelete = { viewModel.askDelete(v) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp).navigationBarsPadding())
        }
    }

    when (val c = state.confirm) {
        null -> Unit
        is VersionConfirm.Restore -> ConfirmDialog(
            title = stringResource(R.string.versions_restore_title),
            text = stringResource(R.string.versions_restore_text, formatTime(c.version.savedAt)),
            confirmText = stringResource(R.string.versions_restore),
            busy = state.busy,
            onConfirm = viewModel::confirm,
            onDismiss = viewModel::cancelConfirm,
        )
        is VersionConfirm.Delete -> ConfirmDialog(
            title = stringResource(R.string.versions_delete_title),
            text = stringResource(R.string.versions_delete_text),
            confirmText = stringResource(R.string.drive_action_delete),
            destructive = true,
            busy = state.busy,
            onConfirm = viewModel::confirm,
            onDismiss = viewModel::cancelConfirm,
        )
        VersionConfirm.Purge -> ConfirmDialog(
            title = stringResource(R.string.versions_purge_title),
            text = stringResource(R.string.versions_purge_text),
            confirmText = stringResource(R.string.versions_purge),
            destructive = true,
            busy = state.busy,
            onConfirm = viewModel::confirm,
            onDismiss = viewModel::cancelConfirm,
        )
    }
}

@Composable
private fun VersionRow(version: FileVersion, busy: Boolean, onRestore: () -> Unit, onDownload: () -> Unit, onDelete: () -> Unit) {
    ListItem(
        modifier = Modifier.testTag("version:${version.id}"),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(formatTime(version.savedAt)) },
        supportingContent = {
            Text(
                stringResource(R.string.versions_row_meta, formatBytes(version.size), formatRelativeTime(version.replacedAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDownload) {
                    Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.versions_download))
                }
                TextButton(onClick = onRestore, enabled = !busy) { Text(stringResource(R.string.versions_restore)) }
                IconButton(onClick = onDelete, enabled = !busy) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.versions_delete_title))
                }
            }
        },
    )
}
