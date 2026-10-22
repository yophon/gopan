package xyz.yophon.gopan.feature.account

import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import xyz.yophon.gopan.core.ui.nav.Routes
import xyz.yophon.gopan.feature.account.security.AgentScreen
import xyz.yophon.gopan.feature.account.security.AppPasswordsScreen
import xyz.yophon.gopan.feature.account.security.DevicesScreen
import xyz.yophon.gopan.feature.admin.AdminScreen
import xyz.yophon.gopan.feature.drive.ifResumed
import xyz.yophon.gopan.feature.about.CrashLogScreen
import xyz.yophon.gopan.feature.about.LicensesScreen
import xyz.yophon.gopan.feature.activity.ActivityScreen
import xyz.yophon.gopan.feature.notifications.NotificationNav
import xyz.yophon.gopan.feature.notifications.NotificationsScreen
import xyz.yophon.gopan.feature.space.SpaceScreen

/**
 * 「我的」tab 的嵌套图:账户页(改密对话框在页内)、站内通知、动态、空间、登录设备、WebDAV 应用密码、Agent 接入、管理端(仅管理员可见)。
 * 回收站页属于文件线(driveGraph 里的 [Routes.Trash]),这里只负责导航过去;
 * 通知点开后去的文件夹 / 预览 / 「分享」tab / 传输列表同样只是导航过去([onOpenPreview] 由主界面给)。
 */
fun NavGraphBuilder.accountGraph(navController: NavController, onOpenPreview: (nodeId: String) -> Unit) {
    navigation<Routes.AccountTab>(startDestination = Routes.Account) {
        composable<Routes.Account> { entry ->
            AccountScreen(
                onOpenTrash = { entry.ifResumed { navController.navigate(Routes.Trash) } },
                onOpenTransfers = { entry.ifResumed { navController.navigate(Routes.Transfers) } },
                onOpenSessions = { entry.ifResumed { navController.navigate(Routes.Sessions) } },
                onOpenAppPasswords = { entry.ifResumed { navController.navigate(Routes.AppPasswords) } },
                onOpenAgent = { entry.ifResumed { navController.navigate(Routes.AgentCredentials) } },
                onOpenAdmin = { entry.ifResumed { navController.navigate(Routes.Admin) } },
                onOpenCrashLogs = { entry.ifResumed { navController.navigate(Routes.CrashLogs) } },
                onOpenLicenses = { entry.ifResumed { navController.navigate(Routes.Licenses) } },
                onOpenNotifications = { entry.ifResumed { navController.navigate(Routes.Notifications) { launchSingleTop = true } } },
                onOpenActivity = { entry.ifResumed { navController.navigate(Routes.Activity()) } },
                onOpenSpace = { entry.ifResumed { navController.navigate(Routes.Space) { launchSingleTop = true } } },
            )
        }
        composable<Routes.Activity> { entry ->
            ActivityScreen(
                onBack = { entry.ifResumed { navController.popBackStack() } },
                onOpen = { nav -> entry.ifResumed { navController.navigateFor(nav, onOpenPreview) } },
            )
        }
        composable<Routes.Space> { entry ->
            SpaceScreen(
                onBack = { entry.ifResumed { navController.popBackStack() } },
                onOpen = { nav -> entry.ifResumed { navController.navigateFor(nav, onOpenPreview) } },
                onOpenTrash = { entry.ifResumed { navController.navigate(Routes.Trash) } },
            )
        }
        composable<Routes.Notifications> { entry ->
            NotificationsScreen(
                onBack = { entry.ifResumed { navController.popBackStack() } },
                onNavigate = { nav -> entry.ifResumed { navController.navigateFor(nav, onOpenPreview) } },
            )
        }
        composable<Routes.Sessions> { entry -> DevicesScreen(onBack = { entry.ifResumed { navController.popBackStack() } }) }
        composable<Routes.AppPasswords> { entry -> AppPasswordsScreen(onBack = { entry.ifResumed { navController.popBackStack() } }) }
        composable<Routes.AgentCredentials> { entry -> AgentScreen(onBack = { entry.ifResumed { navController.popBackStack() } }) }
        composable<Routes.Admin> { entry -> AdminScreen(onBack = { entry.ifResumed { navController.popBackStack() } }) }
        composable<Routes.CrashLogs> { entry -> CrashLogScreen(onBack = { entry.ifResumed { navController.popBackStack() } }) }
        composable<Routes.Licenses> { entry -> LicensesScreen(onBack = { entry.ifResumed { navController.popBackStack() } }) }
    }
}

/**
 * 通知点开后的跳转。文件夹、传输列表、设置页压在通知列表上面(返回回到列表,与传输列表的「所在目录」同一做法);
 * 「分享」tab 换 tab(标签由 SharesTabRequest 指定);「我的」回到账户页。
 */
private fun NavController.navigateFor(nav: NotificationNav, onOpenPreview: (String) -> Unit) {
    when (nav) {
        is NotificationNav.Drive -> {
            navigate(nav.folderId?.let { Routes.Folder(it) } ?: Routes.Drive)
            nav.previewId?.let(onOpenPreview)
        }
        is NotificationNav.FileRequests, is NotificationNav.Links -> navigate(Routes.SharesTab) {
            popUpTo(graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        NotificationNav.Jobs -> navigate(Routes.Transfers) { launchSingleTop = true }
        NotificationNav.Sessions -> navigate(Routes.Sessions)
        NotificationNav.AppPasswords -> navigate(Routes.AppPasswords)
        NotificationNav.Agent -> navigate(Routes.AgentCredentials)
        NotificationNav.Admin -> navigate(Routes.Admin)
        NotificationNav.Account -> if (!popBackStack(Routes.Account, inclusive = false)) navigate(Routes.Account)
        is NotificationNav.Activity -> navigate(Routes.Activity(actor = nav.actor, since = nav.since))
        // 订阅页还没有时回「我的」(订阅的入口在那里)
        is NotificationNav.Feed -> if (!popBackStack(Routes.Account, inclusive = false)) navigate(Routes.Account)
    }
}
