package xyz.yophon.gopan

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import xyz.yophon.gopan.core.share.NavRequest
import xyz.yophon.gopan.core.ui.nav.Routes
import xyz.yophon.gopan.feature.drive.ifResumed
import xyz.yophon.gopan.feature.transfers.TransfersScreen
import xyz.yophon.gopan.feature.account.accountGraph
import xyz.yophon.gopan.feature.chat.chatGraph
import xyz.yophon.gopan.feature.drive.driveGraph
import xyz.yophon.gopan.feature.shares.sharesGraph

/** 底部 tab 定义;顺序即显示顺序 */
private class MainTab(
    val route: Any,
    @StringRes val label: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val matches: (NavDestination) -> Boolean,
)

private val mainTabs = listOf(
    MainTab(Routes.DriveTab, R.string.tab_drive, Icons.Outlined.Folder, Icons.Filled.Folder) { it.hasRoute<Routes.DriveTab>() },
    MainTab(Routes.ChatTab, R.string.tab_chat, Icons.AutoMirrored.Outlined.Chat, Icons.AutoMirrored.Filled.Chat) { it.hasRoute<Routes.ChatTab>() },
    MainTab(Routes.SharesTab, R.string.tab_shares, Icons.Outlined.Share, Icons.Filled.Share) { it.hasRoute<Routes.SharesTab>() },
    MainTab(Routes.AccountTab, R.string.tab_account, Icons.Outlined.Person, Icons.Filled.Person) { it.hasRoute<Routes.AccountTab>() },
)

/**
 * 登录后的主界面:底部导航 + 四个嵌套图。底部栏与 NavHost 只在这个文件;
 * 各 tab 的页面由 feature.* 包里的 xxxGraph 扩展函数提供。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScaffold(viewModel: MainScaffoldViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    // 键盘弹起时收起底部导航:否则输入框(传输助手、搜索)和键盘之间隔着一条导航栏
    val imeVisible = WindowInsets.isImeVisible

    // 外部入口:系统分享 → 传输助手;通知点按 → 传输列表
    val request by viewModel.navRequest.collectAsStateWithLifecycle()
    LaunchedEffect(request) {
        when (val r = request) {
            NavRequest.OPEN_CHAT -> {
                navController.navigate(Routes.ChatTab) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
                viewModel.consume(r)
            }
            NavRequest.OPEN_TRANSFERS -> {
                navController.navigate(Routes.Transfers) { launchSingleTop = true }
                viewModel.consume(r)
            }
            null -> Unit
        }
    }

    Scaffold(
        bottomBar = {
            if (!imeVisible) NavigationBar {
                mainTabs.forEach { tab ->
                    val selected = currentDestination?.hierarchy?.any { tab.matches(it) } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(tab.route) {
                                // 切 tab:保留各 tab 自己的返回栈,回到起点时不重复入栈
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = if (selected) tab.selectedIcon else tab.icon,
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.DriveTab,
            // innerPadding 已含状态栏与底部栏高度;再把这部分 insets 标记为已消费,
            // tab 内页面的 TopAppBar / Scaffold 无论用默认 insets 还是零 insets 都不会出现双倍留白
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
        ) {
            driveGraph(navController)
            chatGraph(navController)
            sharesGraph(navController)
            accountGraph(navController)
            // 传输列表:全局页,不属于哪个 tab(文件页菜单、传输助手、「我的」、通知都能进)
            composable<Routes.Transfers> { entry ->
                TransfersScreen(
                    onBack = { entry.ifResumed { navController.popBackStack() } },
                    onOpenFolder = { id ->
                        entry.ifResumed { navController.navigate(if (id == null) Routes.Drive else Routes.Folder(id)) }
                    },
                )
            }
        }
    }
}
