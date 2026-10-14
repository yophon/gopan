package xyz.yophon.gopan.feature.trash

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant

class TrashExpiryTest {
    private val now = Instant.parse("2026-10-14T12:00:00Z")

    @Test
    fun `uses purgeAt when the server gives it, rounding up`() {
        assertThat(trashDaysLeft(null, "2026-10-16T12:00:00Z", now)).isEqualTo(2)
        assertThat(trashDaysLeft(null, "2026-10-16T13:00:00Z", now)).isEqualTo(3)
        assertThat(trashDaysLeft(null, "2026-11-13T12:00:00Z", now)).isEqualTo(30)
        // 服务端配置了不同保留期时 purgeAt 优先于 deletedAt + 30
        assertThat(trashDaysLeft("2026-10-10T12:00:00Z", "2026-10-17T12:00:00Z", now)).isEqualTo(3)
    }

    @Test
    fun `falls back to deletedAt plus 30 days`() {
        assertThat(trashDaysLeft("2026-10-14T12:00:00Z", null, now)).isEqualTo(30)
        assertThat(trashDaysLeft("2026-10-12T12:00:00Z", null, now)).isEqualTo(28)
        assertThat(trashDaysLeft("2026-10-12T00:00:00Z", null, now)).isEqualTo(28) // 27.5 天 → 向上取整
        assertThat(trashDaysLeft("2026-10-12T12:00:00+08:00", null, now)).isEqualTo(28)
    }

    @Test
    fun `less than a day left means today, including already expired`() {
        assertThat(trashDaysLeft(null, "2026-10-15T11:59:59Z", now)).isEqualTo(0)
        assertThat(trashDaysLeft(null, "2026-10-14T12:00:00Z", now)).isEqualTo(0)
        assertThat(trashDaysLeft(null, "2026-10-01T00:00:00Z", now)).isEqualTo(0)
        assertThat(trashDaysLeft("2026-09-14T13:00:00Z", null, now)).isEqualTo(0)
        // 刚好还剩整 1 天:不算"今天"
        assertThat(trashDaysLeft(null, "2026-10-15T12:00:00Z", now)).isEqualTo(1)
    }

    @Test
    fun `unknown when neither time parses`() {
        assertThat(trashDaysLeft(null, null, now)).isNull()
        assertThat(trashDaysLeft("garbage", "also garbage", now)).isNull()
    }
}
