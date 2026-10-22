package xyz.yophon.gopan.feature.activity

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import xyz.yophon.gopan.core.data.activity.ActivityAction
import xyz.yophon.gopan.core.data.activity.ActivityActor
import xyz.yophon.gopan.core.data.activity.ActivityQuery
import xyz.yophon.gopan.core.data.activity.ActorKind
import xyz.yophon.gopan.core.data.activity.ActorSummary
import xyz.yophon.gopan.core.data.activity.FakeActivityRepository.Companion.ANDROID
import xyz.yophon.gopan.core.data.activity.FakeActivityRepository.Companion.RCLONE
import xyz.yophon.gopan.core.data.activity.FakeActivityRepository.Companion.activity
import xyz.yophon.gopan.core.data.activity.UndoItem
import xyz.yophon.gopan.core.data.activity.UndoOutcome
import java.time.LocalDate
import java.time.ZoneId

/** 动态的文案、按天分组与同一批折叠、撤回提示、通知目标里的来源 */
class ActivityLogicTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 10, 21)

    // ---- 文案 ----

    @Test
    fun `every action has a chinese verb`() {
        val labels = ActivityAction.entries.associateWith(ActivityLogic::actionLabel)
        assertThat(labels[ActivityAction.MKDIR]).isEqualTo("新建文件夹")
        assertThat(labels[ActivityAction.UPLOAD]).isEqualTo("上传")
        assertThat(labels[ActivityAction.OVERWRITE]).isEqualTo("覆盖")
        assertThat(labels[ActivityAction.RENAME]).isEqualTo("改名")
        assertThat(labels[ActivityAction.MOVE]).isEqualTo("移动")
        assertThat(labels[ActivityAction.COPY]).isEqualTo("复制")
        assertThat(labels[ActivityAction.DELETE]).isEqualTo("删除")
        assertThat(labels[ActivityAction.RESTORE]).isEqualTo("还原")
        assertThat(labels[ActivityAction.PURGE]).isEqualTo("彻底删除")
        assertThat(labels[ActivityAction.EXTRACT]).isEqualTo("解压")
        assertThat(labels.values.toSet()).hasSize(ActivityAction.entries.size)
    }

    @Test
    fun `actor text names the source and its kind`() {
        // 无人值守的来源:名字(种类),与大量删除提醒的标题一致
        assertThat(ActivityLogic.actorText(RCLONE)).isEqualTo("rclone(WebDAV)")
        assertThat(ActivityLogic.actorText(ActivityActor(ActorKind.AGENT, "api_key:k1", "Claude"))).isEqualTo("Claude(Agent)")
        assertThat(ActivityLogic.actorText(ActivityActor(ActorKind.COLLECT, "r1", "作业收集 · 张三"))).isEqualTo("作业收集 · 张三(收集链接)")
        // 设备名、任务名本身就说明了来源
        assertThat(ActivityLogic.actorText(ANDROID)).isEqualTo("安卓 App · Pixel 8")
        assertThat(ActivityLogic.actorText(ActivityActor(ActorKind.WEB, "f", "Edge · Windows"))).isEqualTo("Edge · Windows")
        assertThat(ActivityLogic.actorText(ActivityActor(ActorKind.JOB, "j", "解压"))).isEqualTo("解压")
        assertThat(ActivityLogic.actorText(ActivityActor(ActorKind.SYSTEM, "", "回收站到期清理"))).isEqualTo("回收站到期清理")
        // 名字和种类一样、或者没有名字
        assertThat(ActivityLogic.actorText(ActivityActor(ActorKind.CLIP, "j", "剪藏"))).isEqualTo("剪藏")
        assertThat(ActivityLogic.actorText(ActivityActor(ActorKind.SYSTEM))).isEqualTo("系统")
        assertThat(ActivityLogic.actorText(ActivityActor(ActorKind.IMG, "k", "  "))).isEqualTo("图床密钥")
    }

    @Test
    fun `name, location and size text`() {
        val move = activity("1", action = ActivityAction.MOVE).copy(fromPath = "/下载", toPath = "/照片/2024", parentPath = "/照片/2024")
        assertThat(ActivityLogic.locationText(move)).isEqualTo("/下载 → /照片/2024")
        // 移动但没有前后路径时退回所在文件夹
        assertThat(ActivityLogic.locationText(move.copy(fromPath = null))).isEqualTo("/照片/2024")
        assertThat(ActivityLogic.locationText(activity("2", action = ActivityAction.UPLOAD, parentPath = ""))).isEqualTo("/")

        val rename = activity("3", action = ActivityAction.RENAME, name = "b.txt").copy(oldName = "a.txt", newName = "b.txt")
        assertThat(ActivityLogic.nameText(rename)).isEqualTo("a.txt → b.txt")
        assertThat(ActivityLogic.nameText(rename.copy(oldName = null))).isEqualTo("b.txt")
        assertThat(ActivityLogic.nameText(activity("4", name = "x.jpg"))).isEqualTo("x.jpg")

        val del = activity("5", action = ActivityAction.DELETE)
        assertThat(ActivityLogic.sizeText(del.copy(files = 12, bytes = 3 * 1024 * 1024))).isEqualTo("12 个文件,3.0 MB")
        assertThat(ActivityLogic.sizeText(del.copy(files = 1, bytes = 2048))).isEqualTo("2.0 KB")
        assertThat(ActivityLogic.sizeText(activity("6", action = ActivityAction.MKDIR))).isNull()
    }

    @Test
    fun `time is local hours and minutes`() {
        assertThat(ActivityLogic.timeText("2026-10-21T02:05:00Z", zone)).isEqualTo("10:05")
        assertThat(ActivityLogic.timeText("garbage", zone)).isEmpty()
    }

    // ---- 分组 ----

    @Test
    fun `consecutive rows of one batch collapse into a group under its day`() {
        val items = listOf(
            activity("9", batchId = "solo", at = "2026-10-21T03:00:00Z"),
            activity("8", batchId = "multi", at = "2026-10-21T02:00:00Z"),
            activity("7", batchId = "multi", at = "2026-10-21T02:00:00Z"),
            activity("6", batchId = "multi", at = "2026-10-20T15:59:00Z"), // 本地 23:59 昨天,但跟着组走
            activity("5", batchId = "other", at = "2026-10-20T15:00:00Z"),
            activity("4", batchId = "multi", at = "2026-10-20T14:00:00Z"), // 同一批但不连续:另成一条
        )
        val rows = ActivityLogic.rows(items, expanded = emptySet(), zone = zone, today = today)
        val shape = rows.map {
            when (it) {
                is ActivityRow.DayHeader -> "#" + it.label
                is ActivityRow.Single -> it.activity.id
                is ActivityRow.Group -> "G(" + it.items.joinToString(",") { a -> a.id } + ")"
                is ActivityRow.Member -> "m" + it.activity.id
            }
        }
        assertThat(shape).containsExactly("#今天", "9", "G(8,7,6)", "#昨天", "5", "4").inOrder()
        assertThat(rows.map { it.key }.toSet()).hasSize(rows.size)
    }

    @Test
    fun `expanded groups are followed by their members`() {
        val items = listOf(
            activity("3", batchId = "b"),
            activity("2", batchId = "b"),
            activity("1", batchId = "c"),
            activity("0", batchId = "c"),
        )
        val rows = ActivityLogic.rows(items, expanded = setOf("b"), zone = zone, today = today)
        val shape = rows.map {
            when (it) {
                is ActivityRow.DayHeader -> "#"
                is ActivityRow.Single -> it.activity.id
                is ActivityRow.Group -> "G${it.batchId}${if (it.expanded) "+" else ""}"
                is ActivityRow.Member -> "m" + it.activity.id
            }
        }
        assertThat(shape).containsExactly("#", "Gb+", "m3", "m2", "Gc").inOrder()
        assertThat(rows.map { it.key }.toSet()).hasSize(rows.size)
    }

    @Test
    fun `group summary`() {
        val moves = (1..5).map { activity("$it", batchId = "b", name = "p$it.jpg", parentPath = "/照片") }
        assertThat(ActivityLogic.groupTitle(moves)).isEqualTo("移动 5 项")
        assertThat(ActivityLogic.groupNames(moves)).isEqualTo("p1.jpg、p2.jpg、p3.jpg 等")
        assertThat(ActivityLogic.groupNames(moves.take(2))).isEqualTo("p1.jpg、p2.jpg")
        assertThat(ActivityLogic.groupLocation(moves)).isEqualTo("/照片")

        val mixed = moves.take(2) + activity("x", batchId = "b", action = ActivityAction.DELETE, parentPath = "/下载")
        assertThat(ActivityLogic.groupTitle(mixed)).isEqualTo("3 项改动")
        assertThat(ActivityLogic.groupLocation(mixed)).isNull()

        assertThat(ActivityLogic.groupUndoable(moves)).isTrue()
        val done = moves.map { it.copy(undoneAt = "2026-10-21T11:00:00Z") }
        assertThat(ActivityLogic.groupUndoable(done)).isFalse()
        assertThat(ActivityLogic.groupAllUndone(done)).isTrue()
        assertThat(ActivityLogic.groupUndoable(moves.map { it.copy(undoable = false) })).isFalse()
    }

    // ---- 撤回提示 ----

    private fun item(id: String, ok: Boolean, reason: String? = null, name: String = "f$id") =
        UndoItem(id, name, ActivityAction.MOVE, ok, reason)

    @Test
    fun `undo preview summary lists reasons grouped`() {
        val outcome = UndoOutcome(
            done = 3,
            failed = 5,
            items = listOf(
                item("1", true), item("2", true), item("3", true),
                item("4", false, "之后又改过名", "a.txt"),
                item("5", false, "文件在回收站里", "b.txt"),
                item("6", false, "之后又改过名", "c.txt"),
                item("7", false, "之后又改过名", "d.txt"),
                item("8", false, "之后又改过名", "e.txt"),
            ),
        )
        assertThat(ActivityLogic.previewText(outcome)).isEqualTo("会撤回 3 项,5 项撤不了:")
        val lines = ActivityLogic.reasonLines(outcome)
        assertThat(lines.map { it.reason }).containsExactly("之后又改过名", "文件在回收站里").inOrder()
        assertThat(lines.map { ActivityLogic.reasonText(it) }).containsExactly(
            "之后又改过名:a.txt、c.txt、d.txt 等 4 项",
            "文件在回收站里:b.txt",
        ).inOrder()

        assertThat(ActivityLogic.previewText(UndoOutcome(2, 0, listOf(item("1", true), item("2", true))))).isEqualTo("会撤回 2 项。")
        assertThat(ActivityLogic.previewText(UndoOutcome(0, 1, listOf(item("1", false, "已彻底删除,找不回来"))))).isEqualTo("这 1 项都撤不了:")
        assertThat(ActivityLogic.previewText(UndoOutcome(0, 0, emptyList()))).isEqualTo("没有要撤回的改动。")
        // 服务端没给原因
        assertThat(ActivityLogic.reasonLines(UndoOutcome(0, 1, listOf(item("1", false, null)))).single().reason).isEqualTo("原因不明")
    }

    @Test
    fun `undo result text`() {
        assertThat(ActivityLogic.resultText(UndoOutcome(4, 0, emptyList()))).isEqualTo("已撤回 4 项")
        assertThat(ActivityLogic.resultText(UndoOutcome(4, 2, emptyList()))).isEqualTo("已撤回 4 项,2 项没撤成")
        // 单条撤不了:直接说原因
        assertThat(ActivityLogic.resultText(UndoOutcome(0, 1, listOf(item("1", false, "原文件夹已彻底删除"))))).isEqualTo("撤不了:原文件夹已彻底删除")
        assertThat(ActivityLogic.resultText(UndoOutcome(0, 3, emptyList()))).isEqualTo("3 项都没撤成")
        assertThat(ActivityLogic.resultText(UndoOutcome(0, 0, emptyList()))).isEqualTo("没有要撤回的改动")
    }

    // ---- 来源筛选 ----

    @Test
    fun `notification actor id is kind colon ref`() {
        assertThat(ActivityLogic.parseActor("webdav:ap-9")).isEqualTo(ActivityActor(ActorKind.WEBDAV, "ap-9"))
        // Agent 的 ref 自己带冒号:只按第一个切
        assertThat(ActivityLogic.parseActor("agent:api_key:0b7c")).isEqualTo(ActivityActor(ActorKind.AGENT, "api_key:0b7c"))
        assertThat(ActivityLogic.parseActor("img:")).isEqualTo(ActivityActor(ActorKind.IMG, ""))
        assertThat(ActivityLogic.parseActor("system")).isEqualTo(ActivityActor(ActorKind.SYSTEM, ""))
        assertThat(ActivityLogic.parseActor("WEBDAV:x")).isEqualTo(ActivityActor(ActorKind.WEBDAV, "x"))
        assertThat(ActivityLogic.parseActor("teleport:x")).isNull()
        assertThat(ActivityLogic.parseActor("")).isNull()
        assertThat(ActivityLogic.parseActor(null)).isNull()
    }

    @Test
    fun `route args become the list filter`() {
        assertThat(ActivityLogic.queryFor("webdav:ap-9", "2026-10-21T00:00:00Z"))
            .isEqualTo(ActivityQuery(ActorKind.WEBDAV, "ap-9", "2026-10-21T00:00:00Z"))
        // 没有 ref = 这一类来源都算;时间解析不了就不筛时间
        assertThat(ActivityLogic.queryFor("img:", "not a time")).isEqualTo(ActivityQuery(ActorKind.IMG, null, null))
        assertThat(ActivityLogic.queryFor(null, null).isEmpty).isTrue()
        assertThat(ActivityLogic.queryFor("bogus:1", null).isEmpty).isTrue()
    }

    @Test
    fun `filter chips add the filtered source when it is not listed`() {
        val actors = listOf(ActorSummary(ANDROID, 10, "2026-10-21T10:00:00Z"), ActorSummary(RCLONE, 300, "2026-10-21T09:00:00Z"))
        assertThat(ActivityLogic.filterChips(actors, ActivityQuery())).containsExactly(ANDROID, RCLONE).inOrder()
        assertThat(ActivityLogic.filterChips(actors, ActivityQuery(ActorKind.WEBDAV, "ap-9"))).containsExactly(ANDROID, RCLONE).inOrder()
        val extra = ActivityLogic.filterChips(actors, ActivityQuery(ActorKind.AGENT, "oauth:g1"))
        assertThat(extra.first()).isEqualTo(ActivityActor(ActorKind.AGENT, "oauth:g1"))
        assertThat(extra).hasSize(3)

        assertThat(ActivityLogic.isSelected(RCLONE, ActivityQuery(ActorKind.WEBDAV, "ap-9"))).isTrue()
        assertThat(ActivityLogic.isSelected(RCLONE, ActivityQuery(ActorKind.WEBDAV, "other"))).isFalse()
        assertThat(ActivityLogic.isSelected(ActivityActor(ActorKind.IMG, ""), ActivityQuery(ActorKind.IMG, null))).isTrue()
    }

    @Test
    fun `since chip text`() {
        assertThat(ActivityLogic.sinceText("2026-10-21T02:32:00Z", zone, today)).isEqualTo("今天 10:32 起")
        assertThat(ActivityLogic.sinceText("2026-10-01T02:32:00Z", zone, today)).isEqualTo("10月1日 10:32 起")
        assertThat(ActivityLogic.sinceText("bad", zone, today)).isEmpty()
    }
}
