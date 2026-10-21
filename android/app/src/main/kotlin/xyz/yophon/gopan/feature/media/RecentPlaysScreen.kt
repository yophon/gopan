package xyz.yophon.gopan.feature.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
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
import xyz.yophon.gopan.core.data.media.RecentPlay
import xyz.yophon.gopan.core.ui.components.EmptyState
import xyz.yophon.gopan.core.ui.components.ErrorState
import xyz.yophon.gopan.core.ui.components.LoadingState
import xyz.yophon.gopan.core.ui.format.formatRelativeTime
import xyz.yophon.gopan.feature.drive.FullPage
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.NestedScreenInsets
import xyz.yophon.gopan.feature.drive.NodeVisual
import java.time.Instant

/** 最近播放:名称、看到哪(时间或页码)、多久以前;点一条接着看,右边的 × 移出 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentPlaysScreen(
    onBack: () -> Unit,
    onOpenPreview: (nodeId: String) -> Unit,
    viewModel: RecentPlaysViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val currentOnOpenPreview by rememberUpdatedState(onOpenPreview)

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is RecentPlaysEffect.OpenPreview -> currentOnOpenPreview(effect.nodeId)
                is RecentPlaysEffect.Message -> launch { snackbarHostState.showSnackbar(effect.text.resolve(context)) }
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResumed() }

    Scaffold(
        contentWindowInsets = NestedScreenInsets,
        topBar = {
            TopAppBar(
                windowInsets = NestedScreenInsets,
                title = { Text(stringResource(R.string.media_recent_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.drive_nav_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .testTag("recent_plays"),
        ) {
            when (val status = state.status) {
                ListStatus.Loading -> LoadingState()
                is ListStatus.Error -> FullPage { ErrorState(status.message, onRetry = viewModel::retry) }
                ListStatus.Content -> if (state.items.isEmpty()) {
                    FullPage {
                        EmptyState(
                            title = stringResource(R.string.media_recent_empty),
                            description = stringResource(R.string.media_recent_empty_desc),
                            icon = Icons.Outlined.History,
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(state.items, key = { it.node.id }) { play ->
                            RecentPlayRow(
                                play = play,
                                nowMillis = state.nowMillis,
                                onClick = { viewModel.open(play) },
                                onRemove = { viewModel.remove(play) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentPlayRow(play: RecentPlay, nowMillis: Long, onClick: () -> Unit, onRemove: () -> Unit) {
    val progress = when (val t = progressText(play.node.preview.kind, play.progress)) {
        is ProgressText.Time -> stringResource(R.string.media_recent_position, t.position, t.duration)
        is ProgressText.Pages -> stringResource(R.string.media_recent_page, t.page, t.count)
    }
    val time = formatRelativeTime(play.progress.updatedAt, Instant.ofEpochMilli(nowMillis))
    ListItem(
        modifier = Modifier
            .clickable(onClick = onClick)
            .testTag("recent:${play.node.name}"),
        leadingContent = { NodeVisual(play.node, modifier = Modifier.size(44.dp)) },
        headlineContent = {
            Text(play.node.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "$progress · $time",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { progressFraction(play.progress) },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )
            }
        },
        trailingContent = {
            IconButton(onClick = onRemove, modifier = Modifier.testTag("recent_remove:${play.node.name}")) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.media_recent_remove))
            }
        },
    )
}
