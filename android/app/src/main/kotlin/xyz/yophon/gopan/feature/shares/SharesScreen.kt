package xyz.yophon.gopan.feature.shares

import xyz.yophon.gopan.core.ui.components.TabTitle
import xyz.yophon.gopan.core.ui.components.FolderGlyph
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.collect.FileRequest
import xyz.yophon.gopan.core.data.shares.Share
import xyz.yophon.gopan.core.data.shares.ShareVisit
import xyz.yophon.gopan.core.ui.components.EmptyState
import xyz.yophon.gopan.core.ui.components.ErrorState
import xyz.yophon.gopan.core.ui.components.ListSkeleton
import xyz.yophon.gopan.core.ui.components.LoadingState
import xyz.yophon.gopan.core.ui.format.describeUserAgent
import xyz.yophon.gopan.core.ui.format.formatTime
import xyz.yophon.gopan.core.ui.rememberSystemActions
import xyz.yophon.gopan.feature.collect.FileRequestsTab
import xyz.yophon.gopan.feature.drive.FullPage
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.NestedScreenInsets
import xyz.yophon.gopan.feature.drive.resolve
import xyz.yophon.gopan.core.ui.exposeTestTags
import xyz.yophon.gopan.feature.links.DirectLinksTab
import xyz.yophon.gopan.feature.links.DirectLinksViewModel

/**
 * 「分享」tab:上面「分享」「收集」「直链」三个标签(与网页端一致;本站关了直链时没有「直链」)。
 * 分享:我的分享,点一行出操作(复制 / 发送 / 打开 / 访问记录 / 取消分享);收集:我开的收集链接(feature.collect);
 * 直链:我的直链(feature.links)。通知点进来时按 SharesTabRequest 切标签。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharesScreen(
    onOpenVisits: (Share) -> Unit,
    onOpenUploads: (FileRequest) -> Unit,
    viewModel: SharesViewModel = hiltViewModel(),
    links: DirectLinksViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val linksState by links.state.collectAsStateWithLifecycle()
    val pendingTab by links.pendingTab.collectAsStateWithLifecycle()
    val linksEnabled = linksState.enabled
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val system = rememberSystemActions()
    val scope = rememberCoroutineScope()
    var sheetFor by rememberSaveable { mutableStateOf<String?>(null) }
    var revokeFor by rememberSaveable { mutableStateOf<String?>(null) }
    // 0 = 分享,1 = 收集,2 = 直链;进收件记录再返回时留在原来的标签
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // 通知点进来:切到「收集」/「直链」(并打开那一条);直链开关还没问到时先等着
    LaunchedEffect(pendingTab, linksEnabled) {
        val target = pendingTab ?: return@LaunchedEffect
        when (target.tab) {
            SharesTab.SHARES -> tab = 0
            SharesTab.COLLECT -> tab = 1
            SharesTab.LINKS -> when (linksEnabled) {
                null -> return@LaunchedEffect
                true -> {
                    tab = 2
                    target.linkId?.let(links::focus)
                }
                false -> Unit
            }
        }
        links.consumeTab(target)
    }
    // 本站关了直链(或换了账号 / 站点):「直链」标签消失,回到「分享」
    LaunchedEffect(linksEnabled) { if (tab == 2 && linksEnabled == false) tab = 0 }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is SharesEffect.Message -> launch { snackbar.showSnackbar(effect.text.resolve(context)) }
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onResumed()
        // 进页面时没问到直链开关(断网)的话补问
        links.checkEnabled()
    }
    fun toast(res: Int) = scope.launch { snackbar.showSnackbar(context.getString(res)) }

    Scaffold(
        contentWindowInsets = NestedScreenInsets,
        topBar = { TopAppBar(windowInsets = NestedScreenInsets, title = { TabTitle(stringResource(R.string.shares_title)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // 「直链」标签还没出现(开关没问到 / 关了)时下标不能越界
            PrimaryTabRow(selectedTabIndex = if (tab == 2 && linksEnabled != true) 0 else tab) {
                Tab(
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text(stringResource(R.string.collect_tab_share)) },
                    modifier = Modifier.testTag("shares_tab_share"),
                )
                Tab(
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text(stringResource(R.string.collect_tab_collect)) },
                    modifier = Modifier.testTag("shares_tab_collect"),
                )
                if (linksEnabled == true) {
                    Tab(
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        text = { Text(stringResource(R.string.links_tab)) },
                        modifier = Modifier.testTag("shares_tab_links"),
                    )
                }
            }
            if (tab == 1) {
                FileRequestsTab(snackbar = snackbar, onOpenUploads = onOpenUploads)
            } else if (tab == 2 && linksEnabled == true) {
                DirectLinksTab(viewModel = links, snackbar = snackbar)
            } else {
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    when (val status = state.status) {
                        ListStatus.Loading -> ListSkeleton()
                        is ListStatus.Error -> FullPage { ErrorState(status.message, onRetry = viewModel::retry) }
                        ListStatus.Content -> if (state.items.isEmpty()) {
                            FullPage {
                                EmptyState(
                                    title = stringResource(R.string.shares_empty),
                                    description = stringResource(R.string.shares_empty_hint),
                                    icon = Icons.Outlined.Share,
                                )
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize().testTag("shares_list")) {
                                items(state.items, key = { it.id }) { share ->
                                    ShareRow(share, revoking = share.id in state.revoking, onClick = { sheetFor = share.id })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val sheetShare = state.items.firstOrNull { it.id == sheetFor }
    if (sheetShare != null) {
        ShareActionSheet(
            share = sheetShare,
            onDismiss = { sheetFor = null },
            onCopy = { if (system.copy(viewModel.url(sheetShare))) toast(R.string.shares_msg_copied) },
            onSend = { if (!system.sendText(viewModel.url(sheetShare), context.getString(R.string.share_send_chooser))) toast(R.string.share_no_app) },
            onOpen = { if (!system.openUrl(viewModel.url(sheetShare))) toast(R.string.shares_no_browser) },
            onVisits = { onOpenVisits(sheetShare) },
            onRevoke = { revokeFor = sheetShare.id },
        )
    }
    // 操作表开着时那条分享没了(别处取消、刷新后消失):收起
    LaunchedEffect(sheetFor, state.items) {
        if (sheetFor != null && state.status == ListStatus.Content && state.items.none { it.id == sheetFor }) sheetFor = null
    }
    val revokeShare = state.items.firstOrNull { it.id == revokeFor }
    if (revokeShare != null) {
        AlertDialog(
            onDismissRequest = { revokeFor = null },
            modifier = Modifier.exposeTestTags(),
            title = { Text(stringResource(R.string.shares_revoke_title)) },
            text = { Text(stringResource(R.string.shares_revoke_text, revokeShare.nodeName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        revokeFor = null
                        viewModel.revoke(revokeShare)
                    },
                    modifier = Modifier.testTag("shares_revoke_confirm"),
                ) { Text(stringResource(R.string.shares_revoke_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { revokeFor = null }) { Text(stringResource(R.string.shares_revoke_keep)) } },
        )
    }
}

/** 第二行:创建时间 · 有效期 · 访问计数 */
@Composable
private fun shareSecondary(share: Share): String {
    val expiry = when {
        share.expiresAt == null -> stringResource(R.string.shares_forever)
        share.isExpired() -> stringResource(R.string.shares_expired, formatTime(share.expiresAt))
        else -> stringResource(R.string.shares_until, formatTime(share.expiresAt))
    }
    val visits = if (share.verifyCount + share.downloadCount == 0L) {
        stringResource(R.string.shares_no_visits)
    } else {
        stringResource(R.string.shares_visits, share.verifyCount, share.downloadCount)
    }
    return "${formatTime(share.createdAt)} · $expiry · $visits"
}

