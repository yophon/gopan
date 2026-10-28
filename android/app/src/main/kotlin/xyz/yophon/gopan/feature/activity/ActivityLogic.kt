package xyz.yophon.gopan.feature.activity

import androidx.compose.runtime.Immutable
import xyz.yophon.gopan.core.data.activity.Activity
import xyz.yophon.gopan.core.data.activity.ActivityAction
import xyz.yophon.gopan.core.data.activity.ActivityActor
import xyz.yophon.gopan.core.data.activity.ActivityQuery
import xyz.yophon.gopan.core.data.activity.ActorKind
import xyz.yophon.gopan.core.data.activity.ActorSummary
import xyz.yophon.gopan.core.data.activity.UndoOutcome
import xyz.yophon.gopan.core.ui.format.formatBytes
import xyz.yophon.gopan.core.ui.format.parseTime
import xyz.yophon.gopan.feature.chat.ChatLogic
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 列表的一行:日期分隔 / 单条 / 同一批折叠成的一组(展开时后面跟着组员) */
@Immutable
sealed interface ActivityRow {
    val key: String

    data class DayHeader(val label: String, val day: LocalDate) : ActivityRow {
        override val key: String get() = "day-$day"
    }

    data class Single(val activity: Activity) : ActivityRow {
        override val key: String get() = "a-${activity.id}"
    }

    /** 连续几条同一个 batchId:[items] 新的在前 */
    data class Group(val batchId: String, val items: List<Activity>, val expanded: Boolean) : ActivityRow {
        override val key: String get() = "g-$batchId-${items.first().id}"
        val first: Activity get() = items.first()
    }

    /** 展开的组里的一条 */
    data class Member(val activity: Activity, val batchId: String) : ActivityRow {
        override val key: String get() = "m-${activity.id}"
    }
}

/** 一种撤不了的原因,和撤不了的是哪些 */
@Immutable
data class ReasonLine(val reason: String, val count: Int, val names: List<String>)

/**
 * 动态页的纯函数(单测覆盖):操作与来源的文案、位置与大小、按天分组与同一批折叠、撤回预览与结果的文案、
 * 通知目标里的来源(kind:ref)。
 */
object ActivityLogic {

    fun actionLabel(action: ActivityAction): String = when (action) {
        ActivityAction.MKDIR -> "新建文件夹"
        ActivityAction.UPLOAD -> "上传"
        ActivityAction.OVERWRITE -> "覆盖"
        ActivityAction.RENAME -> "改名"
        ActivityAction.MOVE -> "移动"
        ActivityAction.COPY -> "复制"
        ActivityAction.DELETE -> "删除"
        ActivityAction.RESTORE -> "还原"
        ActivityAction.PURGE -> "彻底删除"
        ActivityAction.EXTRACT -> "解压"
        ActivityAction.OTHER -> "改动"
    }

    fun actorKindLabel(kind: ActorKind): String = when (kind) {
        ActorKind.WEB -> "网页"
        ActorKind.ANDROID -> "安卓 App"
        ActorKind.WEBDAV -> "WebDAV"
        ActorKind.AGENT -> "Agent"
        ActorKind.IMG -> "图床密钥"
        ActorKind.COLLECT -> "收集链接"
        ActorKind.SHARE -> "分享访客"
        ActorKind.JOB -> "后台任务"
        ActorKind.FEED -> "订阅"
        ActorKind.CLIP -> "剪藏"
        ActorKind.SYSTEM -> "系统"
    }

    /** 名字本身就说明了来源的:设备名(「Edge · Windows」「安卓 App · Pixel 8」)、任务名(离线下载 / 解压)、系统清理 */
    private val selfDescribing = setOf(ActorKind.WEB, ActorKind.ANDROID, ActorKind.SHARE, ActorKind.JOB, ActorKind.CLIP, ActorKind.SYSTEM)

