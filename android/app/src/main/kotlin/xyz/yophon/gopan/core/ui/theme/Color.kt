package xyz.yophon.gopan.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

// ---- 墨黑(默认):与网页版、发布页同一套——墨黑主色、暖灰底、信号橙只做点缀 ----

val GopanLightColorScheme = lightColorScheme(
    primary = Color(0xFF121212),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE9E7E1),
    onPrimaryContainer = Color(0xFF121212),
    inversePrimary = Color(0xFFFF8A5C),
    secondary = Color(0xFF55534E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE9E7E1),
    onSecondaryContainer = Color(0xFF121212),
    // 橙色当文字用要压暗一档才够对比度;大面积的信号橙在 GopanColors.accent
    tertiary = Color(0xFFC2410C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFDE8DF),
    onTertiaryContainer = Color(0xFF5A1A00),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF121212),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF121212),
    surfaceVariant = Color(0xFFF3F2EE),
    onSurfaceVariant = Color(0xFF625F59),
    surfaceTint = Color(0xFF121212),
    inverseSurface = Color(0xFF2E2D2B),
    inverseOnSurface = Color(0xFFF3F2EE),
    outline = Color(0xFF8F8B83),
    outlineVariant = Color(0xFFE6E4DE),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE9E7E1),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    // 底部面板用 surfaceContainerLow:与列表项(surface)同色,面板里不出现一道道色差;要浅灰底的地方用 surfaceContainer
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF5F4F0),
    surfaceContainerHigh = Color(0xFFF0EEE9),
    surfaceContainerHighest = Color(0xFFE9E7E1),
)

val GopanDarkColorScheme = darkColorScheme(
    primary = Color(0xFFF3F2EE),
    onPrimary = Color(0xFF121212),
    primaryContainer = Color(0xFF34322E),
    onPrimaryContainer = Color(0xFFF3F2EE),
    inversePrimary = Color(0xFFC2410C),
    secondary = Color(0xFFC9C5BD),
    onSecondary = Color(0xFF1E1D1B),
    secondaryContainer = Color(0xFF34322E),
    onSecondaryContainer = Color(0xFFF3F2EE),
    tertiary = Color(0xFFFF8A5C),
    onTertiary = Color(0xFF4A1500),
    tertiaryContainer = Color(0xFF5A2410),
    onTertiaryContainer = Color(0xFFFFDBCC),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF141312),
    onBackground = Color(0xFFEDEBE6),
    surface = Color(0xFF141312),
    onSurface = Color(0xFFEDEBE6),
    surfaceVariant = Color(0xFF2A2926),
    onSurfaceVariant = Color(0xFFA9A59D),
    surfaceTint = Color(0xFFF3F2EE),
    inverseSurface = Color(0xFFEDEBE6),
    inverseOnSurface = Color(0xFF2E2D2B),
    outline = Color(0xFF77736C),
    outlineVariant = Color(0xFF2F2D2A),
    surfaceBright = Color(0xFF3A3834),
    surfaceDim = Color(0xFF141312),
    surfaceContainerLowest = Color(0xFF0E0D0C),
    surfaceContainerLow = Color(0xFF141312),
    surfaceContainer = Color(0xFF1F1E1C),
    surfaceContainerHigh = Color(0xFF292825),
    surfaceContainerHighest = Color(0xFF34322E),
)

// ---- 青绿:3.5 以前的配色(以旧图标的青绿色为种子),设置里可以换回来 ----

val ClassicLightColorScheme = lightColorScheme(
    primary = Color(0xFF00696B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF6FF7F8),
    onPrimaryContainer = Color(0xFF002020),
    secondary = Color(0xFF4A6363),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E7),
    onSecondaryContainer = Color(0xFF051F1F),
    tertiary = Color(0xFF4B607C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD3E4FF),
    onTertiaryContainer = Color(0xFF041C35),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFAFDFC),
    onBackground = Color(0xFF191C1C),
    surface = Color(0xFFFAFDFC),
    onSurface = Color(0xFF191C1C),
    surfaceVariant = Color(0xFFDAE5E4),
    onSurfaceVariant = Color(0xFF3F4948),
    outline = Color(0xFF6F7979),
    // 以下几项 3.5 以前没设,落到 Material 基线的淡紫色;补成同一个青绿种子的中性色
    outlineVariant = Color(0xFFBEC9C8),
    inverseSurface = Color(0xFF2B3231),
    inverseOnSurface = Color(0xFFECF2F1),
    inversePrimary = Color(0xFF4CDADB),
    surfaceTint = Color(0xFF00696B),
    surfaceDim = Color(0xFFD5DBDA),
    surfaceBright = Color(0xFFF5FBFA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFDFC),
    surfaceContainer = Color(0xFFE9EFEE),
    surfaceContainerHigh = Color(0xFFE3E9E8),
    surfaceContainerHighest = Color(0xFFDEE4E3),
)

