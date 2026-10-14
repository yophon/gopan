package xyz.yophon.gopan.feature.login

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import xyz.yophon.gopan.core.ui.nav.Routes

/**
 * 登录图(未登录时根 NavHost 的内容)。登录与注册是同一个页面的两个 tab:
 * [Routes.Login] 从 Yophon ID 主入口开始,[Routes.Register] 直接展开注册 tab。
 * 登录成功后 AuthState 变为 SignedIn,根导航自动切到主界面,这里不 navigate;
 * 启动页(AuthState.Loading)由 RootNavHost 自己画,这里不重复。
 */
@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.loginGraph(navController: NavController) {
    composable<Routes.Login> { LoginScreen() }
    composable<Routes.Register> { LoginScreen(initialMode = LoginMode.REGISTER) }
}
