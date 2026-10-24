package xyz.yophon.gopan

import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import xyz.yophon.gopan.feature.drive.LocalBottomChrome
import xyz.yophon.gopan.feature.drive.BottomChromeState
import androidx.compose.foundation.layout.PaddingValues
import xyz.yophon.gopan.feature.drive.LocalBottomBarInset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.asPaddingValues
import xyz.yophon.gopan.core.ui.nav.TabReselectEvents
import xyz.yophon.gopan.core.ui.nav.LocalTabReselect
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import xyz.yophon.gopan.feature.drive.LocalOfflineMarks
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
import androidx.compose.animation.core.Animatable
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
    /** tab 的首页(嵌套图的起点) */
    val root: Any,
    @StringRes val label: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val matches: (NavDestination) -> Boolean,
    val isRoot: (NavDestination) -> Boolean,
)

private val mainTabs = listOf(
    MainTab(Routes.DriveTab, Routes.Drive, R.string.tab_drive, Icons.Outlined.Folder, Icons.Filled.Folder, { it.hasRoute<Routes.DriveTab>() }, { it.hasRoute<Routes.Drive>() }),
    MainTab(Routes.ChatTab, Routes.Chat, R.string.tab_chat, Icons.AutoMirrored.Outlined.Chat, Icons.AutoMirrored.Filled.Chat, { it.hasRoute<Routes.ChatTab>() }, { it.hasRoute<Routes.Chat>() }),
    MainTab(Routes.SharesTab, Routes.Shares, R.string.tab_shares, Icons.Outlined.Share, Icons.Filled.Share, { it.hasRoute<Routes.SharesTab>() }, { it.hasRoute<Routes.Shares>() }),
    MainTab(Routes.AccountTab, Routes.Account, R.string.tab_account, Icons.Outlined.Person, Icons.Filled.Person, { it.hasRoute<Routes.AccountTab>() }, { it.hasRoute<Routes.Account>() }),
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
    val reselect = remember { TabReselectEvents() }
    fun selectTab(tab: MainTab) {
        val here = navController.currentDestination
        if (here != null && here.hierarchy.any { tab.matches(it) }) {
            // 再点当前 tab:在子页面就退回这个 tab 的首页,已经在首页就让首页滚回顶部
            if (tab.isRoot(here)) reselect.emit(tab.route) else navController.popBackStack(tab.root, inclusive = false)
            return
        }
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

    // 列表上的离线标记(doc/26):文件页、搜索、收藏 / 最近都读它
    val offlineMarks by viewModel.offlineMarks.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalTabReselect provides reselect, LocalOfflineMarks provides offlineMarks) {
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
            ) { innerPadding ->
                // 底部的迷你播放条与胶囊导航浮在页面上面,背后透明:页面铺到屏幕底,
                // 它们占的高度经 LocalBottomBarInset 交给各页(NestedScreenInsets),列表用它做底部留白,能滚到胶囊后面
                val density = LocalDensity.current
                val layoutDirection = LocalLayoutDirection.current
                val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                var chromeHeight by remember { mutableStateOf(if (rail) 0.dp else PILL_BAR_HEIGHT + navBarBottom) }
                // 只让出两侧:状态栏透明,各页顶栏自己让出它,收起时内容能从状态栏底下露出来
                val sides = PaddingValues(
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                )
                val bottomChrome = remember { BottomChromeState() }
                // 键盘弹起时为 0:各页的 imePadding 已经从屏幕底算起(含系统导航栏那段)
                val bottomInset = if (imeVisible) 0.dp else maxOf(chromeHeight, navBarBottom)
                Box(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalBottomBarInset provides bottomInset, LocalBottomChrome provides bottomChrome) {
                        NavHost(
                            navController = navController,
                            startDestination = Routes.DriveTab,
                            // 两侧让出系统栏并标记为已消费;顶部由各页顶栏让,底部由各页按 LocalBottomBarInset 留
                            modifier = Modifier
                                .padding(sides)
                                .consumeWindowInsets(sides),
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
                    // 键盘弹起时收起底部导航:否则输入框(传输助手、搜索)和键盘之间隔着一条导航栏
                    if (!imeVisible) {
                        Column(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .onSizeChanged { chromeHeight = with(density) { it.height.toDp() } }
                                // 传输助手往上翻时整条滑出屏幕
                                .graphicsLayer { translationY = bottomChrome.offsetPx },
                        ) {
                            // 迷你播放条(doc/22 二.4)在导航栏上面,后台在放音频时出现,切 tab 不受影响
                            MiniPlayerBar(onOpenPreview = onOpenPreview, insetBottom = rail)
                            if (!rail) {
                                PillNavBar(
                                    selectedIndex = mainTabs.indexOfFirst { tab -> currentDestination?.hierarchy?.any { tab.matches(it) } == true },
                                    unread = unread,
                                    onSelect = ::selectTab,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 底部导航:浮起的胶囊,选中项垫一块白底。点按切换;按住左右拖,白底跟着手指走,经过每个 tab 轻震一下,松手切到手指下的那个。
 * 四个 tab 都带文字(E2E 也按文字找)。[selectedIndex] = -1(在传输列表这类不属于哪个 tab 的页面)时不画选中底。
 */
@Composable
private fun PillNavBar(selectedIndex: Int, unread: Int, onSelect: (MainTab) -> Unit) {
    val colors = LocalGopanColors.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier
            .fillMaxWidth()
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
            val count = mainTabs.size
            val itemWidth = maxWidth / count
            val itemPx = constraints.maxWidth.toFloat() / count
            // 白底的横向位置(像素);点按时弹簧滑过去,拖动时贴着手指
            val indicatorX = remember { Animatable(itemPx * selectedIndex.coerceAtLeast(0)) }
            // 拖动中手指下的 tab;null = 没在拖
            var dragIndex by remember { mutableStateOf<Int?>(null) }
            val springSpec = spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
            LaunchedEffect(selectedIndex, itemPx) {
                if (dragIndex == null && selectedIndex >= 0) indicatorX.animateTo(itemPx * selectedIndex, springSpec)
            }
            val showIndicator = dragIndex != null || selectedIndex >= 0
            if (showIndicator) {
                Box(
                    Modifier
                        .offset { IntOffset(indicatorX.value.roundToInt(), 0) }
                        .width(itemWidth)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(colors.navSelected),
                )
            }
            Row(
                Modifier
                    .fillMaxSize()
                    .pointerInput(count, itemPx) {
                        fun indexAt(x: Float) = (x / itemPx).toInt().coerceIn(0, count - 1)
                        fun follow(x: Float) = (x - itemPx / 2).coerceIn(0f, itemPx * (count - 1))
                        detectHorizontalDragGestures(
                            onDragStart = { start ->
                                dragIndex = indexAt(start.x)
                                scope.launch { indicatorX.animateTo(follow(start.x), spring(stiffness = Spring.StiffnessHigh)) }
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                val index = indexAt(change.position.x)
                                if (index != dragIndex) {
                                    dragIndex = index
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                }
                                scope.launch { indicatorX.snapTo(follow(change.position.x)) }
                            },
                            onDragEnd = {
                                val index = dragIndex ?: return@detectHorizontalDragGestures
                                dragIndex = null
                                scope.launch { indicatorX.animateTo(itemPx * index, springSpec) }
                                onSelect(mainTabs[index])
                            },
                            onDragCancel = {
                                dragIndex = null
                                if (selectedIndex >= 0) scope.launch { indicatorX.animateTo(itemPx * selectedIndex, springSpec) }
                            },
                        )
                    },
            ) {
                // 底层:灰色的线框图标与文字,负责点按
                mainTabs.forEachIndexed { index, tab ->
                    NavItem(
                        tab = tab,
                        filled = false,
                        tint = colors.onNav,
                        badge = if (tab.route == Routes.AccountTab) unread else 0,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .selectable(
                                selected = index == selectedIndex,
                                interactionSource = null,
                                indication = ripple(color = colors.onNav),
                                role = Role.Tab,
                                onClick = { onSelect(tab) },
                            ),
                    )
                }
            }
            // 上层:墨黑的实心图标与文字,只画在白底盖住的那一块:白底滑到哪,哪里才变色
            if (showIndicator) {
                Row(
                    Modifier
                        .fillMaxSize()
                        .clearAndSetSemantics {}
                        .drawWithContent {
                            val left = indicatorX.value
                            clipPath(Path().apply { addRoundRect(RoundRect(left, 0f, left + itemPx, size.height, CornerRadius(size.height / 2))) }) {
                                this@drawWithContent.drawContent()
                            }
                        },
                ) {
                    mainTabs.forEach { tab ->
                        NavItem(
                            tab = tab,
                            filled = true,
                            tint = colors.onNavSelected,
                            badge = if (tab.route == Routes.AccountTab) unread else 0,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

/** 胶囊里的一项:图标在上、文字在下;两层(灰 / 墨黑)用同一个排版,叠起来逐像素对齐 */
@Composable
private fun NavItem(tab: MainTab, filled: Boolean, tint: Color, badge: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        TabIcon(tab, filled, badge = badge, tint = tint)
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(tab.label),
            color = tint,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** 胶囊导航一条的高度(胶囊 64 + 上下留白 16),不含系统导航栏;第一帧量出来之前用它估 */
private val PILL_BAR_HEIGHT = 80.dp

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
