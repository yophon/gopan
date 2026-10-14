package xyz.yophon.gopan.feature.drive

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.toRoute
import xyz.yophon.gopan.core.ui.components.PlaceholderScreen
import xyz.yophon.gopan.core.ui.nav.Routes

/** 「文件」tab 的嵌套图:目录浏览、回收站、搜索。文件功能线替换此文件及本包内文件。 */
fun NavGraphBuilder.driveGraph(navController: NavController) {
    navigation<Routes.DriveTab>(startDestination = Routes.Drive) {
        composable<Routes.Drive> { PlaceholderScreen(title = "文件") }
        composable<Routes.Folder> { entry ->
            val route = entry.toRoute<Routes.Folder>()
            PlaceholderScreen(title = "文件夹 ${route.folderId}")
        }
        composable<Routes.Trash> { PlaceholderScreen(title = "回收站") }
        composable<Routes.Search> { PlaceholderScreen(title = "搜索") }
    }
}
