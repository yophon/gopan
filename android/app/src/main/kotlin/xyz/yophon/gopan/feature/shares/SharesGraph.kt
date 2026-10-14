package xyz.yophon.gopan.feature.shares

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import xyz.yophon.gopan.core.ui.nav.Routes
import xyz.yophon.gopan.feature.drive.ifResumed

/** 「分享」tab 的嵌套图:我的分享、某个分享的访问记录。新建分享在文件页 / 预览里(对话框)。 */
fun NavGraphBuilder.sharesGraph(navController: NavController) {
    navigation<Routes.SharesTab>(startDestination = Routes.Shares) {
        composable<Routes.Shares> { entry ->
            SharesScreen(
                onOpenVisits = { share ->
                    entry.ifResumed { navController.navigate(Routes.ShareVisits(share.id, share.nodeName)) }
                },
            )
        }
        composable<Routes.ShareVisits> { entry ->
            ShareVisitsScreen(onBack = { entry.ifResumed { navController.popBackStack() } })
        }
    }
}
