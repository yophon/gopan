package xyz.yophon.gopan.feature.preview

import kotlin.math.sign
import kotlin.math.abs
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Animatable
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.HiltViewModelFactory
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.DEFAULT_ARGS_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.prefs.ThemeMode
import xyz.yophon.gopan.core.ui.components.ZoomState
import xyz.yophon.gopan.core.ui.format.formatBytes
import xyz.yophon.gopan.core.ui.theme.GopanTheme
import xyz.yophon.gopan.core.ui.transfer.rememberTransferPermissions
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.ui.nav.LocalOpenArchive
import xyz.yophon.gopan.core.ui.nav.Routes
import xyz.yophon.gopan.feature.archive.ExtractPickerHost
import xyz.yophon.gopan.feature.archive.showExtractStarted
import xyz.yophon.gopan.feature.drive.resolve
import xyz.yophon.gopan.feature.media.followPage
import xyz.yophon.gopan.feature.media.pdfStartPage
import xyz.yophon.gopan.feature.shares.ShareCreateDialog
import xyz.yophon.gopan.core.ui.exposeTestTags

private class PdfZoomRequest(val doc: PdfDocument, val page: Int)

/**
 * 全屏预览:黑底,左右翻打开时那个列表里的文件。单击切换顶栏(隐藏时连系统栏一起收起),
 * 图片放大时不翻页。顶栏:返回、名称与位置、下载、更多(查看原图 / 用其他应用打开 / 发到传输助手)。
 */
@Composable
fun PreviewScreen(onBack: () -> Unit, viewModel: PreviewViewModel = hiltViewModel()) {
    GopanTheme(themeMode = ThemeMode.DARK) {
        PreviewContentHost(onBack, viewModel, pane = false)
    }
}

/**
 * 平板双栏右侧的预览:同一套页面,但不碰系统栏、顶栏常驻,关闭按钮收起侧栏。
 * ViewModel 挂在宿主页面(文件页)上、固定一个 key;点别的文件时 [PreviewViewModel.restart] 换会话,
 * 不会每点一次就多留一个 ViewModel。
 */
@Composable
fun PreviewPane(nodeId: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val owner = checkNotNull(LocalViewModelStoreOwner.current) { "PreviewPane 需要 ViewModelStoreOwner" }
    val defaults = owner as HasDefaultViewModelProviderFactory
    val context = LocalContext.current
    val viewModel: PreviewViewModel = viewModel(
        viewModelStoreOwner = owner,
        key = "preview-pane",
        factory = HiltViewModelFactory(context, defaults.defaultViewModelProviderFactory),
        extras = MutableCreationExtras(defaults.defaultViewModelCreationExtras).apply {
            set(DEFAULT_ARGS_KEY, bundleOf(PreviewViewModel.NODE_ID_ARG to nodeId))
        },
    )
    LaunchedEffect(nodeId) { viewModel.restart(nodeId) }
    Box(modifier.testTag("preview_pane")) {
        GopanTheme(themeMode = ThemeMode.DARK) {
            PreviewContentHost(onClose, viewModel, pane = true)
        }
    }
}

/** 全屏预览为 true:顶栏、PDF / 文本列表要让出状态栏与导航栏;侧栏里为 false */
internal val LocalPreviewFullScreen = staticCompositionLocalOf { true }

/** 预览页底部的 Snackbar(音视频的「从 23:15 继续 · 从头播放」用) */
internal val LocalPreviewSnackbar = staticCompositionLocalOf { SnackbarHostState() }

@Composable
private fun PreviewContentHost(onBack: () -> Unit, viewModel: PreviewViewModel, pane: Boolean) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalPreviewFullScreen provides !pane) {
        // 换了一批(侧栏点了别的文件):翻页器、缩放、对话框状态全部重来
        key(session.generation) {
            PreviewBody(onBack, viewModel, session, pane)
        }
    }
}

