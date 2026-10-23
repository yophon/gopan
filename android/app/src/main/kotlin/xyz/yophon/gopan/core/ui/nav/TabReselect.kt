package xyz.yophon.gopan.core.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter

/**
 * 底部 tab 已经在首页时再点一次:MainScaffold 发出该 tab 的路由(Routes.DriveTab 等),
 * 首页用 [OnTabReselect] 接住,把列表滚回顶部(传输助手滚到最新)。不在首页时 MainScaffold 直接退回首页,不发事件。
 */
class TabReselectEvents {
    private val events = MutableSharedFlow<Any>(extraBufferCapacity = 1)

    fun emit(tab: Any) {
        events.tryEmit(tab)
    }

    internal fun of(tab: Any) = events.filter { it == tab }
}

val LocalTabReselect = staticCompositionLocalOf { TabReselectEvents() }

/** [tab] 在首页时又被点了一次就执行 [action];[enabled] = false 时不接(比如文件页只在根目录接) */
@Composable
fun OnTabReselect(tab: Any, enabled: Boolean = true, action: suspend () -> Unit) {
    val events = LocalTabReselect.current
    val current by rememberUpdatedState(action)
    LaunchedEffect(events, tab, enabled) {
        if (enabled) events.of(tab).collect { current() }
    }
}
