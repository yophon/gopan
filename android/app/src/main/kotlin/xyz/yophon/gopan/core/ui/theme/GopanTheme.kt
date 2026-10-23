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
import xyz.yophon.gopan.core.prefs.Palette
import xyz.yophon.gopan.core.prefs.ThemeMode

/** 外层主题用的配色;嵌套的 GopanTheme(预览页强制深色)默认跟随它 */
val LocalPalette = staticCompositionLocalOf { Palette.GOPAN }

/** 系统支持动态取色(Android 12+) */
val supportsDynamicColor: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Material 3 主题:配色按 [palette](墨黑 / 青绿 / 跟随壁纸),深浅由 [themeMode] 决定;
 * 系统不支持动态取色时「跟随壁纸」退回墨黑。字重与圆角三种配色共用。
 */
@Composable
fun GopanTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    palette: Palette = LocalPalette.current,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = when {
        palette == Palette.DYNAMIC && supportsDynamicColor -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        palette == Palette.CLASSIC -> if (dark) ClassicDarkColorScheme else ClassicLightColorScheme
        else -> if (dark) GopanDarkColorScheme else GopanLightColorScheme
    }
    val brand = when {
        colorScheme === GopanLightColorScheme -> GopanColors.GopanLight
        colorScheme === GopanDarkColorScheme -> GopanColors.GopanDark
        else -> GopanColors.from(colorScheme)
    }

    // 系统栏图标深浅与遮罩由 MainActivity 按同一个 dark 值调用 enableEdgeToEdge 设置(一处负责,避免互相覆盖)

    CompositionLocalProvider(LocalPalette provides palette, LocalGopanColors provides brand) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = GopanTypography,
            shapes = GopanShapes,
            content = content,
        )
    }
}
