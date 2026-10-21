package xyz.yophon.gopan.core.data.archive

import android.content.Context
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import xyz.yophon.gopan.core.AppConfig
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.auth.SignOutHook
import xyz.yophon.gopan.core.data.preview.TextContent
import xyz.yophon.gopan.core.network.TokenRefresher
import xyz.yophon.gopan.core.network.requireData
import xyz.yophon.gopan.core.transfer.upload.PartUploader
import xyz.yophon.gopan.di.ApplicationScope
import xyz.yophon.gopan.graphql.ArchiveEntriesQuery
import xyz.yophon.gopan.graphql.ArchiveInfoQuery
import xyz.yophon.gopan.graphql.ExtractArchiveMutation
import java.io.File
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import xyz.yophon.gopan.graphql.type.ArchiveStatus as GqlArchiveStatus

/** 压缩包:概要、按目录列条目、提交解压、把包里的单个文件取到本地 */
interface ArchiveRepository {
    suspend fun info(nodeId: String): ArchiveInfo

    /** [path] 为空即根目录;目录在前、按名字,每页 200 条 */
    suspend fun entries(nodeId: String, path: String, cursor: String?): ArchiveEntryPage

    /** 提交解压(后台任务),返回任务 id */
    suspend fun extract(request: ExtractRequest): String

    /**
     * 包里的一个文件取到缓存:可预览的按预览取,其余按下载取(download=1);同一次浏览里同一条目只取一次。
     * [onProgress] 已收字节 / 总字节(可能未知)
     */
    suspend fun fetchEntry(nodeId: String, entry: ArchiveEntry, onProgress: (Long, Long?) -> Unit = { _, _ -> }): File

    /** 取到本地的文本条目读一段(见 [readTextSegment]) */
    suspend fun readText(file: File, offset: Long = 0, charset: String? = null): TextContent

    /** 离开压缩包浏览页:删掉这个包取到缓存里的文件(后台进行) */
    fun release(nodeId: String)
}

@Singleton
class DefaultArchiveRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val apollo: ApolloClient,
    config: AppConfig,
    @Named(PartUploader.TRANSFER_CLIENT) client: OkHttpClient,
    session: SessionStore,
    refresher: dagger.Lazy<TokenRefresher>,
    @ApplicationScope private val appScope: CoroutineScope,
) : ArchiveRepository {

    private val fetcher = ArchiveEntryFetcher(
        client = client,
        baseUrl = config.baseUrl,
        dir = File(context.cacheDir, CACHE_DIR),
        accessToken = { session.accessToken },
        refresher = { refresher.get() },
    )

    init {
        // 仓库在进程里第一次被用到时还没有打开着的浏览页:上个进程留下的条目全部清掉
        appScope.launch(Dispatchers.IO) { fetcher.clearAll() }
    }

    override suspend fun info(nodeId: String): ArchiveInfo {
        val a = apollo.query(ArchiveInfoQuery(nodeId)).execute().requireData().archiveInfo
        return ArchiveInfo(
            status = a.status.toDomain(),
            format = a.format,
            entryCount = a.entryCount,
            fileCount = a.fileCount,
            totalSize = a.totalSize,
            solid = a.solid,
            encrypted = a.encrypted,
            error = a.error,
        )
    }

    override suspend fun entries(nodeId: String, path: String, cursor: String?): ArchiveEntryPage {
        val page = apollo.query(
            ArchiveEntriesQuery(nodeId = nodeId, path = Optional.present(path), cursor = Optional.presentIfNotNull(cursor)),
        ).execute().requireData().archiveEntries
        return ArchiveEntryPage(
            items = page.items.map {
                ArchiveEntry(
                    idx = it.idx,
                    path = it.path,
                    name = it.name,
                    isDir = it.isDir,
                    size = it.size,
                    modifiedAt = it.modifiedAt,
                    flag = it.flag?.takeIf { f -> f.isNotEmpty() },
                    previewable = it.previewable,
                    downloadable = it.downloadable,
                )
            },
            nextCursor = page.nextCursor,
            total = page.total,
        )
    }

    override suspend fun extract(request: ExtractRequest): String =
        apollo.mutation(
            ExtractArchiveMutation(
                nodeId = request.nodeId,
                base = Optional.presentIfNotNull(request.base?.takeIf { it.isNotEmpty() }),
                paths = Optional.presentIfNotNull(request.paths.takeIf { it.isNotEmpty() }),
                parentId = Optional.present(request.parentId),
            ),
        ).execute().requireData().extractArchive.id

    override suspend fun fetchEntry(nodeId: String, entry: ArchiveEntry, onProgress: (Long, Long?) -> Unit): File =
        fetcher.fetch(nodeId, entry, onProgress)

    override suspend fun readText(file: File, offset: Long, charset: String?): TextContent =
        withContext(Dispatchers.IO) { readTextSegment(file, offset, charset) }

    override fun release(nodeId: String) {
        appScope.launch(Dispatchers.IO) { fetcher.release(nodeId) }
    }

    /** 登出:上一个账号取下来的文件全部删掉 */
    suspend fun clearAll() = withContext(Dispatchers.IO) { fetcher.clearAll() }

    companion object {
        /** 与 res/xml/file_paths.xml 的 cache-path 一致(交给其他应用打开 / 分享) */
        const val CACHE_DIR = "archive-entry"
    }
}

private fun GqlArchiveStatus.toDomain(): ArchiveStatus = when (this) {
    GqlArchiveStatus.PENDING -> ArchiveStatus.PENDING
    GqlArchiveStatus.DONE -> ArchiveStatus.DONE
    GqlArchiveStatus.TRUNCATED -> ArchiveStatus.TRUNCATED
    GqlArchiveStatus.UNSUPPORTED -> ArchiveStatus.UNSUPPORTED
    else -> ArchiveStatus.FAILED
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ArchiveModule {
    @Binds
    abstract fun bindArchiveRepository(impl: DefaultArchiveRepository): ArchiveRepository

    companion object {
        @Provides
        @IntoSet
        fun provideArchiveSignOutHook(repo: DefaultArchiveRepository): SignOutHook = SignOutHook { repo.clearAll() }
    }
}
