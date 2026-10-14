package xyz.yophon.gopan.core.ui.format

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import xyz.yophon.gopan.core.network.appUserAgent
import xyz.yophon.gopan.feature.shares.testConfig

class UserAgentTest {

    @Test
    fun `describes clients like the web does`() {
        assertThat(describeUserAgent(null)).isEqualTo("未知设备")
        assertThat(describeUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"))
            .isEqualTo("Chrome · Windows")
        assertThat(describeUserAgent("Mozilla/5.0 (Windows NT 10.0) Chrome/120.0 Safari/537.36 Edg/120.0")).isEqualTo("Edge · Windows")
        assertThat(describeUserAgent("Mozilla/5.0 (Linux; Android 14) Chrome/120 Mobile Safari/537.36")).isEqualTo("Chrome · Android · 手机")
        assertThat(describeUserAgent("rclone/v1.65.0")).isEqualTo("rclone")
        assertThat(describeUserAgent("x".repeat(60))).isEqualTo("x".repeat(40) + "…")
    }

    @Test
    fun `the app is not mistaken for a script`() {
        val ua = appUserAgent("3.0.0-p3", "16", "HUAWEI", "AAK-AN00") + " okhttp/4.12.0"
        assertThat(describeUserAgent(ua)).isEqualTo("gopan 安卓 App · Android · 手机")
    }

    @Test
    fun `the user agent header stays printable ascii`() {
        assertThat(appUserAgent("3.0.0", "16", "HUAWEI", "AAK-AN00")).isEqualTo("gopan-android/3.0.0 (Android 16; HUAWEI AAK-AN00)")
        assertThat(appUserAgent("3.0.0", "14", "小米", "Redmi K70")).isEqualTo("gopan-android/3.0.0 (Android 14; Redmi K70)")
        assertThat(appUserAgent("3.0.0", "14", "", "")).isEqualTo("gopan-android/3.0.0 (Android 14; )")
    }

    @Test
    fun `share, webdav and mcp addresses come from the base url`() {
        assertThat(testConfig.shareUrl("Ab_9-x")).isEqualTo("https://drive.example/s/Ab_9-x")
        assertThat(testConfig.davUrl).isEqualTo("https://drive.example/dav")
        assertThat(testConfig.mcpUrl).isEqualTo("https://drive.example/mcp")
    }
}
