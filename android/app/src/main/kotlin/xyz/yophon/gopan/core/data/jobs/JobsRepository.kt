package xyz.yophon.gopan.core.data.jobs

import androidx.compose.runtime.Immutable
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import xyz.yophon.gopan.core.network.requireData
import xyz.yophon.gopan.graphql.CancelJobMutation
import xyz.yophon.gopan.graphql.ClearFinishedJobsMutation
import xyz.yophon.gopan.graphql.FetchEnabledQuery
import xyz.yophon.gopan.graphql.FetchUrlsMutation
import xyz.yophon.gopan.graphql.MyJobsQuery
import xyz.yophon.gopan.graphql.RetryJobMutation
import xyz.yophon.gopan.graphql.fragment.BackgroundJobFields
import javax.inject.Inject
import javax.inject.Singleton
import xyz.yophon.gopan.graphql.type.JobKind as GqlJobKind
import xyz.yophon.gopan.graphql.type.JobStatus as GqlJobStatus
import xyz.yophon.gopan.graphql.type.NodeKind as GqlNodeKind

enum class JobKind { FETCH_URL, EXTRACT_ARCHIVE }

enum class JobStatus { QUEUED, RUNNING, DONE, FAILED, CANCELED }

/** 任务的产物:下好的文件 / 解出来的文件夹 */
@Immutable
data class JobResult(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    /** null = 在根目录 */
    val parentId: String?,
)

/**
 * 一个后台任务(服务端 user_jobs 一行):离线下载或解压。服务端执行,App 关掉也照常跑。
 * 字节 / 文件个数的总量未知时为 null;[errorCode] 在成功时也可能有(GOT_HTML:拿到的是网页,不是文件)。
 */
@Immutable
data class BackgroundJob(
    val id: String,
    val kind: JobKind,
    val status: JobStatus,
    /** 下载的文件名(拿到响应头之前是链接的主机名)/ 压缩包名 */
    val title: String,
    /** 离线下载的链接;解压为 null */
    val url: String? = null,
    /** 目标文件夹;null = 根目录 */
    val parentId: String? = null,
    val result: JobResult? = null,
    val bytesDone: Long = 0,
    val bytesTotal: Long? = null,
    val filesDone: Int = 0,
    val filesTotal: Int? = null,
    val filesSkipped: Int = 0,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val createdAt: String = "",
    val finishedAt: String? = null,
)

/** 后台任务接口(server/internal/service/jobs.go)。失败抛 GopanException。 */
interface JobsRepository {
    /** 我的任务(新的在前,服务端保留 7 天;只取第一页,界面只放值得看的) */
    suspend fun myJobs(): List<BackgroundJob>

    /** 本站是否开了离线下载(GOPAN_FETCH)。不常变:成功拿到一次就在本次登录期间缓存 */
    suspend fun fetchEnabled(): Boolean

    /** 每条链接一个任务,存到 [parentId](null = 根目录);一次最多 [MAX_FETCH_URLS] 条 */
    suspend fun fetchUrls(urls: List<String>, parentId: String?): List<BackgroundJob>

    suspend fun cancel(id: String): BackgroundJob

    suspend fun retry(id: String): BackgroundJob

    /** 清掉已结束的任务记录(不动下好的文件),返回清掉的条数 */
    suspend fun clearFinished(): Int
}

@Singleton
class ApolloJobsRepository @Inject constructor(private val apollo: ApolloClient) : JobsRepository {

    @Volatile private var fetchEnabledCache: Boolean? = null

    override suspend fun myJobs(): List<BackgroundJob> =
        apollo.query(MyJobsQuery(active = Optional.present(false), cursor = Optional.absent()))
            .execute().requireData().myJobs.items.map { it.backgroundJobFields.toDomain() }

    override suspend fun fetchEnabled(): Boolean {
        fetchEnabledCache?.let { return it }
        val enabled = apollo.query(FetchEnabledQuery()).execute().requireData().fetchEnabled
        fetchEnabledCache = enabled
        return enabled
    }

    override suspend fun fetchUrls(urls: List<String>, parentId: String?): List<BackgroundJob> =
        apollo.mutation(FetchUrlsMutation(urls = urls, parentId = Optional.present(parentId)))
            .execute().requireData().fetchUrls.map { it.backgroundJobFields.toDomain() }

    override suspend fun cancel(id: String): BackgroundJob =
        apollo.mutation(CancelJobMutation(id)).execute().requireData().cancelJob.backgroundJobFields.toDomain()

    override suspend fun retry(id: String): BackgroundJob =
        apollo.mutation(RetryJobMutation(id)).execute().requireData().retryJob.backgroundJobFields.toDomain()

    override suspend fun clearFinished(): Int =
        apollo.mutation(ClearFinishedJobsMutation()).execute().requireData().clearFinishedJobs

    /** 登出:换账号(或换站点)后重新问一次开关 */
    fun clearMemory() {
        fetchEnabledCache = null
    }
}

internal fun BackgroundJobFields.toDomain() = BackgroundJob(
    id = id,
    kind = if (kind == GqlJobKind.EXTRACT_ARCHIVE) JobKind.EXTRACT_ARCHIVE else JobKind.FETCH_URL,
    status = when (status) {
        GqlJobStatus.QUEUED -> JobStatus.QUEUED
        GqlJobStatus.RUNNING -> JobStatus.RUNNING
        GqlJobStatus.DONE -> JobStatus.DONE
        GqlJobStatus.FAILED -> JobStatus.FAILED
        GqlJobStatus.CANCELED -> JobStatus.CANCELED
        // 新版服务端加了状态而 App 没跟上:当作失败展示,不会一直显示进行中、触发轮询
        else -> JobStatus.FAILED
    },
    title = title,
    url = url,
    parentId = parentId,
    result = result?.let { JobResult(id = it.id, name = it.name, isFolder = it.kind == GqlNodeKind.FOLDER, parentId = it.parentId) },
    bytesDone = bytesDone,
    bytesTotal = bytesTotal,
    filesDone = filesDone,
    filesTotal = filesTotal,
    filesSkipped = filesSkipped,
    errorCode = errorCode,
    errorMessage = errorMessage,
    createdAt = createdAt,
    finishedAt = finishedAt,
)
