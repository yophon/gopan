package xyz.yophon.gopan.feature.drive

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.ui.components.FolderGlyph
import xyz.yophon.gopan.core.ui.theme.LocalGopanColors
import xyz.yophon.gopan.core.ui.format.formatBytes
import xyz.yophon.gopan.core.ui.format.formatRelativeTime

/*
 * 节点的行 / 卡片 / 图标:文件页、搜索页、回收站、目录选择器共用。
 * 列表项数据是 @Immutable 的 Node,配合 LazyColumn 的 key = id 做到只重组变动的行。
 */

val FileCategory.icon: ImageVector
    get() = when (this) {
        FileCategory.FOLDER -> Icons.Outlined.Folder
        FileCategory.IMAGE -> Icons.Outlined.Image
        FileCategory.VIDEO -> Icons.Outlined.Movie
        FileCategory.AUDIO -> Icons.Outlined.AudioFile
        FileCategory.PDF -> Icons.Outlined.PictureAsPdf
        FileCategory.DOCUMENT -> Icons.Outlined.Description
        FileCategory.SPREADSHEET -> Icons.Outlined.TableChart
        FileCategory.PRESENTATION -> Icons.Outlined.Slideshow
        FileCategory.ARCHIVE -> Icons.Outlined.FolderZip
        FileCategory.TEXT -> Icons.AutoMirrored.Outlined.TextSnippet
        FileCategory.OTHER -> Icons.AutoMirrored.Outlined.InsertDriveFile
    }

@get:StringRes
val FileCategory.labelRes: Int
    get() = when (this) {
        FileCategory.FOLDER -> R.string.drive_type_folder
        FileCategory.IMAGE -> R.string.drive_type_image
        FileCategory.VIDEO -> R.string.drive_type_video
        FileCategory.AUDIO -> R.string.drive_type_audio
        FileCategory.PDF -> R.string.drive_type_pdf
        FileCategory.DOCUMENT -> R.string.drive_type_document
        FileCategory.SPREADSHEET -> R.string.drive_type_spreadsheet
        FileCategory.PRESENTATION -> R.string.drive_type_presentation
        FileCategory.ARCHIVE -> R.string.drive_type_archive
        FileCategory.TEXT -> R.string.drive_type_text
        FileCategory.OTHER -> R.string.drive_type_other
    }

/** 文件类型图标的颜色:墨黑配色下一律暖灰(和网页版一样),青绿与跟随壁纸按类型上色 */
@Composable
fun FileCategory.tint(): Color {
    val colors = LocalGopanColors.current
    return when (this) {
        // 线框文件夹图标(少数没换成 FolderGlyph 的地方)要有对比度,用主色
        FileCategory.FOLDER -> MaterialTheme.colorScheme.primary
        FileCategory.IMAGE, FileCategory.VIDEO, FileCategory.AUDIO -> colors.fileMedia
        FileCategory.PDF -> colors.filePdf
        else -> colors.fileOther
    }
}

/**
 * 图标,图片/视频有缩略图时盖上缩略图(加载中与失败都露出图标)。
 * 缓存 key 用 id + updatedAt:预签名 URL 15 分钟一换,不能拿 URL 当 key。
 * 离线回退缓存时没有 URL(预签名地址不落盘),只从磁盘缓存按 key 读,读不到就显示图标,不发网络请求。
 * [onThumbError]:在线加载失败时回调(多半是列表放久了、预签名过期),由页面决定要不要静默刷新列表。
 */
