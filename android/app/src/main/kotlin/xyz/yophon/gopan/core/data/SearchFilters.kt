package xyz.yophon.gopan.core.data

import androidx.compose.runtime.Immutable
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 搜索筛选(doc/30,与网页端一致):类型、修改时间、大小、只搜某个文件夹、排序。
 * 类型的划分跟空间分析一样(服务端 gopan_file_category),FOLDER 是文件夹。
 */
enum class SearchKind { FOLDER, IMAGE, VIDEO, AUDIO, DOCUMENT, TEXT, ARCHIVE, OTHER }

enum class SearchTime { ANY, TODAY, WEEK, MONTH, YEAR, OLDER }

enum class SearchSize(val min: Long?, val max: Long?) {
    ANY(null, null),
    UNDER_1M(null, MB - 1),
    M1_TO_100M(MB, 100 * MB),
    M100_TO_1G(100 * MB, GB),
    OVER_1G(GB, null),
}

/** 默认最近修改的在前;内容搜索只按修改时间 */
enum class SearchSort { UPDATED, NAME, SIZE }

/** 只搜这个文件夹里的(含子文件夹) */
@Immutable
data class SearchScope(val folderId: String, val name: String)

@Immutable
data class SearchFilters(
    val kinds: Set<SearchKind> = emptySet(),
    val time: SearchTime = SearchTime.ANY,
    val size: SearchSize = SearchSize.ANY,
    val sort: SearchSort = SearchSort.UPDATED,
    val scope: SearchScope? = null,
) {
    /** 设了条件(只换排序不算):没有关键词也能只按条件找 */
    val hasConditions: Boolean
        get() = kinds.isNotEmpty() || time != SearchTime.ANY || size != SearchSize.ANY || scope != null

    val isDefault: Boolean get() = !hasConditions && sort == SearchSort.UPDATED

    /** 内容搜索:没有文件夹,也不换排序 */
    fun forContent(): SearchFilters = copy(kinds = kinds - SearchKind.FOLDER, sort = SearchSort.UPDATED)

    /** 修改时间的区间 [after, before) */
    fun timeRange(now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())): Pair<Instant?, Instant?> = when (time) {
        SearchTime.ANY -> null to null
        SearchTime.TODAY -> now.toLocalDate().atStartOfDay(now.zone).toInstant() to null
        SearchTime.WEEK -> now.minusDays(7).toInstant() to null
        SearchTime.MONTH -> now.minusDays(30).toInstant() to null
        SearchTime.YEAR -> now.toLocalDate().withDayOfYear(1).atStartOfDay(now.zone).toInstant() to null
        SearchTime.OLDER -> null to now.minusDays(365).toInstant()
    }
}

private const val MB = 1024L * 1024
private const val GB = 1024L * MB
