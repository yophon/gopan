package xyz.yophon.gopan.feature.transfers

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import xyz.yophon.gopan.core.data.NodesRepository
import xyz.yophon.gopan.core.data.jobs.FakeJobsRepository
import xyz.yophon.gopan.core.data.jobs.FakeJobsRepository.Companion.job
import xyz.yophon.gopan.core.data.jobs.JobKind
import xyz.yophon.gopan.core.data.jobs.JobResult
import xyz.yophon.gopan.core.data.jobs.JobStatus
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.feature.drive.FakeNodesRepository
import xyz.yophon.gopan.feature.drive.MainDispatcherRule
import java.time.Instant

/** 记下 markChanged 次数的节点仓库(其余交给 FakeNodesRepository) */
private class CountingNodes(delegate: NodesRepository = FakeNodesRepository()) : NodesRepository by delegate {
    var changed = 0

    override fun markChanged() {
        changed++
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class JobsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val repo = FakeJobsRepository()
    private val nodes = CountingNodes()
    private val tabRequest = TransfersTabRequest()

    private fun iso(minutesAgo: Long) = Instant.ofEpochMilli(BASE - minutesAgo * 60_000).toString()

    /**
     * 轮询跑在测试调度器上:测试体结束前停掉(页面不可见),否则 runTest 收尾时等不到空闲。
     * 时钟用虚拟时间,速度和"刚结束"按它算。
     */
    private fun jobsTest(block: suspend TestScope.(JobsViewModel, List<TransfersEffect>) -> Unit) = runTest {
        val vm = JobsViewModel(repo, nodes, tabRequest)
        vm.clock = { BASE + testScheduler.currentTime }
        val effects = mutableListOf<TransfersEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        try {
            block(vm, effects)
        } finally {
            vm.setVisible(false)
            runCurrent()
        }
    }

    @Test
    fun `polls every 2 seconds while visible and something is active, then stops`() = jobsTest { vm, _ ->
        repo.jobs = listOf(job("a", JobStatus.RUNNING))
        vm.setVisible(true)
        runCurrent()
        assertThat(repo.myJobsCalls).isEqualTo(1)
        assertThat(vm.state.value.activeCount).isEqualTo(1)

        advanceTimeBy(JobsViewModel.POLL_INTERVAL_MS + 10)
        runCurrent()
        assertThat(repo.myJobsCalls).isEqualTo(2)

        repo.jobs = listOf(job("a", JobStatus.DONE, finishedAt = iso(0)))
        advanceTimeBy(JobsViewModel.POLL_INTERVAL_MS + 10)
        runCurrent()
        assertThat(repo.myJobsCalls).isEqualTo(3)
        assertThat(vm.state.value.hasActive).isFalse()

        // 全部结束:不再轮询
        advanceTimeBy(30_000)
        runCurrent()
        assertThat(repo.myJobsCalls).isEqualTo(3)
    }

    @Test
    fun `does not poll when nothing is active`() = jobsTest { vm, _ ->
        repo.jobs = listOf(job("a", JobStatus.DONE, finishedAt = iso(1)))
        vm.setVisible(true)
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertThat(repo.myJobsCalls).isEqualTo(1)
        assertThat(vm.state.value.loaded).isTrue()
        assertThat(vm.state.value.rows.map { it.job.id }).containsExactly("a")
    }

    @Test
    fun `hidden page stops polling and showing it again refreshes at once`() = jobsTest { vm, _ ->
        repo.jobs = listOf(job("a", JobStatus.QUEUED))
        vm.setVisible(true)
        runCurrent()
        vm.setVisible(false)
        advanceTimeBy(30_000)
        runCurrent()
        assertThat(repo.myJobsCalls).isEqualTo(1)

        vm.setVisible(true)
        runCurrent()
        assertThat(repo.myJobsCalls).isEqualTo(2)
    }

    @Test
    fun `only jobs worth showing are listed, extraction included`() = jobsTest { vm, _ ->
        repo.jobs = listOf(
            job("running", JobStatus.RUNNING),
            job("extract", JobStatus.RUNNING, kind = JobKind.EXTRACT_ARCHIVE, url = null),
            job("done-5m", JobStatus.DONE, finishedAt = iso(5)),
            job("done-1h", JobStatus.DONE, finishedAt = iso(60)),
            job("failed-3h", JobStatus.FAILED, finishedAt = iso(180)),
            job("failed-2d", JobStatus.FAILED, finishedAt = iso(2 * 24 * 60)),
            job("canceled-30m", JobStatus.CANCELED, finishedAt = iso(30)),
        )
        vm.setVisible(true)
        runCurrent()
        val s = vm.state.value
        assertThat(s.rows.map { it.job.id }).containsExactly("running", "extract", "done-5m", "failed-3h").inOrder()
        assertThat(s.activeCount).isEqualTo(2)
        assertThat(s.hasFinished).isTrue()
        assertThat(s.hasFinishedOnServer).isTrue()
    }

    @Test
    fun `speed comes from consecutive polls and a finished job refreshes the drive`() = jobsTest { vm, _ ->
        repo.jobs = listOf(job("a", JobStatus.RUNNING, bytesDone = 0, bytesTotal = 10_000))
        vm.setVisible(true)
        runCurrent()
        assertThat(vm.state.value.rows.single().speedBps).isNull()

        repo.jobs = listOf(job("a", JobStatus.RUNNING, bytesDone = 4_000, bytesTotal = 10_000))
        advanceTimeBy(JobsViewModel.POLL_INTERVAL_MS)
        runCurrent()
        assertThat(vm.state.value.rows.single().speedBps).isEqualTo(2_000)
        assertThat(nodes.changed).isEqualTo(0)

        repo.jobs = listOf(
            job(
                "a",
                JobStatus.DONE,
                bytesDone = 10_000,
                bytesTotal = 10_000,
                finishedAt = iso(0),
                result = JobResult("f1", "a.zip", isFolder = false, parentId = "dir"),
            ),
        )
        advanceTimeBy(JobsViewModel.POLL_INTERVAL_MS)
        runCurrent()
        assertThat(vm.state.value.rows.single().speedBps).isNull()
        assertThat(nodes.changed).isEqualTo(1)
    }

    @Test
    fun `a job already done on the first load does not trigger a refresh`() = jobsTest { vm, _ ->
        repo.jobs = listOf(job("a", JobStatus.DONE, finishedAt = iso(1)))
        vm.setVisible(true)
        runCurrent()
        assertThat(nodes.changed).isEqualTo(0)
    }

    @Test
    fun `cancel and retry go to the server, refresh, and resume polling`() = jobsTest { vm, _ ->
        repo.jobs = listOf(job("a", JobStatus.RUNNING))
        vm.setVisible(true)
        runCurrent()

        vm.cancel(vm.state.value.rows.single().job)
        runCurrent()
        assertThat(repo.canceled).containsExactly("a")
        assertThat(vm.state.value.rows.single().job.status).isEqualTo(JobStatus.CANCELED)
        val afterCancel = repo.myJobsCalls
        advanceTimeBy(10_000)
        runCurrent()
        assertThat(repo.myJobsCalls).isEqualTo(afterCancel)

        vm.retry(vm.state.value.rows.single().job)
        runCurrent()
        assertThat(repo.retried).containsExactly("a")
        assertThat(vm.state.value.rows.single().job.status).isEqualTo(JobStatus.QUEUED)
        val afterRetry = repo.myJobsCalls
        advanceTimeBy(JobsViewModel.POLL_INTERVAL_MS + 10)
        runCurrent()
        assertThat(repo.myJobsCalls).isEqualTo(afterRetry + 1)
    }

    @Test
    fun `failed actions show the Chinese reason`() = jobsTest { vm, effects ->
        repo.jobs = listOf(job("a", JobStatus.RUNNING))
        vm.setVisible(true)
        runCurrent()
        repo.nextMutationError = GopanException("JOB_FINISHED", "请求失败(JOB_FINISHED)")
        vm.cancel(vm.state.value.rows.single().job)
        runCurrent()
        assertThat(effects).contains(TransfersEffect.Message("任务已经结束"))

        repo.nextMutationError = GopanException("JOB_NOT_RETRYABLE", "x", serverMessage = "只有失败或已取消的任务能重试")
        vm.retry(vm.state.value.rows.single().job)
        runCurrent()
        assertThat(effects.last()).isEqualTo(TransfersEffect.Message("只有失败或已取消的任务能重试"))
    }

    @Test
    fun `clear finished asks the server only when there are finished records`() = jobsTest { vm, _ ->
        repo.jobs = listOf(job("a", JobStatus.RUNNING))
        vm.setVisible(true)
        runCurrent()
        vm.clearFinished()
        runCurrent()
        assertThat(repo.clearCalls).isEqualTo(0)

        // 一小时前结束的不显示,但服务端还有这条记录:照样清
        repo.jobs = listOf(job("a", JobStatus.RUNNING), job("old", JobStatus.DONE, finishedAt = iso(60)), job("b", JobStatus.FAILED, finishedAt = iso(1)))
        advanceTimeBy(JobsViewModel.POLL_INTERVAL_MS + 10)
        runCurrent()
        assertThat(vm.state.value.hasFinished).isTrue()
        vm.clearFinished()
        runCurrent()
        assertThat(repo.clearCalls).isEqualTo(1)
        assertThat(vm.state.value.rows.map { it.job.id }).containsExactly("a")
        assertThat(vm.state.value.hasFinishedOnServer).isFalse()
    }

    @Test
    fun `locate opens the downloaded file's folder or the extracted folder`() = jobsTest { vm, effects ->
        val file = job("f", JobStatus.DONE, finishedAt = iso(1), result = JobResult("n1", "a.zip", isFolder = false, parentId = "dir"))
        val atRoot = job("r", JobStatus.DONE, finishedAt = iso(1), result = JobResult("n2", "b.zip", isFolder = false, parentId = null))
        val extracted = job("x", JobStatus.DONE, kind = JobKind.EXTRACT_ARCHIVE, finishedAt = iso(1), result = JobResult("x1", "包", isFolder = true, parentId = "dir"))
        val gone = job("g", JobStatus.DONE, finishedAt = iso(1), result = null)
        vm.locate(file)
        vm.locate(atRoot)
        vm.locate(extracted)
        vm.locate(gone)
        vm.locate(job("q", JobStatus.QUEUED))
        runCurrent()
        assertThat(effects).containsExactly(
            TransfersEffect.OpenFolder("dir"),
            TransfersEffect.OpenFolder(null),
            TransfersEffect.OpenFolder("x1"),
        ).inOrder()
    }

    @Test
    fun `first load failure shows an error and retry recovers`() = jobsTest { vm, _ ->
        repo.failMyJobs = GopanException(GopanErrors.NETWORK, "网络连接失败,请检查网络后重试")
        vm.setVisible(true)
        runCurrent()
        assertThat(vm.state.value.loaded).isTrue()
        assertThat(vm.state.value.error).isEqualTo("网络连接失败,请检查网络后重试")

        repo.jobs = listOf(job("a", JobStatus.DONE, finishedAt = iso(1)))
        vm.retryLoad()
        runCurrent()
        assertThat(vm.state.value.error).isNull()
        assertThat(vm.state.value.rows).hasSize(1)
    }

    @Test
    fun `a failed poll keeps the rows and keeps polling active jobs`() = jobsTest { vm, _ ->
        repo.jobs = listOf(job("a", JobStatus.RUNNING))
        vm.setVisible(true)
        runCurrent()
        repo.failMyJobs = GopanException(GopanErrors.NETWORK, "网络")
        advanceTimeBy(JobsViewModel.POLL_INTERVAL_MS + 10)
        runCurrent()
        assertThat(vm.state.value.rows).hasSize(1)
        assertThat(vm.state.value.error).isNull()
        advanceTimeBy(JobsViewModel.POLL_INTERVAL_MS + 10)
        runCurrent()
        assertThat(repo.myJobsCalls).isEqualTo(3)
    }

    @Test
    fun `the jobs tab request is consumed by the next transfers screen only`() = runTest {
        tabRequest.requestJobs()
        assertThat(JobsViewModel(repo, nodes, tabRequest).openJobsFirst).isTrue()
        assertThat(JobsViewModel(repo, nodes, tabRequest).openJobsFirst).isFalse()
    }

    private companion object {
        val BASE = Instant.parse(FakeJobsRepository.NOW_ISO).toEpochMilli()
    }
}
