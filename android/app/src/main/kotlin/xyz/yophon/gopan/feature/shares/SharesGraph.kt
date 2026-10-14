package xyz.yophon.gopan.feature.shares

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.toRoute
import xyz.yophon.gopan.core.ui.components.PlaceholderScreen
import xyz.yophon.gopan.core.ui.nav.Routes

/** 「分享」tab 的嵌套图(P3)。 */
fun NavGraphBuilder.sharesGraph(navController: NavController) {
    navigation<Routes.SharesTab>(startDestination = Routes.Shares) {
        composable<Routes.Shares> { PlaceholderScreen(title = "分享") }
        composable<Routes.ShareVisits> { entry ->
            val route = entry.toRoute<Routes.ShareVisits>()
            PlaceholderScreen(title = "访问记录 ${route.shareId}")
        }
    }
}
