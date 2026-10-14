package xyz.yophon.gopan

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
import xyz.yophon.gopan.core.ui.theme.GopanTheme

/** 唯一的 Activity:主题 + 按登录态切换的根导航 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: MainViewModel = hiltViewModel()
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val authState by viewModel.authState.collectAsStateWithLifecycle()
            // 偏好读出来之前先不画(几毫秒,露出的是窗口背景):否则首帧按"跟随系统"渲染,再翻转成用户选的主题,每次启动都闪一下
            val mode = themeMode ?: return@setContent
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
            GopanTheme(themeMode = mode) {
                RootNavHost(authState = authState)
            }
        }
    }

    private companion object {
        // 与 androidx.activity 默认值一致
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
