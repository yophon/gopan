package xyz.yophon.gopan.feature.preview

import android.content.ComponentName
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.ui.format.formatBytes
import xyz.yophon.gopan.core.ui.components.ZoomState
import xyz.yophon.gopan.core.ui.components.doubleTapZoom
import xyz.yophon.gopan.core.ui.components.rememberZoomState
import xyz.yophon.gopan.core.ui.components.zoomable
import java.io.File

/** 预览页上的文字颜色(背景恒为黑) */
internal val OnPreview = Color(0xFFECECEC)
internal val OnPreviewDim = Color(0xB3ECECEC)

/** 顶栏占的高度:列表类内容(PDF、文本)顶部留出这么多,第一行不被盖住 */
@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun topBarSpace() =
    (if (LocalPreviewFullScreen.current) WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding() else 0.dp) + 64.dp

@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun bottomBarSpace() =
    if (LocalPreviewFullScreen.current) WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding() else 0.dp

/** 原图解码的边长上限(再大位图绘制会超出纹理上限,也太占内存) */
private const val ORIGINAL_MAX_SIDE = 4096

// ---- 加载中 / 提示面板 ----

/** 页面信息还没回来:有列表缩略图就先拿它垫底 */
@Composable
internal fun LoadingPage(thumbKey: String?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (thumbKey != null) {
            val context = LocalPlatformContext.current
            val request = remember(thumbKey) {
                ImageRequest.Builder(context)
                    .data("https://thumbnail.cache.invalid/$thumbKey")
                    .memoryCacheKey(thumbKey)
                    .diskCacheKey(thumbKey)
                    .networkCachePolicy(CachePolicy.DISABLED)
                    .build()
            }
            AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        CircularProgressIndicator(color = OnPreview)
    }
}

/** 一行动作按钮 */
internal class PanelAction(val label: String, val icon: ImageVector, val primary: Boolean = false, val onClick: () -> Unit)

