package xyz.yophon.gopan.feature.search

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import xyz.yophon.gopan.core.data.TextRange

/** 片段渲染用的 AnnotatedString:文字原样,高亮落在 UTF-16 下标上 */
class ContentHighlightTest {

    private val mark = SpanStyle(background = Color.Yellow)

    @Test
    fun `highlights land on utf16 indices after an emoji`() {
        val s = highlightSnippet("…😀合同期", listOf(TextRange(2, 2)), mark)
        assertThat(s.text).isEqualTo("…😀合同期")
        val span = s.spanStyles.single()
        assertThat(span.item).isEqualTo(mark)
        assertThat(s.text.substring(span.start, span.end)).isEqualTo("合同")
    }

    @Test
    fun `line breaks and indentation collapse to one space and the highlight follows`() {
        // 码点:行0 1 1 \n2 \n3 空格4-7 合8 同9 \t10 x11
        val s = highlightSnippet("行1\n\n    合同\tx", listOf(TextRange(8, 2)), mark)
        assertThat(s.text).isEqualTo("行1 合同 x")
        val span = s.spanStyles.single()
        assertThat(s.text.substring(span.start, span.end)).isEqualTo("合同")

        // 表情与空白混在一起
        val e = highlightSnippet("😀  \n😀合同", listOf(TextRange(5, 2)), mark)
        assertThat(e.text).isEqualTo("😀 😀合同")
        assertThat(e.spanStyles.single().let { e.text.substring(it.start, it.end) }).isEqualTo("合同")
    }

    @Test
    fun `bad ranges leave plain text`() {
        val s = highlightSnippet("abc", listOf(TextRange(2, 5), TextRange(-1, 1)), mark)
        assertThat(s.text).isEqualTo("abc")
        assertThat(s.spanStyles).isEmpty()
    }
}
