package xyz.yophon.gopan.feature.drive

import xyz.yophon.gopan.core.ui.components.FolderGlyph
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.ui.components.EmptyState
import xyz.yophon.gopan.core.ui.components.ErrorState
import xyz.yophon.gopan.core.ui.components.LoadingState

/**
 * 移动 / 复制 / 解压的目标选择器(全屏对话框):从根开始逐级进入,底部「选择此文件夹」。
 * 返回键先回上一级,到根再关闭。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerDialog(
    mode: PickerMode,
    token: Long,
    excludeIds: List<String>,
    busy: Boolean,
    /** 移动 / 复制失败的提示:页面的 Snackbar 被本对话框盖住,所以显示在这里 */
    error: String?,
    onPick: (Crumb) -> Unit,
    onDismiss: () -> Unit,
    viewModel: FolderPickerViewModel = hiltViewModel(key = "folder-picker"),
) {
    LaunchedEffect(token) { viewModel.open(token, excludeIds) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rootName = stringResource(R.string.drive_title_root)

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BackHandler(enabled = state.canGoUp) { viewModel.up() }
        DialogSystemBarsAppearance()
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(
                                when (mode) {
                                    PickerMode.MOVE -> R.string.drive_picker_move_title
                                    PickerMode.COPY -> R.string.drive_picker_copy_title
                                    PickerMode.EXTRACT -> R.string.archive_picker_title
                                },
                            ),
                        )
                    },
                    navigationIcon = {
                        if (state.canGoUp) {
                            IconButton(onClick = viewModel::up) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.drive_picker_up))
                            }
                        } else {
                            IconButton(onClick = onDismiss, enabled = !busy) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.drive_picker_close))
                            }
                        }
                    },
                )
            },
            bottomBar = {
                Surface(tonalElevation = 3.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = state.current.name.ifEmpty { rootName },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (error != null) {
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .testTag("picker:error"),
                            )
                        }
                        Button(
                            onClick = { onPick(state.current) },
                            enabled = !busy && state.status == ListStatus.Content,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        ) { Text(stringResource(R.string.drive_picker_choose)) }
                    }
                }
            },
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                BreadcrumbBar(crumbs = state.path, onClick = viewModel::jumpTo)
                HorizontalDivider()
                when (val status = state.status) {
                    ListStatus.Loading -> LoadingState()
                    is ListStatus.Error -> ErrorState(status.message, onRetry = viewModel::retry)
                    ListStatus.Content -> if (state.folders.isEmpty()) {
                        EmptyState(title = stringResource(R.string.drive_picker_empty), icon = Icons.Outlined.FolderOff)
                    } else {
                        FolderList(state, viewModel)
                    }
                }
            }
        }
    }
}

/**
 * 全屏对话框(decorFitsSystemWindows = false)有自己的窗口,不继承 Activity 的状态栏外观:
 * 浅色主题下状态栏图标会是白字配浅色背景。按当前配色给它设一遍。
 */
@Composable
private fun DialogSystemBarsAppearance() {
    val view = LocalView.current
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }
}

@Composable
private fun FolderList(state: FolderPickerUiState, viewModel: FolderPickerViewModel) {
    val listState = rememberLazyListState()
    LoadMoreTrigger(itemCount = state.folders.size, isNearEnd = { listState.isNearEnd() }, onLoadMore = viewModel::loadMore)
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(state.folders, key = { it.id }, contentType = { "folder" }) { folder ->
            ListItem(
                modifier = Modifier.clickable { viewModel.enter(folder) },
                leadingContent = {
                    FolderGlyph(Modifier.size(30.dp))
                },
                headlineContent = { Text(folder.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
            )
        }
        if (state.nextCursor != null || state.isLoadingMore) {
            item(key = "footer", contentType = "footer") { LoadMoreFooter(isLoading = state.isLoadingMore, error = null, onRetry = viewModel::loadMore) }
        }
    }
}
