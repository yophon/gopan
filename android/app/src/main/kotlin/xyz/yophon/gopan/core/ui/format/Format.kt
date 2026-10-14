package xyz.yophon.gopan.core.ui.format

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val PLACEHOLDER = "—"
private val units = arrayOf("KB", "MB", "GB", "TB", "PB")

/** 字节数 → 人类可读大小,规则与网页端 formatBytes 一致;null(文件夹无大小)返回占位符 */
fun formatBytes(bytes: Long?): String {
    if (bytes == null) return PLACEHOLDER
    if (bytes < 1024) return "$bytes B"
    var value = bytes.toDouble()
    var idx = -1
    do {
        value /= 1024
        idx++
    } while (value >= 1024 && idx < units.size - 1)
    return String.format(Locale.ROOT, "%.1f %s", value, units[idx])
}

/** RFC3339 → Instant;解析失败返回 null */
fun parseTime(iso: String?): Instant? {
    if (iso.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()
}

private val absoluteFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
private val sameYearFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)

/** 绝对时间 "yyyy-MM-dd HH:mm"(与网页端一致);无效输入返回占位符 */
fun formatTime(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String {
    val instant = parseTime(iso) ?: return PLACEHOLDER
    return absoluteFormatter.format(instant.atZone(zone))
}

/**
 * 相对时间:刚刚 / N 分钟前 / N 小时前 / N 天前(7 天内),再往前同年显示 "MM-dd HH:mm",
 * 跨年显示完整日期;未来时间直接显示绝对时间。
 */
fun formatRelativeTime(
    iso: String?,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val instant = parseTime(iso) ?: return PLACEHOLDER
    val seconds = now.epochSecond - instant.epochSecond
    return when {
        seconds < 0 -> absoluteFormatter.format(instant.atZone(zone))
        seconds < 60 -> "刚刚"
        seconds < 3600 -> "${seconds / 60} 分钟前"
        seconds < 86_400 -> "${seconds / 3600} 小时前"
        seconds < 7 * 86_400 -> "${seconds / 86_400} 天前"
        instant.atZone(zone).year == now.atZone(zone).year -> sameYearFormatter.format(instant.atZone(zone))
        else -> absoluteFormatter.format(instant.atZone(zone))
    }
}
