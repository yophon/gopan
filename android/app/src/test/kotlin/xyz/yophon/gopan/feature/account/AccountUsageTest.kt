package xyz.yophon.gopan.feature.account

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AccountUsageTest {
    private val gb = 1024L * 1024 * 1024

    @Test
    fun `percent rounds and text shows used over quota`() {
        val u = UsageSummary(usedBytes = 123L * 1024 * 1024, quotaBytes = 10 * gb)
        assertThat(u.percent).isEqualTo(1)
        assertThat(u.fraction).isWithin(0.0001f).of(0.012f)
        assertThat(u.text).isEqualTo("123.0 MB / 10.0 GB")
        assertThat(u.percentText).isEqualTo("已用 1%")
        assertThat(u.level).isEqualTo(UsageLevel.NORMAL)
        assertThat(u.noQuota).isFalse()
    }

    @Test
    fun `half used is 50 percent`() {
        val u = UsageSummary(usedBytes = 5 * gb, quotaBytes = 10 * gb)
        assertThat(u.percent).isEqualTo(50)
        assertThat(u.fraction).isWithin(0.0001f).of(0.5f)
    }

    @Test
    fun `levels switch at 70 and 90 percent like the web sidebar`() {
        assertThat(UsageSummary(69, 100).level).isEqualTo(UsageLevel.NORMAL)
        assertThat(UsageSummary(70, 100).level).isEqualTo(UsageLevel.WARN)
        assertThat(UsageSummary(89, 100).level).isEqualTo(UsageLevel.WARN)
        assertThat(UsageSummary(90, 100).level).isEqualTo(UsageLevel.CRITICAL)
        assertThat(UsageSummary(100, 100).level).isEqualTo(UsageLevel.CRITICAL)
    }

    @Test
    fun `over quota is capped at 100 percent`() {
        val u = UsageSummary(usedBytes = 12 * gb, quotaBytes = 10 * gb)
        assertThat(u.fraction).isEqualTo(1f)
        assertThat(u.percent).isEqualTo(100)
        assertThat(u.text).isEqualTo("12.0 GB / 10.0 GB")
        assertThat(u.level).isEqualTo(UsageLevel.CRITICAL)
    }

    @Test
    fun `zero or negative quota means nothing can be uploaded`() {
        // 服务端按 已用 + 新文件 > 配额 拒绝上传:配额 0 = 已满,不是"不限"
        val u = UsageSummary(usedBytes = 2 * gb, quotaBytes = 0)
        assertThat(u.noQuota).isTrue()
        assertThat(u.fraction).isEqualTo(1f)
        assertThat(u.text).isEqualTo("2.0 GB / 0 B")
        assertThat(u.percentText).isEqualTo("无可用配额")
        assertThat(u.level).isEqualTo(UsageLevel.CRITICAL)
        assertThat(UsageSummary(1, -1).noQuota).isTrue()
        assertThat(UsageSummary(0, 0).text).isEqualTo("0 B / 0 B")
    }

    @Test
    fun `negative used bytes is treated as zero`() {
        val u = UsageSummary(usedBytes = -5, quotaBytes = 100)
        assertThat(u.fraction).isEqualTo(0f)
        assertThat(u.text).isEqualTo("0 B / 100 B")
    }

    @Test
    fun `empty account shows zero percent`() {
        val u = UsageSummary(usedBytes = 0, quotaBytes = gb)
        assertThat(u.percent).isEqualTo(0)
        assertThat(u.text).isEqualTo("0 B / 1.0 GB")
    }
}
