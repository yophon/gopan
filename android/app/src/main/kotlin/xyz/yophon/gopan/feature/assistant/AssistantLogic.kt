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
}

/**
 * 助手回答里常见的 Markdown → 带样式的文字:粗体、行内代码、列表、标题;代码块等宽。
 * 只认这几样,别的原样显示(与网页 utils/miniMarkdown.ts 同一范围)。
 */
fun miniMarkdown(src: String): AnnotatedString = buildAnnotatedString {
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
                inline(bullet.groupValues[1])
            }
            heading != null -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { inline(heading.groupValues[1]) }
            else -> inline(raw)
        }
    }
}

private fun AnnotatedString.Builder.inline(text: String) {
    val token = Regex("""`([^`]+)`|\*\*([^*]+)\*\*""")
    var at = 0
    for (m in token.findAll(text)) {
        append(text.substring(at, m.range.first))
        val code = m.groups[1]?.value
        if (code != null) {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
        } else {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(m.groups[2]!!.value) }
        }
        at = m.range.last + 1
    }
    append(text.substring(at))
}
