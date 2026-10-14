package xyz.yophon.gopan.core.transfer.download

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import xyz.yophon.gopan.core.AppConfig
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.RefreshOutcome
import xyz.yophon.gopan.core.network.TokenRefresher
import xyz.yophon.gopan.core.network.await
import xyz.yophon.gopan.core.network.requireData
import xyz.yophon.gopan.core.transfer.SystemTransferClock
import xyz.yophon.gopan.core.transfer.upload.PartUploader
import xyz.yophon.gopan.graphql.DownloadTargetQuery
import xyz.yophon.gopan.graphql.DownloadTreePageQuery
import xyz.yophon.gopan.graphql.type.NodeKind
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** 单个文件的下载目标:每次建连前现取,URL 不落库 */
data class FileTarget(
    val nodeId: String,
    val name: String,
    val size: Long,
    val sha256: String?,
    val mime: String?,
    /** 预签名 GET,原样使用 */
    val url: String,
    /** 取到的时刻(单调时间) */
    val fetchedAtElapsed: Long,
)

sealed interface Resolve {
    data class Ok(val target: FileTarget) : Resolve

    /** NOT_FOUND:已删除、在回收站或已彻删 */
    data object Gone : Resolve

    /** 文件夹,或没有可下载的内容 */
    data object NotAFile : Resolve
}

data class TreeItem(val id: String, val name: String, val isFolder: Boolean, val size: Long?)

data class TreePage(val items: List<TreeItem>, val nextCursor: String?)

sealed interface PackOpen {
    /** 200:没有长度,读到 EOF 后必须校验 zip(服务端中途出错时 HTTP 层看起来也是正常结束) */
    class Stream(val body: ResponseBody) : PackOpen

    /** 429:限流或全站打包名额满(两者分不清) */
    data class RetryLater(val afterSeconds: Long) : PackOpen

    /** 400 / 403 / 404 / 410 / 413:不重试 */
    data class Rejected(val status: Int, val message: String?) : PackOpen

    data class Transient(val error: GopanException) : PackOpen
}

/** 下载协议的服务端部分(doc/03:预签名 GET 直连对象存储;打包走 GET /pack 带 Bearer) */
interface DownloadApi {
    suspend fun resolve(nodeId: String): Resolve

    suspend fun listChildren(folderId: String, cursor: String?): TreePage

    /** [nodeIds] 已去重、去子孙,不超过 100 个 */
    suspend fun openPack(nodeIds: List<String>): PackOpen
}

@Singleton
class GopanDownloadApi @Inject constructor(
    private val apollo: ApolloClient,
    private val config: AppConfig,
    @Named(PartUploader.TRANSFER_CLIENT) transferClient: OkHttpClient,
    private val session: SessionStore,
    private val refresher: dagger.Lazy<TokenRefresher>,
) : DownloadApi {

    /** /pack 收集清单阶段还没有响应头,超大目录要多等一会儿 */
    private val packClient = transferClient.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()

    override suspend fun resolve(nodeId: String): Resolve {
        val node = try {
            apollo.query(DownloadTargetQuery(nodeId)).execute().requireData().node
        } catch (e: GopanException) {
            if (e.code == GopanErrors.NOT_FOUND) return Resolve.Gone
            throw e
        }
        val url = node.downloadUrl
        if (node.kind == NodeKind.FOLDER || url.isNullOrEmpty()) return Resolve.NotAFile
        return Resolve.Ok(
            FileTarget(
                nodeId = node.id,
                name = node.name,
                size = node.size ?: 0L,
                sha256 = node.sha256,
                mime = node.mime,
                url = url,
                fetchedAtElapsed = SystemTransferClock.elapsed(),
            ),
        )
    }

    override suspend fun listChildren(folderId: String, cursor: String?): TreePage {
        val page = apollo.query(DownloadTreePageQuery(parentId = folderId, cursor = Optional.presentIfNotNull(cursor)))
            .execute().requireData().children
        return TreePage(
            items = page.items.map { TreeItem(it.id, it.name, it.kind == NodeKind.FOLDER, it.size) },
            nextCursor = page.nextCursor,
        )
    }

    override suspend fun openPack(nodeIds: List<String>): PackOpen {
        val url = config.baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .encodedPath("/pack")
            .addQueryParameter("nodes", nodeIds.joinToString(","))
            .build()
        var token = session.accessToken
        repeat(2) { attempt ->
            val request = Request.Builder().url(url).get()
                .header("Accept-Encoding", "identity")
                .apply { token?.let { header("Authorization", "Bearer $it") } }
                .build()
            val response = try {
                packClient.newCall(request).await()
            } catch (e: IOException) {
                return PackOpen.Transient(GopanException(GopanErrors.NETWORK, GopanErrors.messageFor(GopanErrors.NETWORK, null), cause = e))
            }
            if (response.code == 401 && attempt == 0) {
                response.close()
                when (val r = refresher.get().refreshAfterUnauthenticated(token)) {
                    is RefreshOutcome.Refreshed -> token = r.accessToken
                    RefreshOutcome.SignedOut -> return PackOpen.Rejected(401, "登录已失效,请重新登录")
                    RefreshOutcome.Unavailable -> return PackOpen.Transient(GopanException(GopanErrors.NETWORK, "网络不可用"))
                }
                return@repeat
            }
            return when {
                response.isSuccessful -> PackOpen.Stream(requireNotNull(response.body))
                response.code == 429 -> {
                    val after = response.header("Retry-After")?.toLongOrNull() ?: 30L
                    response.close()
                    PackOpen.RetryLater(after.coerceIn(1, 600))
                }
                response.code >= 500 -> {
                    response.close()
                    PackOpen.Transient(GopanException(GopanErrors.HTTP, "打包失败(HTTP ${response.code})"))
                }
                else -> {
                    val text = withContext(Dispatchers.IO) {
                        try {
                            response.body?.string()?.trim()?.take(200)
                        } catch (e: IOException) {
                            null
                        }
                    }
                    response.close()
                    PackOpen.Rejected(response.code, text)
                }
            }
        }
        return PackOpen.Rejected(401, "登录已失效,请重新登录")
    }
}
