package xyz.yophon.gopan.core.ui.nav

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 打开在线编辑页([Routes.TextEdit],根导航上的全屏页)。由根导航提供,预览页与文件页(在主界面的嵌套导航里)都能用。
 * 没有提供时什么都不做。
 */
val LocalOpenTextEditor = staticCompositionLocalOf<(Routes.TextEdit) -> Unit> { {} }
