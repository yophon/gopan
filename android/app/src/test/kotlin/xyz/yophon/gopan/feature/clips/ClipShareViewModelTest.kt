package xyz.yophon.gopan.feature.clips

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.auth.UserSummary
import xyz.yophon.gopan.core.data.clips.ClipSubmission
import xyz.yophon.gopan.core.data.clips.FakeClipsRepository
import xyz.yophon.gopan.core.data.jobs.FakeJobsRepository.Companion.job
import xyz.yophon.gopan.core.data.jobs.JobKind
import xyz.yophon.gopan.core.data.jobs.JobResult
import xyz.yophon.gopan.core.data.jobs.JobStatus
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.feature.drive.MainDispatcherRule

/** 系统分享「剪藏到 gopan」:取网址 → 提交 → 已剪过 / 轮询到完成或失败 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClipShareViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val repo = FakeClipsRepository()
    private val session = SessionStore().apply { setSignedIn("t", UserSummary("u1", "me", 0, 0, false)) }

    private fun vm() = ClipShareViewModel(repo, session)

    private fun clipJob(status: JobStatus, result: JobResult? = null, errorMessage: String? = null) =
        job("j1", status, kind = JobKind.CLIP_URL, title = "好文章", result = result, errorMessage = errorMessage)

    @Test
    fun `text without a link says so and submits nothing`() = runTest {
        val vm = vm()
        vm.start("标题", "只是一段话")
        advanceUntilIdle()
        assertThat(vm.state.value.step).isEqualTo(ClipShareStep.NoUrl)
        assertThat(repo.calls).isEmpty()
    }

    @Test
    fun `not signed in asks to log in`() = runTest {
        session.setSignedOut()
        val vm = vm()
        vm.start(null, "https://example.com/a")
        advanceUntilIdle()
        assertThat(vm.state.value.step).isEqualTo(ClipShareStep.NeedLogin)
        assertThat(vm.state.value.url).isEqualTo("https://example.com/a")
        assertThat(repo.calls).isEmpty()
    }

    @Test
    fun `already clipped offers the existing one and re-clips with force`() = runTest {
        val existing = FakeClipsRepository.clip("c1")
        repo.clipResults += Result.success(ClipSubmission.Existing(existing))
        repo.clipResults += Result.success(ClipSubmission.Queued(clipJob(JobStatus.QUEUED)))
        repo.jobStates += Result.success(clipJob(JobStatus.DONE, JobResult("md1", "好文章.md", false, "dir")))
        val vm = vm()
        vm.start("好文章", "好文章 https://example.com/a。")
        advanceUntilIdle()
        assertThat(vm.state.value.step).isEqualTo(ClipShareStep.Existing(existing))
        assertThat(repo.calls).containsExactly("clipUrl(https://example.com/a,force=false)")

        vm.reclip()
        advanceUntilIdle()
        assertThat(repo.calls).contains("clipUrl(https://example.com/a,force=true)")
        val done = vm.state.value.step as ClipShareStep.Done
        assertThat(done.nodeId).isEqualTo("md1")
    }

    @Test
    fun `polls the job until it is done`() = runTest {
        repo.clipResults += Result.success(ClipSubmission.Queued(clipJob(JobStatus.QUEUED)))
        repo.jobStates += Result.success(clipJob(JobStatus.RUNNING))
        repo.jobStates += Result.failure(GopanException(GopanErrors.NETWORK, "网络连接失败"))
        repo.jobStates += Result.success(clipJob(JobStatus.DONE, JobResult("md1", "好文章.md", false, "dir")))
        val vm = vm()
        vm.start(null, "https://example.com/a")
        runCurrent()
        assertThat(vm.state.value.step).isInstanceOf(ClipShareStep.Running::class.java)

        advanceTimeBy(ClipShareViewModel.POLL_MS + 1)
        assertThat((vm.state.value.step as ClipShareStep.Running).job.status).isEqualTo(JobStatus.RUNNING)
        // 查一次失败不打断,接着查
        advanceTimeBy(ClipShareViewModel.POLL_MS)
        assertThat(vm.state.value.step).isInstanceOf(ClipShareStep.Running::class.java)
        advanceUntilIdle()
        assertThat(vm.state.value.step).isEqualTo(ClipShareStep.Done(clipJob(JobStatus.DONE, JobResult("md1", "好文章.md", false, "dir")), "md1"))
        assertThat(repo.calls.count { it.startsWith("job(") }).isEqualTo(3)
    }

    @Test
    fun `a failed job shows the reason and can be retried`() = runTest {
        repo.clipResults += Result.success(ClipSubmission.Queued(clipJob(JobStatus.QUEUED)))
        repo.jobStates += Result.success(clipJob(JobStatus.FAILED, errorMessage = "网站返回 HTTP 403"))
        // 与 requireData 一样:中文的服务端消息同时放在 serverMessage 里
        val disabled = "本站没有开启离线下载,剪藏也用不了"
        repo.clipResults += Result.failure(GopanException("FETCH_DISABLED", disabled, disabled))
        val vm = vm()
        vm.start(null, "https://example.com/a")
        advanceUntilIdle()
        assertThat(vm.state.value.step).isEqualTo(ClipShareStep.Failed("网站返回 HTTP 403"))

        vm.retry()
        advanceUntilIdle()
        assertThat(vm.state.value.step).isEqualTo(ClipShareStep.Failed("本站没有开启离线下载,剪藏也用不了"))
        assertThat(repo.calls.filter { it.startsWith("clipUrl") }).hasSize(2)
    }

    @Test
    fun `starting twice submits once`() = runTest {
        repo.clipResults += Result.success(ClipSubmission.Existing(FakeClipsRepository.clip("c1")))
        val vm = vm()
        vm.start(null, "https://example.com/a")
        vm.start(null, "https://example.com/b")
        advanceUntilIdle()
        assertThat(repo.calls).containsExactly("clipUrl(https://example.com/a,force=false)")
    }
}