    /** 来源的显示名:「rclone(WebDAV)」「作业收集 · 张三(收集链接)」「Edge · Windows」;没有名字时只显示种类 */
    fun actorText(actor: ActivityActor): String {
        val kind = actorKindLabel(actor.kind)
        val label = actor.label.trim()
        return when {
            label.isEmpty() -> kind
            actor.kind in selfDescribing || label == kind -> label
            else -> "$label($kind)"
        }
    }

    /** 标题里的名字:改名显示「旧名 → 新名」 */
    fun nameText(a: Activity): String {
        if (a.action == ActivityAction.RENAME && !a.oldName.isNullOrEmpty() && !a.newName.isNullOrEmpty()) return "${a.oldName} → ${a.newName}"
        return a.name
    }

    /** 位置:移动显示「原文件夹 → 新文件夹」,其余是当时所在的文件夹 */
    fun locationText(a: Activity): String {
        if (a.action == ActivityAction.MOVE && a.fromPath != null && a.toPath != null) return "${a.fromPath} → ${a.toPath}"
        return a.parentPath.ifEmpty { "/" }
    }

    /** 大小:多个文件「12 个文件,3.4 MB」;单个文件只给大小;文件夹空着或新建文件夹不显示 */
    fun sizeText(a: Activity): String? = when {
        a.files > 1 -> "${a.files} 个文件,${formatBytes(a.bytes)}"
        a.bytes > 0 -> formatBytes(a.bytes)
        else -> null
    }

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    /** 行里的时刻(已按天分组,只要 HH:mm) */
    fun timeText(at: String, zone: ZoneId): String = parseTime(at)?.atZone(zone)?.let(timeFormatter::format) ?: ""

    /**
     * 按天分组(新的在前,服务端已排好),连续几条同一个 batchId 折叠成一组;[expanded] 里的批次展开、组员跟在组后。
     * 一组归在它最新那条的日期下。日期标题与通知页一致:今天 / 昨天 / M月D日 / yyyy-MM-dd。
     */
    fun rows(items: List<Activity>, expanded: Set<String>, zone: ZoneId, today: LocalDate): List<ActivityRow> {
        val out = ArrayList<ActivityRow>(items.size + 8)
        var currentDay: LocalDate? = null
        var i = 0
        while (i < items.size) {
            var j = i + 1
            while (j < items.size && items[j].batchId == items[i].batchId) j++
            val run = items.subList(i, j)
            val day = parseTime(run.first().at)?.atZone(zone)?.toLocalDate() ?: currentDay ?: today
            if (day != currentDay) {
                currentDay = day
                out += ActivityRow.DayHeader(ChatLogic.dayLabel(day, today), day)
            }
            if (run.size == 1) {
                out += ActivityRow.Single(run.first())
            } else {
                val batch = run.first().batchId
                val open = batch in expanded
                out += ActivityRow.Group(batch, run.toList(), open)
                if (open) run.forEach { out += ActivityRow.Member(it, batch) }
            }
            i = j
        }
        return out
    }

    /** 组的标题:都是同一种操作时「移动 50 项」,否则「12 项改动」 */
    fun groupTitle(items: List<Activity>): String {
        val actions = items.mapTo(HashSet()) { it.action }
        return if (actions.size == 1) "${actionLabel(actions.first())} ${items.size} 项" else "${items.size} 项改动"
    }

    /** 组里的名字:「a.jpg、b.jpg、c.jpg 等」 */
    fun groupNames(items: List<Activity>, max: Int = 3): String {
        val names = items.map { it.name }.distinct()
        val head = names.take(max).joinToString("、")
        return if (names.size > max) "$head 等" else head
    }

    /** 组的位置:都在同一处时给出,否则 null */
    fun groupLocation(items: List<Activity>): String? = items.map(::locationText).distinct().singleOrNull()

    /** 组里还有能撤的(没撤过、这类操作能撤) */
    fun groupUndoable(items: List<Activity>): Boolean = items.any { it.undoable && !it.undone }

    fun groupAllUndone(items: List<Activity>): Boolean = items.all { it.undone }

    // ---- 撤回 ----