@Composable
private fun PreviewBody(onBack: () -> Unit, viewModel: PreviewViewModel, session: PreviewSessionState, pane: Boolean) {
    val items = session.items
    val pagerState = rememberPagerState(initialPage = session.initialPage) { items.size }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    val zoomStates = remember { HashMap<String, ZoomState>() }
    fun zoomFor(id: String): ZoomState = zoomStates.getOrPut(id) { ZoomState() }
    var pdfZoom by remember { mutableStateOf<PdfZoomRequest?>(null) }
    var shareDialog by remember { mutableStateOf<Pair<Node, String>?>(null) }
    val permissions = rememberTransferPermissions(onStorageResult = viewModel::onStoragePermission)

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { index ->
            viewModel.onPageSettled(index)
            // 翻走的页恢复原大小
            items.forEachIndexed { i, item -> if (i != index) zoomStates[item.nodeId]?.reset() }
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is PreviewEffect.Message -> launch { snackbar.showSnackbar(effect.text.resolve(context)) }
                PreviewEffect.RequestStoragePermission -> permissions.requestStoragePermission()
                PreviewEffect.TransferQueued -> permissions.onTransferQueued()
                // 「查看」:主界面打开传输列表;全屏预览先退回去它才看得见
                PreviewEffect.ExtractStarted -> launch {
                    snackbar.showExtractStarted(context) {
                        viewModel.showTransfers()
                        if (!pane) onBack()
                    }
                }
            }
        }
    }
    ExtractPickerHost(viewModel.extract)
    if (!pane) PreviewSystemBars(hidden = !chromeVisible)

    // 音频:播放器换到了翻页列表里的另一首(上一首 / 下一首 / 放完自动接),正看着刚才那首就跟过去
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val playingId = nowPlaying?.nodeId
    var lastPlayingId by remember { mutableStateOf(playingId) }
    LaunchedEffect(playingId) {
        val target = followPage(lastPlayingId, playingId, items.getOrNull(pagerState.settledPage)?.nodeId, items.map { it.nodeId })
        lastPlayingId = playingId
        if (target != null) pagerState.animateScrollToPage(target)
    }
    // 视频的下一集:在这一批里就翻过去,不在就换成同文件夹的视频列表
    val playNext: (UpNext) -> Unit = { upNext ->
        val index = items.indexOfFirst { it.nodeId == upNext.next.id }
        if (index >= 0) scope.launch { pagerState.animateScrollToPage(index) } else viewModel.openPlaylist(upNext)
    }

    val currentIndex = pagerState.currentPage.coerceIn(0, items.lastIndex)
    val currentItem = items[currentIndex]
    val currentPage = remember(currentItem.nodeId) { viewModel.page(currentItem.nodeId) }
    val currentState by currentPage.state.collectAsStateWithLifecycle()

    // 下滑(或上滑)关闭:只在全屏的图片页、没放大时。拖动时整页跟着手指走并缩小一点,
    // 松手时过了距离或甩得够快就滑出去关掉,否则弹回
    val dismissDrag = remember { Animatable(0f) }
    val density = LocalDensity.current
    val dismissDistance = with(density) { 120.dp.toPx() }
    val dismissVelocity = with(density) { 1000.dp.toPx() }
    val canDragDismiss = !pane && currentState.content is PreviewContent.Image && !zoomFor(currentItem.nodeId).magnified
    val dragProgress = (abs(dismissDrag.value) / (dismissDistance * 3)).coerceIn(0f, 1f)
    val dragToDismiss = if (canDragDismiss) {
        Modifier.pointerInput(currentItem.nodeId) {
            val tracker = VelocityTracker()
            detectVerticalDragGestures(
                onDragStart = { tracker.resetTracking() },
                onVerticalDrag = { change, amount ->
                    change.consume()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    scope.launch { dismissDrag.snapTo(dismissDrag.value + amount) }
                },
                onDragEnd = {
                    val offset = dismissDrag.value
                    val velocity = tracker.calculateVelocity().y
                    val fling = abs(velocity) > dismissVelocity && sign(velocity) == sign(offset)
                    scope.launch {
                        if (abs(offset) > dismissDistance || fling) {
                            dismissDrag.animateTo(sign(offset) * size.height, tween(180))
                            onBack()
                        } else {
                            dismissDrag.animateTo(0f, spring(dampingRatio = 0.8f))
                        }
                    }
                },
                onDragCancel = { scope.launch { dismissDrag.animateTo(0f) } },
            )
        }
    } else {
        Modifier
    }

    Box(Modifier.fillMaxSize().background(Color.Black).then(dragToDismiss).testTag("preview_root")) {
        CompositionLocalProvider(LocalPreviewSnackbar provides snackbar) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = dismissDrag.value
                        val scale = 1f - 0.2f * dragProgress
                        scaleX = scale
                        scaleY = scale
                    }
                    .testTag("preview_pager"),
                beyondViewportPageCount = 1,
                pageSpacing = 16.dp,
                userScrollEnabled = !zoomFor(currentItem.nodeId).magnified,
                key = { items[it].nodeId },
            ) { index ->
                val item = items[index]
                PreviewPageHost(
                    item = item,
                    viewModel = viewModel,
                    isCurrent = index == pagerState.settledPage,
                    zoom = zoomFor(item.nodeId),
                    // 侧栏里顶栏常驻:点图片不收起
                    onToggleChrome = { if (!pane) chromeVisible = !chromeVisible },
                    onChromeVisible = { if (!pane) chromeVisible = it },
                    onOpenPdfPage = { doc, page -> pdfZoom = PdfZoomRequest(doc, page) },
                    onPlayNext = playNext,
                )
            }
        }
        AnimatedVisibility(
            visible = chromeVisible && dismissDrag.value == 0f,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            val info = currentState.info
            val position = if (items.size > 1) "${currentIndex + 1} / ${items.size}" else ""
            val size = (info?.size ?: currentItem.size)?.let { formatBytes(it) }.orEmpty()
            PreviewTopBar(
                title = info?.name ?: currentItem.name,
                subtitle = listOf(position, size).filter { it.isNotEmpty() }.joinToString(" · "),
                canShowOriginal = currentState.canShowOriginal,
                markdownRendered = currentState.markdownRendered,
                onToggleMarkdown = {
                    if (currentState.markdownRendered == true) currentPage.showMarkdownSource() else currentPage.showMarkdownRendered()
                },
                closeIcon = pane,
                onBack = onBack,
                onDownload = viewModel::download,
                onOpenWith = viewModel::openWith,
                onSendToChat = viewModel::sendToChat,
                onShare = { viewModel.shareTarget()?.let { shareDialog = it to System.nanoTime().toString() } },
                onShowOriginal = currentPage::showOriginal,
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        pdfZoom?.let { req -> PdfPageViewer(req.doc, req.page, onDismiss = { pdfZoom = null }) }
    }
    shareDialog?.let { (node, key) ->
        ShareCreateDialog(
            node = node,
            instanceKey = key,
            onDismiss = { shareDialog = null },
            onMessage = { text -> scope.launch { snackbar.showSnackbar(text) } },
        )
    }
}

