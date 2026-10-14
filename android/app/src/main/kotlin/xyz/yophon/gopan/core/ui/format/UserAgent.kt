package xyz.yophon.gopan.core.ui.format

/**
 * 原始 UA 归类成一句人话(登录设备、分享访问记录用),规则与网页端 web/src/utils/userAgent.ts 一致。
 * 认不出来就返回原始 UA 的截断;空 UA 返回「未知设备」。
 */
fun describeUserAgent(ua: String?): String {
    val raw = ua.orEmpty().trim()
    if (raw.isEmpty()) return "未知设备"
    val parts = listOfNotNull(matchFirst(CLIENT_RULES, raw), matchFirst(OS_RULES, raw), matchFirst(FORM_RULES, raw))
    return if (parts.isNotEmpty()) parts.joinToString(" · ") else truncate(raw)
}

private val CLIENT_RULES = listOf(
    // 本 App:gopan-android/3.0.0 (Android 16; HUAWEI AAK-AN00),排在 okhttp 前面
    Regex("gopan-android", RegexOption.IGNORE_CASE) to "gopan 安卓 App",
    Regex("micromessenger", RegexOption.IGNORE_CASE) to "微信",
    Regex("edg/", RegexOption.IGNORE_CASE) to "Edge",
    Regex("opr/|opera", RegexOption.IGNORE_CASE) to "Opera",
    Regex("firefox", RegexOption.IGNORE_CASE) to "Firefox",
    Regex("chrome|crios", RegexOption.IGNORE_CASE) to "Chrome",
    Regex("safari", RegexOption.IGNORE_CASE) to "Safari",
    Regex("rclone", RegexOption.IGNORE_CASE) to "rclone",
    Regex("gopan-?webdav", RegexOption.IGNORE_CASE) to "gopan WebDAV",
    Regex("curl", RegexOption.IGNORE_CASE) to "curl",
    Regex("wget", RegexOption.IGNORE_CASE) to "wget",
    Regex("okhttp|dart|python-requests|go-http-client|node-fetch", RegexOption.IGNORE_CASE) to "脚本/客户端",
)

private val OS_RULES = listOf(
    Regex("iphone|ipad|ipod", RegexOption.IGNORE_CASE) to "iOS",
    Regex("android", RegexOption.IGNORE_CASE) to "Android",
    Regex("windows", RegexOption.IGNORE_CASE) to "Windows",
    Regex("mac os x|macintosh", RegexOption.IGNORE_CASE) to "macOS",
    Regex("linux", RegexOption.IGNORE_CASE) to "Linux",
)

private val FORM_RULES = listOf(
    Regex("ipad|tablet", RegexOption.IGNORE_CASE) to "平板",
    Regex("mobile|iphone|android", RegexOption.IGNORE_CASE) to "手机",
)

private fun matchFirst(rules: List<Pair<Regex, String>>, ua: String): String? =
    rules.firstOrNull { (re, _) -> re.containsMatchIn(ua) }?.second

private fun truncate(s: String, max: Int = 40): String = if (s.length <= max) s else s.take(max) + "…"
