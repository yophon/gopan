package xyz.yophon.gopan

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import xyz.yophon.gopan.core.prefs.ThemeMode
import xyz.yophon.gopan.core.share.AppNavRequests
import xyz.yophon.gopan.core.share.IncomingShareStore
import xyz.yophon.gopan.core.share.NavRequest
import xyz.yophon.gopan.core.transfer.AppVisibility
import xyz.yophon.gopan.core.network.ChangeStream
import xyz.yophon.gopan.core.ui.theme.GopanTheme
import xyz.yophon.gopan.feature.clips.ClipOpenRequests
import javax.inject.Inject

/**
 * 唯一的 Activity:主题 + 按登录态切换的根导航。
 * 还负责接住外部入口:系统分享(SEND / SEND_MULTIPLE → 传输助手)、通知点按(→ 传输列表)、剪藏分享页的「打开」,
 * 以及长按图标的快捷方式、桌面上的文件夹快捷方式与小组件(→ 传输助手 / 搜索 / 某个文件夹)。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var incomingShare: IncomingShareStore

    @Inject
    lateinit var navRequests: AppNavRequests

    @Inject
    lateinit var clipOpens: ClipOpenRequests

    /** 自动刷新的推送连接:在前台时连着(doc/32 二) */
    @Inject
    lateinit var changeStream: ChangeStream

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: MainViewModel = hiltViewModel()
            val appearance by viewModel.appearance.collectAsStateWithLifecycle()
            val authState by viewModel.authState.collectAsStateWithLifecycle()
            // 偏好读出来之前先不画(几毫秒,露出的是窗口背景):否则首帧按"跟随系统"渲染,再翻转成用户选的主题,每次启动都闪一下
            val look = appearance ?: return@setContent
            val mode = look.theme
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // App 内主题可能与系统不同:按实际深浅重新设置系统栏。API 26–28 的三键导航栏不支持透明,
            // 需要一层与主题匹配的遮罩,否则深色主题下浅色按键压在浅色遮罩上就看不见了
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose {}
            }
            GopanTheme(themeMode = mode, palette = look.palette) {
                RootNavHost(authState = authState)
            }
        }
        // 进程被回收后重建时 savedInstanceState 非空:那次分享已经处理过,不要再拷一遍
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.foreground = true
        changeStream.start()
    }

    override fun onStop() {
        super.onStop()
        AppVisibility.foreground = false
        changeStream.stop()
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (incomingShare.accept(intent)) {
            navRequests.post(NavRequest.OPEN_CHAT)
            return
        }
        when (intent.getStringExtra(EXTRA_OPEN)) {
            OPEN_TRANSFERS -> navRequests.post(NavRequest.OPEN_TRANSFERS)
            OPEN_NOTIFICATIONS -> navRequests.post(NavRequest.OPEN_NOTIFICATIONS)
            // 系统分享「剪藏到 gopan」剪好后点「打开」:进那篇的阅读页
            OPEN_CLIP -> intent.getStringExtra(EXTRA_NODE_ID)?.let(clipOpens::post)
            OPEN_CHAT -> navRequests.post(NavRequest.OPEN_CHAT)
            OPEN_SEARCH -> navRequests.post(NavRequest.OPEN_SEARCH)
            OPEN_FOLDER -> intent.getStringExtra(EXTRA_NODE_ID)?.let(navRequests::postFolder)
        }
    }

    companion object {
        /** 通知点按带的跳转目标 */
        const val EXTRA_OPEN = "xyz.yophon.gopan.OPEN"
        const val OPEN_TRANSFERS = "transfers"
        const val OPEN_NOTIFICATIONS = "notifications"
        const val OPEN_CLIP = "clip"

        /** 快捷方式 / 小组件:传输助手、搜索、桌面上的文件夹(带 EXTRA_NODE_ID) */
        const val OPEN_CHAT = "chat"
        const val OPEN_SEARCH = "search"
        const val OPEN_FOLDER = "folder"

        /** OPEN_CLIP 带的那篇剪藏(.md 的节点 id);OPEN_FOLDER 带的文件夹 id */
        const val EXTRA_NODE_ID = "xyz.yophon.gopan.NODE_ID"

        // 与 androidx.activity 默认值一致
        private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
