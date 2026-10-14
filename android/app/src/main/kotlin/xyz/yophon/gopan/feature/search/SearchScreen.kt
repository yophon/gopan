package xyz.yophon.gopan.feature.search

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.ui.transfer.rememberTransferPermissions
import xyz.yophon.gopan.core.ui.components.EmptyState
import xyz.yophon.gopan.core.ui.components.ErrorState
import xyz.yophon.gopan.core.ui.components.LoadingState
import xyz.yophon.gopan.feature.drive.ListStatus
import xyz.yophon.gopan.feature.drive.LoadMoreFooter
import xyz.yophon.gopan.feature.drive.LoadMoreTrigger
import xyz.yophon.gopan.feature.drive.NodeDialogHost
import xyz.yophon.gopan.feature.drive.NodeEffect
import xyz.yophon.gopan.feature.drive.NestedScreenInsets
import xyz.yophon.gopan.feature.drive.NodeRow
import xyz.yophon.gopan.feature.drive.SEARCH_ACTIONS
import xyz.yophon.gopan.feature.drive.isNearEnd
import xyz.yophon.gopan.feature.drive.nodeSecondaryText
import xyz.yophon.gopan.feature.drive.resolve

/**
 * 搜索页:顶栏就是输入框(自动聚焦,300ms 防抖),结果复用文件页的行与操作。
 * 输入框文本放在界面的 rememberSaveable 里(StateFlow 回灌 TextField 会丢字),ViewModel 只收 trim 后的关键词。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenFolder: (folderId: String) -> Unit,
    onOpenRoot: () -> Unit,
    onOpenPreview: (nodeId: String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val inputError by viewModel.inputError.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val permissions = rememberTransferPermissions(onStorageResult = viewModel::onStoragePermission)
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val currentOnOpenFolder by rememberUpdatedState(onOpenFolder)
    val currentOnOpenPreview by rememberUpdatedState(onOpenPreview)

    var text by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(text) { viewModel.onQueryChange(text) }
    var focused by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!focused) {
            focused = true
            focusRequester.requestFocus()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is NodeEffect.Message -> launch { snackbarHostState.showSnackbar(effect.text.resolve(context)) }
                is NodeEffect.OpenFolder -> currentOnOpenFolder(effect.folderId)
                is NodeEffect.OpenPreview -> currentOnOpenPreview(effect.nodeId)
                NodeEffect.RequestStoragePermission -> permissions.requestStoragePermission()
                NodeEffect.TransferQueued -> permissions.onTransferQueued()
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResumed() }

    Scaffold(
        contentWindowInsets = NestedScreenInsets,
        topBar = {
            TopAppBar(
                windowInsets = NestedScreenInsets,
                title = {
                    TextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                        singleLine = true,
                        trailingIcon = if (text.isNotEmpty()) {
                            {
                                IconButton(onClick = { text = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_clear))
                                }
                            }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.drive_nav_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        // 键盘弹起时结果末尾、加载更多与重试不被挡住
        val contentModifier = Modifier
            .padding(padding)
            .consumeWindowInsets(padding)
            .imePadding()
            .fillMaxSize()
        when {
            state.isIdle -> EmptyState(
                title = stringResource(R.string.search_idle),
                icon = Icons.Filled.Search,
                modifier = contentModifier,
            )
            state.status == ListStatus.Loading -> LoadingState(modifier = contentModifier)
            state.status is ListStatus.Error -> ErrorState(
                (state.status as ListStatus.Error).message,
                modifier = contentModifier,
                onRetry = viewModel::retry,
            )
            state.isEmpty -> EmptyState(
                title = stringResource(R.string.search_empty, state.query),
                icon = Icons.Outlined.SearchOff,
                modifier = contentModifier,
            )
            else -> SearchResults(state, viewModel, contentModifier)
        }
    }

    NodeDialogHost(
        dialog = dialog,
        busy = busy,
        inputError = inputError,
        viewModel = viewModel,
        actions = SEARCH_ACTIONS,
        // 「所在目录」:跳到结果的父文件夹(根目录下的直接回网盘首页)
        onLocate = { node -> node.parentId?.let(onOpenFolder) ?: onOpenRoot() },
    )
}

@Composable
private fun SearchResults(state: SearchUiState, viewModel: SearchViewModel, modifier: Modifier) {
    val listState = rememberLazyListState()
    LoadMoreTrigger(
        itemCount = state.items.size,
        isNearEnd = { listState.isNearEnd() },
        onLoadMore = viewModel::loadMore,
    )
    LazyColumn(state = listState, modifier = modifier, contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "count", contentType = "count") {
            Text(
                stringResource(R.string.search_result_count, state.total),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        items(state.items, key = { it.id }, contentType = { "node" }) { node ->
            NodeRow(
                node = node,
                secondary = nodeSecondaryText(node),
                onClick = { viewModel.onItemClick(node) },
                onLongClick = { viewModel.showActions(node) },
                onMore = { viewModel.showActions(node) },
            )
        }
        if (state.nextCursor != null || state.isLoadingMore || state.loadMoreError != null) {
            item(key = "footer", contentType = "footer") {
                LoadMoreFooter(isLoading = state.isLoadingMore, error = state.loadMoreError, onRetry = viewModel::loadMore)
            }
        }
    }
}
