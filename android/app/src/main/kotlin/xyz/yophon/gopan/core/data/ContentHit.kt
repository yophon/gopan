package xyz.yophon.gopan.core.data

import androidx.compose.runtime.Immutable
import xyz.yophon.gopan.graphql.SearchContentQuery

/*
 * 按内容搜索(doc/20 第三节)的领域模型:一条结果 = 文件 + 第一处命中前后的一段原文 + 高亮区间 + PDF 页码。
 * 不碰网络与 IO 的部分(区间换算、分页合并)放在这里,直接单测。
 */

/** 片段里的一处命中;[start] / [length] 按码点计(与服务端、网页端 Array.from 一致),不是 UTF-16 下标 */
@Immutable
data class TextRange(val start: Int, val length: Int)

@Immutable
data class ContentHit(
    val node: Node,
    /** 两头被截时服务端已带上"…" */
    val snippet: String,
    val highlights: List<TextRange>,
    /** 第一处命中所在页(PDF / 由 Office 转出的 PDF,从 1 起);其余格式为 null */
    val page: Int?,
)

/** 一页内容搜索结果;没有 total(服务端要把候选全复核一遍才数得出来),「加载更多」只靠游标 */
@Immutable
data class ContentHitPage(
    val items: List<ContentHit>,
    val nextCursor: String?,
) {
    companion object {
        val EMPTY = ContentHitPage(emptyList(), null)
    }
}

/**
 * 把按码点计的高亮区间换成 [snippet] 里的 UTF-16 下标区间(start 含、end 不含),按起点升序。
 * 表情符号等代理对占两个 UTF-16 单元、只算一个码点,直接拿 start 当 String 下标会错位甚至切开代理对。
 * 与网页端 splitHighlights 同样的取舍:长度 ≤ 0、越界、与前一个重叠的区间忽略。
 */
fun highlightRanges(snippet: String, ranges: List<TextRange>): List<IntRange> {
    if (ranges.isEmpty() || snippet.isEmpty()) return emptyList()
    // offsets[i] = 第 i 个码点在 snippet 里的 UTF-16 下标;offsets[码点数] = snippet.length
    val count = snippet.codePointCount(0, snippet.length)
    val offsets = IntArray(count + 1)
    var index = 0
    for (i in 0 until count) {
        offsets[i] = index
        index += Character.charCount(snippet.codePointAt(index))
    }
    offsets[count] = snippet.length

    val out = ArrayList<IntRange>(ranges.size)
    var pos = 0
    for (r in ranges.sortedBy { it.start }) {
        if (r.length <= 0 || r.start < pos || r.length > count - r.start) continue
        out += offsets[r.start] until offsets[r.start + r.length]
        pos = r.start + r.length
    }
    return out
}

/** 追加下一页:已有的文件丢弃(翻页期间文件被改动时服务端可能重复给出同一个,列表 key 也不能重复) */
fun mergeHits(existing: List<ContentHit>, next: List<ContentHit>): List<ContentHit> {
    if (next.isEmpty()) return existing
    val seen = HashSet<String>(existing.size + next.size)
    val merged = ArrayList<ContentHit>(existing.size + next.size)
    for (h in existing) if (seen.add(h.node.id)) merged += h
    for (h in next) if (seen.add(h.node.id)) merged += h
    return merged
}

fun SearchContentQuery.Item.toContentHit(): ContentHit = ContentHit(
    node = node.nodeItem.toNode(),
    snippet = snippet,
    highlights = highlights.map { TextRange(it.start, it.length) },
    page = page,
)
