package xyz.yophon.gopan.feature.shares

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import xyz.yophon.gopan.core.ui.nav.Routes
import xyz.yophon.gopan.feature.collect.FileRequestUploadsScreen
import xyz.yophon.gopan.feature.drive.ifResumed

/**
 * 「分享」tab 的嵌套图:我的分享 / 收集(同一页的两个标签)、某个分享的访问记录、某条收集链接的收件记录。
 * 新建分享、新建收集链接在文件页 / 搜索页 / 预览里(对话框)。
 */
fun NavGraphBuilder.sharesGraph(navController: NavController) {
    navigation<Routes.SharesTab>(startDestination = Routes.Shares) {
        composable<Routes.Shares> { entry ->
            SharesScreen(
                onOpenVisits = { share ->
                    entry.ifResumed { navController.navigate(Routes.ShareVisits(share.id, share.nodeName)) }
                },
                onOpenUploads = { request ->
                    entry.ifResumed { navController.navigate(Routes.FileRequestUploads(request.id, request.title, request.newCount)) }
                },
            )
        }
        composable<Routes.ShareVisits> { entry ->
            ShareVisitsScreen(onBack = { entry.ifResumed { navController.popBackStack() } })
        }
        composable<Routes.FileRequestUploads> { entry ->
            FileRequestUploadsScreen(
                onBack = { entry.ifResumed { navController.popBackStack() } },
                // 跳到文件现在所在的目录(文件 tab 的路由,与传输助手、传输列表的「所在目录」同一做法)
                onOpenFolder = { id -> entry.ifResumed { navController.navigate(if (id == null) Routes.Drive else Routes.Folder(id)) } },
            )
        }
    }
}
