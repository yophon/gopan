package xyz.yophon.gopan.feature.feeds

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import xyz.yophon.gopan.core.ui.nav.Routes
import xyz.yophon.gopan.feature.clips.ClipReaderScreen
import xyz.yophon.gopan.feature.clips.ClipsScreen
import xyz.yophon.gopan.feature.drive.ifResumed

/**
 * 订阅与剪藏的页面(doc/23 四、五),挂在「我的」tab 的嵌套图里:订阅列表 → 添加 / 详情;剪藏列表 → 阅读页。
 * 订阅下好的文件用预览打开([onOpenPreview] 由主界面给:音频走同一套播放器),存放的文件夹进文件页。
 */
fun NavGraphBuilder.feedsAndClipsDestinations(navController: NavController, onOpenPreview: (nodeId: String) -> Unit) {
    composable<Routes.Feeds> { entry ->
        FeedsScreen(
            onBack = { entry.ifResumed { navController.popBackStack() } },
            onAdd = { entry.ifResumed { navController.navigate(Routes.FeedAdd) } },
            onOpen = { feed -> entry.ifResumed { navController.navigate(Routes.FeedDetail(feed.id, feed.title)) } },
        )
    }
    composable<Routes.FeedAdd> { entry ->
        FeedAddScreen(
            onBack = { entry.ifResumed { navController.popBackStack() } },
            // 订阅好了直接进它的详情;返回回到列表,不再回添加页
            onCreated = { feed ->
                entry.ifResumed {
                    navController.navigate(Routes.FeedDetail(feed.id, feed.title)) {
                        popUpTo<Routes.FeedAdd> { inclusive = true }
                    }
                }
            },
        )
    }
    composable<Routes.FeedDetail> { entry ->
        val route = entry.toRoute<Routes.FeedDetail>()
        FeedDetailScreen(
            title = route.title,
            onBack = { entry.ifResumed { navController.popBackStack() } },
            onOpenNode = { id -> entry.ifResumed { onOpenPreview(id) } },
            onOpenFolder = { id -> entry.ifResumed { navController.navigate(Routes.Folder(id)) } },
        )
    }
    composable<Routes.Clips> { entry ->
        ClipsScreen(
            onBack = { entry.ifResumed { navController.popBackStack() } },
            onOpen = { clip ->
                val nodeId = clip.nodeId ?: return@ClipsScreen
                entry.ifResumed { navController.navigate(Routes.ClipReader(nodeId = nodeId, clipId = clip.id, title = clip.title)) }
            },
        )
    }
    composable<Routes.ClipReader> { entry ->
        val route = entry.toRoute<Routes.ClipReader>()
        ClipReaderScreen(title = route.title, onBack = { entry.ifResumed { navController.popBackStack() } })
    }
}
