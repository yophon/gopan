package xyz.yophon.gopan.core.data.clips

import xyz.yophon.gopan.core.data.jobs.BackgroundJob
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException

/** 内存版剪藏仓库:[clipResults] 依次给出 clipUrl 的结果,[jobStates] 依次给出轮询到的任务 */
class FakeClipsRepository : ClipsRepository {
    val calls = mutableListOf<String>()
    var clips: List<Clip> = emptyList()
    val clipResults = ArrayDeque<Result<ClipSubmission>>()
    val jobStates = ArrayDeque<Result<BackgroundJob>>()

    override suspend fun clips(unreadOnly: Boolean, cursor: String?): ClipPage {
        calls += "clips(unread=$unreadOnly,$cursor)"
        return ClipPage(clips.filter { !unreadOnly || it.unread }, null)
    }

    override suspend fun clip(id: String): Clip {
        calls += "clip($id)"
        return clips.firstOrNull { it.id == id } ?: throw GopanException(GopanErrors.NOT_FOUND, "对象不存在或已被删除")
    }

    override suspend fun nodeClip(nodeId: String): Clip? {
        calls += "nodeClip($nodeId)"
        return clips.firstOrNull { it.nodeId == nodeId }
    }

    override suspend fun clipUrl(url: String, force: Boolean): ClipSubmission {
        calls += "clipUrl($url,force=$force)"
        return clipResults.removeFirst().getOrThrow()
    }

    override suspend fun markRead(id: String, read: Boolean): Clip {
        calls += "markRead($id,$read)"
        val updated = clip(id).copy(readAt = if (read) "2026-10-21T12:00:00Z" else null)
        clips = clips.map { if (it.id == id) updated else it }
        return updated
    }

    override suspend fun job(id: String): BackgroundJob {
        calls += "job($id)"
        return jobStates.removeFirst().getOrThrow()
    }

    companion object {
        fun clip(id: String, nodeId: String = "n-$id", read: Boolean = false) = Clip(
            id = id,
            url = "https://example.com/$id",
            title = "文章 $id",
            site = "示例",
            nodeId = nodeId,
            readAt = if (read) "2026-10-20T12:00:00Z" else null,
            createdAt = "2026-10-20T12:00:00Z",
        )
    }
}
