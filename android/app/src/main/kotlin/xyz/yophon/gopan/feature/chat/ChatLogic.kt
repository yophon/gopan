package xyz.yophon.gopan.feature.chat

import androidx.compose.runtime.Immutable
import xyz.yophon.gopan.core.data.PreviewKind
import xyz.yophon.gopan.core.data.chat.ChatMessage
import xyz.yophon.gopan.core.transfer.LiveProgress
import xyz.yophon.gopan.core.transfer.db.UploadTaskEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 乐观上屏的文本(发送中 / 发送失败) */
@Immutable
data class PendingText(val id: String, val body: String, val createdAtMillis: Long, val failed: Boolean = false)

/** 发往传输助手的上传任务卡片 */
@Immutable
data class UploadCard(val task: UploadTaskEntity, val progress: LiveProgress?)

/** 列表里的一项(按时间从旧到新;界面用 reverseLayout 从底部排) */
sealed interface ChatItem {
    val key: String

    data class DayHeader(val label: String, val date: LocalDate) : ChatItem {
        override val key: String get() = "day-$date"
    }

    data class Message(val message: ChatMessage) : ChatItem {
        override val key: String get() = message.id
    }

    /** 2–9 张相邻(间隔 ≤ 2 分钟)的图片 / 视频合成一组 */
    data class MediaGroup(val messages: List<ChatMessage>) : ChatItem {
        override val key: String get() = "grid-${messages.first().id}"
    }

    data class Pending(val pending: PendingText) : ChatItem {
        override val key: String get() = pending.id
    }

    data class Upload(val card: UploadCard) : ChatItem {
        override val key: String get() = "upload-${card.task.id}"
    }
}

/**
 * 传输助手的纯逻辑(与网页端 web/src/utils/chat.ts 对齐,修掉了它的几处问题):
 * - 排序、去重、"最新页覆盖范围"都按消息 id(uuid v7 字典序 = 时间序),createdAt 只用来显示;
 * - 同一 id 用服务端的新副本覆盖本地(文件被删后能立刻变成「文件已不存在」,缩略图地址也跟着续签)。
 */
object ChatLogic {
    const val MEDIA_GROUP_WINDOW_MS = 2 * 60_000L
    const val MEDIA_GROUP_MAX = 9
    const val COLLAPSE_LINES = 8

    /** 合并轮询拿到的最新页:返回新列表与新增条数 */
    fun mergeLatest(
        local: List<ChatMessage>,
        latest: List<ChatMessage>,
        tombstones: Set<String>,
        pageLimit: Int,
    ): Pair<List<ChatMessage>, Int> {
        val fresh = latest.filter { it.id !in tombstones }
        val freshById = fresh.associateBy { it.id }
        // 最新页覆盖的范围:不满一页说明整个会话都在里面
        val coversAll = latest.size < pageLimit
        val oldest = latest.firstOrNull()?.id
        val kept = local.filter { m ->
            if (m.id in tombstones) return@filter false
            if (m.id in freshById) return@filter true
            // 范围内本地有、服务端没有:别端删掉了
            !(coversAll || (oldest != null && m.id >= oldest))
        }
        val localIds = local.mapTo(HashSet()) { it.id }
        val keptIds = kept.mapTo(HashSet()) { it.id }
        val merged = (kept.map { freshById[it.id] ?: it } + fresh.filter { it.id !in keptIds }).sortedBy { it.id }
        val added = fresh.count { it.id !in localIds }
        return merged to added
    }

    /** 往前翻历史 / 往后续页:按 id 去重合并,跳过已删的 */
    fun mergeWindow(local: List<ChatMessage>, more: List<ChatMessage>, tombstones: Set<String>): List<ChatMessage> {
        val byId = LinkedHashMap<String, ChatMessage>()
        local.forEach { byId[it.id] = it }
        more.filter { it.id !in tombstones }.forEach { byId[it.id] = it }
        return byId.values.sortedBy { it.id }
    }

    fun isMedia(m: ChatMessage): Boolean {
        val node = m.node ?: return false
        if (node.isFolder) return false
        return (node.preview.kind == PreviewKind.IMAGE || node.preview.kind == PreviewKind.VIDEO) && node.hasThumb
    }

