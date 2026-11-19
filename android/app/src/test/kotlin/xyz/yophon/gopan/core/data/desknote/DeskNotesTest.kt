package xyz.yophon.gopan.core.data.desknote

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.time.LocalDateTime

class DeskNotesTest {

    private val sample = """
        {
          "title": "ToDo",
          "items": [
            { "text": "买牛奶", "done": false },
            { "text": "交周报", "done": true, "remind": "2026-11-19 09:00", "reminded": true }
          ],
          "style": { "opacity": 0.9, "textColor": "#C9D1D9", "bgColor": "#34424D", "checkboxes": true },
          "future": { "keep": 1 }
        }
    """.trimIndent()

    private fun note() = DeskNotes.parse(sample)!!

    @Test
    fun `认出便签,别的 json 不认`() {
        assertThat(note().title).isEqualTo("ToDo")
        assertThat(note().items.map { it.text }).containsExactly("买牛奶", "交周报").inOrder()
        assertThat(note().bgColor).isEqualTo(0x34424DL)
        assertThat(note().textColor).isEqualTo(0xC9D1D9L)
        assertThat(DeskNotes.parse("""{"a":1}""")).isNull()
        assertThat(DeskNotes.parse("""{"title":"x","items":[{"t":1}]}""")).isNull()
        assertThat(DeskNotes.parse("not json")).isNull()
        assertThat(DeskNotes.isName("a.JSON")).isTrue()
        assertThat(DeskNotes.isName("a.txt")).isFalse()
    }

    @Test
    fun `操作，勾选、改字、清空即删除、添加、改标题`() {
        val n = note()
        assertThat(DeskNotes.apply(n, DeskNoteOp.Toggle(0, "买牛奶", true))!!.items[0].done).isTrue()
        assertThat(n.items[0].done).isFalse()
        assertThat(DeskNotes.apply(n, DeskNoteOp.Edit(0, "买牛奶", " 买酸奶 "))!!.items[0].text).isEqualTo("买酸奶")
        assertThat(DeskNotes.apply(n, DeskNoteOp.Edit(0, "买牛奶", "  "))!!.items).hasSize(1)
        assertThat(DeskNotes.apply(n, DeskNoteOp.Add(" 第三条 "))!!.items.last().text).isEqualTo("第三条")
        assertThat(DeskNotes.apply(n, DeskNoteOp.Title("周\n计划"))!!.title).isEqualTo("周 计划")
        assertThat(DeskNotes.apply(n, DeskNoteOp.Title(" "))!!.title).isEqualTo("ToDo")
        // 那一条已经不在了
        assertThat(DeskNotes.apply(n, DeskNoteOp.Remove(0, "不在了"))).isNull()
    }

    @Test
    fun `条目挪了位置，按文字找回同一条`() {
        val moved = DeskNotes.apply(note(), DeskNoteOp.Add("新加的"))!!.let { n ->
            val items = (n.raw["items"] as JsonArray).toMutableList()
            items.add(0, items.removeAt(items.size - 1))
            DeskNote(JsonObject(n.raw + ("items" to JsonArray(items))))
        }
        val r = DeskNotes.apply(moved, DeskNoteOp.Toggle(0, "买牛奶", true))!!
        assertThat(r.items.map { it.text to it.done }).containsExactly("新加的" to false, "买牛奶" to true, "交周报" to true).inOrder()
    }

    @Test
    fun `写回保留不认识的字段和条目上的其他字段`() {
        val r = DeskNotes.apply(note(), DeskNoteOp.Toggle(1, "交周报", false))!!
        val back = Json.parseToJsonElement(DeskNotes.serialize(r)) as JsonObject
        assertThat(back["future"].toString()).isEqualTo("""{"keep":1}""")
        val item = (back["items"] as JsonArray)[1] as JsonObject
        assertThat((item["remind"] as JsonPrimitive).content).isEqualTo("2026-11-19 09:00")
        assertThat((item["done"] as JsonPrimitive).content).isEqualTo("false")
    }

    @Test
    fun `提醒的说法和桌面端一致`() {
        val now = LocalDateTime.of(2026, 11, 19, 10, 0)
        assertThat(DeskNotes.formatRemind("2026-11-19 15:00", now)).isEqualTo("15:00")
        assertThat(DeskNotes.formatRemind("2026-11-20 09:05", now)).isEqualTo("明天 09:05")
        assertThat(DeskNotes.formatRemind("2026-12-01 08:00", now)).isEqualTo("12月1日 08:00")
        assertThat(DeskNotes.formatRemind("2027-01-02 08:00", now)).isEqualTo("2027-1-2 08:00")
        assertThat(DeskNotes.formatRemind(null, now)).isEmpty()
        assertThat(note().items[1].due).isFalse() // 已完成的不算过期
    }

    @Test
    fun `切出链接,去掉结尾标点`() {
        val text = "看一下 https://example.com/a?b=1。然后 www.example.org, 好"
        assertThat(DeskNotes.links(text).map { it.third }).containsExactly("https://example.com/a?b=1", "http://www.example.org").inOrder()
        val (s, e, _) = DeskNotes.links(text)[0]
        assertThat(text.substring(s, e)).isEqualTo("https://example.com/a?b=1")
    }

    /** 假的服务器:sha256 用版本号代替;base 对不上就报冲突 */
    private class FakeRemote(initial: DeskNote) {
        var content = DeskNotes.serialize(initial)
        var version = 1
        val conflict = IllegalStateException("EDIT_CONFLICT")
        val note: DeskNote get() = DeskNotes.parse(content)!!
        fun edit(op: DeskNoteOp) {
            content = DeskNotes.serialize(DeskNotes.apply(note, op)!!)
            version++
        }
        val sync = DeskNoteSync(
            read = { content to "v$version" },
            write = { c, b ->
                if (b != "v$version") throw conflict
                content = c
                version++
                "v$version"
            },
            isConflict = { it === conflict },
        )
    }

    @Test
    fun `一步步存,后一步基于前一步`() = runBlocking<Unit> {
        val r = FakeRemote(note())
        r.sync.load()
        assertThat(r.sync.persist(DeskNoteOp.Add("第三条"))).isEqualTo(DeskNoteSaveResult.Saved)
        assertThat(r.sync.persist(DeskNoteOp.Toggle(2, "第三条", true))).isEqualTo(DeskNoteSaveResult.Saved)
        assertThat(r.note.items.map { it.text to it.done }).containsExactly("买牛奶" to false, "交周报" to true, "第三条" to true).inOrder()
    }

    @Test
    fun `桌面端中途加了一条，重放这一步,不盖掉桌面端的改动`() = runBlocking<Unit> {
        val r = FakeRemote(note())
        r.sync.load()
        r.edit(DeskNoteOp.Add("桌面端加的"))
        assertThat(r.sync.persist(DeskNoteOp.Toggle(0, "买牛奶", true))).isEqualTo(DeskNoteSaveResult.Saved)
        assertThat(r.note.items.map { it.text to it.done }).containsExactly("买牛奶" to true, "交周报" to true, "桌面端加的" to false).inOrder()
        assertThat(r.sync.server!!.items).hasSize(3)
    }

    @Test
    fun `那一条在桌面端被删了，放弃这一步`() = runBlocking<Unit> {
        val r = FakeRemote(note())
        r.sync.load()
        r.edit(DeskNoteOp.Remove(0, "买牛奶"))
        assertThat(r.sync.persist(DeskNoteOp.Edit(0, "买牛奶", "买酸奶"))).isEqualTo(DeskNoteSaveResult.Gone)
        assertThat(r.note.items.map { it.text }).containsExactly("交周报")
    }
}
