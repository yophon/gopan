package xyz.yophon.gopan.feature.drive

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.ui.components.ConfirmDialog
import xyz.yophon.gopan.core.ui.components.TextInputDialog
import xyz.yophon.gopan.core.ui.format.formatBytes
import xyz.yophon.gopan.core.ui.format.formatTime

/** 长按 / ⋮ 弹出的操作;文件页、搜索页、回收站各挑自己的子集 */
enum class NodeAction(@StringRes val label: Int, val icon: ImageVector, val destructive: Boolean = false) {
    RENAME(R.string.drive_action_rename, Icons.Outlined.DriveFileRenameOutline),
    MOVE(R.string.drive_action_move, Icons.AutoMirrored.Outlined.DriveFileMove),
    COPY(R.string.drive_action_copy, Icons.Outlined.ContentCopy),
    DELETE(R.string.drive_action_delete, Icons.Outlined.Delete, destructive = true),
    DETAILS(R.string.drive_action_details, Icons.Outlined.Info),
    RESTORE(R.string.trash_action_restore, Icons.Outlined.RestoreFromTrash),
    PURGE(R.string.trash_action_purge, Icons.Outlined.DeleteForever, destructive = true),
}

val DRIVE_ACTIONS = listOf(NodeAction.RENAME, NodeAction.MOVE, NodeAction.COPY, NodeAction.DELETE, NodeAction.DETAILS)
val TRASH_ACTIONS = listOf(NodeAction.RESTORE, NodeAction.PURGE, NodeAction.DETAILS)

/** 操作 BottomSheet:头部是节点本身,下面一行一个操作。选中后先收起动画再回调。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeActionSheet(
    node: Node,
    secondary: String,
    actions: List<NodeAction>,
    onAction: (NodeAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    fun pick(action: NodeAction) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onAction(action) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        ListItem(
            leadingContent = { NodeVisual(node, modifier = Modifier.size(44.dp)) },
            headlineContent = { Text(node.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        HorizontalDivider()
        actions.forEach { action ->
            val tint = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            ListItem(
                modifier = Modifier.clickable { pick(action) },
                leadingContent = { Icon(action.icon, contentDescription = null, tint = tint) },
                headlineContent = { Text(stringResource(action.label), color = tint) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        Spacer(Modifier.height(16.dp).navigationBarsPadding())
    }
}

/**
 * 详情 BottomSheet:名称、类型、大小、时间。文件有「打开」「下载」两个按钮,P1 只提示"即将推出"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeDetailsSheet(
    node: Node,
    onDismiss: () -> Unit,
    onOpen: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
) {
    val category = fileCategory(node)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                NodeVisual(node, modifier = Modifier.size(56.dp), iconSize = 36.dp)
                Text(node.name, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            DetailRow(R.string.drive_details_type, stringResource(category.labelRes))
            if (node.isFolder) {
                node.subtreeBytes?.let { bytes ->
                    val text = if (node.statsStale) stringResource(R.string.drive_size_approx, formatBytes(bytes)) else formatBytes(bytes)
                    DetailRow(R.string.drive_details_size, text)
                }
                node.subtreeCount?.let { DetailRow(R.string.drive_details_items, stringResource(R.string.drive_details_items_value, it)) }
            } else {
                DetailRow(R.string.drive_details_size, formatBytes(node.size ?: 0))
            }
            node.createdAt?.let { DetailRow(R.string.drive_details_created, formatTime(it)) }
            node.updatedAt?.let { DetailRow(R.string.drive_details_updated, formatTime(it)) }
            node.deletedAt?.let { DetailRow(R.string.drive_details_deleted, formatTime(it)) }
            if (!node.isFolder && (onOpen != null || onDownload != null)) {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (onOpen != null) {
                        FilledTonalButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.drive_action_open))
                        }
                    }
                    if (onDownload != null) {
                        FilledTonalButton(onClick = onDownload, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.Download, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.drive_action_download))
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp).navigationBarsPadding())
        }
    }
}

@Composable
private fun DetailRow(@StringRes label: Int, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 16.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
    }
}

/**
 * 新建文件夹 / 重命名共用的输入框:本地校验(非空、不含 / \ 等)+ 服务端错误(同名已存在)贴在输入框下。
 * [inputError] 只在当前文本等于当初提交的名字时显示,改一个字就消失。
 */
