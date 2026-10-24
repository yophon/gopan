package xyz.yophon.gopan.core.data.text

import androidx.compose.runtime.Immutable
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import xyz.yophon.gopan.core.data.NodesRepository
import xyz.yophon.gopan.core.network.requireData
import xyz.yophon.gopan.graphql.SaveTextFileMutation
import xyz.yophon.gopan.graphql.TextFileQuery
import javax.inject.Inject
import javax.inject.Singleton

/** 一份能编辑的文本:内容 + 打开时的 sha256(保存时带回去,在别处改过了服务端报 EDIT_CONFLICT) */
@Immutable
data class TextFile(val content: String, val sha256: String, val size: Long)

/** 在线编辑文本(doc/25)。失败抛 GopanException。 */
interface TextFilesRepository {
    suspend fun read(nodeId: String): TextFile

    /** 返回新内容的 sha256;[baseSha256] 为 null = 不检查冲突(「仍然保存」) */
    suspend fun save(nodeId: String, content: String, baseSha256: String?): String
}

@Singleton
class ApolloTextFilesRepository @Inject constructor(
    private val apollo: ApolloClient,
    private val nodes: NodesRepository,
) : TextFilesRepository {

    override suspend fun read(nodeId: String): TextFile {
        val t = apollo.query(TextFileQuery(nodeId)).execute().requireData().textFile
        return TextFile(t.content, t.sha256, t.size)
    }

    override suspend fun save(nodeId: String, content: String, baseSha256: String?): String {
        val node = apollo.mutation(SaveTextFileMutation(nodeId, content, Optional.present(baseSha256))).execute().requireData().saveTextFile
        // 文件的大小与修改时间变了:列表页回到前台时刷新
        nodes.markChanged()
        return node.sha256.orEmpty()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TextFilesModule {
    @Binds
    abstract fun bindTextFilesRepository(impl: ApolloTextFilesRepository): TextFilesRepository
}
