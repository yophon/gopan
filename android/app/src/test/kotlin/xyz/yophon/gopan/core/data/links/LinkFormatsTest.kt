package xyz.yophon.gopan.core.data.links

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 复制直链的几种写法 */
class LinkFormatsTest {

    private val photo = LinkText("猫.jpg", "https://drive.example.com/x/AbC123xyz9/猫.jpg", isImage = true)
    private val doc = LinkText("报告.pdf", "https://drive.example.com/x/QwE456rty8/报告.pdf", isImage = false)

    @Test
    fun `relative paths get the app base url, absolute urls are kept`() {
        assertThat(LinkFormats.absoluteUrl("/x/AbC123xyz9/a.png", "https://drive.example.com/"))
            .isEqualTo("https://drive.example.com/x/AbC123xyz9/a.png")
        assertThat(LinkFormats.absoluteUrl("/x/AbC123xyz9/a.png", "http://10.0.2.2:8090"))
            .isEqualTo("http://10.0.2.2:8090/x/AbC123xyz9/a.png")
        assertThat(LinkFormats.absoluteUrl("https://img.example.com/x/t/a.png", "https://drive.example.com"))
            .isEqualTo("https://img.example.com/x/t/a.png")
    }

    @Test
    fun `images are detected by mime first, then by extension`() {
        assertThat(LinkFormats.isImage("a.bin", "image/png")).isTrue()
        assertThat(LinkFormats.isImage("a.png", "application/octet-stream")).isFalse()
        assertThat(LinkFormats.isImage("Photo.JPG", null)).isTrue()
        assertThat(LinkFormats.isImage("icon.svg", "")).isTrue()
        assertThat(LinkFormats.isImage("movie.mkv", null)).isFalse()
        assertThat(LinkFormats.isImage("README", null)).isFalse()
    }

    @Test
    fun `plain url`() {
        assertThat(LinkFormats.format(photo, LinkFormat.URL)).isEqualTo(photo.url)
    }

    @Test
    fun `markdown uses image syntax for images and link syntax for the rest`() {
        assertThat(LinkFormats.format(photo, LinkFormat.MARKDOWN)).isEqualTo("![猫.jpg](https://drive.example.com/x/AbC123xyz9/猫.jpg)")
        assertThat(LinkFormats.format(doc, LinkFormat.MARKDOWN)).isEqualTo("[报告.pdf](https://drive.example.com/x/QwE456rty8/报告.pdf)")
    }

    @Test
    fun `markdown escapes brackets in names and parentheses and spaces in urls`() {
        val odd = LinkText("[草稿] a\\b (1).png", "https://d.example.com/x/T/[草稿] a (1).png", isImage = true)
        assertThat(LinkFormats.format(odd, LinkFormat.MARKDOWN))
            .isEqualTo("![\\[草稿\\] a\\\\b (1).png](https://d.example.com/x/T/[草稿]%20a%20%281%29.png)")
    }

    @Test
    fun `html uses img for images and a for the rest, escaping attributes and text`() {
        assertThat(LinkFormats.format(photo, LinkFormat.HTML))
            .isEqualTo("<img src=\"https://drive.example.com/x/AbC123xyz9/猫.jpg\" alt=\"猫.jpg\">")
        assertThat(LinkFormats.format(doc, LinkFormat.HTML))
            .isEqualTo("<a href=\"https://drive.example.com/x/QwE456rty8/报告.pdf\">报告.pdf</a>")
        val odd = LinkText("a<b>&\"c\".txt", "https://d.example.com/x/T/a.txt?s=1&t=2", isImage = false)
        assertThat(LinkFormats.format(odd, LinkFormat.HTML))
            .isEqualTo("<a href=\"https://d.example.com/x/T/a.txt?s=1&amp;t=2\">a&lt;b&gt;&amp;&quot;c&quot;.txt</a>")
    }

    @Test
    fun `several files give one line each`() {
        assertThat(LinkFormats.format(listOf(photo, doc), LinkFormat.URL)).isEqualTo("${photo.url}\n${doc.url}")
        assertThat(LinkFormats.format(listOf(photo, doc), LinkFormat.MARKDOWN).lines()).hasSize(2)
        assertThat(LinkFormats.format(emptyList(), LinkFormat.HTML)).isEmpty()
    }

    @Test
    fun `direct link maps to link text`() {
        val link = DirectLink(id = "l1", url = "https://d.example.com/x/T/a.webp", nodeId = "n1", name = "a.webp", mime = "image/webp")
        assertThat(link.toLinkText()).isEqualTo(LinkText("a.webp", "https://d.example.com/x/T/a.webp", isImage = true))
    }
}
