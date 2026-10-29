package xyz.yophon.gopan.feature.assistant

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.assistant.ChatImages
import xyz.yophon.gopan.core.assistant.RefNode
import xyz.yophon.gopan.core.ui.format.formatBytes

/**
 * 助手的一段回答(doc/35 六):Markdown 里的 [名字](gopan:<id>) 是能点的名字;只列文件的段落变成卡片,
 * 图片、视频直接显示缩略图。点文件夹进那个文件夹,点文件开预览。
 */
@Composable
fun AssistantReply(text: String, refs: Map<String, RefNode?>, onOpen: (RefNode) -> Unit) {
    val linkStyle = SpanStyle(color = MaterialTheme.colorScheme.primary, background = MaterialTheme.colorScheme.surfaceVariant)
    val goneStyle = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, textDecoration = TextDecoration.LineThrough)
    val gone = stringResource(R.string.assistant_ref_gone)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistantLogic.splitReply(text).forEach { block ->
            when (block) {
                is AssistantLogic.ReplyBlock.Md -> Text(
                    miniMarkdown(block.text) { ref ->
                        val node = refs[ref.id]
                        when {
                            refs.containsKey(ref.id) && node == null -> withStyle(goneStyle) { append(ref.name + gone) }
                            node != null -> withLink(LinkAnnotation.Clickable(ref.id, TextLinkStyles(linkStyle)) { onOpen(node) }) { append(" ${node.name} ") }
                            else -> withStyle(linkStyle) { append(" ${ref.name} ") }
                        }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                is AssistantLogic.ReplyBlock.Cards -> RefCards(block.refs, refs, onOpen)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RefCards(list: List<AssistantLogic.FileRef>, refs: Map<String, RefNode?>, onOpen: (RefNode) -> Unit) {
    val visual = list.mapNotNull { refs[it.id] }.filter { it.visual }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (visual.size == 1) {
            Tile(visual[0], large = true, onOpen = onOpen, modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp))
        } else if (visual.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), maxItemsInEachRow = 3) {
                visual.forEach { Tile(it, large = false, onOpen = onOpen, modifier = Modifier.weight(1f).aspectRatio(1f)) }
                // 最后一行补齐,格子大小一致
                repeat((3 - visual.size % 3) % 3) { Box(Modifier.weight(1f)) }
            }
        }
        list.forEach { ref ->
            val node = refs[ref.id]
            when {
                node != null && !node.visual -> RefRow(node, onOpen)
                node == null && refs.containsKey(ref.id) -> Text(ref.name + stringResource(R.string.assistant_ref_gone), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textDecoration = TextDecoration.LineThrough)
                node == null -> Text(ref.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Tile(node: RefNode, large: Boolean, onOpen: (RefNode) -> Unit, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable { onOpen(node) }, contentAlignment = Alignment.Center) {
        val url = if (large) node.largeUrl ?: node.thumbUrl else node.thumbUrl ?: node.largeUrl
        if (url != null) {
            AsyncImage(model = url, contentDescription = node.name, contentScale = if (large) ContentScale.Fit else ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(node.name, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(6.dp))
        }
        if (node.previewKind == "VIDEO") {
            Box(Modifier.size(36.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White)
            }
        }
    }
}

@Composable
private fun RefRow(node: RefNode, onOpen: (RefNode) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .clickable { onOpen(node) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(
            if (node.folder) Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.InsertDriveFile,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 10.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(node.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val where = node.path?.substringBeforeLast('/', "")?.ifEmpty { null } ?: stringResource(R.string.assistant_ref_root)
            Text(
                if (node.folder || node.size == null) where else "$where · ${formatBytes(node.size)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 一排图(data URL):用户消息里发的、输入框上待发的;点开看大图 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatImageRow(images: List<String>, size: Int, onRemove: ((Int) -> Unit)? = null) {
    var shown by remember { mutableStateOf<String?>(null) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        images.forEachIndexed { i, url ->
            val bitmap = remember(url) { ChatImages.decodeDataUrl(url)?.asImageBitmap() }
            Box(Modifier.size(size.dp)) {
                if (bitmap != null) {
                    Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).clickable { shown = url })
                }
                if (onRemove != null) {
                    Box(
                        Modifier.align(Alignment.TopEnd).size(22.dp).background(MaterialTheme.colorScheme.inverseSurface, CircleShape).clickable { onRemove(i) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("×", color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
    shown?.let { url ->
        val bitmap = remember(url) { ChatImages.decodeDataUrl(url)?.asImageBitmap() }
        Dialog(onDismissRequest = { shown = null }) {
            if (bitmap != null) Image(bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().clickable { shown = null })
        }
    }
}
