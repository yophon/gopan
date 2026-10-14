package xyz.yophon.gopan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import xyz.yophon.gopan.core.auth.AuthState
import xyz.yophon.gopan.core.ui.nav.Routes
import xyz.yophon.gopan.feature.login.loginGraph

/**
 * 根导航:按 [AuthState] 三选一。
 * - Loading:启动页(正在用 refresh cookie 续登);
 * - SignedOut:登录图(feature.login);
 * - SignedIn:带底部导航的 [MainScaffold]。
 * 登录态一变整棵树重建,所以登录/登出都不需要手动 navigate。
 */
@Composable
fun RootNavHost(authState: AuthState) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (authState) {
            AuthState.Loading -> StartupScreen()
            AuthState.SignedOut -> AuthNavHost()
            is AuthState.SignedIn -> MainScaffold()
        }
    }
}

@Composable
private fun AuthNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.Login) {
        loginGraph(navController)
    }
}

@Composable
private fun StartupScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            CircularProgressIndicator()
        }
    }
}
