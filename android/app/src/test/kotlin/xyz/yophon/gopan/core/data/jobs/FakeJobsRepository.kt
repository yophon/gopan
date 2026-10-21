package xyz.yophon.gopan.core.data.jobs

import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException

/** ViewModel 测试用的假后台任务仓库:任务列表可随时改,调用都记下来 */
class FakeJobsRepository : JobsRepository {
    /** myJobs 返回的列表(新的在前) */
    var jobs: List<BackgroundJob> = emptyList()
    var enabled = true
    var myJobsCalls = 0
        private set
    var fetchEnabledCalls = 0
        private set

    /** 下一次 myJobs 抛出的错误;[failMyJobsAlways] 时一直抛 */
    var failMyJobs: GopanException? = null
    var failMyJobsAlways = false

    /** 下一次 fetchUrls / cancel / retry / clearFinished 抛出的错误(抛一次就清掉) */
    var nextMutationError: GopanException? = null

    val fetched = mutableListOf<Pair<List<String>, String?>>()
    val canceled = mutableListOf<String>()
    val retried = mutableListOf<String>()
    var clearCalls = 0
        private set

    override suspend fun myJobs(): List<BackgroundJob> {
        myJobsCalls++
        failMyJobs?.let {
            if (!failMyJobsAlways) failMyJobs = null
            throw it
        }
        return jobs
    }

    /** 下一次 fetchEnabled 抛出的错误(抛一次就清掉) */
    var failFetchEnabled: GopanException? = null

    override suspend fun fetchEnabled(): Boolean {
        fetchEnabledCalls++
        failFetchEnabled?.let {
            failFetchEnabled = null
            throw it
        }
        return enabled
    }

    override suspend fun fetchUrls(urls: List<String>, parentId: String?): List<BackgroundJob> {
        takeError()
        fetched += urls to parentId
        val created = urls.mapIndexed { i, u -> job("new-${fetched.size}-$i", JobStatus.QUEUED, url = u, parentId = parentId) }
        jobs = created + jobs
        return created
    }

    override suspend fun cancel(id: String): BackgroundJob {
        takeError()
        canceled += id
        return update(id) { it.copy(status = JobStatus.CANCELED, finishedAt = NOW_ISO) }
    }

    override suspend fun retry(id: String): BackgroundJob {
        takeError()
        retried += id
        return update(id) { it.copy(status = JobStatus.QUEUED, finishedAt = null, errorCode = null, errorMessage = null) }
    }

    override suspend fun clearFinished(): Int {
        takeError()
        clearCalls++
        val before = jobs.size
        jobs = jobs.filter { it.isActive }
        return before - jobs.size
    }

    private fun takeError() {
        nextMutationError?.let {
            nextMutationError = null
            throw it
        }
    }

    private fun update(id: String, change: (BackgroundJob) -> BackgroundJob): BackgroundJob {
        val target = jobs.firstOrNull { it.id == id } ?: throw GopanException(GopanErrors.NOT_FOUND, "对象不存在或已被删除")
        val next = change(target)
        jobs = jobs.map { if (it.id == id) next else it }
        return next
    }

    companion object {
        const val NOW_ISO = "2026-10-21T12:00:00Z"

        fun job(
            id: String,
            status: JobStatus,
            kind: JobKind = JobKind.FETCH_URL,
            title: String = "file-$id.zip",
            url: String? = "https://example.com/$id.zip",
            parentId: String? = "dir",
            bytesDone: Long = 0,
            bytesTotal: Long? = null,
            finishedAt: String? = null,
            result: JobResult? = null,
            errorCode: String? = null,
            errorMessage: String? = null,
        ) = BackgroundJob(
            id = id,
            kind = kind,
            status = status,
            title = title,
            url = url,
            parentId = parentId,
            result = result,
            bytesDone = bytesDone,
            bytesTotal = bytesTotal,
            errorCode = errorCode,
            errorMessage = errorMessage,
            createdAt = NOW_ISO,
            finishedAt = finishedAt,
        )
    }
}
