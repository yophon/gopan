package xyz.yophon.gopan.core.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SearchFiltersTest {
    private val now = ZonedDateTime.of(2026, 10, 25, 15, 30, 0, 0, ZoneId.of("Asia/Shanghai"))

    @Test
    fun `time presets become a half-open range`() {
        assertThat(SearchFilters(time = SearchTime.TODAY).timeRange(now))
            .isEqualTo(ZonedDateTime.of(2026, 10, 25, 0, 0, 0, 0, now.zone).toInstant() to null)
        assertThat(SearchFilters(time = SearchTime.YEAR).timeRange(now).first)
            .isEqualTo(ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, now.zone).toInstant())
        assertThat(SearchFilters(time = SearchTime.OLDER).timeRange(now)).isEqualTo(null to now.minusDays(365).toInstant())
        assertThat(SearchFilters().timeRange(now)).isEqualTo(null to null)
    }

    @Test
    fun `conditions, defaults and the content variant`() {
        assertThat(SearchFilters().isDefault).isTrue()
        assertThat(SearchFilters(sort = SearchSort.SIZE).hasConditions).isFalse()
        assertThat(SearchFilters(sort = SearchSort.SIZE).isDefault).isFalse()
        assertThat(SearchFilters(size = SearchSize.OVER_1G).hasConditions).isTrue()
        val content = SearchFilters(kinds = setOf(SearchKind.FOLDER, SearchKind.DOCUMENT), sort = SearchSort.NAME).forContent()
        assertThat(content.kinds).containsExactly(SearchKind.DOCUMENT)
        assertThat(content.sort).isEqualTo(SearchSort.UPDATED)
        assertThat(SearchSize.UNDER_1M.max).isEqualTo(1024L * 1024 - 1)
    }
}
