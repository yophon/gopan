package xyz.yophon.gopan.core.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.navigation.NavBackStackEntry

/**
 * NavHost 的页面切换:短淡入淡出。Navigation Compose 默认是 700ms,
 * 每进一页都要先"糊"大半秒,再叠上加载就显得很慢。根导航与主界面导航都用这一套。
 */
const val NAV_FADE_MS = 180

val navEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = { fadeIn(tween(NAV_FADE_MS)) }

val navExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = { fadeOut(tween(NAV_FADE_MS)) }
