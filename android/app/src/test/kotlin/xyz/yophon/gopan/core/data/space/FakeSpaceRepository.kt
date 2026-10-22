package xyz.yophon.gopan.core.data.space

import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.NodeKind
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException

/**
 * 内存版空间仓库:各个清单直接给;重复文件按 [duplicatePageSize] 分页(offset 即下标)。
 * [failNext] 置上时下一次调用抛 NETWORK;[calls] 记下每次调用。
 */
class FakeSpaceRepository : SpaceRepository {
    var overview = SpaceOverview(
        usedBytes = 1000, quotaBytes = 10_000, trashFiles = 2, trashBytes = 100,
        byType = listOf(CategoryUsage(SpaceCategory.VIDEO, 3, 600), CategoryUsage(SpaceCategory.IMAGE, 10, 300)),
        duplicateGroups = 1, duplicateBytes = 50,
    )
    val levels = HashMap<String?, SpaceFolderLevel>()
    var large: List<SpaceFile> = emptyList()
    var stale: List<SpaceFile> = emptyList()
    var empty: List<SpaceFile> = emptyList()
    var duplicates: List<DuplicateGroup> = emptyList()
    var duplicatePageSize = 50
    var idle = SpaceIdle(emptyList(), emptyList(), emptyList())
    var failNext = false
    val calls = mutableListOf<String>()

    private fun record(call: String) {
        calls += call
        if (failNext) {
            failNext = false
            throw GopanException(GopanErrors.NETWORK, "网络连接失败,请检查网络后重试")
        }
    }

    override suspend fun overview(): SpaceOverview {
        record("overview")
        return overview
    }

    override suspend fun folders(parentId: String?): SpaceFolderLevel {
        record("folders($parentId)")
        return levels[parentId] ?: throw GopanException(GopanErrors.NOT_FOUND, "对象不存在或已被删除")
    }

    override suspend fun largeFiles(category: SpaceCategory?): List<SpaceFile> {
        record("large($category)")
        return large.filter { category == null || it.category == category }
    }

    override suspend fun staleFiles(): List<SpaceFile> {
        record("stale")
        return stale
    }

    override suspend fun duplicates(offset: Int): DuplicatePage {
        record("duplicates($offset)")
        val end = minOf(duplicates.size, offset + duplicatePageSize)
        val items = if (offset < duplicates.size) duplicates.subList(offset, end) else emptyList()
        return DuplicatePage(items, if (end < duplicates.size) end else null)
    }

    override suspend fun emptyFolders(): List<SpaceFile> {
        record("empty")
        return empty
    }

    override suspend fun idle(): SpaceIdle {
        record("idle")
        return idle
    }

    override suspend fun revokeShare(id: String) = record("revokeShare($id)")

    override suspend fun revokeLink(id: String) = record("revokeLink($id)")

    override suspend fun revokeRequest(id: String) = record("revokeRequest($id)")

    companion object {
        fun spaceFile(id: String, size: Long = 100, path: String = "/", category: SpaceCategory? = SpaceCategory.OTHER, folder: Boolean = false) =
            SpaceFile(
                node = Node(
                    id = id,
                    parentId = null,
                    name = "$id.bin",
                    kind = if (folder) NodeKind.FOLDER else NodeKind.FILE,
                    size = if (folder) null else size,
                    createdAt = "2026-01-0${(id.hashCode() and 7) + 1}T00:00:00Z",
                ),
                path = path,
                category = category,
            )

        /** 一组重复文件:[ids] 最早的在前 */
        fun group(sha: String, size: Long, vararg ids: String) =
            DuplicateGroup(sha, size, ids.size, ids.map { spaceFile(it, size) })
    }
}