    /** 撤回预览的开头一句:「会撤回 N 项,M 项撤不了:」 */
    fun previewText(outcome: UndoOutcome): String = when {
        outcome.done > 0 && outcome.failed == 0 -> "会撤回 ${outcome.done} 项。"
        outcome.done > 0 -> "会撤回 ${outcome.done} 项,${outcome.failed} 项撤不了:"
        outcome.failed > 0 -> "这 ${outcome.failed} 项都撤不了:"
        else -> "没有要撤回的改动。"
    }

    /** 撤不了的按原因归在一起(按先出现的顺序),每种原因列几个名字 */
    fun reasonLines(outcome: UndoOutcome, maxNames: Int = 3): List<ReasonLine> {
        val groups = LinkedHashMap<String, MutableList<String>>()
        outcome.items.filter { !it.ok }.forEach { item ->
            groups.getOrPut(item.reason?.takeIf { it.isNotBlank() } ?: "原因不明") { ArrayList() } += item.name
        }
        return groups.map { (reason, names) -> ReasonLine(reason, names.size, names.distinct().take(maxNames)) }
    }

    /** 一种原因的一行:「之后又改过名:a.txt、b.txt 等 5 项」 */
    fun reasonText(line: ReasonLine): String {
        if (line.names.isEmpty()) return line.reason
        val names = line.names.joinToString("、")
        return if (line.count > line.names.size) "${line.reason}:$names 等 ${line.count} 项" else "${line.reason}:$names"
    }

    /** 撤回之后的提示 */
    fun resultText(outcome: UndoOutcome): String = when {
        outcome.done > 0 && outcome.failed == 0 -> "已撤回 ${outcome.done} 项"
        outcome.done > 0 -> "已撤回 ${outcome.done} 项,${outcome.failed} 项没撤成"
        outcome.failed == 1 -> "撤不了:" + (outcome.items.firstOrNull { !it.ok }?.reason?.takeIf { it.isNotBlank() } ?: "原因不明")
        outcome.failed > 1 -> "${outcome.failed} 项都没撤成"
        else -> "没有要撤回的改动"
    }

    // ---- 筛选 ----

    /**
     * 通知目标里的来源(server notify.go TgActivity:id = kind:ref,kind 是小写)。
     * ref 本身可能带冒号(Agent 的 api_key:… / oauth:…),只按第一个冒号切;认不出的种类返回 null。
     */
    fun parseActor(id: String?): ActivityActor? {
        val raw = id?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val kind = ActorKind.of(raw.substringBefore(':')) ?: return null
        val ref = if (':' in raw) raw.substringAfter(':') else ""
        return ActivityActor(kind, ref)
    }

    /** 路由参数(通知点进来的 actor = kind:ref、since = RFC 3339)→ 列表筛选 */
    fun queryFor(actor: String?, since: String?): ActivityQuery {
        val a = parseActor(actor)
        return ActivityQuery(actorKind = a?.kind, actorRef = a?.ref?.takeIf { it.isNotEmpty() }, since = since?.takeIf { parseTime(it) != null })
    }

    /** 筛选条上的来源:近 90 天出现过的;当前筛的来源不在里面(通知点进来、名字查不到)时补在最前面 */
    fun filterChips(actors: List<ActorSummary>, query: ActivityQuery): List<ActivityActor> {
        val list = actors.map { it.actor }
        val kind = query.actorKind ?: return list
        val ref = query.actorRef.orEmpty()
        if (list.any { it.kind == kind && it.ref == ref }) return list
        return listOf(ActivityActor(kind, ref)) + list
    }

    fun isSelected(actor: ActivityActor, query: ActivityQuery): Boolean =
        query.actorKind == actor.kind && query.actorRef.orEmpty() == actor.ref

    /** 「从什么时候起」的筛选:「今天 10:32 起」 */
    fun sinceText(since: String, zone: ZoneId, today: LocalDate): String {
        val t = parseTime(since)?.atZone(zone) ?: return ""
        return "${ChatLogic.dayLabel(t.toLocalDate(), today)} ${timeFormatter.format(t)} 起"
    }
}
