package xyz.yophon.gopan.feature.account

import xyz.yophon.gopan.core.ui.format.formatBytes
import kotlin.math.roundToInt

/** 用量条的颜色档位:80% 起提醒,95% 起告警 */
enum class UsageLevel { NORMAL, WARN, CRITICAL }

/**
 * 「我的」页头部用量条的纯数据:已用 / 配额 → 比例、百分比、文案。
 * 配额为 0 或负数视为不限(比例 0,文案只显示已用);已用超过配额时比例封顶 100%。
 */
data class UsageSummary(
    val usedBytes: Long,
    val quotaBytes: Long,
) {
    val unlimited: Boolean get() = quotaBytes <= 0

    /** 0f..1f,给 LinearProgressIndicator */
    val fraction: Float
        get() = if (unlimited) 0f else (usedBytes.coerceAtLeast(0).toDouble() / quotaBytes).coerceIn(0.0, 1.0).toFloat()

    /** 四舍五入的整数百分比 0..100 */
    val percent: Int get() = (fraction * 100).roundToInt()

    val level: UsageLevel
        get() = when {
            unlimited -> UsageLevel.NORMAL
            percent >= CRITICAL_PERCENT -> UsageLevel.CRITICAL
            percent >= WARN_PERCENT -> UsageLevel.WARN
            else -> UsageLevel.NORMAL
        }

    /** "1.2 GB / 10.0 GB";不限配额时 "1.2 GB" */
    val text: String
        get() = if (unlimited) formatBytes(usedBytes.coerceAtLeast(0)) else "${formatBytes(usedBytes.coerceAtLeast(0))} / ${formatBytes(quotaBytes)}"

    /** "已用 12%";不限配额时 "不限配额" */
    val percentText: String get() = if (unlimited) "不限配额" else "已用 $percent%"

    companion object {
        const val WARN_PERCENT = 80
        const val CRITICAL_PERCENT = 95
    }
}
