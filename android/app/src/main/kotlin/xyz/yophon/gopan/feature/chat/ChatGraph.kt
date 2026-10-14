package xyz.yophon.gopan.feature.chat

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import xyz.yophon.gopan.core.ui.components.PlaceholderScreen
import xyz.yophon.gopan.core.ui.nav.Routes

/** 「传输助手」tab 的嵌套图(P2)。 */
fun NavGraphBuilder.chatGraph(navController: NavController) {
    navigation<Routes.ChatTab>(startDestination = Routes.Chat) {
        composable<Routes.Chat> { PlaceholderScreen(title = "传输助手") }
        composable<Routes.Transfers> { PlaceholderScreen(title = "传输队列") }
    }
}
