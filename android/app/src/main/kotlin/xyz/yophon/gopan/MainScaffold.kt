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
import xyz.yophon.gopan.core.ui.nav.navEnter
import xyz.yophon.gopan.core.ui.nav.navExit
import xyz.yophon.gopan.feature.drive.ifResumed
import xyz.yophon.gopan.feature.transfers.TransfersScreen
import xyz.yophon.gopan.feature.account.accountGraph
import xyz.yophon.gopan.feature.chat.chatGraph
import xyz.yophon.gopan.feature.drive.driveGraph
import xyz.yophon.gopan.feature.shares.sharesGraph
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.ui.platform.testTag
import xyz.yophon.gopan.core.ui.isMediumWidth
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import xyz.yophon.gopan.feature.notifications.NotificationsEntryViewModel
import xyz.yophon.gopan.feature.notifications.NotificationsLogic
import xyz.yophon.gopan.feature.media.MiniPlayerBar
import xyz.yophon.gopan.feature.clips.ClipOpenViewModel
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import xyz.yophon.gopan.core.ui.theme.LocalGopanColors

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
fun MainScaffold(onOpenPreview: (nodeId: String) -> Unit, viewModel: MainScaffoldViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    // 键盘弹起时收起底部导航:否则输入框(传输助手、搜索)和键盘之间隔着一条导航栏
    val imeVisible = WindowInsets.isImeVisible
    // 宽屏(横屏、平板、折叠屏展开)用侧边导航栏,底部不再占一条
    val rail = isMediumWidth()
    fun selectTab(tab: MainTab) {
        navController.navigate(tab.route) {
            // 切 tab:保留各 tab 自己的返回栈,回到起点时不重复入栈
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // 站内通知的未读数:「我的」上的角标;主界面在前台时每分钟拉一次(doc/22 三.4)
    val inbox: NotificationsEntryViewModel = hiltViewModel()
    val unread by inbox.unreadCount.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(inbox, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { inbox.pollWhileVisible() } }

    // 外部入口:系统分享 → 传输助手;通知点按 → 传输列表 / 站内通知列表
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
            NavRequest.OPEN_TRANSFERS, NavRequest.OPEN_JOBS -> {
                if (r == NavRequest.OPEN_JOBS) viewModel.requestJobsTab()
                navController.navigate(Routes.Transfers) { launchSingleTop = true }
                viewModel.consume(r)
            }
            NavRequest.OPEN_NOTIFICATIONS -> {
                navController.navigate(Routes.Notifications) { launchSingleTop = true }
                viewModel.consume(r)
            }
            null -> Unit
        }
    }

    // 系统分享「剪藏到 gopan」后点了「打开」:进阅读页(doc/23 五.3)
    val clipOpen: ClipOpenViewModel = hiltViewModel()
    val clipNode by clipOpen.pending.collectAsStateWithLifecycle()
    LaunchedEffect(clipNode) {
        val nodeId = clipNode ?: return@LaunchedEffect
        navController.navigate(Routes.ClipReader(nodeId = nodeId)) { launchSingleTop = true }
        clipOpen.consume(nodeId)
    }

    Row(Modifier.fillMaxSize()) {
        if (rail) {
            NavigationRail(modifier = Modifier.testTag("main_rail")) {
                Spacer(Modifier.weight(1f))
                mainTabs.forEach { tab ->
                    val selected = currentDestination?.hierarchy?.any { tab.matches(it) } == true
                    NavigationRailItem(
                        selected = selected,
                        onClick = { selectTab(tab) },
                        icon = { TabIcon(tab, selected, badge = if (tab.route == Routes.AccountTab) unread else 0) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
                Spacer(Modifier.weight(1f))
            }
        }
        Scaffold(
            // 侧边导航栏已经让出了起始侧的系统栏(横屏三键导航在左边时)
            modifier = if (rail) Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)) else Modifier,
            bottomBar = {
                // 迷你播放条(doc/22 二.4)在导航栏上面,后台在放音频时出现,切 tab 不受影响
                if (!imeVisible) Column {
                    MiniPlayerBar(onOpenPreview = onOpenPreview, insetBottom = rail)
                    if (!rail) {
                        PillNavBar(
                            selectedIndex = mainTabs.indexOfFirst { tab -> currentDestination?.hierarchy?.any { tab.matches(it) } == true },
                            unread = unread,
                            onSelect = ::selectTab,
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
                enterTransition = navEnter,
                exitTransition = navExit,
            ) {
                driveGraph(navController, onOpenPreview)
                chatGraph(navController, onOpenPreview)
                sharesGraph(navController)
                accountGraph(navController, onOpenPreview)
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
}

/**
 * 底部导航:浮起的胶囊,选中项垫一块会滑动的底;四个 tab 都带文字(E2E 也按文字找)。
 * [selectedIndex] = -1(在传输列表这类不属于哪个 tab 的页面)时不画选中底。
 */
@Composable
private fun PillNavBar(selectedIndex: Int, unread: Int, onSelect: (MainTab) -> Unit) {
    val colors = LocalGopanColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .height(64.dp)
                .clip(CircleShape)
                .background(colors.nav)
                .padding(5.dp)
                .selectableGroup()
                .testTag("main_nav"),
        ) {
            val itemWidth = maxWidth / mainTabs.size
            val indicatorX by animateDpAsState(
                targetValue = itemWidth * selectedIndex.coerceAtLeast(0),
                animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
                label = "navIndicator",
            )
            if (selectedIndex >= 0) {
                Box(
                    Modifier
                        .offset { IntOffset(indicatorX.roundToPx(), 0) }
                        .width(itemWidth)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(colors.navSelected),
                )
            }
            Row(Modifier.fillMaxSize()) {
                mainTabs.forEachIndexed { index, tab ->
                    val selected = index == selectedIndex
                    val tint by animateColorAsState(if (selected) colors.onNavSelected else colors.onNav, label = "navTint")
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .selectable(
                                selected = selected,
                                interactionSource = null,
                                indication = ripple(color = colors.onNav),
                                role = Role.Tab,
                                onClick = { onSelect(tab) },
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        TabIcon(tab, selected, badge = if (tab.route == Routes.AccountTab) unread else 0, tint = tint)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(tab.label),
                            color = tint,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** tab 图标;[badge] > 0 时右上角带未读数(「我的」= 站内通知),角标用强调色 */
@Composable
private fun TabIcon(tab: MainTab, selected: Boolean, badge: Int, tint: Color = LocalContentColor.current) {
    val icon = if (selected) tab.selectedIcon else tab.icon
    if (badge > 0) {
        val colors = LocalGopanColors.current
        BadgedBox(
            badge = {
                Badge(containerColor = colors.accent, contentColor = colors.onAccent, modifier = Modifier.testTag("main_unread_badge")) {
                    Text(NotificationsLogic.badgeText(badge))
                }
            },
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
    } else {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}