val ClassicDarkColorScheme = darkColorScheme(
    primary = Color(0xFF4CDADB),
    onPrimary = Color(0xFF003737),
    primaryContainer = Color(0xFF004F50),
    onPrimaryContainer = Color(0xFF6FF7F8),
    secondary = Color(0xFFB0CCCB),
    onSecondary = Color(0xFF1B3534),
    secondaryContainer = Color(0xFF324B4B),
    onSecondaryContainer = Color(0xFFCCE8E7),
    tertiary = Color(0xFFB3C8E8),
    onTertiary = Color(0xFF1C314B),
    tertiaryContainer = Color(0xFF334863),
    onTertiaryContainer = Color(0xFFD3E4FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF191C1C),
    onBackground = Color(0xFFE0E3E2),
    surface = Color(0xFF191C1C),
    onSurface = Color(0xFFE0E3E2),
    surfaceVariant = Color(0xFF3F4948),
    onSurfaceVariant = Color(0xFFBEC9C8),
    outline = Color(0xFF889392),
    outlineVariant = Color(0xFF3F4948),
    inverseSurface = Color(0xFFDEE4E3),
    inverseOnSurface = Color(0xFF2B3231),
    inversePrimary = Color(0xFF00696B),
    surfaceTint = Color(0xFF4CDADB),
    surfaceDim = Color(0xFF0E1514),
    surfaceBright = Color(0xFF343A3A),
    surfaceContainerLowest = Color(0xFF090F0F),
    surfaceContainerLow = Color(0xFF191C1C),
    surfaceContainer = Color(0xFF1B2121),
    surfaceContainerHigh = Color(0xFF252B2B),
    surfaceContainerHighest = Color(0xFF303636),
)

/**
 * Material 配色之外的品牌色:主按钮的强调色、文件夹图形、文件夹面板、底部胶囊导航、文件类型图标。
 * 墨黑配色有自己的一套;青绿与跟随壁纸从各自的 [ColorScheme] 推出来,看上去和换配色前一致。
 */
@Immutable
data class GopanColors(
    /** 主 FAB、未读角标 */
    val accent: Color,
    val onAccent: Color,
    /** 文件夹图形:后片与前片 */
    val folderBack: Color,
    val folderFront: Color,
    /** 文件夹面板(登录、「我的」头部)的底色 */
    val panel: Color,
    /** 底部胶囊导航:底色、未选中的图标与文字、选中项的底与字 */
    val nav: Color,
    val onNav: Color,
    val navSelected: Color,
    val onNavSelected: Color,
    /** 文件类型图标:影音、PDF、其它 */
    val fileMedia: Color,
    val filePdf: Color,
    val fileOther: Color,
) {
    companion object {
        private val SignalOrange = Color(0xFFF2541B)

        val GopanLight = GopanColors(
            accent = SignalOrange,
            onAccent = Color.White,
            folderBack = Color(0xFFE3DFD5),
            folderFront = Color(0xFFCFC9BC),
            panel = Color(0xFFF3F2EE),
            nav = Color(0xFF121212),
            onNav = Color(0xFF8F8B83),
            navSelected = Color(0xFFFFFFFF),
            onNavSelected = Color(0xFF121212),
            fileMedia = Color(0xFF55534E),
            filePdf = Color(0xFF55534E),
            fileOther = Color(0xFF55534E),
        )

        val GopanDark = GopanColors(
            accent = SignalOrange,
            onAccent = Color.White,
            folderBack = Color(0xFF3A3732),
            folderFront = Color(0xFF4D4943),
            panel = Color(0xFF1F1E1C),
            nav = Color(0xFF2A2926),
            onNav = Color(0xFF9C988F),
            navSelected = Color(0xFFF3F2EE),
            onNavSelected = Color(0xFF121212),
            fileMedia = Color(0xFFB5B1A9),
            filePdf = Color(0xFFB5B1A9),
            fileOther = Color(0xFFB5B1A9),
        )

        /** 青绿与跟随壁纸:强调色用 FAB 原本的 primaryContainer,文件夹按主色深浅两片 */
        fun from(scheme: ColorScheme) = GopanColors(
            accent = scheme.primaryContainer,
            onAccent = scheme.onPrimaryContainer,
            folderBack = lerp(scheme.surface, scheme.primary, 0.38f),
            folderFront = lerp(scheme.surface, scheme.primary, 0.62f),
            panel = scheme.surfaceContainerHigh,
            nav = scheme.surfaceContainerHigh,
            onNav = scheme.onSurfaceVariant,
            navSelected = scheme.secondaryContainer,
            onNavSelected = scheme.onSecondaryContainer,
            fileMedia = scheme.tertiary,
            filePdf = scheme.error,
            fileOther = scheme.secondary,
        )
    }
}

val LocalGopanColors = staticCompositionLocalOf { GopanColors.GopanLight }