@Composable
private fun PreviewPageHost(
    item: PreviewItem,
    viewModel: PreviewViewModel,
    isCurrent: Boolean,
    zoom: ZoomState,
    onToggleChrome: () -> Unit,
    onChromeVisible: (Boolean) -> Unit,
    onOpenPdfPage: (PdfDocument, Int) -> Unit,
    onPlayNext: (UpNext) -> Unit,
) {
    val page = remember(item.nodeId) { viewModel.page(item.nodeId) }
    DisposableEffect(page) {
        viewModel.attach(item.nodeId)
        onDispose { viewModel.detach(item.nodeId) }
    }
    val state by page.state.collectAsStateWithLifecycle()
    val title = state.info?.name ?: item.name
    when (val c = state.content) {
        PreviewContent.Loading -> LoadingPage(item.thumbKey)
        is PreviewContent.Error -> ErrorPage(c.message, onRetry = page::retry)
        is PreviewContent.Image -> ImagePage(c, item, title, zoom, onTap = onToggleChrome, onRetry = page::retry)
        is PreviewContent.Video -> VideoPage(
            c,
            isCurrent,
            viewModel.mediaClient,
            page,
            onControllerVisible = onChromeVisible,
            onReportProgress = { position, duration -> viewModel.reportMedia(item.nodeId, position, duration) },
            onFindNext = { viewModel.nextVideo(item.nodeId) },
            onPlayNext = onPlayNext,
        )
        is PreviewContent.Audio -> AudioPage(item.nodeId, title, c, isCurrent, onLoadQueue = { viewModel.loadAudioQueue(item.nodeId, title) })
        is PreviewContent.PdfDownloading -> PdfDownloadingPage(c.fraction)
        is PreviewContent.Pdf -> PdfPage(
            c.file,
            onOpenPdfPage,
            onOpenWith = viewModel::openWith,
            onDownload = viewModel::download,
            // 内容搜索的命中页优先,其次上次看到的页
            initialPage = pdfStartPage(item.pdfPage, state.info?.progress),
            onPageSettled = { p, count -> viewModel.reportPdf(item.nodeId, p, count) },
        )
        is PreviewContent.Text -> TextPage(c, onLoadMore = page::loadMoreText)
        PreviewContent.Markdown -> MarkdownPreviewPage(item.nodeId)
        PreviewContent.OfficeConverting -> OfficeConvertingPage()
        PreviewContent.OfficeUnavailable -> MessagePanel(
            Icons.Outlined.Description,
            stringResource(R.string.preview_office_unavailable),
            listOf(openWithAction(viewModel::openWith), downloadAction(viewModel::download)),
            modifier = Modifier.testTag("preview_office_unavailable"),
        )
        PreviewContent.OfficeFailed -> MessagePanel(
            Icons.Outlined.ErrorOutline,
            stringResource(R.string.preview_office_failed),
            listOf(
                PanelAction(stringResource(R.string.preview_office_retry), Icons.Outlined.Refresh, primary = true, onClick = page::retryConversion),
                openWithAction(viewModel::openWith),
            ),
            modifier = Modifier.testTag("preview_office_failed"),
        )
        PreviewContent.Unsupported -> UnsupportedPage(onOpenWith = viewModel::openWith, onDownload = viewModel::download)
        is PreviewContent.Archive -> {
            val openArchive = LocalOpenArchive.current
            val extracting by viewModel.extract.state.collectAsStateWithLifecycle()
            val info = state.info
            ArchiveSummaryPage(
                summary = c.summary,
                busy = extracting.busy,
                onBrowse = { info?.let { openArchive(Routes.Archive(it.nodeId, it.name, it.parentId)) } },
                onExtractHere = { info?.let(viewModel::extractHere) },
                onExtractTo = { info?.let(viewModel::extractTo) },
                onOpenWith = viewModel::openWith,
                onDownload = viewModel::download,
            )
        }
    }
}

