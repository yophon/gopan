package xyz.yophon.gopan.feature.account

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import xyz.yophon.gopan.core.ui.components.PlaceholderScreen
import xyz.yophon.gopan.core.ui.nav.Routes

/** 「我的」tab 的嵌套图:账户、改密、设备、应用密码、Agent 凭据、设置、管理端(仅管理员可见)。 */
fun NavGraphBuilder.accountGraph(navController: NavController) {
    navigation<Routes.AccountTab>(startDestination = Routes.Account) {
        composable<Routes.Account> { PlaceholderScreen(title = "我的") }
        composable<Routes.ChangePassword> { PlaceholderScreen(title = "修改密码") }
        composable<Routes.Sessions> { PlaceholderScreen(title = "登录设备") }
        composable<Routes.AppPasswords> { PlaceholderScreen(title = "应用密码") }
        composable<Routes.AgentCredentials> { PlaceholderScreen(title = "Agent 凭据") }
        composable<Routes.Settings> { PlaceholderScreen(title = "设置") }
        composable<Routes.Admin> { PlaceholderScreen(title = "管理端") }
    }
}