/** 居中的图标 + 说明 + 按钮(不支持、转换失败、加载失败…) */
@Composable
internal fun MessagePanel(
    icon: ImageVector,
    text: String,
    actions: List<PanelAction>,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = OnPreviewDim, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(text, color = OnPreview, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (detail != null) {
            Spacer(Modifier.height(6.dp))
            Text(detail, color = OnPreviewDim, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
        if (actions.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                actions.forEach { action ->
                    if (action.primary) {
                        FilledTonalButton(onClick = action.onClick) {
                            Icon(action.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(action.label)
                        }
                    } else {
                        OutlinedButton(onClick = action.onClick) {
                            Icon(action.icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = OnPreview)
                            Spacer(Modifier.width(8.dp))
                            Text(action.label, color = OnPreview)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun openWithAction(onClick: () -> Unit) =
    PanelAction(stringResource(R.string.preview_open_with), Icons.AutoMirrored.Outlined.OpenInNew, primary = true, onClick = onClick)

@Composable
internal fun downloadAction(onClick: () -> Unit) =
    PanelAction(stringResource(R.string.preview_download), Icons.Outlined.Download, onClick = onClick)

@Composable
internal fun retryAction(onClick: () -> Unit) =
    PanelAction(stringResource(R.string.action_retry), Icons.Outlined.Refresh, primary = true, onClick = onClick)

@Composable
internal fun ErrorPage(message: String, onRetry: () -> Unit) {
    MessagePanel(Icons.Outlined.ErrorOutline, message, listOf(retryAction(onRetry)))
}

@Composable
internal fun UnsupportedPage(onOpenWith: () -> Unit, onDownload: () -> Unit) {
    MessagePanel(
        Icons.AutoMirrored.Outlined.InsertDriveFile,
        stringResource(R.string.preview_unsupported),
        listOf(openWithAction(onOpenWith), downloadAction(onDownload)),
        modifier = Modifier.testTag("preview_unsupported"),
    )
}

@Composable
internal fun OfficeConvertingPage() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp).testTag("preview_office_converting"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.Description, contentDescription = null, tint = OnPreviewDim, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.preview_office_converting), color = OnPreview, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(modifier = Modifier.width(200.dp))
    }
}

@Composable
internal fun PdfDownloadingPage(fraction: Float?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (fraction == null) {
            CircularProgressIndicator(color = OnPreview)
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.width(200.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.preview_pdf_loading, (fraction * 100).toInt()),
                color = OnPreviewDim,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// ---- 图片 ----

/** 图片:2048 大图(或原图)可缩放;大图出来之前列表缩略图垫底 */
@Composable
internal fun ImagePage(
    content: PreviewContent.Image,
    item: PreviewItem,
    title: String,
    zoom: ZoomState,
    onTap: () -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalPlatformContext.current
    val request = remember(content) {
        ImageRequest.Builder(context)
            .data(content.url)
            .memoryCacheKey(content.cacheKey)
            .diskCacheKey(content.cacheKey)
            .placeholderMemoryCacheKey(item.thumbKey)
            .size(if (content.original) Size(ORIGINAL_MAX_SIDE, ORIGINAL_MAX_SIDE) else Size.ORIGINAL)
            .precision(Precision.INEXACT)
            .build()
    }
    var state by remember(content) { mutableStateOf<AsyncImagePainter.State?>(null) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zoomable(zoom)
            .doubleTapZoom(zoom, onTap),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = request,
            contentDescription = title,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().testTag("preview_image"),
            onState = { state = it },
        )
    }
    when (state) {
        is AsyncImagePainter.State.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = OnPreview)
        }
        // 多半是预签名地址过期(页面开着放了一刻钟):重新取信息再加载
        is AsyncImagePainter.State.Error -> ErrorPage(stringResource(R.string.preview_image_error), onRetry)
        else -> Unit
    }
}

// ---- 视频 ----

/**
 * 视频:页面内的 ExoPlayer(不进后台播放),翻到这页才开始缓冲并自动播放,翻走就暂停,离开预览释放。
 * 播放控件显隐与顶栏同步;地址过期(403)时重新取地址从原位置接着放。
 */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VideoPage(
    content: PreviewContent.Video,
    isCurrent: Boolean,
    client: OkHttpClient,
    page: PreviewPage,
    onControllerVisible: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentIsCurrent by rememberUpdatedState(isCurrent)
    val currentOnControllerVisible by rememberUpdatedState(onControllerVisible)
    var firstFrame by remember { mutableStateOf(false) }
    val player = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(OkHttpDataSource.Factory(client)))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrame = true
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!PlaybackService.isExpiredUrl(error)) return
                val position = player.currentPosition
                scope.launch {
                    val url = page.freshContentUrl() ?: return@launch
                    player.setMediaItem(MediaItem.fromUri(url), position)
                    player.prepare()
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(isCurrent) {
        if (isCurrent) {
            if (player.currentMediaItem == null) {
                player.setMediaItem(MediaItem.fromUri(content.url))
                player.prepare()
            }
            player.play()
        } else {
            player.pause()
        }
    }
    // 切到后台暂停;回来不自动续播
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }

    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            if (currentIsCurrent) currentOnControllerVisible(visibility == View.VISIBLE)
                        },
                    )
                }
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize().testTag("preview_video"),
        )
        // 第一帧出来之前显示封面
        if (!firstFrame && content.posterUrl != null) {
            AsyncImage(
                model = content.posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ---- 音频 ----

/** 连到 [PlaybackService] 的控制器;离开组合时断开(在放的照常放,通知栏可控) */
@Composable
private fun rememberMediaController(): State<MediaController?> {
    val context = LocalContext.current
    val state = remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            { state.value = runCatching { future.get() }.getOrNull() },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            state.value = null
            MediaController.releaseFuture(future)
        }
    }
    return state
}

/**
 * 音频:交给后台播放服务(锁屏、离开预览照常播,通知栏有控制按钮)。翻到这页自动播放这一首;
 * 翻走不停(像音乐播放器),翻到另一首音频时换成那首。
 */
