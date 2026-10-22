package xyz.yophon.gopan.core.data.jobs

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.jobs.FakeJobsRepository.Companion.job

/** 剪藏任务(CLIP_URL)在「后台任务」栏与分享页上的样子 */
class ClipJobsTest {

    private val clip = job("c", JobStatus.RUNNING, kind = JobKind.CLIP_URL, title = "example.com")

    @Test
    fun `job kind labels`() {
        assertThat(jobKindLabel(JobKind.FETCH_URL)).isEqualTo(R.string.jobs_kind_fetch)
        assertThat(jobKindLabel(JobKind.EXTRACT_ARCHIVE)).isEqualTo(R.string.jobs_kind_extract)
        assertThat(jobKindLabel(JobKind.CLIP_URL)).isEqualTo(R.string.jobs_kind_clip)
    }

    @Test
    fun `clip progress counts images only`() {
        // 网页一次读完,字节数不算进度
        assertThat(jobPercent(clip.copy(bytesDone = 500, bytesTotal = 500))).isNull()
        assertThat(jobPercent(clip.copy(filesDone = 2, filesSkipped = 1, filesTotal = 6))).isEqualTo(50)
        assertThat(jobPercent(clip.copy(status = JobStatus.DONE))).isEqualTo(100)
    }

    @Test
    fun `clip status text`() {
        assertThat(jobStatusText(clip)).isEqualTo("正在提取正文")
        assertThat(jobStatusText(clip.copy(filesDone = 3, filesSkipped = 1, filesTotal = 10))).isEqualTo("正在存图片 4 / 10")
        assertThat(jobStatusText(clip.copy(status = JobStatus.DONE))).isEqualTo("已剪藏")
        assertThat(jobStatusText(clip.copy(status = JobStatus.DONE, filesDone = 5))).isEqualTo("已剪藏 · 5 张图片")
        assertThat(jobStatusText(clip.copy(status = JobStatus.DONE, errorCode = "CLIP_FALLBACK", errorMessage = "没认出正文,存的是整页文字")))
            .isEqualTo("已剪藏 · 没认出正文,存的是整页文字")
        assertThat(jobStatusText(clip.copy(status = JobStatus.FAILED, errorMessage = "网站返回 HTTP 403"))).isEqualTo("网站返回 HTTP 403")
    }
}
