package xyz.yophon.gopan

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import xyz.yophon.gopan.core.auth.AuthState
import xyz.yophon.gopan.core.ui.nav.Routes
import xyz.yophon.gopan.feature.drive.ifResumed
import xyz.yophon.gopan.feature.login.loginGraph
import xyz.yophon.gopan.feature.preview.PreviewScreen

/**
 * 根导航:一个 NavHost 装下启动页、登录图与主界面,按 [AuthState] 切换。
 * - Loading:启动页(正在用 refresh cookie 续登);
 * - SignedOut:登录图(feature.login);
 * - SignedIn:带底部导航的 [MainScaffold]。
 *
 * 切换时把整个返回栈弹空再进入目标页:被弹出的条目会清掉自己的 ViewModelStore,
 * 主界面里各页的 ViewModel(以及它们还在收集的 Flow)随登出一起销毁,不会留在 Activity 里泄漏到下一个账号。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RootNavHost(authState: AuthState) {
    val navController = rememberNavController()
    Surface(
        modifier = Modifier
            .fillMaxSize()
            // debug / releaseE2e 包把 Compose testTag 暴露成 resource-id,E2E(uiautomator)据此定位控件
            .semantics { testTagsAsResourceId = BuildConfig.TEST_HOOKS },
        color = MaterialTheme.colorScheme.background,
    ) {
        NavHost(navController = navController, startDestination = Routes.Startup) {
            composable<Routes.Startup> { StartupScreen() }
            loginGraph(navController)
            composable<Routes.Main> { entry ->
                MainScaffold(onOpenPreview = { id -> entry.ifResumed { navController.navigate(Routes.Preview(id)) } })
            }
            // 预览:根导航上的全屏页,盖住底部导航;从文件、搜索、传输助手进来
            composable<Routes.Preview>(
                enterTransition = { fadeIn(tween(FADE_MS)) },
                exitTransition = { fadeOut(tween(FADE_MS)) },
                popEnterTransition = { fadeIn(tween(FADE_MS)) },
                popExitTransition = { fadeOut(tween(FADE_MS)) },
            ) { entry ->
                PreviewScreen(onBack = { entry.ifResumed { navController.popBackStack() } })
            }
        }
    }

    val target: Any = when (authState) {
        AuthState.Loading -> Routes.Startup
        AuthState.SignedOut -> Routes.Login
        is AuthState.SignedIn -> Routes.Main
    }
    LaunchedEffect(target) {
        val current = navController.currentDestination
        if (current != null && current.hasRoute(target::class)) return@LaunchedEffect
        navController.navigate(target) {
            popUpTo(navController.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }
}

private const val FADE_MS = 180

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
