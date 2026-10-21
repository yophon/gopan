package xyz.yophon.gopan.core.data.media

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.toNode
import xyz.yophon.gopan.core.network.requireData
import xyz.yophon.gopan.graphql.ClearProgressMutation
import xyz.yophon.gopan.graphql.PlaylistQuery
import xyz.yophon.gopan.graphql.RecentPlaysQuery
import xyz.yophon.gopan.graphql.SaveProgressMutation
import xyz.yophon.gopan.graphql.fragment.PlayProgressFields
import javax.inject.Inject
import javax.inject.Singleton

/** 影音(doc/22 二):连播队列、最近播放、播放进度 */
interface MediaRepository {
    /** 同文件夹里同类(视频或音频)的文件,自然顺序(第2集在第10集前),最多 1000 个,含 [nodeId] 本身 */
    suspend fun playlist(nodeId: String): List<Node>

    /** 没看完的,最近的在前 */
    suspend fun recentPlays(limit: Int = RECENT_LIMIT): List<RecentPlay>

    /** 服务端不记(5 分钟以下的音视频、3 页以下的 PDF)时返回 null */
    suspend fun saveProgress(nodeId: String, position: Double, duration: Double): PlayProgress?

    /** 从「最近播放」里去掉 */
    suspend fun clearProgress(nodeId: String)

    companion object {
        const val RECENT_LIMIT = 20
    }
}

@Singleton
class ApolloMediaRepository @Inject constructor(private val apollo: ApolloClient) : MediaRepository {

    override suspend fun playlist(nodeId: String): List<Node> =
        apollo.query(PlaylistQuery(nodeId)).execute().requireData().playlist.map { it.nodeItem.toNode() }

    override suspend fun recentPlays(limit: Int): List<RecentPlay> =
        apollo.query(RecentPlaysQuery(Optional.present(limit))).execute().requireData().recentPlays.map {
            RecentPlay(it.node.nodeItem.toNode(), it.progress.playProgressFields.toDomain())
        }

    override suspend fun saveProgress(nodeId: String, position: Double, duration: Double): PlayProgress? =
        apollo.mutation(SaveProgressMutation(nodeId, position, duration)).execute().requireData()
            .saveProgress?.playProgressFields?.toDomain()

    override suspend fun clearProgress(nodeId: String) {
        apollo.mutation(ClearProgressMutation(nodeId)).execute().requireData()
    }
}

fun PlayProgressFields.toDomain(): PlayProgress =
    PlayProgress(position = position, duration = duration, finished = finished, updatedAt = updatedAt)
