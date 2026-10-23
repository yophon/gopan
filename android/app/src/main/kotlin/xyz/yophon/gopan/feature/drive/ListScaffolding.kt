package xyz.yophon.gopan.feature.drive

import kotlin.math.roundToInt
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.Stable
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.compositionLocalOf
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
 * 列表页共用的脚手架:分页触发、空态/错误态的可下拉容器、嵌套 Scaffold 的 insets、跟着滚动收起的顶栏。
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
 * 主界面底部浮着的迷你播放条与胶囊导航(连同系统导航栏)占的高度,MainScaffold 量出来往下传。
 * 键盘弹起时导航收起,只剩系统导航栏的高度。
 */
val LocalBottomBarInset = compositionLocalOf { 0.dp }

/**
 * 主界面底部浮层(迷你播放条 + 胶囊导航)往下滑出的像素:传输助手随滚动把它连同输入栏一起收起,离开页面时归零。
 */
@Stable
class BottomChromeState {
    var offsetPx by mutableFloatStateOf(0f)
}

val LocalBottomChrome = staticCompositionLocalOf { BottomChromeState() }

/**
 * tab 内页面 Scaffold 的 contentWindowInsets:两侧 MainScaffold 已经让过,顶部由顶栏让出状态栏,这里只让出底部浮着的导航。
 * 想让列表滚到导航后面的页面,内容只用顶部内边距,底部那段放进列表的 contentPadding。
 */
val NestedScreenInsets: WindowInsets
    @Composable get() = WindowInsets(bottom = LocalBottomBarInset.current)

/** 列表按宽度分栏(LazyVerticalGrid + GridCells.Adaptive)时一栏的最窄宽度:手机竖屏一栏,横屏两栏 */
val ListColumnMinWidth = 340.dp

/** tab 内页面 TopAppBar 的 insets:页面铺到屏幕顶(状态栏透明),顶栏自己让出状态栏 */
val TopBarInsets: WindowInsets
    @Composable get() = WindowInsets.statusBars

/**
 * 跟着滚动收起的顶栏(底部 tab 的首页)的颜色:白底,内容滚到底下也不变深;
 * 收起时连状态栏那一条一起淡成透明,滚动的内容从状态栏底下露出来(配合 [belowCollapsingTopBar])。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun collapsingTopBarColors(scroll: TopAppBarScrollBehavior): TopAppBarColors {
    val background = MaterialTheme.colorScheme.surface.copy(alpha = 1f - scroll.state.collapsedFraction)
    return TopAppBarDefaults.topAppBarColors(containerColor = background, scrolledContainerColor = background)
}

/**
 * 跟着滚动收起的顶栏下面的内容:让出顶栏;顶栏收起时再往上伸进状态栏那一条。
 * 在布局阶段读滚动状态,顶栏收放的每一帧不重组页面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Modifier.belowCollapsingTopBar(padding: PaddingValues, scroll: TopAppBarScrollBehavior): Modifier {
    val statusBars = WindowInsets.statusBars
    return layout { measurable, constraints ->
        val top = (padding.calculateTopPadding().toPx() - statusBars.getTop(this) * scroll.state.collapsedFraction)
            .roundToInt().coerceAtLeast(0)
        val height = if (constraints.hasBoundedHeight) (constraints.maxHeight - top).coerceAtLeast(0) else constraints.maxHeight
        val placeable = measurable.measure(constraints.copy(minHeight = minOf(constraints.minHeight, height), maxHeight = height))
        layout(placeable.width, placeable.height + top) { placeable.place(0, top) }
    }
}
