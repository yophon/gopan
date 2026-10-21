package xyz.yophon.gopan.feature.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.notifications.AppNotification
import xyz.yophon.gopan.core.data.notifications.NotificationLevel
import xyz.yophon.gopan.core.ui.components.ConfirmDialog
import xyz.yophon.gopan.core.ui.components.EmptyState
import xyz.yophon.gopan.core.ui.components.ErrorState
import xyz.yophon.gopan.core.ui.components.LoadingState
import xyz.yophon.gopan.core.ui.format.formatRelativeTime
import xyz.yophon.gopan.feature.drive.FullPage
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.LoadMoreFooter
import xyz.yophon.gopan.feature.drive.LoadMoreTrigger
import xyz.yophon.gopan.feature.drive.NestedScreenInsets
import xyz.yophon.gopan.feature.drive.isNearEnd
import xyz.yophon.gopan.feature.drive.resolve
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「我的 → 通知」(doc/22 三.4):按天分组,新的在前;顶栏「全部已读」「清空」;点一条标已读并跳过去
 * (跳转由 [onNavigate] 交给导航图)。打开时收起通知栏里已发的站内通知;Android 13+ 第一次打开时问一次通知权限。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    onBack: () -> Unit,
    onNavigate: (NotificationNav) -> Unit,
    viewModel: NotificationsViewModel = hiltViewModel(),
    entry: NotificationsEntryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val currentOnNavigate by rememberUpdatedState(onNavigate)
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val permission = rememberNotificationPermission()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is NotificationsEffect.Message -> launch { snackbar.showSnackbar(effect.text.resolve(context)) }
                is NotificationsEffect.Navigate -> currentOnNavigate(effect.nav)
            }
        }
    }
    LaunchedEffect(Unit) {
        if (!permission.granted && permission.canRequest && entry.consumePermissionAsk()) permission.request()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onResumed()
        entry.clearPosted()
    }

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
                title = { Text(stringResource(R.string.notifications_title)) },
                actions = {
                    IconButton(
                        onClick = viewModel::markAllRead,
                        enabled = state.hasUnread && !state.busy,
                        modifier = Modifier.testTag("notifications_read_all"),
                    ) {
                        Icon(Icons.Outlined.DoneAll, contentDescription = stringResource(R.string.notifications_action_read_all))
                    }
                    IconButton(
                        onClick = { confirmClear = true },
                        enabled = state.items.isNotEmpty() && !state.busy,
                        modifier = Modifier.testTag("notifications_clear"),
                    ) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.notifications_action_clear))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = state.unreadOnly,
                    onClick = { viewModel.setUnreadOnly(!state.unreadOnly) },
                    label = { Text(stringResource(R.string.notifications_filter_unread)) },
                    modifier = Modifier.testTag("notifications_unread_only"),
                )
            }
            PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
                when (val status = state.status) {
                    ListStatus.Loading -> LoadingState()
                    is ListStatus.Error -> FullPage { ErrorState(status.message, onRetry = viewModel::retry) }
                    ListStatus.Content -> if (state.items.isEmpty()) {
                        FullPage {
                            EmptyState(
                                title = stringResource(if (state.unreadOnly) R.string.notifications_empty_unread else R.string.notifications_empty),
                                description = stringResource(R.string.notifications_empty_hint),
                                icon = Icons.Outlined.NotificationsNone,
                            )
                        }
                    } else {
                        NotificationList(state, viewModel)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.notifications_clear_title),
            text = stringResource(R.string.notifications_clear_text),
            confirmText = stringResource(R.string.notifications_clear_confirm),
            destructive = true,
            onConfirm = {
                confirmClear = false
                viewModel.clearAll()
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun NotificationList(state: NotificationsUiState, viewModel: NotificationsViewModel) {
    val listState = rememberLazyListState()
    val zone = remember { ZoneId.systemDefault() }
    // 跨过零点后「今天 / 昨天」跟着变:每次进来按当时的日期算
    val today = remember(state.items) { LocalDate.now(zone) }
    val rows = remember(state.items, today) { NotificationsLogic.rows(state.items, zone, today) }
    LoadMoreTrigger(itemCount = state.items.size, isNearEnd = { listState.isNearEnd() }, onLoadMore = viewModel::loadMore)
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("notifications_list")) {
        items(rows, key = { it.key }, contentType = { it::class }) { row ->
            when (row) {
                is NotificationRow.DayHeader -> DayHeader(row.label)
                is NotificationRow.Item -> {
                    NotificationItem(
                        notification = row.notification,
                        opening = state.opening == row.notification.id,
                        onClick = { viewModel.open(row.notification) },
                    )
                    HorizontalDivider()
                }
            }
        }
        if (state.nextCursor != null || state.loadingMore || state.loadMoreError != null) {
            item("more") { LoadMoreFooter(isLoading = state.loadingMore, error = state.loadMoreError, onRetry = viewModel::loadMore) }
        }
    }
}

@Composable
private fun DayHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** 级别的图标与配色:提示 = 主色,警告 = 第三色,严重 = 错误色(未读的严重通知整行带一层浅底) */
private data class LevelStyle(val icon: ImageVector, val tint: Color, val container: Color)

@Composable
private fun levelStyle(level: NotificationLevel): LevelStyle = when (level) {
    NotificationLevel.INFO -> LevelStyle(Icons.Outlined.Info, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
    NotificationLevel.WARN -> LevelStyle(Icons.Outlined.WarningAmber, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.tertiaryContainer)
    NotificationLevel.ALERT -> LevelStyle(Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
}

@Composable
private fun NotificationItem(notification: AppNotification, opening: Boolean, onClick: () -> Unit) {
    val style = levelStyle(notification.level)
    val unread = !notification.read
    val levelLabel = stringResource(
        when (notification.level) {
            NotificationLevel.INFO -> R.string.notifications_level_info
            NotificationLevel.WARN -> R.string.notifications_level_warn
            NotificationLevel.ALERT -> R.string.notifications_level_alert
        },
    )
    ListItem(
        modifier = Modifier.clickable(enabled = !opening, onClick = onClick).testTag("notification_row"),
        leadingContent = {
            Box(
                modifier = Modifier.size(40.dp).background(style.container.copy(alpha = if (unread) 1f else 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(style.icon, contentDescription = levelLabel, tint = style.tint, modifier = Modifier.size(22.dp))
            }
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    notification.title,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (notification.count > 1) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.notifications_count, notification.count),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        supportingContent = {
            Column {
                if (notification.body.isNotBlank()) {
                    Text(notification.body, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    formatRelativeTime(notification.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        },
        trailingContent = {
            when {
                opening -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                unread -> Badge(modifier = Modifier.testTag("notification_unread_dot"))
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = if (unread && notification.level == NotificationLevel.ALERT) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
            } else {
                Color.Transparent
            },
        ),
    )
}
