package xyz.yophon.gopan.feature.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.PreviewKind
import xyz.yophon.gopan.core.data.chat.ChatMessage
import xyz.yophon.gopan.core.transfer.TransferState
import xyz.yophon.gopan.core.transfer.UploadPhase
import xyz.yophon.gopan.core.ui.format.formatBytes
import xyz.yophon.gopan.feature.drive.NodeVisual
import xyz.yophon.gopan.feature.drive.fileCategory
import xyz.yophon.gopan.feature.drive.icon
import xyz.yophon.gopan.feature.drive.tint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME = DateTimeFormatter.ofPattern("HH:mm")

fun timeOf(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String = TIME.format(Instant.ofEpochMilli(millis).atZone(zone))

/** 每条消息的外壳:左对齐、长按出操作面板、定位时高亮 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageShell(
    highlighted: Boolean,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    testTag: String,
    content: @Composable () -> Unit,
) {
    val bg = if (highlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag(testTag),
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth(0.86f)
                .clip(RoundedCornerShape(14.dp))
                .combinedClickable(
                    enabled = onClick != null || onLongClick != null,
                    onClick = { onClick?.invoke() },
                    onLongClick = onLongClick,
                ),
        ) { content() }
    }
}

/** 行尾:时间、来源标签、(搜索时)定位 */
@Composable
fun MessageFooter(millis: Long, source: String?, onLocate: (() -> Unit)?, extra: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 2.dp)) {
        Text(timeOf(millis), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (extra != null) {
            Text(" · $extra", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        source?.let {
            Spacer(Modifier.width(6.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        if (onLocate != null) {
            TextButton(onClick = onLocate) { Text(stringResource(R.string.chat_locate), style = MaterialTheme.typography.labelMedium) }
        }
    }
}

/** 文本气泡:保留换行、识别 http(s) 链接、超过 8 行折叠 */
@Composable
fun TextBubble(body: String, key: String, failed: Boolean = false, pending: Boolean = false) {
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    var overflow by remember(key) { mutableStateOf(false) }
    val linkColor = MaterialTheme.colorScheme.primary
    val text = remember(body, linkColor) { linkify(body, linkColor) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .alpha(if (pending && !failed) 0.6f else 1f)
            .then(if (failed) Modifier.border(1.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(14.dp)) else Modifier),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = if (expanded) Int.MAX_VALUE else ChatLogic.COLLAPSE_LINES,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) overflow = it.hasVisualOverflow },
            )
            if (overflow || expanded) {
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(top = 2.dp)) {
                    Text(stringResource(if (expanded) R.string.chat_collapse else R.string.chat_expand))
                }
            }
        }
    }
}

private fun linkify(body: String, color: Color): AnnotatedString {
    val links = ChatLogic.findLinks(body)
    if (links.isEmpty()) return AnnotatedString(body)
    return buildAnnotatedString {
        var pos = 0
        for (r in links) {
            append(body.substring(pos, r.first))
            val url = body.substring(r.first, r.last + 1)
            pushLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline))))
            append(url)
            pop()
            pos = r.last + 1
        }
        append(body.substring(pos))
    }
}

/** 文件 / 文件夹 / 文件已不存在 卡片 */
@Composable
fun FileCard(message: ChatMessage, onDownload: (Node) -> Unit) {
    val node = message.node
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            if (node == null) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(stringResource(R.string.chat_gone_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.chat_gone_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val category = fileCategory(node)
                Icon(
                    if (node.isFolder) Icons.Outlined.Folder else category.icon,
                    contentDescription = null,
                    tint = category.tint(),
                    modifier = Modifier.size(36.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(node.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
                    Text(
                        if (node.isFolder) stringResource(R.string.chat_folder) else formatBytes(node.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!node.isFolder) {
                    IconButton(onClick = { onDownload(node) }) {
                        Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.chat_download))
                    }
                }
            }
        }
    }
    if (message.node != null && message.body.isNotEmpty()) {
        Text(message.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
    }
}

/** 单张图片 / 视频:大缩略图 + 名字 */
@Composable
fun MediaCard(node: Node, onDownload: (Node) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp)) {
        Column {
            Box(contentAlignment = Alignment.Center) {
                NodeVisual(node, modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f), iconSize = 48.dp, shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                if (node.preview.kind == PreviewKind.VIDEO) VideoBadge()
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = 4.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(node.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    Text(formatBytes(node.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onDownload(node) }) {
                    Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.chat_download))
                }
            }
        }
    }
}

