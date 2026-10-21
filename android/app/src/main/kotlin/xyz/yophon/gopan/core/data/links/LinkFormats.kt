package xyz.yophon.gopan.core.data.links

/** 复制直链的格式:链接(默认)/ Markdown / HTML */
enum class LinkFormat { URL, MARKDOWN, HTML }

/**
 * 直链的几种写法(纯函数,单测覆盖)。与网页端「复制直链」一致:
 * Markdown 图片 `![名](地址)`、其余 `[名](地址)`;HTML 图片 `<img src alt>`、其余 `<a href>名</a>`;多个文件一行一条。
 */
object LinkFormats {

    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "bmp", "svg", "ico", "heic", "heif", "tif", "tiff")

    /**
     * 服务端知道站点地址时 [url] 已是完整地址;没配(GOPAN_PUBLIC_URL 为空)时是 /x/… 的站内路径,拼上 App 连的服务端地址。
     */
    fun absoluteUrl(url: String, baseUrl: String): String =
        if (url.startsWith("/")) baseUrl.trimEnd('/') + url else url

    /** 按 MIME(没有时看扩展名)判断是不是图片:Markdown / HTML 里图片写成内嵌图 */
    fun isImage(name: String, mime: String?): Boolean {
        if (!mime.isNullOrBlank()) return mime.startsWith("image/", ignoreCase = true)
        return name.substringAfterLast('.', "").lowercase() in imageExtensions
    }

    fun format(link: LinkText, format: LinkFormat): String = when (format) {
        LinkFormat.URL -> link.url
        LinkFormat.MARKDOWN -> {
            val name = markdownText(link.name)
            val url = markdownUrl(link.url)
            if (link.isImage) "![$name]($url)" else "[$name]($url)"
        }
        LinkFormat.HTML -> {
            val name = html(link.name)
            val url = html(link.url)
            if (link.isImage) "<img src=\"$url\" alt=\"$name\">" else "<a href=\"$url\">$name</a>"
        }
    }

    /** 多个文件一行一条 */
    fun format(links: List<LinkText>, format: LinkFormat): String = links.joinToString("\n") { format(it, format) }

    /** 方括号与反斜杠转义,换行换成空格(文件名里极少见,但会把链接文字截断) */
    private fun markdownText(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '\\', '[', ']' -> append('\\').append(c)
                '\n', '\r' -> append(' ')
                else -> append(c)
            }
        }
    }

    /** 地址里的空格和圆括号会让 Markdown 解析提前结束:百分号编码 */
    private fun markdownUrl(s: String): String = s.replace(" ", "%20").replace("(", "%28").replace(")", "%29")

    private fun html(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

/** 拼文案需要的三样:文件名、完整地址、是不是图片 */
data class LinkText(val name: String, val url: String, val isImage: Boolean)

fun DirectLink.toLinkText(): LinkText = LinkText(name, url, LinkFormats.isImage(name, mime))
