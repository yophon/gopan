package xyz.yophon.gopan.core.data.clips

import androidx.compose.runtime.Immutable
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import xyz.yophon.gopan.core.data.jobs.BackgroundJob
import xyz.yophon.gopan.core.data.jobs.toDomain
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.requireData
import xyz.yophon.gopan.graphql.ClipJobQuery
import xyz.yophon.gopan.graphql.ClipQuery
import xyz.yophon.gopan.graphql.ClipUrlMutation
import xyz.yophon.gopan.graphql.ClipsQuery
import xyz.yophon.gopan.graphql.MarkClipReadMutation
import xyz.yophon.gopan.graphql.NodeClipQuery
import xyz.yophon.gopan.graphql.fragment.ClipFields
import javax.inject.Inject
import javax.inject.Singleton

/** 一篇剪藏(doc/23 五):网页正文存成的 .md;阅读用 /md/{nodeId} */
@Immutable
data class Clip(
    val id: String,
    /** 原网页 */
    val url: String,
    val title: String,
    val site: String = "",
    val byline: String = "",
    val excerpt: String = "",
    val publishedAt: String? = null,
    /** 那篇 .md;进了回收站的剪藏服务端不列,这里几乎总有值 */
    val nodeId: String? = null,
    val nodeName: String? = null,
    val parentId: String? = null,
    val words: Int = 0,
    val images: Int = 0,
    val readAt: String? = null,
    val createdAt: String = "",
) {
    val unread: Boolean get() = readAt == null
}

@Immutable
data class ClipPage(val items: List<Clip>, val nextCursor: String?)

/** 剪藏一个网址的结果:剪过的直接给出已有的那篇,否则是新建的剪藏任务 */
sealed interface ClipSubmission {
    data class Existing(val clip: Clip) : ClipSubmission

    data class Queued(val job: BackgroundJob) : ClipSubmission
}

/** 剪藏接口(server/internal/service/clips.go)。失败抛 GopanException(服务端消息是中文,直接给人看)。 */
interface ClipsRepository {
    /** 新的在前,进了回收站的不列 */
    suspend fun clips(unreadOnly: Boolean, cursor: String?): ClipPage

    suspend fun clip(id: String): Clip

    /** 某篇 .md 是不是剪藏;不是为 null */
    suspend fun nodeClip(nodeId: String): Clip?

    /** 同一个地址剪过、那篇还在时给出已有的([force] 重新剪) */
    suspend fun clipUrl(url: String, force: Boolean): ClipSubmission

    suspend fun markRead(id: String, read: Boolean): Clip

    /** 剪藏任务的进度(分享页轮询) */
    suspend fun job(id: String): BackgroundJob
}

@Singleton
class ApolloClipsRepository @Inject constructor(private val apollo: ApolloClient) : ClipsRepository {

    override suspend fun clips(unreadOnly: Boolean, cursor: String?): ClipPage {
        val page = apollo.query(ClipsQuery(unreadOnly = Optional.present(unreadOnly), cursor = Optional.present(cursor)))
            .execute().requireData().clips
        return ClipPage(page.items.map { it.clipFields.toDomain() }, page.nextCursor)
    }

    override suspend fun clip(id: String): Clip = apollo.query(ClipQuery(id)).execute().requireData().clip.clipFields.toDomain()

    override suspend fun nodeClip(nodeId: String): Clip? =
        apollo.query(NodeClipQuery(nodeId)).execute().requireData().nodeClip?.clipFields?.toDomain()

    override suspend fun clipUrl(url: String, force: Boolean): ClipSubmission {
        val r = apollo.mutation(ClipUrlMutation(url = url, force = Optional.present(force))).execute().requireData().clipUrl
        r.existing?.let { return ClipSubmission.Existing(it.clipFields.toDomain()) }
        val job = r.job ?: throw GopanException(GopanErrors.EMPTY_RESPONSE, GopanErrors.messageFor(GopanErrors.EMPTY_RESPONSE, null))
        return ClipSubmission.Queued(job.backgroundJobFields.toDomain())
    }

    override suspend fun markRead(id: String, read: Boolean): Clip =
        apollo.mutation(MarkClipReadMutation(id, Optional.present(read))).execute().requireData().markClipRead.clipFields.toDomain()

    override suspend fun job(id: String): BackgroundJob =
        apollo.query(ClipJobQuery(id)).execute().requireData().job.backgroundJobFields.toDomain()
}

internal fun ClipFields.toDomain() = Clip(
    id = id,
    url = url,
    title = title,
    site = site,
    byline = byline,
    excerpt = excerpt,
    publishedAt = publishedAt,
    nodeId = node?.id,
    nodeName = node?.name,
    parentId = node?.parentId,
    words = words,
    images = images,
    readAt = readAt,
    createdAt = createdAt,
)

/** 剪藏自己的 Hilt 模块(不动 DataModule) */
@Module
@InstallIn(SingletonComponent::class)
abstract class ClipsModule {
    @Binds
    abstract fun bindClipsRepository(impl: ApolloClipsRepository): ClipsRepository
}
