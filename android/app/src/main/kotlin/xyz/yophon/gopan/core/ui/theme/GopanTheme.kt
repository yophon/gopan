package xyz.yophon.gopan.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import xyz.yophon.gopan.core.prefs.ThemeMode

/**
 * Material 3 主题:API 31+ 动态取色,其余用静态配色;深浅由 [themeMode](用户偏好)决定。
 */
@Composable
fun GopanTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColorScheme
        else -> LightColorScheme
    }

    // 系统栏图标深浅与遮罩由 MainActivity 按同一个 dark 值调用 enableEdgeToEdge 设置(一处负责,避免互相覆盖)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = GopanTypography,
        content = content,
    )
}
