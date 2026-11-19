package xyz.yophon.gopan.core.data.desknote

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * DeskNote 便签(doc/39):Windows 桌面便签把每张便签存成同步文件夹里的一个 json,和网页端 utils/desknote.ts 一一对应。
 *
 *   { "title": "ToDo", "items": [{ "text": "…", "done": false, "remind": "2026-11-19 15:00", "reminded": false }],
 *     "style": { "textColor": "#C9D1D9", "bgColor": "#34424D", "checkboxes": true, … } }
 *
 * 整份留着原始 json([raw]),写回时只动 title 和 items,别的字段(包括不认识的)原样留着。
 */
@Immutable
data class DeskNoteItem(val text: String, val done: Boolean, val remind: String?, val reminded: Boolean) {
    /** 提醒到点了还没做:桌面端到点会把 reminded 置 true */
    val due: Boolean get() = remind != null && reminded && !done
}

@Immutable
data class DeskNote(val raw: JsonObject) {
    val title: String get() = raw["title"]?.jsonPrimitive?.contentOrNull.orEmpty()

    val items: List<DeskNoteItem>
        get() = (raw["items"] as? JsonArray).orEmpty().map { e ->
            val o = e as JsonObject
            DeskNoteItem(
                text = o.str("text").orEmpty(),
                done = o.bool("done"),
                remind = o.str("remind")?.takeIf { it.isNotBlank() },
                reminded = o.bool("reminded"),
            )
        }

    private val style: JsonObject? get() = raw["style"] as? JsonObject

    /** 0xRRGGBB;没设或格式不对时用桌面端的默认值 */
    val bgColor: Long get() = style?.str("bgColor").hexColor() ?: 0x34424D
    val textColor: Long get() = style?.str("textColor").hexColor() ?: 0xA9B1B7
    val checkboxes: Boolean get() = (style?.get("checkboxes") as? JsonPrimitive)?.booleanOrNull != false
}

/** 一步操作。带上操作时看到的文字,重放到别处改过的新内容上时据此找回同一条 */
sealed interface DeskNoteOp {
    data class Toggle(val index: Int, val text: String, val done: Boolean) : DeskNoteOp
    data class Edit(val index: Int, val text: String, val newText: String) : DeskNoteOp
    data class Remove(val index: Int, val text: String) : DeskNoteOp
    data class Add(val text: String) : DeskNoteOp
    data class Title(val title: String) : DeskNoteOp
}

object DeskNotes {
    @OptIn(ExperimentalSerializationApi::class)
    private val pretty = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun isName(name: String?): Boolean = name != null && name.endsWith(".json", ignoreCase = true)

    /** 是不是一张便签:顶层有字符串 title 和 items 数组,每条都有字符串 text */
    fun parse(text: String): DeskNote? {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val title = root["title"] as? JsonPrimitive ?: return null
        if (!title.isString) return null
        val items = root["items"] as? JsonArray ?: return null
        val ok = items.all { e -> (e as? JsonObject)?.get("text").let { it is JsonPrimitive && it.isString } }
        return if (ok) DeskNote(root) else null
    }

    fun serialize(note: DeskNote): String = pretty.encodeToString(JsonObject.serializer(), note.raw) + "\n"

    /** 在 note 上做一步操作。找不到要改的那一条(别处已经改了或删了)返回 null */
    fun apply(note: DeskNote, op: DeskNoteOp): DeskNote? {
        val items = (note.raw["items"] as JsonArray).map { it as JsonObject }.toMutableList()
        fun withItems() = DeskNote(JsonObject(note.raw + ("items" to JsonArray(items))))
        when (op) {
            is DeskNoteOp.Title -> {
                val t = op.title.replace(Regex("\\s*\\n\\s*"), " ").trim().ifEmpty { "ToDo" }
                return DeskNote(JsonObject(note.raw + ("title" to JsonPrimitive(t))))
            }
            is DeskNoteOp.Add -> {
                val t = op.text.trim()
                if (t.isNotEmpty()) items += JsonObject(mapOf("text" to JsonPrimitive(t), "done" to JsonPrimitive(false)))
                return withItems()
            }
            is DeskNoteOp.Toggle -> {
                val i = locate(items, op.index, op.text).takeIf { it >= 0 } ?: return null
                items[i] = JsonObject(items[i] + ("done" to JsonPrimitive(op.done)))
            }
            is DeskNoteOp.Remove -> {
                val i = locate(items, op.index, op.text).takeIf { it >= 0 } ?: return null
                items.removeAt(i)
            }
            is DeskNoteOp.Edit -> {
                val i = locate(items, op.index, op.text).takeIf { it >= 0 } ?: return null
                val t = op.newText.trim()
                // 清空文字 = 删掉这一条,和桌面端一样
                if (t.isEmpty()) items.removeAt(i) else items[i] = JsonObject(items[i] + ("text" to JsonPrimitive(t)))
            }
        }
        return withItems()
    }

