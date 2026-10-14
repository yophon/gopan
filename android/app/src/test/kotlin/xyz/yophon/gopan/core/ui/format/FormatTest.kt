package xyz.yophon.gopan.core.ui.format

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class FormatTest {
    @Test
    fun `formatBytes matches the web rules`() {
        assertThat(formatBytes(null)).isEqualTo("—")
        assertThat(formatBytes(0)).isEqualTo("0 B")
        assertThat(formatBytes(1023)).isEqualTo("1023 B")
        assertThat(formatBytes(1024)).isEqualTo("1.0 KB")
        assertThat(formatBytes(1536)).isEqualTo("1.5 KB")
        assertThat(formatBytes(1048576)).isEqualTo("1.0 MB")
        assertThat(formatBytes(1073741824)).isEqualTo("1.0 GB")
        assertThat(formatBytes(1024L * 1024 * 1024 * 1024 * 1024 * 1024)).isEqualTo("1024.0 PB")
    }

    @Test
    fun `formatTime renders absolute time in the given zone`() {
        val zone = ZoneId.of("Asia/Shanghai")
        assertThat(formatTime("2026-10-14T01:05:00Z", zone)).isEqualTo("2026-10-14 09:05")
        assertThat(formatTime("2026-10-14T09:05:00+08:00", zone)).isEqualTo("2026-10-14 09:05")
        assertThat(formatTime("not a time", zone)).isEqualTo("—")
        assertThat(formatTime(null, zone)).isEqualTo("—")
    }

    @Test
    fun `formatRelativeTime buckets`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = Instant.parse("2026-10-14T08:00:00Z")
        fun rel(iso: String?) = formatRelativeTime(iso, now, zone)

        assertThat(rel("2026-10-14T07:59:30Z")).isEqualTo("刚刚")
        assertThat(rel("2026-10-14T07:45:00Z")).isEqualTo("15 分钟前")
        assertThat(rel("2026-10-14T05:00:00Z")).isEqualTo("3 小时前")
        assertThat(rel("2026-10-12T08:00:00Z")).isEqualTo("2 天前")
        assertThat(rel("2026-09-01T02:30:00Z")).isEqualTo("09-01 10:30")
        // UTC 还是去年,但按本地时区已是今年 → 同年格式
        assertThat(rel("2025-12-31T16:30:00Z")).isEqualTo("01-01 00:30")
        assertThat(rel("2025-06-01T02:30:00Z")).isEqualTo("2025-06-01 10:30")
        assertThat(rel("2026-10-14T09:00:00Z")).isEqualTo("2026-10-14 17:00")
        assertThat(rel(null)).isEqualTo("—")
    }
}
