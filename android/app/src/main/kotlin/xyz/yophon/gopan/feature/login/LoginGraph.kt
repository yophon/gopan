package xyz.yophon.gopan.feature.login

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import xyz.yophon.gopan.core.ui.components.PlaceholderScreen
import xyz.yophon.gopan.core.ui.nav.Routes

/**
 * 登录图(未登录时的根 NavHost 内容)。登录功能线替换此文件及本包内文件:
 * Yophon ID 登录用 core.auth.YophonIdLogin + rememberYophonIdLauncher,密码登录/注册调 SessionManager;
 * 成功后 AuthState 变为 SignedIn,根导航自动切到主界面,不需要在这里 navigate。
 */
fun NavGraphBuilder.loginGraph(navController: NavController) {
    composable<Routes.Login> { PlaceholderScreen(title = "登录") }
    composable<Routes.Register> { PlaceholderScreen(title = "注册") }
}
