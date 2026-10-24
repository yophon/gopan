package xyz.yophon.gopan.feature.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.activity.Activity
import xyz.yophon.gopan.core.data.activity.ActivityAction
import xyz.yophon.gopan.core.ui.components.EmptyState
import xyz.yophon.gopan.core.ui.components.ErrorState
import xyz.yophon.gopan.core.ui.components.ListSkeleton
import xyz.yophon.gopan.feature.drive.FullPage
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.LoadMoreFooter
import xyz.yophon.gopan.feature.drive.LoadMoreTrigger
import xyz.yophon.gopan.feature.drive.NestedScreenInsets
import xyz.yophon.gopan.feature.drive.TopBarInsets
import xyz.yophon.gopan.feature.drive.isNearEnd
import xyz.yophon.gopan.feature.drive.resolve
import xyz.yophon.gopan.feature.notifications.NotificationNav
import java.time.LocalDate
import java.time.ZoneId
import androidx.compose.ui.input.nestedscroll.nestedScroll
import xyz.yophon.gopan.feature.drive.bottomBarPadding
import xyz.yophon.gopan.feature.drive.pinnedHeaderTopBarColors
import xyz.yophon.gopan.feature.drive.rememberSubPageScroll

/**
 * 「我的 → 动态」(doc/23 二.4):按天分组,同一批折叠成一组;顶部按来源筛选;每行「撤回」,一组「撤回这一组」(先预览);
 * 按来源筛着时顶栏「全部撤回」。点一行打开那个文件(文件夹进去,文件进所在文件夹再预览),由 [onOpen] 交给导航图。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    onBack: () -> Unit,
    onOpen: (NotificationNav.Drive) -> Unit,
    viewModel: ActivityViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val currentOnOpen by rememberUpdatedState(onOpen)

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ActivityEffect.Message -> launch { snackbar.showSnackbar(effect.text.resolve(context)) }
                is ActivityEffect.Open -> currentOnOpen(effect.nav)
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResumed() }

    val topBarScroll = rememberSubPageScroll()
    Scaffold(
        contentWindowInsets = NestedScreenInsets,
        modifier = Modifier.nestedScroll(topBarScroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                windowInsets = TopBarInsets,
                scrollBehavior = topBarScroll,
                colors = pinnedHeaderTopBarColors(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.preview_back))
                    }
                },
                title = { Text(stringResource(R.string.activity_title)) },
                actions = {
                    if (state.query.actorKind != null) {
                        TextButton(
                            onClick = viewModel::requestUndoAll,
                            enabled = state.canUndoAll && state.working == null,
                            modifier = Modifier.testTag("activity_undo_all"),
                        ) {
                            if (state.working == ActivityUiState.ALL_KEY) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text(stringResource(R.string.activity_action_undo_all))
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // 顶栏收起时筛选条顶到最上面;列表能滚到底部导航后面
        Column(modifier = Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()) {
            FilterBar(state, viewModel)
            PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
                when (val status = state.status) {
                    ListStatus.Loading -> ListSkeleton()
                    is ListStatus.Error -> FullPage { ErrorState(status.message, onRetry = viewModel::retry) }
                    ListStatus.Content -> if (state.items.isEmpty()) {
                        FullPage {
                            EmptyState(
                                title = stringResource(if (state.query.isEmpty) R.string.activity_empty else R.string.activity_empty_filtered),
                                description = stringResource(R.string.activity_empty_hint),
                                icon = Icons.Outlined.History,
                            )
                        }
                    } else {
                        ActivityList(state, viewModel)
                    }
                }
            }
        }
    }

    state.confirm?.let { confirm -> UndoConfirmDialog(confirm, busy = state.working != null, onConfirm = viewModel::confirmUndo, onDismiss = viewModel::dismissConfirm) }
}

/** 来源筛选:「全部来源」+ 近 90 天出现过的来源;通知点进来带的时间段是一个可去掉的标签 */
@Composable
private fun FilterBar(state: ActivityUiState, viewModel: ActivityViewModel) {
    val chips = remember(state.actors, state.query) { ActivityLogic.filterChips(state.actors, state.query) }
    val zone = remember { ZoneId.systemDefault() }
    LazyRow(
        modifier = Modifier.fillMaxWidth().testTag("activity_filters"),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.query.since?.let { since ->
            item("since") {
                InputChip(
                    selected = true,
                    onClick = viewModel::clearSince,
                    label = { Text(ActivityLogic.sinceText(since, zone, LocalDate.now(zone))) },
                    trailingIcon = {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.activity_clear_since),
                            modifier = Modifier.size(InputChipDefaults.IconSize),
                        )
                    },
                )
            }
        }
        item("all") {
            FilterChip(
                selected = state.query.actorKind == null,
                onClick = { viewModel.selectActor(null) },
                label = { Text(stringResource(R.string.activity_filter_all)) },
            )
        }
        items(chips, key = { "${it.kind}:${it.ref}" }) { actor ->
            FilterChip(
                selected = ActivityLogic.isSelected(actor, state.query),
                onClick = { viewModel.selectActor(actor) },
                label = { Text(ActivityLogic.actorText(actor), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.testTag("activity_actor_chip"),
            )
        }
    }
}