@Composable
private fun PreviewTopBar(
    title: String,
    subtitle: String,
    canShowOriginal: Boolean,
    /** Markdown 的「看源文件 / 渲染显示」:true 正在看渲染的,false 正在看源文件,null 不显示 */
    markdownRendered: Boolean?,
    onToggleMarkdown: () -> Unit,
    closeIcon: Boolean,
    onBack: () -> Unit,
    onDownload: () -> Unit,
    onOpenWith: () -> Unit,
    onSendToChat: () -> Unit,
    onShare: () -> Unit,
    onShowOriginal: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)))
            .then(if (LocalPreviewFullScreen.current) Modifier.statusBarsPadding() else Modifier)
            .padding(bottom = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("preview_back")) {
                if (closeIcon) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.preview_close), tint = OnPreview)
                } else {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.preview_back), tint = OnPreview)
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    title,
                    color = OnPreview,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.testTag("preview_title"),
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        color = OnPreviewDim,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        modifier = Modifier.testTag("preview_subtitle"),
                    )
                }
            }
            if (markdownRendered != null) {
                IconButton(onClick = onToggleMarkdown, modifier = Modifier.testTag("preview_markdown_toggle")) {
                    if (markdownRendered) {
                        Icon(Icons.Outlined.Code, contentDescription = stringResource(R.string.md_show_source), tint = OnPreview)
                    } else {
                        Icon(Icons.AutoMirrored.Outlined.Article, contentDescription = stringResource(R.string.md_show_rendered), tint = OnPreview)
                    }
                }
            }
            IconButton(onClick = onDownload, modifier = Modifier.testTag("preview_download")) {
                Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.preview_download), tint = OnPreview)
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("preview_more")) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.preview_more), tint = OnPreview)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.exposeTestTags()) {
                    if (canShowOriginal) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.preview_view_original)) },
                            leadingIcon = { Icon(Icons.Outlined.HighQuality, contentDescription = null) },
                            onClick = {
                                menu = false
                                onShowOriginal()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.preview_open_with)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                        onClick = {
                            menu = false
                            onOpenWith()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.preview_share)) },
                        leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                        onClick = {
                            menu = false
                            onShare()
                        },
                        modifier = Modifier.testTag("preview_share"),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.preview_send_to_chat)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null) },
                        onClick = {
                            menu = false
                            onSendToChat()
                        },
                    )
                }
            }
        }
    }
}

/**
 * 预览页的系统栏:黑底上用浅色图标;顶栏收起时连状态栏、导航栏一起隐藏(从边缘滑出可临时呼出)。
 * 离开预览恢复原样。
 */
@Composable
internal fun PreviewSystemBars(hidden: Boolean) {
    val activity = LocalActivity.current ?: return
    val view = LocalView.current
    val controller = remember(activity, view) { WindowCompat.getInsetsController(activity.window, view) }
    DisposableEffect(controller) {
        val lightStatus = controller.isAppearanceLightStatusBars
        val lightNav = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = lightStatus
            controller.isAppearanceLightNavigationBars = lightNav
        }
    }
    LaunchedEffect(controller, hidden) {
        if (hidden) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
    }
}