@Composable
private fun VideoBadge() {
    Icon(
        Icons.Outlined.PlayCircle,
        contentDescription = null,
        tint = Color.White,
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.35f)),
    )
}

/** 多图网格:2 张两列,3 张及以上三列;每格正方形裁切,单独点开 / 长按 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGrid(
    messages: List<ChatMessage>,
    highlightId: String?,
    onOpen: (ChatMessage) -> Unit,
    onLongPress: (ChatMessage) -> Unit,
) {
    val columns = if (messages.size == 2) 2 else 3
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        messages.chunked(columns).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                rowItems.forEach { m ->
                    val node = m.node ?: return@forEach
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .then(if (m.id == highlightId) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier)
                            .combinedClickable(onClick = { onOpen(m) }, onLongClick = { onLongPress(m) })
                            .testTag("chat_grid_cell"),
                    ) {
                        NodeVisual(node, modifier = Modifier.fillMaxWidth().aspectRatio(1f), iconSize = 32.dp, shape = RoundedCornerShape(8.dp))
                        if (node.preview.kind == PreviewKind.VIDEO) VideoBadge()
                    }
                }
                // 最后一行不满时补空位,格子大小一致
                repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 上传进度卡片:名字、进度、状态;失败可重试 / 移除,进行中可取消 */
@Composable
fun UploadCardView(card: UploadCard, onRetry: () -> Unit, onCancel: () -> Unit, onRemove: () -> Unit) {
    val t = card.task
    val p = card.progress
    val failed = t.state == TransferState.FAILED
    val category = fileCategory(xyz.yophon.gopan.core.data.NodeKind.FILE, t.name, t.mime)
    val status = when (t.state) {
        TransferState.QUEUED -> t.errorMessage?.takeIf { t.nextAttemptAt > System.currentTimeMillis() }?.let { "等待重试 · $it" }
            ?: stringResource(R.string.chat_upload_queued)
        TransferState.PAUSED -> stringResource(R.string.chat_upload_paused)
        TransferState.FAILED -> t.errorMessage ?: stringResource(R.string.transfer_state_failed)
        TransferState.DONE -> stringResource(R.string.transfer_phase_verifying)
        else -> when (p?.phase) {
            UploadPhase.HASHING.name -> stringResource(R.string.transfer_phase_hashing, pct(p.doneBytes, p.totalBytes))
            UploadPhase.UPLOADING.name -> stringResource(R.string.transfer_phase_uploading, pct(p.doneBytes, p.totalBytes), formatBytes(p.bytesPerSecond))
            UploadPhase.VERIFYING.name -> stringResource(R.string.transfer_phase_verifying)
            else -> stringResource(R.string.transfer_phase_preparing)
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        modifier = if (failed) Modifier.border(1.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(14.dp)) else Modifier,
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(category.icon, contentDescription = null, tint = category.tint(), modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(t.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    Text(
                        "${formatBytes(t.size.takeIf { it >= 0 })} · $status",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (failed) {
                    IconButton(onClick = onRetry) { Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.chat_retry)) }
                    IconButton(onClick = onRemove) { Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.chat_remove)) }
                } else if (t.state != TransferState.DONE) {
                    IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_cancel)) }
                }
            }
            if (t.state == TransferState.RUNNING || t.state == TransferState.DONE) {
                Spacer(Modifier.size(6.dp))
                val fraction = p?.takeIf { it.phase == UploadPhase.UPLOADING.name || it.phase == UploadPhase.HASHING.name }?.fraction
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(end = 8.dp))
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(end = 8.dp))
                }
            }
        }
    }
}

private fun pct(done: Long, total: Long): Int = if (total <= 0) 0 else (done * 100 / total).toInt().coerceIn(0, 100)
