package xyz.yophon.gopan.feature.trash

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant

/** 与 web/src/utils/trash.test.ts 同一组边界 */
class TrashExpiryTest {
    private val now = Instant.parse("2026-10-14T12:00:00Z")

    private fun at(offsetMs: Long) = Instant.ofEpochMilli(now.toEpochMilli() + offsetMs).toString()

    @Test
    fun `missing purgeAt is not shown`() {
        assertThat(trashExpiry(null, now)).isEqualTo(TrashExpiry(null, TrashExpiryLevel.NORMAL))
        assertThat(trashExpiry("not a time", now)).isEqualTo(TrashExpiry(null, TrashExpiryLevel.NORMAL))
    }

    @Test
    fun `past purgeAt is expired`() {
        assertThat(trashExpiry(at(-1_000), now)).isEqualTo(TrashExpiry(0, TrashExpiryLevel.EXPIRED))
        assertThat(trashExpiry(at(0), now)).isEqualTo(TrashExpiry(0, TrashExpiryLevel.EXPIRED))
    }

    @Test
    fun `days are rounded up and the last day is urgent`() {
        assertThat(trashExpiry(at(3 * 3_600_000L), now)).isEqualTo(TrashExpiry(1, TrashExpiryLevel.SOON))
        assertThat(trashExpiry(at(DAY), now)).isEqualTo(TrashExpiry(1, TrashExpiryLevel.SOON))
        assertThat(trashExpiry(at(DAY + 1), now)).isEqualTo(TrashExpiry(2, TrashExpiryLevel.NORMAL))
        assertThat(trashExpiry(at(5 * DAY), now)).isEqualTo(TrashExpiry(5, TrashExpiryLevel.NORMAL))
        assertThat(trashExpiry(at(30 * DAY), now)).isEqualTo(TrashExpiry(30, TrashExpiryLevel.NORMAL))
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
