package xyz.yophon.gopan.feature.drive

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
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
 *
 * 所有导航动作只在本页处于 RESUMED 时生效:导航一开始当前条目就离开 RESUMED,
 * 于是连点返回不会把起始页也弹掉(变成空白页),连点面包屑/文件夹也不会重复入栈。
 */
fun NavGraphBuilder.driveGraph(navController: NavController) {
    navigation<Routes.DriveTab>(startDestination = Routes.Drive) {
        composable<Routes.Drive> { entry ->
            DriveScreen(
                onOpenFolder = { id -> entry.ifResumed { navController.navigate(Routes.Folder(id)) } },
                onCrumbClick = { crumb -> entry.ifResumed { navController.navigateToCrumb(crumb) } },
                onOpenSearch = { entry.ifResumed { navController.navigate(Routes.Search) } },
                onOpenTrash = { entry.ifResumed { navController.navigate(Routes.Trash) } },
                onBack = null,
            )
        }
        composable<Routes.Folder> { entry ->
            DriveScreen(
                onOpenFolder = { id -> entry.ifResumed { navController.navigate(Routes.Folder(id)) } },
                onCrumbClick = { crumb -> entry.ifResumed { navController.navigateToCrumb(crumb) } },
                onOpenSearch = { entry.ifResumed { navController.navigate(Routes.Search) } },
                onOpenTrash = { entry.ifResumed { navController.navigate(Routes.Trash) } },
                onBack = { entry.ifResumed { navController.popBackStack() } },
            )
        }
        composable<Routes.Trash> { entry ->
            TrashScreen(onBack = { entry.ifResumed { navController.popBackStack() } })
        }
        composable<Routes.Search> { entry ->
            SearchScreen(
                onBack = { entry.ifResumed { navController.popBackStack() } },
                onOpenFolder = { id -> entry.ifResumed { navController.navigate(Routes.Folder(id)) } },
                onOpenRoot = { entry.ifResumed { navController.navigateToCrumb(Crumb(null, "")) } },
            )
        }
    }
}

/** 只有当前页面还在前台(没有正在进行的导航)时才执行 */
internal inline fun NavBackStackEntry.ifResumed(action: () -> Unit) {
    if (lifecycle.currentState == Lifecycle.State.RESUMED) action()
}

private fun NavController.navigateToCrumb(crumb: Crumb) {
    val id = crumb.id
    if (id == null) {
        if (!popBackStack(Routes.Drive, inclusive = false)) navigate(Routes.Drive)
    } else {
        if (!popBackStack(Routes.Folder(id), inclusive = false)) navigate(Routes.Folder(id))
    }
}