@Composable
private fun ShareRow(share: Share, revoking: Boolean, onClick: () -> Unit) {
    val expired = share.isExpired()
    ListItem(
        modifier = Modifier.clickable(enabled = !revoking, onClick = onClick).testTag("share_row"),
        leadingContent = {
            if (share.isFolder) {
                FolderGlyph(Modifier.size(26.dp))
            } else {
                Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(share.nodeName, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f, fill = false))
                if (share.hasPassword) {
                    Spacer(Modifier.size(6.dp))
                    Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.shares_has_password), modifier = Modifier.size(16.dp))
                }
            }
        },
        supportingContent = {
            Text(
                shareSecondary(share),
                maxLines = 2,
                color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            IconButton(onClick = onClick, enabled = !revoking) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.preview_more))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareActionSheet(
    share: Share,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onSend: () -> Unit,
    onOpen: () -> Unit,
    onVisits: () -> Unit,
    onRevoke: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun pick(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        ListItem(
            headlineContent = { Text(share.nodeName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(shareSecondary(share), maxLines = 2) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        HorizontalDivider()
        SheetAction(Icons.Outlined.ContentCopy, stringResource(R.string.shares_action_copy)) { pick(onCopy) }
        SheetAction(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.shares_action_send)) { pick(onSend) }
        SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.shares_action_open)) { pick(onOpen) }
        SheetAction(Icons.Outlined.Insights, stringResource(R.string.shares_action_visits)) { pick(onVisits) }
        SheetAction(Icons.Outlined.LinkOff, stringResource(R.string.shares_action_revoke), destructive = true) { pick(onRevoke) }
        Spacer(Modifier.height(16.dp).navigationBarsPadding())
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, destructive: Boolean = false, onClick: () -> Unit) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
        headlineContent = { Text(label, color = tint) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

// ---- 访问记录 ----

/** 访问记录:最近 50 条(时间、动作、IP、客户端) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareVisitsScreen(onBack: () -> Unit, viewModel: ShareVisitsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        contentWindowInsets = NestedScreenInsets,
        topBar = {
            TopAppBar(
                windowInsets = NestedScreenInsets,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.preview_back))
                    }
                },
                title = {
                    Column {
                        Text(stringResource(R.string.visits_title))
                        if (viewModel.shareName.isNotEmpty()) {
                            Text(
                                viewModel.shareName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        when (val status = state.status) {
            ListStatus.Loading -> LoadingState(modifier = modifier)
            is ListStatus.Error -> ErrorState(status.message, modifier = modifier, onRetry = viewModel::load)
            ListStatus.Content -> LazyColumn(modifier = modifier.testTag("visits_list")) {
                item {
                    Text(
                        stringResource(R.string.visits_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (state.items.isEmpty()) {
                    item { EmptyState(title = stringResource(R.string.visits_empty), modifier = Modifier.padding(top = 48.dp)) }
                }
                items(state.items) { visit -> VisitRow(visit) }
            }
        }
    }
}

@Composable
private fun VisitRow(visit: ShareVisit) {
    ListItem(
        headlineContent = { Text(visitKindText(visit.kind)) },
        supportingContent = {
            Text(
                listOf(formatTime(visit.createdAt), visit.ip ?: "—", describeUserAgent(visit.userAgent)).joinToString(" · "),
                maxLines = 2,
            )
        },
        modifier = Modifier.testTag("visit_row"),
    )
}

@Composable
private fun visitKindText(kind: String): String = when (kind) {
    "verify" -> stringResource(R.string.visits_kind_verify)
    "download" -> stringResource(R.string.visits_kind_download)
    "pack" -> stringResource(R.string.visits_kind_pack)
    else -> kind
}
