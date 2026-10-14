package xyz.yophon.gopan.feature.drive

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import xyz.yophon.gopan.core.ui.nav.Routes
import xyz.yophon.gopan.feature.search.SearchScreen
import xyz.yophon.gopan.feature.trash.TrashScreen

/**
 * 「文件」tab 的嵌套图:根目录(Routes.Drive)、文件夹(Routes.Folder)、回收站、搜索。
 * 进入文件夹是 push 一个 Folder 路由;面包屑点击优先 popBackStack 回到已在栈里的那一级,不在栈里才 navigate
 * (从搜索结果直接跳进深层文件夹时会这样)。
 */
fun NavGraphBuilder.driveGraph(navController: NavController) {
    navigation<Routes.DriveTab>(startDestination = Routes.Drive) {
        composable<Routes.Drive> {
            DriveScreen(
                onOpenFolder = { navController.navigate(Routes.Folder(it)) },
                onCrumbClick = { navController.navigateToCrumb(it) },
                onOpenSearch = { navController.navigate(Routes.Search) },
                onOpenTrash = { navController.navigate(Routes.Trash) },
                onBack = null,
            )
        }
        composable<Routes.Folder> {
            DriveScreen(
                onOpenFolder = { navController.navigate(Routes.Folder(it)) },
                onCrumbClick = { navController.navigateToCrumb(it) },
                onOpenSearch = { navController.navigate(Routes.Search) },
                onOpenTrash = { navController.navigate(Routes.Trash) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<Routes.Trash> {
            TrashScreen(onBack = { navController.popBackStack() })
        }
        composable<Routes.Search> {
            SearchScreen(
                onBack = { navController.popBackStack() },
                onOpenFolder = { navController.navigate(Routes.Folder(it)) },
            )
        }
    }
}

private fun NavController.navigateToCrumb(crumb: Crumb) {
    val id = crumb.id
    if (id == null) {
        if (!popBackStack(Routes.Drive, inclusive = false)) navigate(Routes.Drive)
    } else {
        if (!popBackStack(Routes.Folder(id), inclusive = false)) navigate(Routes.Folder(id))
    }
}