@Composable
fun NodeVisual(
    node: Node,
    modifier: Modifier = Modifier,
    iconSize: Dp = 28.dp,
    shape: Shape = RoundedCornerShape(8.dp),
    onThumbError: (() -> Unit)? = null,
) {
    val category = fileCategory(node)
    var thumbShown by remember(node.imageCacheKey) { mutableStateOf(false) }
    Box(modifier = modifier.clip(shape), contentAlignment = Alignment.Center) {
        if (!thumbShown) {
            if (category == FileCategory.FOLDER) {
                // 文件夹:实心两片,与网页版、发布页的文件夹记号一致
                val label = stringResource(category.labelRes)
                FolderGlyph(Modifier.size(iconSize * 1.15f).semantics { contentDescription = label })
            } else {
                Icon(
                    imageVector = category.icon,
                    contentDescription = stringResource(category.labelRes),
                    tint = category.tint(),
                    modifier = Modifier.size(iconSize),
                )
            }
        }
        if (node.hasThumb) {
            val url = node.preview.thumbUrl
            val request = ImageRequest.Builder(LocalPlatformContext.current)
                .data(url ?: offlineThumbPlaceholder(node))
                .memoryCacheKey(node.imageCacheKey)
                .diskCacheKey(node.imageCacheKey)
                .apply { if (url == null) networkCachePolicy(CachePolicy.DISABLED) }
                .crossfade(true)
                .build()
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { thumbShown = true },
                onError = {
                    thumbShown = false
                    if (url != null) onThumbError?.invoke()
                },
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/** 离线时给 Coil 的占位地址:永远不会真的请求(网络策略关闭),只用来走磁盘缓存查找 */
private fun offlineThumbPlaceholder(node: Node): String = "https://thumbnail.cache.invalid/${node.imageCacheKey}"

/** 列表行的第二行:文件 = 大小 · 时间;文件夹 = 时间 · (约)子树体积 */
@Composable
fun nodeSecondaryText(node: Node): String {
    val time = formatRelativeTime(node.updatedAt)
    if (!node.isFolder) return "${formatBytes(node.size ?: 0)} · $time"
    val bytes = node.subtreeBytes ?: return time
    val size = if (node.statsStale) stringResource(R.string.drive_size_approx, formatBytes(bytes)) else formatBytes(bytes)
    return "$time · $size"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NodeRow(
    node: Node,
    secondary: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onMore: (() -> Unit)? = null,
    onThumbError: (() -> Unit)? = null,
    /** 第二行的强调色(回收站临期条目用);null 用默认的次要文字色 */
    secondaryColor: Color? = null,
) {
    val container = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    ListItem(
        modifier = modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .selectionSemantics(selectionMode, selected)
            .testTag("node:${node.name}"),
        colors = ListItemDefaults.colors(containerColor = container),
        leadingContent = { NodeVisual(node, modifier = Modifier.size(44.dp), onThumbError = onThumbError) },
        headlineContent = {
            Text(node.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
        },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (node.starred) StarMark(Modifier.padding(end = 4.dp))
                Text(
                    secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = when {
            selectionMode -> {
                { Checkbox(checked = selected, onCheckedChange = { onClick() }) }
            }
            onMore != null -> {
                {
                    IconButton(onClick = onMore) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.drive_action_more))
                    }
                }
            }
            else -> null
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NodeCard(
    node: Node,
    secondary: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onMore: (() -> Unit)? = null,
    onThumbError: (() -> Unit)? = null,
) {
    val shape = MaterialTheme.shapes.medium
    Card(
        modifier = modifier
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .selectionSemantics(selectionMode, selected)
            .testTag("node:${node.name}"),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            NodeVisual(node, modifier = Modifier.fillMaxSize(), iconSize = 48.dp, shape = RectangleShape, onThumbError = onThumbError)
            if (node.starred) StarMark(Modifier.align(Alignment.BottomStart).padding(8.dp), size = 18.dp)
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.align(Alignment.TopStart),
                )
            } else if (onMore != null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                ) {
                    IconButton(onClick = onMore, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.drive_action_more))
                    }
                }
            }
        }
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                node.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 收藏的星标(doc/24):琥珀色,深浅色底上都看得清 */
val StarTint = Color(0xFFE0A030)

@Composable
fun StarMark(modifier: Modifier = Modifier, size: Dp = 14.dp) {
    Icon(
        Icons.Filled.Star,
        contentDescription = stringResource(R.string.drive_starred),
        tint = StarTint,
        modifier = modifier.size(size),
    )
}

/** 列表末尾:加载中转圈,失败给重试;放在 LazyColumn 最后一项 */
@Composable
fun LoadMoreFooter(
    isLoading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            isLoading -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            error != null -> {
                Text(
                    stringResource(R.string.drive_load_more_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}

/** 多选模式下让读屏知道这是可勾选项及其状态("已选中 / 未选中") */
@Composable
private fun Modifier.selectionSemantics(selectionMode: Boolean, selected: Boolean): Modifier {
    if (!selectionMode) return this
    val state = stringResource(if (selected) R.string.drive_a11y_selected else R.string.drive_a11y_not_selected)
    return semantics {
        role = Role.Checkbox
        stateDescription = state
    }
}
