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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFolderUpload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Visibility
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.archive.ExtractRequest
import xyz.yophon.gopan.core.ui.components.ConfirmDialog
import xyz.yophon.gopan.core.ui.components.TextInputDialog
import xyz.yophon.gopan.core.ui.format.formatBytes
import xyz.yophon.gopan.core.ui.format.formatTime
import xyz.yophon.gopan.feature.archive.ExtractEvent
import xyz.yophon.gopan.feature.archive.ExtractPickerHost
import xyz.yophon.gopan.feature.archive.ExtractViewModel
import xyz.yophon.gopan.feature.archive.isArchiveNode
import xyz.yophon.gopan.feature.collect.FileRequestDialog
import xyz.yophon.gopan.feature.collect.FileRequestTarget
import xyz.yophon.gopan.feature.links.CopyLinkHost
import xyz.yophon.gopan.feature.links.CopyLinkViewModel
import xyz.yophon.gopan.feature.shares.ShareCreateDialog

/** 长按 / ⋮ 弹出的操作;文件页、搜索页、回收站各挑自己的子集 */
enum class NodeAction(@StringRes val label: Int, val icon: ImageVector, val destructive: Boolean = false) {
    OPEN(R.string.drive_action_open, Icons.AutoMirrored.Outlined.OpenInNew),
    DOWNLOAD(R.string.drive_action_download, Icons.Outlined.Download),
    PACK(R.string.drive_action_pack, Icons.Outlined.FolderZip),
    SEND_TO_CHAT(R.string.drive_action_send_to_chat, Icons.AutoMirrored.Outlined.Send),
    SHARE(R.string.drive_action_share, Icons.Outlined.Share),
    /** 收藏(doc/24):已收藏的显示「取消收藏」,见 [labelFor] */
    STAR(R.string.drive_action_star, Icons.Outlined.StarOutline),
    /** 离线可用(doc/26):已加过的显示「取消离线可用」 */
    OFFLINE(R.string.offline_action_pin, Icons.Outlined.OfflinePin),
    /** 直链(doc/22 四):只对文件,本站关了直链时不出现 */
    COPY_LINK(R.string.drive_action_copy_link, Icons.Outlined.Link),
    COLLECT(R.string.drive_action_collect, Icons.Outlined.DriveFolderUpload),
    EXTRACT_HERE(R.string.archive_extract_here, Icons.Outlined.Unarchive),
    EXTRACT_TO(R.string.archive_extract_to, Icons.Outlined.CreateNewFolder),
    RENAME(R.string.drive_action_rename, Icons.Outlined.DriveFileRenameOutline),
    MOVE(R.string.drive_action_move, Icons.AutoMirrored.Outlined.DriveFileMove),
    COPY(R.string.drive_action_copy, Icons.Outlined.ContentCopy),
    DELETE(R.string.drive_action_delete, Icons.Outlined.Delete, destructive = true),
    DETAILS(R.string.drive_action_details, Icons.Outlined.Info),
    /** 历史版本(doc/24):只对文件 */
    VERSIONS(R.string.drive_action_versions, Icons.Outlined.History),
    /** 「最近」页:从最近里拿掉(文件本身不动) */
    FORGET(R.string.quick_action_forget, Icons.Outlined.RemoveCircleOutline),
    RESTORE(R.string.trash_action_restore, Icons.Outlined.RestoreFromTrash),
    PURGE(R.string.trash_action_purge, Icons.Outlined.DeleteForever, destructive = true),
    LOCATE(R.string.search_action_locate, Icons.Outlined.FolderOpen),
}

val DRIVE_ACTIONS = listOf(
    NodeAction.OPEN,
    NodeAction.DOWNLOAD,
    NodeAction.PACK,
    NodeAction.SHARE,
    NodeAction.STAR,
    NodeAction.OFFLINE,
    NodeAction.COPY_LINK,
    NodeAction.COLLECT,
    NodeAction.EXTRACT_HERE,
    NodeAction.EXTRACT_TO,
    NodeAction.SEND_TO_CHAT,
    NodeAction.RENAME,
    NodeAction.MOVE,
    NodeAction.COPY,
    NodeAction.VERSIONS,
    NodeAction.DELETE,
    NodeAction.DETAILS,
)

/** 收藏 / 取消收藏随节点变,离线可用随 [offline](手动加过)变 */
fun NodeAction.labelFor(node: Node, offline: Boolean = false): Int = when {
    this == NodeAction.STAR && node.starred -> R.string.drive_action_unstar
    this == NodeAction.OFFLINE && offline -> R.string.offline_action_unpin
    else -> label
}

