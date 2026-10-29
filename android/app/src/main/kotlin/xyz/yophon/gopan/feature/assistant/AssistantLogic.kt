package xyz.yophon.gopan.feature.assistant

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import xyz.yophon.gopan.core.assistant.AiProvider

/** 助手页面的纯逻辑(doc/33 八之 2),单测直接引 */
object AssistantLogic {
    /** 这台设备上次选的「服务 id|模型」;不在了就用第一个服务的默认模型 */
    fun resolvePick(providers: List<AiProvider>, raw: String): Pair<AiProvider, String>? {
        val id = raw.substringBefore('|', "")
        val model = raw.substringAfter('|', "")
        val provider = providers.firstOrNull { it.id == id } ?: providers.firstOrNull() ?: return null
        val chosen = if (model in provider.models) model else provider.models.firstOrNull() ?: return null
        return provider to chosen
    }

    fun pickValue(provider: AiProvider, model: String) = "${provider.id}|$model"

    /** 交给模型的「你在看什么」,与网页 state.ts 的 describeContext 同一个说法 */
    fun contextText(path: String, folderId: String?, selection: List<Selected>): String {
        val lines = mutableListOf("用户正在看:$path" + if (folderId != null) "(文件夹 id $folderId)" else "(根目录)")
        if (selection.isNotEmpty()) {
            lines += "选中了 ${selection.size} 项:"
            selection.take(50).forEach { lines += "- ${it.name}(${if (it.folder) "文件夹" else "文件"},id ${it.id})" }
            if (selection.size > 50) lines += "……还有 ${selection.size - 50} 项"
        }
        return lines.joinToString("\n")
    }

    /** 面板底下那一行:「/旅行 · 选中 3 项」 */
    fun contextLabel(path: String, count: Int): String {
        val where = if (path == "/") "我的文件" else path
        return if (count > 0) "$where · 选中 $count 项" else where
    }

    /** 模型服务表单里「一行一个」的模型 */
    fun parseModels(raw: String): List<String> = raw.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    data class Selected(val id: String, val name: String, val folder: Boolean)

    // ---- 回答里引用的文件(doc/35 六,与网页 assistant/refs.ts 同一套规矩) ----

    /** [id] 是节点 id(小写),或以 / 开头的网盘路径(模型手里没有 id 时写路径) */
    data class FileRef(val name: String, val id: String) {
        val isPath: Boolean get() = id.startsWith("/")
    }

    sealed interface ReplyBlock {
        data class Md(val text: String) : ReplyBlock
        data class Cards(val refs: List<FileRef>) : ReplyBlock
    }

    internal val REF = Regex("""\[([^\]\n]+)\]\(gopan:([0-9a-fA-F-]{36}|/[^)\n]*)\)""")

    /** 引用的键:id 转小写,路径去掉结尾的 / */
    internal fun refKey(raw: String): String = if (raw.startsWith("/")) raw.replace(Regex("""(.)/+$"""), "$1") else raw.lowercase()

    fun refsIn(text: String): List<FileRef> = REF.findAll(text).map { FileRef(it.groupValues[1], refKey(it.groupValues[2])) }.toList()

    /** 去掉引用、列表记号与分隔符后什么都不剩:这一段只是在列文件 */
    private fun onlyRefs(paragraph: String): Boolean =
        refsIn(paragraph).isNotEmpty() &&
            paragraph.split('\n').all { line ->
                line.replace(REF, "").replace(Regex("""^\s*(?:[-*•]|\d+[.)])\s*"""), "").replace(Regex("""[\s,，、;；。]"""), "").isEmpty()
            }

    /** 按空行切段(代码块里的空行不算),只列文件的段落变成卡片,其余原样当 Markdown */
    fun splitReply(text: String): List<ReplyBlock> {
        val paragraphs = mutableListOf<String>()
        var cur = mutableListOf<String>()
        var fenced = false
        for (line in text.replace("\r\n", "\n").split('\n')) {
            if (line.trimStart().startsWith("```")) fenced = !fenced
            if (!fenced && line.isBlank()) {
                if (cur.isNotEmpty()) paragraphs += cur.joinToString("\n")
                cur = mutableListOf()
            } else {
                cur += line
            }
        }
        if (cur.isNotEmpty()) paragraphs += cur.joinToString("\n")
        val out = mutableListOf<ReplyBlock>()
        for (p in paragraphs) {
            val last = out.lastOrNull()
            if (onlyRefs(p)) {
                if (last is ReplyBlock.Cards) out[out.size - 1] = ReplyBlock.Cards(last.refs + refsIn(p)) else out += ReplyBlock.Cards(refsIn(p))
            } else if (last is ReplyBlock.Md) {
                out[out.size - 1] = ReplyBlock.Md(last.text + "\n\n" + p)
            } else {
                out += ReplyBlock.Md(p)
            }
        }
        return out
    }
}

/**
 * 助手回答里常见的 Markdown → 带样式的文字:粗体、行内代码、列表、标题;代码块等宽。
 * 只认这几样,别的原样显示(与网页 utils/miniMarkdown.ts 同一范围)。
 * [ref] 画回答里引用的文件 [名字](gopan:<id>)(doc/35 六);没给就只写名字。
 */
fun miniMarkdown(src: String, ref: (AnnotatedString.Builder.(AssistantLogic.FileRef) -> Unit)? = null): AnnotatedString = buildAnnotatedString {
    val lines = src.replace("\r\n", "\n").split('\n')
    var inCode = false
    lines.forEachIndexed { i, raw ->
        if (raw.trimStart().startsWith("```")) {
            inCode = !inCode
            return@forEachIndexed
        }
        if (i > 0 && length > 0) append('\n')
        if (inCode) {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(raw) }
            return@forEachIndexed
        }
        val bullet = Regex("""^\s*[-*]\s+(.*)$""").find(raw)
        val heading = Regex("""^\s*#{1,6}\s+(.*)$""").find(raw)
        when {
            bullet != null -> {
                append("•  ")
                inline(bullet.groupValues[1], ref)
            }
            heading != null -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { inline(heading.groupValues[1], ref) }
            else -> inline(raw, ref)
        }
    }
}

private fun AnnotatedString.Builder.inline(text: String, ref: (AnnotatedString.Builder.(AssistantLogic.FileRef) -> Unit)?) {
    val token = Regex("""`([^`]+)`|\*\*([^*]+)\*\*|""" + AssistantLogic.REF.pattern)
    var at = 0
    for (m in token.findAll(text)) {
        append(text.substring(at, m.range.first))
        val code = m.groups[1]?.value
        val bold = m.groups[2]?.value
        when {
            code != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
            bold != null -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { inline(bold, ref) }
            else -> {
                val r = AssistantLogic.FileRef(m.groups[3]!!.value, AssistantLogic.refKey(m.groups[4]!!.value))
                if (ref != null) ref(r) else append(r.name)
            }
        }
        at = m.range.last + 1
    }
    append(text.substring(at))
}
