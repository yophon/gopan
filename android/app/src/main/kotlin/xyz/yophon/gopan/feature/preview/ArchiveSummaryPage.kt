package xyz.yophon.gopan.feature.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.ui.format.formatBytes
import xyz.yophon.gopan.feature.archive.ArchiveSummary

/**
 * 预览页上的压缩包:概要(格式、文件数、解压后大小、只列了一部分 / 有加密文件的提示)与三个入口:
 * 「浏览压缩包」「解压到这里」「解压到…」。还在读目录时只显示进度;打不开时给「用其他应用打开」「下载」。
 */
@Composable
internal fun ArchiveSummaryPage(
    summary: ArchiveSummary,
    busy: Boolean,
    onBrowse: () -> Unit,
    onExtractHere: () -> Unit,
    onExtractTo: () -> Unit,
    onOpenWith: () -> Unit,
    onDownload: () -> Unit,
) {
    when (summary) {
        ArchiveSummary.Pending -> Column(
            modifier = Modifier.fillMaxSize().padding(32.dp).testTag("preview_archive_pending"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Outlined.FolderZip, contentDescription = null, tint = OnPreviewDim, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator(color = OnPreview)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.archive_pending), color = OnPreview, style = MaterialTheme.typography.titleMedium)
        }
        is ArchiveSummary.Unavailable -> MessagePanel(
            Icons.Outlined.FolderZip,
            summary.message ?: stringResource(R.string.archive_unavailable),
            listOf(openWithAction(onOpenWith), downloadAction(onDownload)),
            modifier = Modifier.testTag("preview_archive_unavailable"),
        )
        is ArchiveSummary.Ready -> ReadySummary(summary, busy, onBrowse, onExtractHere, onExtractTo)
    }
}

@Composable
private fun ReadySummary(
    summary: ArchiveSummary.Ready,
    busy: Boolean,
    onBrowse: () -> Unit,
    onExtractHere: () -> Unit,
    onExtractTo: () -> Unit,
) {
    // 内容少时居中,横屏放不下时可滚动;上下让出顶栏与导航栏
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(start = 32.dp, end = 32.dp, top = topBarSpace(), bottom = bottomBarSpace() + 24.dp)
                .testTag("preview_archive"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Outlined.FolderZip, contentDescription = null, tint = OnPreviewDim, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(12.dp))
            Text(summary.format, color = OnPreview, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.archive_summary, summary.fileCount, formatBytes(summary.totalSize)),
                color = OnPreviewDim,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("preview_archive_summary"),
            )
            if (summary.truncated) Notice(Icons.Outlined.Info, stringResource(R.string.archive_truncated))
            if (summary.encrypted) Notice(Icons.Outlined.Lock, stringResource(R.string.archive_encrypted))
            Spacer(Modifier.height(24.dp))
            Column(modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = onBrowse, modifier = Modifier.fillMaxWidth().testTag("preview_archive_browse")) {
                    Icon(Icons.Outlined.FolderZip, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.archive_browse))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = onExtractHere,
                        enabled = !busy,
                        modifier = Modifier.weight(1f).testTag("preview_archive_extract_here"),
                    ) {
                        Icon(Icons.Outlined.Unarchive, contentDescription = null, modifier = Modifier.size(18.dp), tint = OnPreview)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.archive_extract_here), color = OnPreview, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onExtractTo,
                        enabled = !busy,
                        modifier = Modifier.weight(1f).testTag("preview_archive_extract_to"),
                    ) {
                        Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp), tint = OnPreview)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.archive_extract_to), color = OnPreview, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun Notice(icon: ImageVector, text: String) {
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = OnPreviewDim, modifier = Modifier.size(16.dp))
        Text(text, color = OnPreviewDim, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}
