package xyz.yophon.gopan.core.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 内容搜索的高亮区间换算(码点 → UTF-16)与分页合并;与网页端 utils/highlight.test.ts 同样的用例打底 */
class ContentHitTest {

    private fun r(start: Int, length: Int) = TextRange(start, length)

    /** 按换算出的区间切出被高亮的文字,断言起来更直观 */
    private fun marked(snippet: String, ranges: List<TextRange>): List<String> =
        highlightRanges(snippet, ranges).map { snippet.substring(it.first, it.last + 1) }

    @Test
    fun `ranges split the snippet like the web does`() {
        val snippet = "…签订的租房合同,合同期"
        assertThat(highlightRanges(snippet, listOf(r(6, 2), r(9, 2)))).containsExactly(6 until 8, 9 until 11).inOrder()
        assertThat(marked(snippet, listOf(r(6, 2), r(9, 2)))).containsExactly("合同", "合同").inOrder()
    }

    @Test
    fun `ranges count code points, so an emoji before the hit is one character`() {
        // 😀 是代理对:两个 UTF-16 单元、一个码点
        assertThat(highlightRanges("😀合同", listOf(r(1, 2)))).containsExactly(2 until 4)
        assertThat(marked("😀合同", listOf(r(1, 2)))).containsExactly("合同")
        // 肤色修饰是第二个码点;高亮紧跟其后
        assertThat(marked("👍🏽好合同", listOf(r(3, 2)))).containsExactly("合同")
        // 两处命中之间夹着表情
        assertThat(marked("合同😀😀合同", listOf(r(0, 2), r(4, 2)))).containsExactly("合同", "合同").inOrder()
    }

    @Test
    fun `a highlighted emoji keeps its surrogate pair whole`() {
        assertThat(highlightRanges("a😀b", listOf(r(1, 1)))).containsExactly(1 until 3)
        assertThat(marked("a😀b", listOf(r(1, 1)))).containsExactly("😀")
        // 区间收在字符串末尾的表情上
        assertThat(marked("ab😀", listOf(r(2, 1)))).containsExactly("😀")
    }

    @Test
    fun `out of range, overlapping, empty and negative ranges are ignored`() {
        // 与网页端同一用例:越界的、与前一个重叠的都丢掉
        assertThat(marked("abc", listOf(r(2, 5), r(0, 1), r(0, 2)))).containsExactly("a")
        assertThat(highlightRanges("abc", listOf(r(-1, 2)))).isEmpty()
        assertThat(highlightRanges("abc", listOf(r(1, 0)))).isEmpty()
        assertThat(highlightRanges("abc", listOf(r(3, 1)))).isEmpty()
        assertThat(highlightRanges("abc", listOf(r(1, Int.MAX_VALUE)))).isEmpty()
        // 码点数比 UTF-16 长度少:按 UTF-16 长度算没越界、按码点算越界的区间也要丢
        assertThat(highlightRanges("😀😀", listOf(r(1, 2)))).isEmpty()
        // 正好到末尾是合法的
        assertThat(marked("abc", listOf(r(0, 3)))).containsExactly("abc")
        assertThat(marked("😀😀", listOf(r(0, 2)))).containsExactly("😀😀")
    }

    @Test
    fun `ranges are applied in order of their start`() {
        assertThat(marked("abcde", listOf(r(4, 1), r(0, 1)))).containsExactly("a", "e").inOrder()
    }

    @Test
    fun `no ranges or an empty snippet means nothing to highlight`() {
        assertThat(highlightRanges("abc", emptyList())).isEmpty()
        assertThat(highlightRanges("", listOf(r(0, 1)))).isEmpty()
    }

    @Test
    fun `merging pages drops files already shown`() {
        fun hit(id: String) = ContentHit(Node(id = id, name = id, kind = NodeKind.FILE), id, emptyList(), null)
        val merged = mergeHits(listOf(hit("a"), hit("b")), listOf(hit("b"), hit("c")))
        assertThat(merged.map { it.node.id }).containsExactly("a", "b", "c").inOrder()
        val existing = listOf(hit("a"))
        assertThat(mergeHits(existing, emptyList())).isSameInstanceAs(existing)
    }
}
