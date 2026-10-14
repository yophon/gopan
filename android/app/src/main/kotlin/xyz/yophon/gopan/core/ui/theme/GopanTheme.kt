package xyz.yophon.gopan.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import xyz.yophon.gopan.core.prefs.ThemeMode

/** 外层主题用的是不是动态取色;嵌套的 GopanTheme(预览页强制深色)默认跟随它 */
val LocalDynamicColor = staticCompositionLocalOf { true }

/** 系统支持动态取色(Android 12+) */
val supportsDynamicColor: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Material 3 主题:API 31+ 且用户没关时动态取色,其余用静态配色;深浅由 [themeMode](用户偏好)决定。
 */
@Composable
fun GopanTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = LocalDynamicColor.current,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = when {
        dynamicColor && supportsDynamicColor -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColorScheme
        else -> LightColorScheme
    }

    // 系统栏图标深浅与遮罩由 MainActivity 按同一个 dark 值调用 enableEdgeToEdge 设置(一处负责,避免互相覆盖)

    CompositionLocalProvider(LocalDynamicColor provides dynamicColor) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = GopanTypography,
            content = content,
        )
    }
}
