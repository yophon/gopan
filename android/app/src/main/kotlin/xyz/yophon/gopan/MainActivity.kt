package xyz.yophon.gopan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
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
            GopanTheme(themeMode = themeMode) {
                RootNavHost(authState = authState)
            }
        }
    }
}
