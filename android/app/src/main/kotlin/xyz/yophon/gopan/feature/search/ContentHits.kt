package xyz.yophon.gopan.feature.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.ContentHit
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.TextRange
import xyz.yophon.gopan.core.data.highlightRanges
import xyz.yophon.gopan.core.ui.format.formatRelativeTime
import xyz.yophon.gopan.feature.drive.LoadMoreFooter
import xyz.yophon.gopan.feature.drive.LoadMoreTrigger
import xyz.yophon.gopan.feature.drive.NodeVisual
import xyz.yophon.gopan.feature.drive.isNearEnd

/*
 * 搜索页「内容」标签的结果(doc/20):一行一个文件——图标 / 缩略图、名字、命中页、带高亮的片段、修改时间、所在目录。
 */

/**
 * 片段转成带高亮的 AnnotatedString;区间按码点计,换算见 [highlightRanges](片段是用户文件里的文字,只当纯文本)。
 * 换行、缩进等连续空白压成一个空格:服务端在命中前留约 60 字,手机上只显示 [SNIPPET_MAX_LINES] 行,
 * 代码与文本里的空行、缩进会把命中挤出可见范围。压缩后高亮按新下标重新对齐。
 */
fun highlightSnippet(snippet: String, ranges: List<TextRange>, style: SpanStyle): AnnotatedString {
    val text = StringBuilder(snippet.length)
    // at[i] = 原片段第 i 个 UTF-16 单元在压缩后文字里的位置
    val at = IntArray(snippet.length + 1)
    var lastWasSpace = false
    for (i in snippet.indices) {
        at[i] = text.length
        val c = snippet[i]
        if (c.isWhitespace()) {
            if (!lastWasSpace) text.append(' ')
            lastWasSpace = true
        } else {
            text.append(c)
            lastWasSpace = false
        }
    }
    at[snippet.length] = text.length
    val spans = highlightRanges(snippet, ranges)
        .map { at[it.first] to at[it.last + 1] }
        .filter { (start, end) -> start < end }
    if (spans.isEmpty()) return AnnotatedString(text.toString())
    return AnnotatedString(text.toString(), spanStyles = spans.map { (start, end) -> AnnotatedString.Range(style, start, end) })
}

/** 片段最多显示几行:命中前约 60 字,窄屏上大约落在第 4 行 */
const val SNIPPET_MAX_LINES = 5

@Composable
internal fun ContentHitList(
    content: ContentResults,
    onClick: (ContentHit) -> Unit,
    onLongClick: (ContentHit) -> Unit,
    onLocate: (Node) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LoadMoreTrigger(
        itemCount = content.hits.size,
        isNearEnd = { listState.isNearEnd() },
        onLoadMore = onLoadMore,
    )
    LazyColumn(state = listState, modifier = modifier.testTag("search_content_list"), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(content.hits, key = { it.node.id }, contentType = { "hit" }) { hit ->
            ContentHitRow(hit, onClick = { onClick(hit) }, onLongClick = { onLongClick(hit) }, onLocate = { onLocate(hit.node) })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
        if (content.nextCursor != null || content.isLoadingMore || content.loadMoreError != null) {
            item(key = "footer", contentType = "footer") {
                LoadMoreFooter(isLoading = content.isLoadingMore, error = content.loadMoreError, onRetry = onLoadMore)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContentHitRow(hit: ContentHit, onClick: () -> Unit, onLongClick: () -> Unit, onLocate: () -> Unit) {
    val node = hit.node
    val markBg = MaterialTheme.colorScheme.tertiaryContainer
    val markFg = MaterialTheme.colorScheme.onTertiaryContainer
    val snippet = remember(hit.snippet, hit.highlights, markBg, markFg) {
        highlightSnippet(hit.snippet, hit.highlights, SpanStyle(background = markBg, color = markFg, fontWeight = FontWeight.Medium))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = stringResource(R.string.search_hit_more),
            )
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)
            .testTag("hit:${node.name}"),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        NodeVisual(node, modifier = Modifier.size(44.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    node.name,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f, fill = false),
                )
                hit.page?.let { PageTag(it) }
            }
            Text(
                snippet,
                maxLines = SNIPPET_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("hit_snippet"),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatRelativeTime(node.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onLocate, modifier = Modifier.testTag("hit_locate")) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.search_action_locate))
                }
            }
        }
    }
}

/** 「第 N 页」小标签:只是说明,不可点 */
@Composable
private fun PageTag(page: Int) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.testTag("hit_page"),
    ) {
        Text(
            stringResource(R.string.search_content_page, page),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** 关键词太短 / 本站没开内容搜索:结果区一行灰字(与网页端一致,不是错误页) */
@Composable
internal fun ContentNoticeText(notice: ContentNotice, modifier: Modifier = Modifier) {
    val text = when (notice) {
        ContentNotice.QUERY_TOO_SHORT -> stringResource(R.string.search_content_too_short)
        ContentNotice.DISABLED -> stringResource(R.string.search_content_disabled)
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 32.dp)
                .testTag("search_content_notice"),
        )
    }
}
