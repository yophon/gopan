package xyz.yophon.gopan.feature.chat

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import xyz.yophon.gopan.core.ui.nav.Routes
import xyz.yophon.gopan.feature.drive.ifResumed

/** 「传输助手」tab 的嵌套图。传输列表是全局页(MainScaffold 顶层),从这里只是跳过去。 */
fun NavGraphBuilder.chatGraph(navController: NavController, onOpenPreview: (nodeId: String) -> Unit) {
    navigation<Routes.ChatTab>(startDestination = Routes.Chat) {
        composable<Routes.Chat> { entry ->
            ChatScreen(
                onOpenFolder = { id -> entry.ifResumed { navController.navigate(Routes.Folder(id)) } },
                onOpenTransfers = { entry.ifResumed { navController.navigate(Routes.Transfers) } },
                onOpenPreview = { id -> entry.ifResumed { onOpenPreview(id) } },
            )
        }
    }
}
