package xyz.yophon.gopan.core.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withTimeoutOrNull

/*
 * 手势小工具:抢在子元素之前的长按、标签栏与左右滑动页面的联动。
 */

/**
 * 长按:在 Initial 阶段先于子元素判断,按住不动到长按时长就触发并吃掉这次手势剩下的事件,
 * 里面的链接、按钮不会再当成单击。用在整块可长按、内部又有可点子元素的地方(传输助手里整条都是链接的消息)。
 * 移动超过触摸阈值就放弃,不影响列表滚动;外层不要再挂 combinedClickable 的 onLongClick,否则会触发两次。
 */
@Composable
fun Modifier.longPressFirst(onLongPress: (() -> Unit)?): Modifier {
    if (onLongPress == null) return this
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(onLongPress)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // 超时 = 一直按着没动没抬:是长按;块正常结束 = 抬起、移动或被别人消费
            val longPressed = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var aborted = false
                while (!aborted) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    aborted = change == null || change.changedToUp() || change.isConsumed ||
                        (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                }
                false
            } ?: true
            if (longPressed) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                current()
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
    }
}

/**
 * 标签栏嵌在列表里、没法改成左右翻页的地方(Agent 接入):横着划过一段距离就切到相邻标签。
 * 只在横向越过触摸阈值后才接手,竖着滚列表不受影响。
 */
@Composable
fun Modifier.swipeBetweenTabs(onNext: () -> Unit, onPrevious: () -> Unit): Modifier {
    val next by rememberUpdatedState(onNext)
    val previous by rememberUpdatedState(onPrevious)
    return pointerInput(Unit) {
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onHorizontalDrag = { change, amount ->
                total += amount
                change.consume()
            },
            onDragEnd = {
                val threshold = 72.dp.toPx()
                if (total < -threshold) next() else if (total > threshold) previous()
            },
        )
    }
}

/**
 * 标签栏 + HorizontalPager 联动:点标签([selected] 变了)滚到那一页,手指滑动停下后把新页回报给 [onSelect]。
 * 标签栏的选中项用返回的 PagerState.currentPage,滑过一半指示条就跟过去。
 */
@Composable
fun rememberTabPager(selected: Int, pageCount: Int, onSelect: (Int) -> Unit): PagerState {
    val state = rememberPagerState(initialPage = selected.coerceIn(0, (pageCount - 1).coerceAtLeast(0))) { pageCount }
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    LaunchedEffect(selected, pageCount) {
        val target = selected.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        if (state.settledPage != target && !state.isScrollInProgress) state.animateScrollToPage(target)
    }
    LaunchedEffect(state) {
        // 跳过第一次:选中项超出当前页数(「直链」标签还没出现)时,初始页被夹回来,不能把它当成用户选的
        snapshotFlow { state.settledPage }.drop(1).collect { page -> if (page != currentSelected) currentOnSelect(page) }
    }
    return state
}
