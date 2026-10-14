package xyz.yophon.gopan.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/** Material 3 的窗口宽度断点:600dp 起是中等(侧边导航栏),840dp 起是展开(列表 + 预览双栏) */
const val MEDIUM_WIDTH_DP = 600
const val EXPANDED_WIDTH_DP = 840

@Composable
fun isMediumWidth(): Boolean = LocalConfiguration.current.screenWidthDp >= MEDIUM_WIDTH_DP

@Composable
fun isExpandedWidth(): Boolean = LocalConfiguration.current.screenWidthDp >= EXPANDED_WIDTH_DP
