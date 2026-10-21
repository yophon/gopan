package xyz.yophon.gopan.feature.archive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Size
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.prefs.ThemeMode
import xyz.yophon.gopan.core.ui.components.ZoomState
import xyz.yophon.gopan.core.ui.components.doubleTapZoom
import xyz.yophon.gopan.core.ui.components.rememberZoomState
import xyz.yophon.gopan.core.ui.components.zoomable
import xyz.yophon.gopan.core.ui.format.formatBytes
import xyz.yophon.gopan.core.ui.theme.GopanTheme
import xyz.yophon.gopan.feature.preview.ErrorPage
import xyz.yophon.gopan.feature.preview.MessagePanel
import xyz.yophon.gopan.feature.preview.OnPreview
import xyz.yophon.gopan.feature.preview.OnPreviewDim
import xyz.yophon.gopan.feature.preview.PanelAction
import xyz.yophon.gopan.feature.preview.PdfDocument
import xyz.yophon.gopan.feature.preview.PdfPage
import xyz.yophon.gopan.feature.preview.PdfPageViewer
import xyz.yophon.gopan.feature.preview.PreviewSystemBars
import xyz.yophon.gopan.feature.preview.TextPage
import java.io.File

/** 图片解码的边长上限(与网盘预览的原图一致) */
private const val IMAGE_MAX_SIDE = 4096

private class PdfZoom(val doc: PdfDocument, val page: Int)

/**
 * 包里一个文件的查看器:黑底全屏盖在列表上,和网盘预览同一套组件(图片可缩放、PDF 逐页、文本分段)。
 * 顶栏:返回目录、名字与大小、用其他应用打开、分享(取到本地之后才可用)。
 */
@Composable
internal fun ArchiveEntryViewer(
    viewer: EntryViewer,
    snackbar: SnackbarHostState,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onLaunch: (share: Boolean) -> Unit,
    onLoadMoreText: () -> Unit,
) {
    GopanTheme(themeMode = ThemeMode.DARK) {
        PreviewSystemBars(hidden = false)
        var pdfZoom by remember(viewer.entry.idx) { mutableStateOf<PdfZoom?>(null) }
        val zoom = rememberZoomState(viewer.entry.idx)
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("archive_viewer")) {
            when (viewer) {
                is EntryViewer.Loading -> LoadingEntry(viewer.fraction)
                is EntryViewer.Failed -> ErrorPage(viewer.message, onRetry)
                is EntryViewer.Image -> ImageEntry(viewer.file, viewer.entry.name, zoom, onOpenWith = { onLaunch(false) })
                is EntryViewer.Pdf -> PdfPage(
                    viewer.file,
                    onOpenPage = { doc, page -> pdfZoom = PdfZoom(doc, page) },
                    onOpenWith = { onLaunch(false) },
                    onDownload = null,
                )
                is EntryViewer.Text -> TextPage(viewer.text, onLoadMore = onLoadMoreText)
            }
            ViewerTopBar(
                name = viewer.entry.name,
                size = formatBytes(viewer.entry.size),
                ready = viewer.file != null,
                onClose = onClose,
                onLaunch = onLaunch,
            )
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
            pdfZoom?.let { z -> PdfPageViewer(z.doc, z.page, onDismiss = { pdfZoom = null }) }
        }
    }
}

@Composable
private fun ViewerTopBar(name: String, size: String, ready: Boolean, onClose: () -> Unit, onLaunch: (share: Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)))
            .statusBarsPadding()
            .padding(bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose, modifier = Modifier.testTag("archive_viewer_close")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.archive_back_to_list), tint = OnPreview)
            }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(name, color = OnPreview, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text(size, color = OnPreviewDim, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            IconButton(onClick = { onLaunch(false) }, enabled = ready, modifier = Modifier.testTag("archive_viewer_open_with")) {
                Icon(
                    Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = stringResource(R.string.archive_open_with),
                    tint = if (ready) OnPreview else OnPreviewDim,
                )
            }
            IconButton(onClick = { onLaunch(true) }, enabled = ready, modifier = Modifier.testTag("archive_viewer_share")) {
                Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.archive_share), tint = if (ready) OnPreview else OnPreviewDim)
            }
        }
    }
}

/** 正在从服务端取:总大小已知时显示百分比 */
@Composable
private fun LoadingEntry(fraction: Float?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp).testTag("archive_viewer_loading"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (fraction == null) {
            CircularProgressIndicator(color = OnPreview)
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.width(200.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.archive_fetching_percent, (fraction * 100).toInt()),
                color = OnPreviewDim,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** 图片:本地文件按边长上限解码,双指 / 双击放大;解不了的(如 SVG)给「用其他应用打开」 */
@Composable
private fun ImageEntry(file: File, name: String, zoom: ZoomState, onOpenWith: () -> Unit) {
    val context = LocalPlatformContext.current
    val request = remember(file) {
        ImageRequest.Builder(context)
            .data(file)
            .memoryCacheKey("archive:${file.path}")
            .diskCachePolicy(CachePolicy.DISABLED)
            .size(Size(IMAGE_MAX_SIDE, IMAGE_MAX_SIDE))
            .precision(Precision.INEXACT)
            .build()
    }
    var state by remember(file) { mutableStateOf<AsyncImagePainter.State?>(null) }
    Box(
        modifier = Modifier.fillMaxSize().zoomable(zoom).doubleTapZoom(zoom),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = request,
            contentDescription = name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().testTag("archive_viewer_image"),
            onState = { state = it },
        )
    }
    when (state) {
        is AsyncImagePainter.State.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = OnPreview)
        }
        is AsyncImagePainter.State.Error -> MessagePanel(
            Icons.Outlined.ErrorOutline,
            stringResource(R.string.preview_image_error),
            listOf(PanelAction(stringResource(R.string.archive_open_with), Icons.AutoMirrored.Outlined.OpenInNew, primary = true, onClick = onOpenWith)),
        )
        else -> Unit
    }
}
