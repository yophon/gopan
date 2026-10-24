package xyz.yophon.gopan.core.data.quick

/** 测试用:记下调用,列表与版本由测试预置 */
class FakeQuickRepository : QuickRepository {
    val calls = mutableListOf<String>()
    var starredItems: List<QuickItem> = emptyList()
    var recentItems: List<QuickItem> = emptyList()
    var versionList = FileVersionList(emptyList(), keep = 10, keepDays = 30)

    override suspend fun starred(): List<QuickItem> = starredItems.also { calls += "starred" }

    override suspend fun recent(): List<QuickItem> = recentItems.also { calls += "recent" }

    override suspend fun markOpened(nodeId: String) {
        calls += "markOpened:$nodeId"
    }

    override suspend fun clearRecent(ids: List<String>?) {
        calls += "clearRecent:${ids?.joinToString(",") ?: "all"}"
    }

    override suspend fun versions(nodeId: String): FileVersionList = versionList.also { calls += "versions:$nodeId" }

    override suspend fun restoreVersion(id: String) {
        calls += "restore:$id"
    }

    override suspend fun deleteVersions(ids: List<String>) {
        calls += "delete:${ids.joinToString(",")}"
    }

    override suspend fun purgeVersions(nodeId: String): Long = 0L.also { calls += "purge:$nodeId" }
}
