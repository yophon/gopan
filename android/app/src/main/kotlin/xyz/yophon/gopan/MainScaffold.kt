package xyz.yophon.gopan

import androidx.annotation.StringRes
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import xyz.yophon.gopan.core.ui.nav.Routes
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
@Composable
fun MainScaffold() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
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
            modifier = Modifier.padding(innerPadding),
        ) {
            driveGraph(navController)
            chatGraph(navController)
            sharesGraph(navController)
            accountGraph(navController)
        }
    }
}