    /**
     * 列表项(旧 → 新):按本地日历日加日期头;同一天里相邻、间隔不超过 2 分钟的媒体消息合成网格(2–9 条);
     * 末尾依次是发送中的文本与上传卡片。
     */
    fun buildItems(
        messages: List<ChatMessage>,
        pending: List<PendingText>,
        uploads: List<UploadCard>,
        zone: ZoneId,
        today: LocalDate,
    ): List<ChatItem> {
        val out = ArrayList<ChatItem>(messages.size + pending.size + uploads.size + 8)
        var currentDay: LocalDate? = null
        var group = ArrayList<ChatMessage>()

        fun flush() {
            when (group.size) {
                0 -> Unit
                1 -> out += ChatItem.Message(group[0])
                else -> out += ChatItem.MediaGroup(group.toList())
            }
            group = ArrayList()
        }

        for (m in messages) {
            val day = Instant.ofEpochMilli(m.createdAtMillis).atZone(zone).toLocalDate()
            if (day != currentDay) {
                flush()
                currentDay = day
                out += ChatItem.DayHeader(dayLabel(day, today), day)
            }
            if (isMedia(m)) {
                val last = group.lastOrNull()
                val gap = last?.let { m.createdAtMillis - it.createdAtMillis }
                if (last != null && (gap == null || gap < 0 || gap > MEDIA_GROUP_WINDOW_MS || group.size >= MEDIA_GROUP_MAX)) flush()
                group += m
            } else {
                flush()
                out += ChatItem.Message(m)
            }
        }
        flush()
        pending.forEach { out += ChatItem.Pending(it) }
        uploads.forEach { out += ChatItem.Upload(it) }
        return out
    }

    /** 今天 / 昨天 / M月D日(同年)/ yyyy-MM-dd(跨年) */
    fun dayLabel(day: LocalDate, today: LocalDate): String = when {
        day == today -> "今天"
        day == today.minusDays(1) -> "昨天"
        day.year == today.year -> "${day.monthValue}月${day.dayOfMonth}日"
        else -> day.toString()
    }

    /** mcp → Agent,webdav → WebDAV,其余(web、App)不显示 */
    fun sourceLabel(source: String): String? = when (source) {
        "mcp" -> "Agent"
        "webdav" -> "WebDAV"
        else -> null
    }

    fun utf8Length(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    /** 最新一条纯文本消息(没有附件、正文非空);"复制最新文本"用 */
    fun latestText(messages: List<ChatMessage>): String? = messages.lastOrNull { it.isText }?.body

    /**
     * 识别 http(s) 链接(与网页端 linkify 一致):结尾的中英文标点、括号不算进链接。返回 (起, 止) 区间。
     */
    fun findLinks(text: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        for (match in LINK.findAll(text)) {
            var end = match.range.last
            while (end > match.range.first && text[end] in TRAILING) end--
            if (end - match.range.first >= "http://x".length - 1) out += match.range.first..end
        }
        return out
    }

    private val LINK = Regex("https?://[^\\s<>\"'　，。！？；：、（）【】「」『』《》]+")
    private const val TRAILING = ".,;:!?)]}'\"…，。！？；：、）】」』》"

    /** 传输助手里显示的上传卡片:发往传输助手、没取消;完成的卡片保留到对应消息出现(最多 15 秒) */
    fun uploadCards(
        tasks: List<UploadTaskEntity>,
        progress: Map<Long, LiveProgress>,
        chatFolderId: String?,
        messageNodeIds: Set<String>,
        now: Long,
    ): List<UploadCard> = tasks.filter { t ->
        val toChat = t.toChat || (chatFolderId != null && t.parentId == chatFolderId)
        if (!toChat) return@filter false
        when (t.state) {
            xyz.yophon.gopan.core.transfer.TransferState.CANCELED -> false
            xyz.yophon.gopan.core.transfer.TransferState.DONE ->
                t.nodeId != null && t.nodeId !in messageNodeIds && now - (t.finishedAt ?: 0) < DONE_CARD_LINGER_MS
            else -> true
        }
    }.map { UploadCard(it, progress[it.id]) }

    const val DONE_CARD_LINGER_MS = 15_000L
}
