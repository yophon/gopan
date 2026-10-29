package xyz.yophon.gopan.feature.drive

import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.ui.nav.Routes
import xyz.yophon.gopan.feature.assistant.AssistantLogic

/** 从文件页问助手(doc/33 一):把当前文件夹与选中的文件写成给模型看的上下文 */
internal fun assistantRoute(crumbs: List<Crumb>, selected: List<Node>): Routes.Assistant {
    val path = "/" + crumbs.joinToString("/") { it.name }
    val picked = selected.map { AssistantLogic.Selected(it.id, it.name, it.isFolder) }
    return Routes.Assistant(
        context = AssistantLogic.contextText(path, crumbs.lastOrNull()?.id, picked),
        label = AssistantLogic.contextLabel(path, picked.size),
    )
}
