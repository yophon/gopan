package xyz.yophon.gopan.feature.clips

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import xyz.yophon.gopan.core.data.clips.Clip

/** 系统分享来的文字里取网址、站点名 */
class ClipLogicTest {

    @Test
    fun `shared url is the first http(s) link in the text`() {
        assertThat(ClipLogic.sharedUrl(null, "https://example.com/post/1")).isEqualTo("https://example.com/post/1")
        // 浏览器分享:「标题 网址」,subject 是标题
        assertThat(ClipLogic.sharedUrl("好文章", "好文章 https://blog.example.com/a?id=3 http://other.com/b"))
            .isEqualTo("https://blog.example.com/a?id=3")
        assertThat(ClipLogic.sharedUrl(null, "HTTPS://Example.com/Up")).isEqualTo("HTTPS://Example.com/Up")
    }

    @Test
    fun `shared url is cut off from Chinese text and trailing punctuation`() {
        assertThat(ClipLogic.sharedUrl(null, "看这篇(https://example.com/a)。")).isEqualTo("https://example.com/a")
        assertThat(ClipLogic.sharedUrl(null, "看这篇（https://example.com/a）。")).isEqualTo("https://example.com/a")
        assertThat(ClipLogic.sharedUrl(null, "【标题】https://example.com/a,推荐")).isEqualTo("https://example.com/a")
        assertThat(ClipLogic.sharedUrl(null, "https://example.com/a，转给你")).isEqualTo("https://example.com/a")
        assertThat(ClipLogic.sharedUrl(null, "链接:https://example.com/a.html.")).isEqualTo("https://example.com/a.html")
        assertThat(ClipLogic.sharedUrl(null, "https://example.com/a!")).isEqualTo("https://example.com/a")
        // 链接里的逗号(后面紧跟着 ASCII)保留
        assertThat(ClipLogic.sharedUrl(null, "https://example.com/a?ids=1,2 再看")).isEqualTo("https://example.com/a?ids=1,2")
    }

    @Test
    fun `shared url falls back to the subject and is null without any link`() {
        assertThat(ClipLogic.sharedUrl("https://example.com/from-subject", "只有标题")).isEqualTo("https://example.com/from-subject")
        assertThat(ClipLogic.sharedUrl("标题", "没有网址的一段话")).isNull()
        assertThat(ClipLogic.sharedUrl(null, null)).isNull()
        assertThat(ClipLogic.sharedUrl(null, "ftp://example.com/x mailto:a@b.com")).isNull()
        assertThat(ClipLogic.sharedUrl(null, "https://")).isNull()
    }

    @Test
    fun `site label falls back to the host without www`() {
        val clip = Clip(id = "c", url = "https://www.example.com/post", title = "t")
        assertThat(ClipLogic.siteLabel(clip)).isEqualTo("example.com")
        assertThat(ClipLogic.siteLabel(clip.copy(site = " 少数派 "))).isEqualTo("少数派")
        assertThat(ClipLogic.siteLabel(clip.copy(url = "not a url"))).isEmpty()
    }
}