@Composable
internal fun AudioPage(nodeId: String, title: String, content: PreviewContent.Audio, isCurrent: Boolean) {
    val controller by rememberMediaController()
    var mediaId by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf((content.durationSec ?: 0) * 1000L) }
    var dragging by remember { mutableStateOf<Float?>(null) }

    DisposableEffect(controller) {
        val c = controller ?: return@DisposableEffect onDispose {}
        fun sync() {
            mediaId = c.currentMediaItem?.mediaId
            playing = c.isPlaying
            if (mediaId == nodeId && c.duration > 0) duration = c.duration
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = sync()
        }
        c.addListener(listener)
        sync()
        onDispose { c.removeListener(listener) }
    }
    fun playThis(c: MediaController) {
        c.setMediaItem(PlaybackService.mediaItem(nodeId, content.url, title))
        c.prepare()
        c.play()
    }
    LaunchedEffect(isCurrent, controller) {
        val c = controller ?: return@LaunchedEffect
        if (isCurrent && c.currentMediaItem?.mediaId != nodeId) playThis(c)
    }
    val isThis = mediaId == nodeId
    LaunchedEffect(isThis, controller) {
        val c = controller ?: return@LaunchedEffect
        while (isThis) {
            position = c.currentPosition
            if (c.duration > 0) duration = c.duration
            delay(500)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp).testTag("preview_audio"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.Headphones, contentDescription = null, tint = OnPreviewDim, modifier = Modifier.size(96.dp))
        Spacer(Modifier.height(24.dp))
        Text(title, color = OnPreview, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        val fraction = if (isThis && duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        Slider(
            value = dragging ?: fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                val target = dragging
                dragging = null
                val c = controller
                if (target != null && c != null && isThis && duration > 0) {
                    position = (target * duration).toLong()
                    c.seekTo(position)
                }
            },
            enabled = isThis && duration > 0,
            modifier = Modifier.fillMaxWidth().testTag("preview_audio_seek"),
        )
        Row(Modifier.fillMaxWidth()) {
            val shown = dragging?.let { (it * duration).toLong() } ?: if (isThis) position else 0L
            Text(formatClock(shown), color = OnPreviewDim, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(if (duration > 0) formatClock(duration) else "--:--", color = OnPreviewDim, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            IconButton(onClick = { controller?.takeIf { isThis }?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) } }, enabled = isThis) {
                Icon(Icons.Filled.Replay10, contentDescription = stringResource(R.string.preview_audio_back), tint = OnPreview)
            }
            FilledIconButton(
                onClick = {
                    val c = controller ?: return@FilledIconButton
                    when {
                        !isThis -> playThis(c)
                        c.isPlaying -> c.pause()
                        else -> {
                            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
                            if (c.playbackState == Player.STATE_IDLE) c.prepare()
                            c.play()
                        }
                    }
                },
                enabled = controller != null,
                modifier = Modifier.size(64.dp).testTag("preview_audio_toggle"),
                colors = IconButtonDefaults.filledIconButtonColors(),
            ) {
                val showPause = isThis && playing
                Icon(
                    if (showPause) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (showPause) R.string.preview_audio_pause else R.string.preview_audio_play),
                    modifier = Modifier.size(36.dp),
                )
            }
            IconButton(onClick = { controller?.takeIf { isThis }?.let { it.seekTo(it.currentPosition + 10_000) } }, enabled = isThis) {
                Icon(Icons.Filled.Forward10, contentDescription = stringResource(R.string.preview_audio_forward), tint = OnPreview)
            }
        }
    }
}

/** mm:ss(超过一小时 h:mm:ss) */
internal fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

// ---- PDF ----

/**
 * PDF(原文件或 Office 派生):系统 PdfRenderer 逐页渲染;点一页全屏放大看。
 * [initialPage]:打开时先停在这一页(从 1 起,内容搜索的命中页);null 从第一页看。
 */
@Composable
internal fun PdfPage(
    file: File,
    onOpenPage: (PdfDocument, Int) -> Unit,
    onOpenWith: () -> Unit,
    onDownload: () -> Unit,
    initialPage: Int? = null,
) {
    val result by produceState<Result<PdfDocument>?>(null, file) {
        val r = try {
            Result.success(PdfDocument.open(file))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        value = r
        awaitDispose { r.getOrNull()?.close() }
    }
    val r = result
    when {
        r == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = OnPreview) }
        r.isFailure -> MessagePanel(
            Icons.Outlined.ErrorOutline,
            stringResource(R.string.preview_pdf_error),
            listOf(openWithAction(onOpenWith), downloadAction(onDownload)),
        )
        else -> PdfPages(r.getOrThrow(), onOpenPage, initialPage)
    }
}

