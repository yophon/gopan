package xyz.yophon.gopan.feature.preview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.clips.Clip
import xyz.yophon.gopan.core.ui.rememberSystemActions
import xyz.yophon.gopan.feature.clips.ClipLogic
import xyz.yophon.gopan.feature.clips.MarkdownPageViewModel
import xyz.yophon.gopan.feature.clips.MarkdownView

/**
 * 预览里渲染着看的 .md(doc/23 五.2):预览页恒为深色,渲染页也要深色。是剪藏的话顶上一条来源:站点 · 打开原网页 · 标记已读。
 * 顶栏的「看源文件」切回文本预览(PreviewPage 负责)。每个文件一个 ViewModel(按节点 id 区分)。
 */
@Composable
internal fun MarkdownPreviewPage(nodeId: String) {
    val viewModel: MarkdownPageViewModel = hiltViewModel(key = "markdown-$nodeId")
    LaunchedEffect(viewModel, nodeId) { viewModel.bind(nodeId, dark = true) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(top = topBarSpace(), bottom = bottomBarSpace()).testTag("preview_markdown")) {
        state.clip?.let { clip -> ClipSourceBar(clip, marking = state.marking, onMarkRead = viewModel::markRead) }
        val error = state.error
        if (error != null) {
            ErrorPage(error, onRetry = viewModel::retry)
        } else {
            MarkdownView(
                url = state.url,
                baseUrl = viewModel.baseUrl,
                retoken = viewModel::retoken,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                background = Color.Black,
            )
        }
    }
}

/** 剪藏的来源条:剪藏自 站点 · 打开原网页 · 标记已读 */
@Composable
private fun ClipSourceBar(clip: Clip, marking: Boolean, onMarkRead: () -> Unit) {
    val actions = rememberSystemActions()
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().testTag("preview_clip_source")) {
        Row(modifier = Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ContentCut, contentDescription = null, tint = OnPreviewDim, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.md_clip_from, ClipLogic.siteLabel(clip)),
                color = OnPreview,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (clip.url.isNotBlank()) {
                TextButton(onClick = { actions.openUrl(clip.url) }, modifier = Modifier.testTag("preview_clip_open")) {
                    Text(stringResource(R.string.clips_open_original))
                }
            }
            when {
                marking -> CircularProgressIndicator(modifier = Modifier.padding(horizontal = 12.dp).size(16.dp), strokeWidth = 2.dp)
                clip.unread -> TextButton(onClick = onMarkRead, modifier = Modifier.testTag("preview_clip_read")) {
                    Text(stringResource(R.string.clips_mark_read))
                }
                else -> Text(
                    stringResource(R.string.md_read),
                    color = OnPreviewDim,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
    }
}
