package xyz.yophon.gopan.feature.trash

import xyz.yophon.gopan.core.ui.format.parseTime
import java.time.Instant

/** 服务端默认保留期;只在 purgeAt 缺失时用来兜底估算 */
const val TRASH_RETENTION_DAYS = 30

private const val DAY_MS = 86_400_000L

/**
 * 距彻删还剩几天(向上取整):优先用服务端给的 purgeAt(保留期由运维配置),没有就按 deletedAt + 30 天估。
 * 不足一天(含已到期、等清理任务扫到)返回 0,界面显示「今天清理」;两个时间都没有返回 null。
 */
fun trashDaysLeft(
    deletedAt: String?,
    purgeAt: String?,
    now: Instant,
    retentionDays: Int = TRASH_RETENTION_DAYS,
): Int? {
    val purge = parseTime(purgeAt)
        ?: parseTime(deletedAt)?.plusMillis(retentionDays * DAY_MS)
        ?: return null
    val remaining = purge.toEpochMilli() - now.toEpochMilli()
    if (remaining < DAY_MS) return 0
    return ((remaining + DAY_MS - 1) / DAY_MS).toInt()
}