fun NodeAction.iconFor(node: Node, offline: Boolean = false): ImageVector = when {
    this == NodeAction.STAR && node.starred -> Icons.Outlined.Star
    this == NodeAction.OFFLINE && offline -> Icons.Filled.OfflinePin
    else -> icon
}

/** 这个操作对这个节点有没有意义:打开、复制直链只对文件,打包、收集文件只对文件夹,解压只对压缩包 */
fun NodeAction.appliesTo(node: Node): Boolean = when (this) {
    NodeAction.OPEN, NodeAction.COPY_LINK, NodeAction.VERSIONS -> !node.isFolder
    NodeAction.PACK, NodeAction.COLLECT -> node.isFolder
    NodeAction.EXTRACT_HERE, NodeAction.EXTRACT_TO -> isArchiveNode(node)
    else -> true
}

/** 搜索结果多一项「所在目录」(与网页端一致) */
val SEARCH_ACTIONS = listOf(NodeAction.LOCATE) + DRIVE_ACTIONS
val TRASH_ACTIONS = listOf(NodeAction.RESTORE, NodeAction.PURGE, NodeAction.DETAILS)

/**
 * 操作 BottomSheet:头部是节点本身,下面一行一个操作。
 * 选中后先收起动画,再关掉弹层(onDismiss),最后回调操作 —— 若操作本身不换弹层(例如回收站的「还原」),
 * 隐藏的 ModalBottomSheet 会留在组合里,它的全屏透明窗口会吞掉整页点击。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeActionSheet(
    node: Node,
    secondary: String,
    actions: List<NodeAction>,
    onAction: (NodeAction) -> Unit,
    onDismiss: () -> Unit,
) {
    // 一次展开到底:操作多(文件页 9 项),半展开时下面几项(删除、详情)要拖上来才看得到
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun pick(action: NodeAction) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            onAction(action)
        }
    }
    val offline = node.id in LocalOfflineMarks.current.pinned
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        ListItem(
            leadingContent = { NodeVisual(node, modifier = Modifier.size(44.dp)) },
            headlineContent = { Text(node.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        HorizontalDivider()
        // 操作多(文件页 9 项),小屏放不下:操作区单独滚动,头部固定
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            actions.filter { it.appliesTo(node) }.forEach { action ->
                val tint = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                ListItem(
                    modifier = Modifier.clickable { pick(action) },
                    leadingContent = { Icon(action.iconFor(node, offline), contentDescription = null, tint = tint) },
                    headlineContent = { Text(stringResource(action.labelFor(node, offline)), color = tint) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            Spacer(Modifier.height(16.dp).navigationBarsPadding())
        }
    }
}

/**
 * 详情 BottomSheet:名称、类型、大小、时间;文件有「预览」「下载」两个按钮。
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
                            Icon(Icons.Outlined.Visibility, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.drive_action_preview))
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
    actions: List<NodeAction> = DRIVE_ACTIONS,
    onLocate: ((Node) -> Unit)? = null,
    onForget: ((Node) -> Unit)? = null,
) {
    val rootName = stringResource(R.string.drive_title_root)
    // 「解压到这里」「解压到…」:提交结果借页面的 Snackbar 提示(进度在「传输」的后台任务里)
    val extractor: ExtractViewModel = hiltViewModel()
    LaunchedEffect(extractor) {
        extractor.events.collect { event ->
            when (event) {
                is ExtractEvent.Started -> viewModel.message(UiText.Res(R.string.archive_msg_extract_started))
                is ExtractEvent.Failed -> viewModel.message(UiText.Plain(event.message))
            }
        }
    }
    ExtractPickerHost(extractor.extract)
    // 「复制直链」:选格式的对话框与提示(多选时文件页的选择栏也交给同一个 ViewModel)
    val links: CopyLinkViewModel = hiltViewModel()
    val linkState by links.state.collectAsStateWithLifecycle()
    val offlinePinned = LocalOfflineMarks.current.pinned
    CopyLinkHost(links, onMessage = viewModel::message)
    when (dialog) {
        null -> Unit
        is NodeDialog.Actions -> NodeActionSheet(
            node = dialog.node,
            secondary = nodeSecondaryText(dialog.node),
            actions = if (linkState.enabled) actions else actions - NodeAction.COPY_LINK,
            onAction = { action ->
                val node = dialog.node
                when (action) {
                    NodeAction.OPEN -> viewModel.open(node)
                    NodeAction.DOWNLOAD -> viewModel.download(listOf(node))
                    NodeAction.PACK -> viewModel.pack(listOf(node))
                    NodeAction.SEND_TO_CHAT -> viewModel.sendToChat(node)
                    NodeAction.SHARE -> viewModel.requestShare(node)
                    NodeAction.STAR -> viewModel.toggleStar(listOf(node))
                    NodeAction.OFFLINE -> viewModel.toggleOffline(listOf(node), offlinePinned)
                    NodeAction.VERSIONS -> viewModel.showVersions(node)
                    NodeAction.FORGET -> onForget?.invoke(node)
                    NodeAction.COPY_LINK -> links.copy(listOf(node))
                    NodeAction.COLLECT -> viewModel.requestCollect(node)
                    // 解到压缩包所在的文件夹(搜索结果也是压缩包旁边,不是当前页面)
                    NodeAction.EXTRACT_HERE -> extractor.extract.submit(ExtractRequest(nodeId = node.id, parentId = node.parentId))
                    NodeAction.EXTRACT_TO -> extractor.extract.pick(ExtractRequest(nodeId = node.id))
                    NodeAction.RENAME -> viewModel.requestRename(node)
                    NodeAction.MOVE -> viewModel.requestMove(listOf(node.id))
                    NodeAction.COPY -> viewModel.requestCopy(listOf(node.id))
                    NodeAction.DELETE -> viewModel.requestDelete(listOf(node))
                    NodeAction.DETAILS -> viewModel.showDetails(node)
                    NodeAction.LOCATE -> onLocate?.invoke(node)
                    NodeAction.RESTORE, NodeAction.PURGE -> Unit
                }
            },
            onDismiss = viewModel::dismissDialog,
        )
        is NodeDialog.Details -> NodeDetailsSheet(
            node = dialog.node,
            onDismiss = viewModel::dismissDialog,
            onOpen = { viewModel.preview(dialog.node) },
            onDownload = { viewModel.download(listOf(dialog.node)) },
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
        is NodeDialog.ConfirmOffline -> ConfirmOfflineDialog(
            dialog = dialog,
            onConfirm = { viewModel.confirmOffline(dialog.nodes) },
            onDismiss = viewModel::dismissDialog,
        )
        is NodeDialog.Share -> ShareCreateDialog(
            node = dialog.node,
            instanceKey = dialog.token.toString(),
            onDismiss = viewModel::dismissDialog,
            onMessage = { viewModel.message(UiText.Plain(it)) },
        )
        is NodeDialog.Collect -> FileRequestDialog(
            target = FileRequestTarget.Create(folderId = dialog.node.id, folderName = dialog.node.name),
            instanceKey = dialog.token.toString(),
            onDismiss = viewModel::dismissDialog,
            onMessage = { viewModel.message(UiText.Plain(it)) },
        )
        is NodeDialog.Picker -> FolderPickerDialog(
            mode = dialog.mode,
            token = dialog.token,
            excludeIds = dialog.ids,
            busy = busy,
            error = viewModel.pickerError.collectAsStateWithLifecycle().value?.resolve(),
            onPick = { target ->
                when (dialog.mode) {
                    PickerMode.MOVE -> viewModel.moveTo(dialog.ids, target, rootName)
                    PickerMode.COPY -> viewModel.copyTo(dialog.ids, target, rootName)
                    // 解压的选择器由 ExtractPickerHost 画、存分享的由传输助手画,不走这里
                    PickerMode.EXTRACT, PickerMode.UPLOAD -> Unit
                }
            },
            onDismiss = viewModel::dismissDialog,
        )
        is NodeDialog.Versions -> xyz.yophon.gopan.feature.quick.VersionsSheet(
            node = dialog.node,
            token = dialog.token,
            onChanged = viewModel::onVersionsChanged,
            onMessage = viewModel::message,
            onDismiss = viewModel::dismissDialog,
        )
        else -> Unit
    }
}

/** 加入离线可用前的确认(doc/26):写明大小;很大、在用移动数据时多一句提醒 */
@Composable
private fun ConfirmOfflineDialog(dialog: NodeDialog.ConfirmOffline, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val size = if (dialog.bytes > 0) formatBytes(dialog.bytes) else stringResource(R.string.offline_size_unknown)
    val single = dialog.nodes.singleOrNull()
    val text = listOfNotNull(
        if (single != null) stringResource(R.string.offline_confirm_one, single.name, size) else stringResource(R.string.offline_confirm_many, dialog.nodes.size, size),
        if (dialog.bytes >= NodeActionsViewModel.OFFLINE_LARGE_BYTES) stringResource(R.string.offline_confirm_large, size) else null,
        if (dialog.metered) stringResource(R.string.offline_confirm_metered) else null,
    ).joinToString("\n\n")
    ConfirmDialog(
        title = stringResource(R.string.offline_confirm_title),
        text = text,
        confirmText = stringResource(R.string.offline_confirm_action),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}