@Composable
fun NodeNameDialog(
    title: String,
    label: String,
    initialValue: String,
    confirmText: String,
    busy: Boolean,
    inputError: InputError?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val messages = mapOf(
        NameError.EMPTY to stringResource(R.string.drive_name_error_empty),
        NameError.ILLEGAL_CHAR to stringResource(R.string.drive_name_error_illegal),
        NameError.RESERVED to stringResource(R.string.drive_name_error_reserved),
        NameError.TOO_LONG to stringResource(R.string.drive_name_error_too_long),
    )
    val serverError = inputError?.let { it.name to it.message.resolve() }
    TextInputDialog(
        title = title,
        label = label,
        initialValue = initialValue,
        confirmText = confirmText,
        busy = busy,
        validate = { text ->
            validateNodeName(text)?.let { messages[it] }
                ?: serverError?.takeIf { it.first == text.trim() }?.second
        },
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * 文件页与搜索页共用的弹层渲染:操作表、详情、重命名、删除确认、移动/复制目标选择器。
 * 页面自己的弹层(新建文件夹等)在调用方先 when 掉,其余交给这里。
 */
@Composable
fun NodeDialogHost(
    dialog: NodeDialog?,
    busy: Boolean,
    inputError: InputError?,
    viewModel: NodeActionsViewModel,
) {
    val rootName = stringResource(R.string.drive_title_root)
    when (dialog) {
        null -> Unit
        is NodeDialog.Actions -> NodeActionSheet(
            node = dialog.node,
            secondary = nodeSecondaryText(dialog.node),
            actions = DRIVE_ACTIONS,
            onAction = { action ->
                val node = dialog.node
                when (action) {
                    NodeAction.RENAME -> viewModel.requestRename(node)
                    NodeAction.MOVE -> viewModel.requestMove(listOf(node.id))
                    NodeAction.COPY -> viewModel.requestCopy(listOf(node.id))
                    NodeAction.DELETE -> viewModel.requestDelete(listOf(node))
                    NodeAction.DETAILS -> viewModel.showDetails(node)
                    NodeAction.RESTORE, NodeAction.PURGE -> Unit
                }
            },
            onDismiss = viewModel::dismissDialog,
        )
        is NodeDialog.Details -> NodeDetailsSheet(
            node = dialog.node,
            onDismiss = viewModel::dismissDialog,
            onOpen = viewModel::notYet,
            onDownload = viewModel::notYet,
        )
        is NodeDialog.Rename -> NodeNameDialog(
            title = stringResource(R.string.drive_dialog_rename),
            label = stringResource(R.string.drive_dialog_rename_hint),
            initialValue = dialog.node.name,
            confirmText = stringResource(R.string.action_confirm),
            busy = busy,
            inputError = inputError,
            onConfirm = { viewModel.rename(dialog.node.id, it) },
            onDismiss = viewModel::dismissDialog,
        )
        is NodeDialog.ConfirmDelete -> ConfirmDialog(
            title = stringResource(R.string.drive_delete_title),
            text = dialog.singleName?.let { stringResource(R.string.drive_delete_confirm_one, it) }
                ?: stringResource(R.string.drive_delete_confirm_many, dialog.ids.size),
            confirmText = stringResource(R.string.drive_action_delete),
            destructive = true,
            busy = busy,
            onConfirm = { viewModel.confirmDelete(dialog.ids) },
            onDismiss = viewModel::dismissDialog,
        )
        is NodeDialog.Picker -> FolderPickerDialog(
            mode = dialog.mode,
            token = dialog.token,
            excludeIds = dialog.ids,
            busy = busy,
            onPick = { target ->
                when (dialog.mode) {
                    PickerMode.MOVE -> viewModel.moveTo(dialog.ids, target, rootName)
                    PickerMode.COPY -> viewModel.copyTo(dialog.ids, target, rootName)
                }
            },
            onDismiss = viewModel::dismissDialog,
        )
        else -> Unit
    }
}
