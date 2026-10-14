package xyz.yophon.gopan.feature.preview

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import xyz.yophon.gopan.core.data.preview.TextFetcher
import xyz.yophon.gopan.core.data.preview.readUpTo
import java.nio.charset.Charset

class PreviewLogicTest {

    @Test
    fun `text chunks follow lines and join back to the original`() {
        val text = (1..130).joinToString("\n") { "line $it" } + "\n"
        val chunks = chunkText(text, maxLines = 60)
        assertThat(chunks).hasSize(3)
        assertThat(chunks.joinToString("\n")).isEqualTo(text)
        assertThat(chunks[0].lines()).hasSize(60)
    }

    @Test
    fun `text chunks stay under the character limit and split very long lines`() {
        val long = "x".repeat(25)
        val chunks = chunkText("ab\n$long\ncd", maxLines = 60, maxChars = 10)
        assertThat(chunks.all { it.length <= 10 }).isTrue()
        assertThat(chunks.joinToString("").replace("\n", "")).isEqualTo("ab${long}cd")
    }

    @Test
    fun `empty text is one empty chunk`() {
        assertThat(chunkText("")).containsExactly("")
    }

    @Test
    fun `pdf pages render at the requested width unless a side would exceed the cap`() {
        assertThat(PdfDocument.renderSize(1080, 1.414f)).isEqualTo(1080 to 1527)
        assertThat(PdfDocument.renderSize(9000, 1f)).isEqualTo(PdfDocument.MAX_SIDE to PdfDocument.MAX_SIDE)
        // 长条页(收据、长图转的 PDF):按高封顶,宽等比缩小,不变形
        val (w, h) = PdfDocument.renderSize(2700, 10f)
        assertThat(h).isEqualTo(PdfDocument.MAX_SIDE)
        assertThat(w).isEqualTo(409)
    }

    @Test
    fun `clock format`() {
        assertThat(formatClock(0)).isEqualTo("00:00")
        assertThat(formatClock(65_400)).isEqualTo("01:05")
        assertThat(formatClock(3_725_000)).isEqualTo("1:02:05")
        assertThat(formatClock(-5)).isEqualTo("00:00")
    }

    @Test
    fun `text decoding prefers utf8, strips the bom and falls back to gb18030`() {
        val utf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "你好 gopan".toByteArray()
        assertThat(TextFetcher.decode(utf8, truncated = false))
            .isEqualTo(xyz.yophon.gopan.core.data.preview.TextContent("你好 gopan", false, "UTF-8", nextOffset = 15))

        val gbk = "中文老文件".toByteArray(Charset.forName("GB18030"))
        val decoded = TextFetcher.decode(gbk, truncated = false)
        assertThat(decoded.text).isEqualTo("中文老文件")
        assertThat(decoded.charset).isEqualTo("GB18030")
    }

    @Test
    fun `a truncated utf8 text drops the half character at the end`() {
        val bytes = "完整的字".toByteArray()
        val cut = bytes.copyOf(bytes.size - 1)
        val decoded = TextFetcher.decode(cut, truncated = true)
        assertThat(decoded.text).isEqualTo("完整的")
        assertThat(decoded.charset).isEqualTo("UTF-8")
        assertThat(decoded.truncated).isTrue()
    }

    @Test
    fun `text reads stop at the limit (no InputStream readNBytes below api 33)`() {
        val data = ByteArray(100_000) { (it % 251).toByte() }
        val head = java.io.ByteArrayInputStream(data).readUpTo(40_000)
        assertThat(head.size).isEqualTo(40_000)
        assertThat(head.toList()).isEqualTo(data.copyOf(40_000).toList())
        val all = java.io.ByteArrayInputStream(data.copyOf(10)).readUpTo(40_000)
        assertThat(all.size).isEqualTo(10)
    }

    @Test
    fun `content range total`() {
        assertThat(TextFetcher.contentRangeTotal("bytes 0-524287/9000000")).isEqualTo(9_000_000L)
        assertThat(TextFetcher.contentRangeTotal("bytes 0-10/*")).isNull()
        assertThat(TextFetcher.contentRangeTotal(null)).isNull()
    }

    @Test
    fun `preview items carry the list thumbnail key only for files with thumbnails`() {
        val withThumb = xyz.yophon.gopan.core.data.Node(
            id = "a",
            name = "a.jpg",
            kind = xyz.yophon.gopan.core.data.NodeKind.FILE,
            updatedAt = "t",
            preview = xyz.yophon.gopan.core.data.NodePreview(xyz.yophon.gopan.core.data.PreviewKind.IMAGE, hasThumbnail = true),
        )
        val plain = withThumb.copy(id = "b", preview = xyz.yophon.gopan.core.data.NodePreview())
        assertThat(withThumb.toPreviewItem().thumbKey).isEqualTo("a@t")
        assertThat(plain.toPreviewItem().thumbKey).isNull()
    }
}
