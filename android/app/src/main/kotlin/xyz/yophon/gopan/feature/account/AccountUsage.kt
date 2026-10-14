package xyz.yophon.gopan.feature.account

import xyz.yophon.gopan.core.ui.format.formatBytes
import kotlin.math.roundToInt

/** 用量条的颜色档位:与网页端侧栏一致,70% 起提醒,90% 起告警 */
enum class UsageLevel { NORMAL, WARN, CRITICAL }

/**
 * 「我的」页头部用量条的纯数据:已用 / 配额 → 比例、百分比、文案。
 * 服务端没有"不限配额":上传时按 已用 + 新文件 > 配额 拒绝,配额为 0 就是一点都传不了,
 * 所以配额 ≤ 0 显示为已满(告警色),而不是"不限"。已用超过配额时比例封顶 100%。
 */
data class UsageSummary(
    val usedBytes: Long,
    val quotaBytes: Long,
) {
    /** 管理员把配额设成了 0(或异常的负数) */
    val noQuota: Boolean get() = quotaBytes <= 0

    /** 0f..1f,给 LinearProgressIndicator */
    val fraction: Float
        get() = if (noQuota) 1f else (usedBytes.coerceAtLeast(0).toDouble() / quotaBytes).coerceIn(0.0, 1.0).toFloat()

    /** 四舍五入的整数百分比 0..100 */
    val percent: Int get() = (fraction * 100).roundToInt()

    val level: UsageLevel
        get() = when {
            noQuota || percent >= CRITICAL_PERCENT -> UsageLevel.CRITICAL
            percent >= WARN_PERCENT -> UsageLevel.WARN
            else -> UsageLevel.NORMAL
        }

    /** "1.2 GB / 10.0 GB" */
    val text: String
        get() = "${formatBytes(usedBytes.coerceAtLeast(0))} / ${formatBytes(quotaBytes.coerceAtLeast(0))}"

    /** "已用 12%";配额为 0 时 "无可用配额" */
    val percentText: String get() = if (noQuota) "无可用配额" else "已用 $percent%"

    companion object {
        const val WARN_PERCENT = 70
        const val CRITICAL_PERCENT = 90
    }
}
