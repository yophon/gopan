package xyz.yophon.gopan.feature.drive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier

/*
 * 列表页共用的脚手架:分页触发、空态/错误态的可下拉容器、嵌套 Scaffold 的 insets。
 */

/** 距列表底部还有几项时就开始拉下一页 */
const val LOAD_MORE_THRESHOLD = 8

/** 最后一个可见项距末尾不足 [threshold] 项;尚未布局(没有可见项)时为 false,避免首帧误触发 */
fun LazyListState.isNearEnd(threshold: Int = LOAD_MORE_THRESHOLD): Boolean {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull()?.index ?: return false
    return last >= info.totalItemsCount - threshold
}

fun LazyGridState.isNearEnd(threshold: Int = LOAD_MORE_THRESHOLD): Boolean {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull()?.index ?: return false
    return last >= info.totalItemsCount - threshold
}

/**
 * 滚到接近底部就拉下一页。[itemCount] 变了(新页到了)再判断一次:一页很短、仍在底部时继续拉,
 * 否则 nearEnd 一直是 true 不会再触发。
 */
@Composable
fun LoadMoreTrigger(itemCount: Int, isNearEnd: () -> Boolean, onLoadMore: () -> Unit) {
    val nearEnd by remember { derivedStateOf(isNearEnd) }
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(nearEnd, itemCount) { if (nearEnd) currentOnLoadMore() }
}

/** 空态 / 错误态也放进可滚动容器,这样 PullToRefreshBox 的下拉刷新照样能用 */
@Composable
fun FullPage(content: @Composable () -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { Box(modifier = Modifier.fillParentMaxSize()) { content() } }
    }
}

/**
 * tab 内页面的 Scaffold / TopAppBar 用零 insets:MainScaffold 已经按系统栏给了内边距,
 * 再让内层处理一遍会出现双倍状态栏/导航栏留白。
 */
val NestedScreenInsets: WindowInsets = WindowInsets(0, 0, 0, 0)
