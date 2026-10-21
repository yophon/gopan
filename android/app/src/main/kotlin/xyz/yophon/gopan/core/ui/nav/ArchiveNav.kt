package xyz.yophon.gopan.core.ui.nav

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 打开压缩包浏览页([Routes.Archive],根导航上的全屏页)。由根导航提供:预览全屏页与平板双栏里的侧栏预览
 * 都在它下面,侧栏预览的宿主(文件页)不用一层层往下传回调。没有提供时什么都不做。
 */
val LocalOpenArchive = staticCompositionLocalOf<(Routes.Archive) -> Unit> { {} }
