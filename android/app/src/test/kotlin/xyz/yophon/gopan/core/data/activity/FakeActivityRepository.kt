package xyz.yophon.gopan.core.data.activity

import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException

/**
 * 内存版动态仓库:[items] 新的在前,按 [pageSize] 分页(游标是下一页的起始下标),按来源 / 时间筛;
 * 撤回结果由 [outcomeFor] 决定(默认全部成功)。[failNext] 置上时下一次调用抛 NETWORK;[calls] 记下每次调用。
 */
class FakeActivityRepository : ActivityRepository {
    var items: List<Activity> = emptyList()
    var actors: List<ActorSummary> = emptyList()
    var pageSize = 50
    var failNext = false
    val calls = mutableListOf<String>()

    /** 预览与撤回的结果;默认:选中的都能撤 */
    var outcomeFor: (UndoTarget) -> UndoOutcome = { target ->
        val picked = select(target)
        UndoOutcome(picked.size, 0, picked.map { UndoItem(it.id, it.name, it.action, ok = true) })
    }

    private fun maybeFail() {
        if (failNext) {
            failNext = false
            throw GopanException(GopanErrors.NETWORK, "网络连接失败,请检查网络后重试")
        }
    }

    private fun matches(a: Activity, q: ActivityQuery): Boolean =
        (q.actorKind == null || a.actor.kind == q.actorKind) &&
            (q.actorRef.isNullOrEmpty() || a.actor.ref == q.actorRef) &&
            (q.since == null || a.at >= q.since)

    fun select(target: UndoTarget): List<Activity> = when (target) {
        is UndoTarget.Ids -> items.filter { it.id in target.ids }
        is UndoTarget.Batch -> items.filter { it.batchId == target.batchId }
        is UndoTarget.Filter -> items.filter { matches(it, target.query) }
    }

    override suspend fun list(query: ActivityQuery, cursor: String?): ActivityPage {
        calls += "list(${query.actorKind},${query.actorRef},${query.since},$cursor)"
        maybeFail()
        val all = items.filter { matches(it, query) }
        val start = cursor?.toInt() ?: 0
        val end = minOf(all.size, start + pageSize)
        return ActivityPage(all.subList(start, end), if (end < all.size) end.toString() else null)
    }

    override suspend fun actors(): List<ActorSummary> {
        calls += "actors"
        return actors
    }

    override suspend fun preview(target: UndoTarget): UndoOutcome {
        calls += "preview($target)"
        maybeFail()
        return outcomeFor(target)
    }

    override suspend fun undo(target: UndoTarget): UndoOutcome {
        calls += "undo($target)"
        maybeFail()
        val outcome = outcomeFor(target)
        val done = outcome.items.filter { it.ok }.mapTo(HashSet()) { it.activityId }
        items = items.map { if (it.id in done) it.copy(undoneAt = "2026-10-21T12:00:00Z", undoable = false) else it }
        return outcome
    }

    companion object {
        val ANDROID = ActivityActor(ActorKind.ANDROID, "fam-1", "安卓 App · Pixel 8")
        val RCLONE = ActivityActor(ActorKind.WEBDAV, "ap-9", "rclone")

        fun activity(
            id: String,
            batchId: String = "b-$id",
            action: ActivityAction = ActivityAction.MOVE,
            at: String = "2026-10-21T10:00:00Z",
            actor: ActivityActor = ANDROID,
            name: String = "文件$id.txt",
            node: Node? = null,
            undoable: Boolean = true,
            undoneAt: String? = null,
            parentPath: String = "/文档",
        ) = Activity(
            id = id,
            at = at,
            actor = actor,
            batchId = batchId,
            action = action,
            nodeId = node?.id ?: "n-$id",
            node = node,
            isFolder = node?.isFolder ?: false,
            name = name,
            parentPath = parentPath,
            undoable = undoable,
            undoneAt = undoneAt,
        )
    }
}
