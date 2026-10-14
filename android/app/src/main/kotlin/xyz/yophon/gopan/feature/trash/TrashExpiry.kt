package xyz.yophon.gopan.feature.trash

import xyz.yophon.gopan.core.ui.format.parseTime
import java.time.Instant

private const val DAY_MS = 86_400_000L

enum class TrashExpiryLevel { NORMAL, SOON, EXPIRED }

/** 距彻删还剩几天与紧迫程度;[days] 为 null 表示服务端没给 purgeAt(不展示) */
data class TrashExpiry(val days: Int?, val level: TrashExpiryLevel)

/**
 * 与网页端 web/src/utils/trash.ts 一致:只认服务端给的 purgeAt(保留期由运维配置,客户端不估算);
 * 剩余时间向上取整成天;≤ 1 天是临期(界面标警示色);已过 purgeAt 是「已到期,待清理」(清理任务每小时扫一次)。
 */
fun trashExpiry(purgeAt: String?, now: Instant): TrashExpiry {
    val purge = parseTime(purgeAt) ?: return TrashExpiry(null, TrashExpiryLevel.NORMAL)
    val left = purge.toEpochMilli() - now.toEpochMilli()
    if (left <= 0) return TrashExpiry(0, TrashExpiryLevel.EXPIRED)
    val days = ((left + DAY_MS - 1) / DAY_MS).toInt()
    return TrashExpiry(days, if (days <= 1) TrashExpiryLevel.SOON else TrashExpiryLevel.NORMAL)
}
