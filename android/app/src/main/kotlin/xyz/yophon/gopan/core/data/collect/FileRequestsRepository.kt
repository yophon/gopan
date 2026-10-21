package xyz.yophon.gopan.core.data.collect

import androidx.compose.runtime.Immutable
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import xyz.yophon.gopan.core.network.requireData
import xyz.yophon.gopan.core.ui.format.parseTime
import xyz.yophon.gopan.graphql.CreateFileRequestMutation
import xyz.yophon.gopan.graphql.FileRequestUploadsQuery
import xyz.yophon.gopan.graphql.MarkFileRequestSeenMutation
import xyz.yophon.gopan.graphql.MyFileRequestsQuery
import xyz.yophon.gopan.graphql.RevokeFileRequestMutation
import xyz.yophon.gopan.graphql.UpdateFileRequestMutation
import xyz.yophon.gopan.graphql.fragment.FileRequestFields
import xyz.yophon.gopan.graphql.type.FileRequestInput
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一条收集链接(doc/20):访客打开 {base}/c/{token} 往 [folderId] 里传文件,看不到里面已有的内容。
 * 收件统计按收件记录算,文件删了也算;[newCount] 是属主上次看收件记录之后新收到的个数。
 */
@Immutable
data class FileRequest(
    val id: String,
    val token: String,
    val folderId: String,
    val folderName: String,
    val title: String,
    val note: String = "",
    val hasPassword: Boolean = false,
    val requireName: Boolean = false,
    val groupByUploader: Boolean = false,
    /** 上限,null = 不限 */
    val maxFileSize: Long? = null,
    val maxTotalBytes: Long? = null,
    val maxFiles: Int? = null,
    /** null = 不限时间 */
    val expiresAt: String? = null,
    val createdAt: String = "",
    val fileCount: Int = 0,
    val totalBytes: Long = 0,
    val uploaderCount: Int = 0,
    val newCount: Int = 0,
    val lastUploadAt: String? = null,
) {
    fun isExpired(now: Instant = Instant.now()): Boolean = parseTime(expiresAt)?.isBefore(now) == true
}

/** 新建 / 修改提交的整张表单:上限为 null = 不限,[expiresAt] 为 null = 不限时间 */
@Immutable
data class FileRequestForm(
    val title: String,
    val note: String = "",
    val requireName: Boolean = false,
    val groupByUploader: Boolean = false,
    val maxFileSize: Long? = null,
    val maxTotalBytes: Long? = null,
    val maxFiles: Int? = null,
    val expiresAt: Instant? = null,
)

/** 一条收件记录。[nodeId] 为 null:文件进了回收站或已彻删(记录仍保留) */
@Immutable
data class FileRequestUpload(
    val id: String,
    val fileName: String,
    val size: Long,
    /** 访客自填,可能为空 */
    val uploaderName: String,
    val ip: String?,
    val userAgent: String?,
    val createdAt: String,
    val nodeId: String? = null,
    /** 文件当前所在目录;null 且 [nodeId] 非空 = 在根目录 */
    val parentId: String? = null,
) {
    val fileGone: Boolean get() = nodeId == null
}

@Immutable
data class FileRequestUploadPage(val items: List<FileRequestUpload>, val nextCursor: String?)

/** 收集文件的属主侧(server/internal/service/collect.go)。失败抛 GopanException。 */
interface FileRequestsRepository {
    suspend fun myRequests(): List<FileRequest>

    /** [password] 为空表示不设密码;建好后不能改 */
    suspend fun create(folderId: String, form: FileRequestForm, password: String?): FileRequest

    /** 整表单替换(标题、说明、截止、上限、留名) */
    suspend fun update(id: String, form: FileRequestForm): FileRequest

    suspend fun revoke(id: String)

    /** 标为已读:列表上的「新 N」清零 */
    suspend fun markSeen(id: String)

    /** 收件记录,新的在前;[cursor] 为上一页的 nextCursor */
    suspend fun uploads(id: String, cursor: String?): FileRequestUploadPage
}

@Singleton
class ApolloFileRequestsRepository @Inject constructor(private val apollo: ApolloClient) : FileRequestsRepository {

    override suspend fun myRequests(): List<FileRequest> =
        apollo.query(MyFileRequestsQuery()).execute().requireData().myFileRequests.map { it.fileRequestFields.toDomain() }

    override suspend fun create(folderId: String, form: FileRequestForm, password: String?): FileRequest =
        apollo.mutation(
            CreateFileRequestMutation(
                folderId = folderId,
                input = form.toInput(),
                password = Optional.presentIfNotNull(password?.takeIf { it.isNotEmpty() }),
            ),
        ).execute().requireData().createFileRequest.fileRequestFields.toDomain()

    override suspend fun update(id: String, form: FileRequestForm): FileRequest =
        apollo.mutation(UpdateFileRequestMutation(id = id, input = form.toInput()))
            .execute().requireData().updateFileRequest.fileRequestFields.toDomain()

    override suspend fun revoke(id: String) {
        apollo.mutation(RevokeFileRequestMutation(id)).execute().requireData()
    }

    override suspend fun markSeen(id: String) {
        apollo.mutation(MarkFileRequestSeenMutation(id)).execute().requireData()
    }

    override suspend fun uploads(id: String, cursor: String?): FileRequestUploadPage {
        val page = apollo.query(FileRequestUploadsQuery(id = id, cursor = Optional.presentIfNotNull(cursor)))
            .execute().requireData().fileRequestUploads
        return FileRequestUploadPage(
            items = page.items.map {
                FileRequestUpload(
                    id = it.id,
                    fileName = it.fileName,
                    size = it.size,
                    uploaderName = it.uploaderName,
                    ip = it.ip,
                    userAgent = it.userAgent,
                    createdAt = it.createdAt,
                    nodeId = it.node?.id,
                    parentId = it.node?.parentId,
                )
            },
            nextCursor = page.nextCursor,
        )
    }

    private fun FileRequestFields.toDomain() = FileRequest(
        id = id,
        token = token,
        folderId = folder.id,
        folderName = folder.name,
        title = title,
        note = note,
        hasPassword = hasPassword,
        requireName = requireName,
        groupByUploader = groupByUploader,
        maxFileSize = maxFileSize,
        maxTotalBytes = maxTotalBytes,
        maxFiles = maxFiles,
        expiresAt = expiresAt,
        createdAt = createdAt,
        fileCount = fileCount,
        totalBytes = totalBytes,
        uploaderCount = uploaderCount,
        newCount = newCount,
        lastUploadAt = lastUploadAt,
    )

    /** 每个字段都显式给出(null 也发):服务端按整表单替换,没给 = 不限 */
    private fun FileRequestForm.toInput() = FileRequestInput(
        title = title,
        note = Optional.present(note),
        requireName = Optional.present(requireName || groupByUploader),
        groupByUploader = Optional.present(groupByUploader),
        maxFileSize = Optional.present(maxFileSize),
        maxTotalBytes = Optional.present(maxTotalBytes),
        maxFiles = Optional.present(maxFiles),
        expiresAt = Optional.present(expiresAt?.toString()),
    )
}
