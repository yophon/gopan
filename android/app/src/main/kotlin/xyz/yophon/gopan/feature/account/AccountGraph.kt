package xyz.yophon.gopan.feature.account

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import xyz.yophon.gopan.core.ui.components.PlaceholderScreen
import xyz.yophon.gopan.core.ui.nav.Routes

/**
 * 「我的」tab 的嵌套图:账户页(P1)、改密对话框在页内;设备 / 应用密码 / Agent 凭据(P3)、管理端(P4)暂是占位。
 * 回收站页属于文件线(driveGraph 里的 [Routes.Trash]),这里只负责导航过去。
 */
fun NavGraphBuilder.accountGraph(navController: NavController) {
    navigation<Routes.AccountTab>(startDestination = Routes.Account) {
        composable<Routes.Account> {
            AccountScreen(
                onOpenTrash = { navController.navigate(Routes.Trash) },
                onOpenSessions = { navController.navigate(Routes.Sessions) },
                onOpenAppPasswords = { navController.navigate(Routes.AppPasswords) },
                onOpenAgent = { navController.navigate(Routes.AgentCredentials) },
                onOpenAdmin = { navController.navigate(Routes.Admin) },
            )
        }
        composable<Routes.ChangePassword> { PlaceholderScreen(title = "修改密码") }
        composable<Routes.Sessions> { PlaceholderScreen(title = "登录设备") }
        composable<Routes.AppPasswords> { PlaceholderScreen(title = "应用密码") }
        composable<Routes.AgentCredentials> { PlaceholderScreen(title = "Agent 凭据") }
        composable<Routes.Settings> { PlaceholderScreen(title = "设置") }
        composable<Routes.Admin> { PlaceholderScreen(title = "管理端") }
    }
}
