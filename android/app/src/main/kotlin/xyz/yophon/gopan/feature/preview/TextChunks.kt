package xyz.yophon.gopan.feature.preview

/**
 * 文本预览的分块:几百 KB 的文本放进一个 Text 排版要好几秒,切成小块交给 LazyColumn 按需排版。
 * 按行切,每块不超过 [maxLines] 行、约 [maxChars] 个字符;单行超过 [maxChars](压缩过的 JSON、日志)就硬切。
 * 不含硬切时,各块用换行连起来就是原文。
 */
fun chunkText(text: String, maxLines: Int = 60, maxChars: Int = 6000): List<String> {
    val out = ArrayList<String>()
    val block = StringBuilder()
    var lines = 0

    fun flush() {
        if (lines == 0) return
        out += block.toString()
        block.setLength(0)
        lines = 0
    }

    var start = 0
    while (true) {
        val nl = text.indexOf('\n', start)
        val end = if (nl < 0) text.length else nl
        var from = start
        while (end - from > maxChars) {
            flush()
            out += text.substring(from, from + maxChars)
            from += maxChars
        }
        if (lines >= maxLines || (lines > 0 && block.length + 1 + (end - from) > maxChars)) flush()
        if (lines > 0) block.append('\n')
        block.append(text, from, end)
        lines++
        if (nl < 0) break
        start = nl + 1
    }
    flush()
    return out
}
