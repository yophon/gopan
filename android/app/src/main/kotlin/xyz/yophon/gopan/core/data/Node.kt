package xyz.yophon.gopan.core.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import xyz.yophon.gopan.graphql.fragment.NodeItem
import xyz.yophon.gopan.graphql.type.NodeKind as GqlNodeKind
import xyz.yophon.gopan.graphql.type.PreviewKind as GqlPreviewKind

/** 与 GraphQL 的 NodeKind 同名;领域层不直接依赖生成代码,未知值一律按 FILE 处理 */
enum class NodeKind { FILE, FOLDER }

/** 与 GraphQL 的 PreviewKind 同名;服务端新增类型时旧客户端按 NONE 处理(见 [previewKindOf]) */
enum class PreviewKind { NONE, IMAGE, PDF, NATIVE_PDF, VIDEO, AUDIO, TEXT, OFFICE, ARCHIVE }

/** GraphQL 枚举的原始值 → 领域枚举;不认识的(更新的服务端加的类型)按 NONE,预览页显示「暂不支持」 */
fun previewKindOf(rawValue: String): PreviewKind = PreviewKind.entries.firstOrNull { it.name == rawValue } ?: PreviewKind.NONE

@Immutable
@Serializable
data class NodePreview(
    val kind: PreviewKind = PreviewKind.NONE,
    /** 服务端给过缩略图:离线回退缓存时据此只从图片磁盘缓存里读(按 [Node.imageCacheKey]) */
    val hasThumbnail: Boolean = false,
    /**
     * 15 分钟有效的预签名缩略图地址,只在内存里即取即用。
     * @Transient:不随列表缓存写进 Room —— 预签名 URL 不得持久化,过期后也毫无用处。
     */
    @Transient val thumbUrl: String? = null,
)

/**
 * 文件/文件夹节点(列表、搜索、回收站共用的领域模型)。
 * 可序列化:Room 的 cached_listing 表把整页 items 存成 JSON;新增字段请给默认值,旧缓存才能照常解析。
 * 时间字段保持 RFC3339 字符串,格式化交给 core.ui.format。
 */
@Immutable
@Serializable
data class Node(
    val id: String,
    val parentId: String? = null,
    val name: String,
    val kind: NodeKind,
    val size: Long? = null,
    val mime: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    /** 仅回收站条目有值 */
    val deletedAt: String? = null,
    /** 仅回收站条目有值:预计被彻删的时刻(服务端按保留期算好) */
    val purgeAt: String? = null,
    val preview: NodePreview = NodePreview(),
    /** 文件夹子树统计(异步重算,statsStale 时数字可能滞后) */
    val subtreeBytes: Long? = null,
    val subtreeCount: Long? = null,
    val statsStale: Boolean = false,
) {
    val isFolder: Boolean get() = kind == NodeKind.FOLDER

    /** 缩略图/大图的内存与磁盘缓存 key:预签名 URL 会变,id + updatedAt 不变 */
    val imageCacheKey: String get() = "$id@${updatedAt.orEmpty()}"

    /** 图片/视频文件且服务端给过缩略图(在线时有 [NodePreview.thumbUrl],离线缓存里只剩标记) */
    val hasThumb: Boolean
        get() = kind == NodeKind.FILE &&
            (preview.kind == PreviewKind.IMAGE || preview.kind == PreviewKind.VIDEO) &&
            (preview.hasThumbnail || !preview.thumbUrl.isNullOrEmpty())
}

/** 一页列表(children / searchNodes / trash 共用) */
@Immutable
data class NodePage(
    val items: List<Node>,
    val nextCursor: String?,
    val total: Int,
) {
    companion object {
        val EMPTY = NodePage(emptyList(), null, 0)
    }
}

fun NodeItem.toNode(): Node = Node(
    id = id,
    parentId = parentId,
    name = name,
    kind = if (kind == GqlNodeKind.FOLDER) NodeKind.FOLDER else NodeKind.FILE,
    size = size,
    mime = mime,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    purgeAt = purgeAt,
    preview = NodePreview(
        kind = preview.kind.toDomain(),
        hasThumbnail = !preview.thumbUrl.isNullOrEmpty(),
        thumbUrl = preview.thumbUrl,
    ),
    subtreeBytes = subtreeBytes,
    subtreeCount = subtreeCount,
    statsStale = statsStale ?: false,
)

private fun GqlPreviewKind.toDomain(): PreviewKind =
    previewKindOf(rawValue)
