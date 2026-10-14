package xyz.yophon.gopan.core.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import xyz.yophon.gopan.BuildConfig

/**
 * debug 包把 Compose testTag 暴露成 resource-id(E2E 用 uiautomator 定位)。根导航上设了一次,
 * 但对话框、弹出菜单是单独的窗口、不继承:它们的内容根上再加这个。
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.exposeTestTags(): Modifier = if (BuildConfig.DEBUG) semantics { testTagsAsResourceId = true } else this