@Composable
private fun ActivityList(state: ActivityUiState, viewModel: ActivityViewModel) {
    val listState = rememberLazyListState()
    val zone = remember { ZoneId.systemDefault() }
    // 跨过零点后「今天 / 昨天」跟着变:每次数据变了按当时的日期算
    val today = remember(state.items) { LocalDate.now(zone) }
    val rows = remember(state.items, state.expanded, today) { ActivityLogic.rows(state.items, state.expanded, zone, today) }
    LoadMoreTrigger(itemCount = state.items.size, isNearEnd = { listState.isNearEnd() }, onLoadMore = viewModel::loadMore)
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("activity_list"), contentPadding = PaddingValues(bottom = bottomBarPadding(24.dp))) {
        items(rows, key = { it.key }, contentType = { it::class }) { row ->
            when (row) {
                is ActivityRow.DayHeader -> DayHeader(row.label)
                is ActivityRow.Single -> {
                    ActivityItem(
                        activity = row.activity,
                        zone = zone,
                        working = state.working == row.activity.id,
                        undoEnabled = state.working == null,
                        onClick = { viewModel.open(row.activity) },
                        onUndo = { viewModel.undo(row.activity) },
                    )
                    HorizontalDivider()
                }
                is ActivityRow.Group -> {
                    GroupItem(
                        group = row,
                        zone = zone,
                        working = state.working == row.batchId,
                        undoEnabled = state.working == null,
                        onToggle = { viewModel.toggleGroup(row.batchId) },
                        onUndo = { viewModel.requestUndoGroup(row.batchId) },
                    )
                    if (!row.expanded) HorizontalDivider()
                }
                is ActivityRow.Member -> {
                    ActivityItem(
                        activity = row.activity,
                        zone = zone,
                        working = state.working == row.activity.id,
                        undoEnabled = state.working == null,
                        onClick = { viewModel.open(row.activity) },
                        onUndo = { viewModel.undo(row.activity) },
                        nested = true,
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 32.dp))
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

private fun actionIcon(action: ActivityAction): ImageVector = when (action) {
    ActivityAction.MKDIR -> Icons.Outlined.CreateNewFolder
    ActivityAction.UPLOAD -> Icons.Outlined.Upload
    ActivityAction.OVERWRITE -> Icons.Outlined.EditNote
    ActivityAction.RENAME -> Icons.Outlined.DriveFileRenameOutline
    ActivityAction.MOVE -> Icons.AutoMirrored.Outlined.DriveFileMove
    ActivityAction.COPY -> Icons.Outlined.ContentCopy
    ActivityAction.DELETE -> Icons.Outlined.DeleteOutline
    ActivityAction.RESTORE -> Icons.Outlined.RestoreFromTrash
    ActivityAction.PURGE -> Icons.Outlined.DeleteForever
    ActivityAction.EXTRACT -> Icons.Outlined.FolderZip
    ActivityAction.OTHER -> Icons.Outlined.History
}

@Composable
private fun ActionBadge(icon: ImageVector, dimmed: Boolean, destructive: Boolean) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val container = if (destructive) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
    Box(
        modifier = Modifier.size(40.dp).background(container.copy(alpha = if (dimmed) 0.35f else 0.8f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint.copy(alpha = if (dimmed) 0.5f else 1f), modifier = Modifier.size(22.dp))
    }
}

/** 小标签:已撤回 / 撤回操作 / 不可撤回 */
@Composable
private fun Tag(text: String, emphasized: Boolean = false) {
    Surface(
        color = if (emphasized) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (emphasized) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** 一条的标签:已撤回(强调)/ 撤回操作 / 不可撤回 */
@Composable
private fun activityTags(activity: Activity): List<Pair<String, Boolean>> = buildList {
    if (activity.undone) add(stringResource(R.string.activity_tag_undone) to true)
    if (activity.undoOf != null) add(stringResource(R.string.activity_tag_undo_of) to false)
    if (!activity.undoable && !activity.undone) add(stringResource(R.string.activity_tag_not_undoable) to false)
}

/** 时刻 · 来源,后面跟标签 */
@Composable
private fun MetaLine(activity: Activity, zone: ZoneId, tags: List<Pair<String, Boolean>> = activityTags(activity)) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
        Text(
            "${ActivityLogic.timeText(activity.at, zone)} · ${ActivityLogic.actorText(activity.actor)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        tags.forEach { (text, emphasized) ->
            Spacer(Modifier.width(6.dp))
            Tag(text, emphasized)
        }
    }
}

@Composable
private fun ActivityItem(
    activity: Activity,
    zone: ZoneId,
    working: Boolean,
    undoEnabled: Boolean,
    onClick: () -> Unit,
    onUndo: () -> Unit,
    nested: Boolean = false,
) {
    val verb = ActivityLogic.actionLabel(activity.action)
    val destructive = activity.action == ActivityAction.DELETE || activity.action == ActivityAction.PURGE
    ListItem(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(start = if (nested) 16.dp else 0.dp)
            .testTag("activity_row"),
        colors = ListItemDefaults.colors(containerColor = if (nested) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent),
        leadingContent = { ActionBadge(actionIcon(activity.action), dimmed = activity.undone, destructive = destructive) },
        headlineContent = {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(verb) }
                    append("  ")
                    append(ActivityLogic.nameText(activity))
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                val size = ActivityLogic.sizeText(activity)
                Text(
                    listOfNotNull(ActivityLogic.locationText(activity), size).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                activity.note?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                MetaLine(activity, zone)
            }
        },
        trailingContent = {
            if (working) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else if (!activity.undone) {
                TextButton(
                    onClick = onUndo,
                    enabled = undoEnabled && activity.undoable,
                    modifier = Modifier.testTag("activity_undo"),
                ) { Text(stringResource(R.string.activity_action_undo)) }
            }
        },
    )
}

@Composable
private fun GroupItem(
    group: ActivityRow.Group,
    zone: ZoneId,
    working: Boolean,
    undoEnabled: Boolean,
    onToggle: () -> Unit,
    onUndo: () -> Unit,
) {
    val items = group.items
    val actions = items.mapTo(HashSet()) { it.action }
    val single = actions.singleOrNull()
    val allUndone = ActivityLogic.groupAllUndone(items)
    ListItem(
        modifier = Modifier.clickable(onClick = onToggle).testTag("activity_group"),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            ActionBadge(
                icon = single?.let(::actionIcon) ?: Icons.Outlined.Layers,
                dimmed = allUndone,
                destructive = single == ActivityAction.DELETE || single == ActivityAction.PURGE,
            )
        },
        headlineContent = {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(ActivityLogic.groupTitle(items)) }
                    append("  ")
                    append(ActivityLogic.groupNames(items))
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                ActivityLogic.groupLocation(items)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                // 组的标签只看整组:全撤回过才标「已撤回」;是一次撤回产生的(整批撤回)标「撤回操作」
                val tags = buildList {
                    if (allUndone) add(stringResource(R.string.activity_tag_undone) to true)
                    if (items.all { it.undoOf != null }) add(stringResource(R.string.activity_tag_undo_of) to false)
                }
                MetaLine(group.first, zone, tags)
                if (!allUndone) {
                    Box(modifier = Modifier.padding(top = 2.dp)) {
                        if (working) {
                            CircularProgressIndicator(modifier = Modifier.padding(8.dp).size(18.dp), strokeWidth = 2.dp)
                        } else {
                            TextButton(
                                onClick = onUndo,
                                enabled = undoEnabled && ActivityLogic.groupUndoable(items),
                                contentPadding = ButtonDefaults.TextButtonWithIconContentPadding,
                                modifier = Modifier.testTag("activity_undo_group"),
                            ) { Text(stringResource(R.string.activity_action_undo_group)) }
                        }
                    }
                }
            }
        },
        trailingContent = {
            Icon(
                if (group.expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = stringResource(if (group.expanded) R.string.activity_group_collapse else R.string.activity_group_expand),
            )
        },
    )
}

/** 整批撤回的确认:预览的结论 + 撤不了的原因;确认按钮写明撤几项 */
@Composable
private fun UndoConfirmDialog(confirm: UndoConfirm, busy: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val preview = confirm.preview
    val reasons = remember(preview) { ActivityLogic.reasonLines(preview) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (confirm.all) R.string.activity_confirm_title_all else R.string.activity_confirm_title_group)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(ActivityLogic.previewText(preview))
                reasons.forEach { line ->
                    Text(
                        "· " + ActivityLogic.reasonText(line),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (preview.done > 0) {
                    Text(
                        stringResource(R.string.activity_confirm_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy && preview.done > 0, modifier = Modifier.testTag("activity_confirm_undo")) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.activity_confirm_button, preview.done))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
