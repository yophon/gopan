package xyz.yophon.gopan.feature.account

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import xyz.yophon.gopan.core.ui.components.PlaceholderScreen
import xyz.yophon.gopan.core.ui.nav.Routes
import xyz.yophon.gopan.feature.account.security.AgentScreen
import xyz.yophon.gopan.feature.account.security.AppPasswordsScreen
import xyz.yophon.gopan.feature.account.security.DevicesScreen
import xyz.yophon.gopan.feature.drive.ifResumed

/**
 * 「我的」tab 的嵌套图:账户页(改密对话框在页内)、登录设备、WebDAV 应用密码、Agent 接入;管理端(P4)暂是占位。
 * 回收站页属于文件线(driveGraph 里的 [Routes.Trash]),这里只负责导航过去。
 */
fun NavGraphBuilder.accountGraph(navController: NavController) {
    navigation<Routes.AccountTab>(startDestination = Routes.Account) {
        composable<Routes.Account> { entry ->
            AccountScreen(
                onOpenTrash = { entry.ifResumed { navController.navigate(Routes.Trash) } },
                onOpenTransfers = { entry.ifResumed { navController.navigate(Routes.Transfers) } },
                onOpenSessions = { entry.ifResumed { navController.navigate(Routes.Sessions) } },
                onOpenAppPasswords = { entry.ifResumed { navController.navigate(Routes.AppPasswords) } },
                onOpenAgent = { entry.ifResumed { navController.navigate(Routes.AgentCredentials) } },
                onOpenAdmin = { entry.ifResumed { navController.navigate(Routes.Admin) } },
            )
        }
        composable<Routes.Sessions> { entry -> DevicesScreen(onBack = { entry.ifResumed { navController.popBackStack() } }) }
        composable<Routes.AppPasswords> { entry -> AppPasswordsScreen(onBack = { entry.ifResumed { navController.popBackStack() } }) }
        composable<Routes.AgentCredentials> { entry -> AgentScreen(onBack = { entry.ifResumed { navController.popBackStack() } }) }
        composable<Routes.Admin> { PlaceholderScreen(title = "管理端") }
    }
}
