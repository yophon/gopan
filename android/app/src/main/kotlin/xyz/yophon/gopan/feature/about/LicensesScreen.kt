package xyz.yophon.gopan.feature.about

import androidx.annotation.RawRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.ui.exposeTestTags
import xyz.yophon.gopan.core.ui.rememberSystemActions
import xyz.yophon.gopan.feature.drive.NestedScreenInsets
import xyz.yophon.gopan.feature.drive.TopBarInsets
import androidx.compose.ui.input.nestedscroll.nestedScroll
import xyz.yophon.gopan.feature.drive.belowCollapsingTopBar
import xyz.yophon.gopan.feature.drive.bottomBarPadding
import xyz.yophon.gopan.feature.drive.collapsingTopBarColors
import xyz.yophon.gopan.feature.drive.rememberSubPageScroll
import androidx.compose.foundation.layout.PaddingValues

enum class OssLicense(val label: String, @RawRes val text: Int) {
    APACHE_2_0("Apache License 2.0", R.raw.license_apache_2_0),
    MIT("MIT License", R.raw.license_mit),
}

@Immutable
data class OssLibrary(val name: String, val author: String, val license: OssLicense, val url: String)

/**
 * App 里打包的第三方库,含传递依赖。加依赖或升级后用
 * `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` 核对,漏了的在这里补一行。
 */
val OSS_LIBRARIES = listOf(
    OssLibrary("Kotlin 标准库 / kotlinx.coroutines / kotlinx.serialization / Compose Multiplatform 运行时", "JetBrains", OssLicense.APACHE_2_0, "https://github.com/JetBrains/kotlin"),
    OssLibrary("AndroidX(Core、Activity、Lifecycle、Navigation、Room、WorkManager、DataStore、Security)", "The Android Open Source Project", OssLicense.APACHE_2_0, "https://developer.android.com/jetpack/androidx"),
    OssLibrary("Jetpack Compose 与 Material 3、Material Icons", "The Android Open Source Project", OssLicense.APACHE_2_0, "https://developer.android.com/jetpack/compose"),
    OssLibrary("Media3(ExoPlayer、Session、UI)", "The Android Open Source Project", OssLicense.APACHE_2_0, "https://github.com/androidx/media"),
    OssLibrary("Dagger / Hilt", "Google", OssLicense.APACHE_2_0, "https://github.com/google/dagger"),
    OssLibrary("Apollo Kotlin", "Apollo Graph, Inc.", OssLicense.MIT, "https://github.com/apollographql/apollo-kotlin"),
    OssLibrary("OkHttp / Okio", "Square, Inc.", OssLicense.APACHE_2_0, "https://github.com/square/okhttp"),
    OssLibrary("Coil", "Coil Contributors", OssLicense.APACHE_2_0, "https://github.com/coil-kt/coil"),
    OssLibrary("AppAuth for Android", "The AppAuth Authors", OssLicense.APACHE_2_0, "https://github.com/openid/AppAuth-Android"),
    OssLibrary("Timber", "Jake Wharton", OssLicense.APACHE_2_0, "https://github.com/JakeWharton/timber"),
    // 助手(doc/33):QuickJS 里跑 tau 内核
    OssLibrary("quickjs-kt", "dokar3", OssLicense.APACHE_2_0, "https://github.com/dokar3/quickjs-kt"),
    OssLibrary("QuickJS", "Fabrice Bellard, Charlie Gordon", OssLicense.MIT, "https://bellard.org/quickjs/"),
    OssLibrary("tau(助手内核)", "yophon", OssLicense.MIT, "https://github.com/yophon/tau"),
    OssLibrary("Guava", "Google", OssLicense.APACHE_2_0, "https://github.com/google/guava"),
    OssLibrary("Tink(登录态加密存储的加密实现)", "Google", OssLicense.APACHE_2_0, "https://github.com/tink-crypto/tink-java"),
    OssLibrary("Gson", "Google", OssLicense.APACHE_2_0, "https://github.com/google/gson"),
    OssLibrary("Accompanist Drawable Painter", "Google", OssLicense.APACHE_2_0, "https://github.com/google/accompanist"),
    OssLibrary("uuid", "Ben Asher", OssLicense.MIT, "https://github.com/benasher44/uuid"),
    OssLibrary("javax.inject / Jakarta Dependency Injection", "JSR-330 Expert Group / Eclipse Foundation", OssLicense.APACHE_2_0, "https://github.com/jakartaee/inject"),
    OssLibrary("注解库(JSpecify、JetBrains Annotations、JSR 305)", "JSpecify / JetBrains / FindBugs", OssLicense.APACHE_2_0, "https://jspecify.dev"),
)

/** 开源许可:每个库的作者、许可证,点许可证看全文,点名字打开项目主页 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val system = rememberSystemActions()
    val context = LocalContext.current
    var showing by remember { mutableStateOf<OssLicense?>(null) }
    val topBarScroll = rememberSubPageScroll()
    Scaffold(
        contentWindowInsets = NestedScreenInsets,
        modifier = Modifier.nestedScroll(topBarScroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                windowInsets = TopBarInsets,
                scrollBehavior = topBarScroll,
                colors = collapsingTopBarColors(topBarScroll),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.preview_back)) }
                },
                title = { Text(stringResource(R.string.licenses_title)) },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.belowCollapsingTopBar(padding, topBarScroll).fillMaxSize().testTag("licenses_list"),
            contentPadding = PaddingValues(bottom = bottomBarPadding()),
        ) {
            item {
                Text(
                    stringResource(R.string.licenses_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(OSS_LIBRARIES) { lib ->
                ListItem(
                    modifier = Modifier.clickable { showing = lib.license },
                    headlineContent = { Text(lib.name) },
                    supportingContent = { Text("${lib.author} · ${lib.license.label}") },
                    trailingContent = {
                        TextButton(onClick = { system.openUrl(lib.url) }) { Text(stringResource(R.string.licenses_homepage)) }
                    },
                )
            }
        }
    }
    showing?.let { license ->
        val text = remember(license) { context.resources.openRawResource(license.text).bufferedReader().use { it.readText() } }
        AlertDialog(
            onDismissRequest = { showing = null },
            modifier = Modifier.exposeTestTags(),
            title = { Text(license.label) },
            text = {
                SelectionContainer {
                    Text(
                        text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.verticalScroll(rememberScrollState()).testTag("license_text"),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showing = null }) { Text(stringResource(R.string.update_close)) } },
        )
    }
}