@Composable
private fun PdfPages(doc: PdfDocument, onOpenPage: (PdfDocument, Int) -> Unit, initialPage: Int?) {
    // 起始页只决定列表刚出现时停在哪;之后用户怎么滚都不会被拉回去
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = PdfDocument.startIndex(initialPage, doc.pageCount))
    val density = LocalDensity.current
    val side = 8.dp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(density) { (maxWidth - side * 2).roundToPx() }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("preview_pdf"),
            contentPadding = PaddingValues(start = side, end = side, top = topBarSpace(), bottom = bottomBarSpace() + 48.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(doc.pageCount) { index ->
                PdfPageItem(doc, index, widthPx, onClick = { onOpenPage(doc, index) })
            }
        }
        val current by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }
        Text(
            stringResource(R.string.preview_pdf_page, current, doc.pageCount),
            color = OnPreview,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomBarSpace() + 12.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .testTag("preview_pdf_indicator"),
        )
    }
}

@Composable
private fun PdfPageItem(doc: PdfDocument, index: Int, widthPx: Int, onClick: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(null, doc, index, widthPx) {
        value = doc.render(index, widthPx)?.asImageBitmap()
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f / doc.ratio(index))
            .background(Color.White)
            .clickable(onClick = onClick)
            .testTag("preview_pdf_page"),
    ) {
        bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
    }
}

/** 单页全屏:按 2.5 倍屏宽渲染,双指 / 双击放大 */
@Composable
internal fun PdfPageViewer(doc: PdfDocument, index: Int, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val zoom = rememberZoomState(index)
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).testTag("preview_pdf_zoom")) {
        val renderWidth = with(density) { (maxWidth.toPx() * 2.5f).toInt() }
        val bitmap by produceState<ImageBitmap?>(null, doc, index, renderWidth) {
            value = doc.render(index, renderWidth)?.asImageBitmap()
        }
        Box(
            modifier = Modifier.fillMaxSize().zoomable(zoom).doubleTapZoom(zoom),
            contentAlignment = Alignment.Center,
        ) {
            val b = bitmap
            if (b == null) {
                CircularProgressIndicator(color = OnPreview)
            } else {
                Image(
                    b,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f / doc.ratio(index)).background(Color.White),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss, modifier = Modifier.testTag("preview_pdf_zoom_close")) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.preview_close), tint = OnPreview)
            }
            Text(
                stringResource(R.string.preview_pdf_page, index + 1, doc.pageCount),
                color = OnPreview,
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

// ---- 文本 ----

/** 文本 / 代码:等宽、可选中复制;分块排版,几百 KB 也不卡;大文件分段读,末尾「继续加载」 */
@Composable
internal fun TextPage(page: PreviewContent.Text, onLoadMore: () -> Unit) {
    val content = page.content
    val blocks = remember(content.text) { chunkText(content.text.replace("\r\n", "\n")) }
    val listState = rememberLazyListState()
    // 滚到末尾(「继续加载」那一项露出来)就自动拉下一段;上次失败了不自动重试,留给按钮
    val atEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 1
        }
    }
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(atEnd, content.truncated, page.loadingMore, page.moreError) {
        if (atEnd && content.truncated && !page.loadingMore && page.moreError == null) currentOnLoadMore()
    }
    SelectionContainer {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("preview_text"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topBarSpace(), bottom = bottomBarSpace() + 24.dp),
        ) {
            // 开头也放一条:一眼看出只读了一部分,不用滑到底就能接着读
            if (content.truncated) {
                item { TextMore(page, onLoadMore, top = true) }
            }
            items(blocks.size) { i ->
                Text(
                    blocks[i],
                    color = OnPreview,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            }
            if (content.truncated) {
                item { TextMore(page, onLoadMore, top = false) }
            }
        }
    }
}

@Composable
private fun TextMore(page: PreviewContent.Text, onLoadMore: () -> Unit, top: Boolean) {
    val content = page.content
    val suffix = if (top) "_top" else ""
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (top) 0.dp else 16.dp, bottom = if (top) 12.dp else 0.dp)
            .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("preview_text_more$suffix"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            content.totalBytes?.let { stringResource(R.string.preview_text_partial, formatBytes(content.nextOffset), formatBytes(it)) }
                ?: stringResource(R.string.preview_text_partial_unknown, formatBytes(content.nextOffset)),
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            style = MaterialTheme.typography.bodySmall,
        )
        page.moreError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (page.loadingMore) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            TextButton(onClick = onLoadMore, modifier = Modifier.testTag("preview_text_load_more$suffix")) {
                Text(stringResource(R.string.preview_text_more))
            }
        }
    }
}