    /** 先看原位置是不是那一条,不是就按文字找第一条;找不到返回 -1 */
    private fun locate(items: List<JsonObject>, index: Int, text: String): Int {
        if (index in items.indices && items[index].str("text") == text) return index
        return items.indexOfFirst { it.str("text") == text }
    }

    /** 提醒时间的说法,和桌面端一致:今天 → 「15:00」,明天 → 「明天 15:00」,其余 → 「11月21日 15:00」(跨年带年份) */
    fun formatRemind(remind: String?, now: LocalDateTime = LocalDateTime.now()): String {
        val m = Regex("""^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})""").find(remind.orEmpty()) ?: return ""
        val (y, mo, d, hh, mm) = m.destructured
        val day = runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull() ?: return ""
        val hm = "$hh:$mm"
        return when (ChronoUnit.DAYS.between(now.toLocalDate(), day)) {
            0L -> hm
            1L -> "明天 $hm"
            else -> if (day.year == now.year) "${day.monthValue}月${day.dayOfMonth}日 $hm" else "${day.year}-${day.monthValue}-${day.dayOfMonth} $hm"
        }
    }

    /** 条目里的链接(http(s):// 或 www. 开头,到空白或中文标点为止,和桌面端一致):返回 (起点, 终点, 地址) */
    fun links(text: String): List<Triple<Int, Int, String>> {
        val out = mutableListOf<Triple<Int, Int, String>>()
        val re = Regex("""(?i)(https?://|www\.)[^\s<>"'，。；：、！？（）【】《》「」]+""")
        for (m in re.findAll(text)) {
            var raw = m.value
            while (raw.isNotEmpty() && raw.last() in ".,;:!?)]}") raw = raw.dropLast(1)
            if (raw.length < 8) continue
            out += Triple(m.range.first, m.range.first + raw.length, if (raw.startsWith("www.", ignoreCase = true)) "http://$raw" else raw)
        }
        return out
    }
}

/** 一步存下来的结果 */
sealed interface DeskNoteSaveResult {
    data object Saved : DeskNoteSaveResult

    /** 那一条在别处已经改没了:放弃这一步 */
    data object Gone : DeskNoteSaveResult

    data class Failed(val error: Throwable?) : DeskNoteSaveResult
}

/**
 * 便签的写回:一步步存(后一步基于前一步存完的内容),存的时候带上服务器上那份的 sha256;
 * 别处(桌面端)改过了就重新读,把这一步在最新内容上重放再存,不会盖掉别处的改动。和网页端 createDeskNoteSync 一样。
 */
class DeskNoteSync(
    private val read: suspend () -> Pair<String, String?>,
    private val write: suspend (content: String, baseSha256: String?) -> String?,
    private val isConflict: (Throwable) -> Boolean,
) {
    /** 服务器上最后一份已知内容 */
    @Volatile
    var server: DeskNote? = null
        private set
    private var base: String? = null
    private val mutex = Mutex()

    /** 读最新的;不是便签返回 false */
    suspend fun load(): Boolean {
        val (content, sha) = read()
        val note = DeskNotes.parse(content) ?: return false
        server = note
        base = sha
        return true
    }

    private suspend fun reloadQuietly() {
        try {
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // 读不到:留着现在的,下一步再试
        }
    }

    suspend fun persist(op: DeskNoteOp): DeskNoteSaveResult = mutex.withLock {
        repeat(MAX_ATTEMPTS) {
            val current = server ?: return@withLock DeskNoteSaveResult.Gone
            val target = DeskNotes.apply(current, op) ?: return@withLock DeskNoteSaveResult.Gone
            try {
                base = write(DeskNotes.serialize(target), base)
                server = target
                return@withLock DeskNoteSaveResult.Saved
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                reloadQuietly()
                if (!isConflict(e)) return@withLock DeskNoteSaveResult.Failed(e)
                // 别处改过了:拿到最新的,下一轮重放这一步
            }
        }
        DeskNoteSaveResult.Failed(null)
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

private fun String?.hexColor(): Long? =
    this?.takeIf { Regex("^#[0-9a-fA-F]{6}$").matches(it) }?.substring(1)?.toLong(16)
