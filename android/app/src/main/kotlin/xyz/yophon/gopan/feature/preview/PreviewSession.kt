package xyz.yophon.gopan.feature.preview

import androidx.compose.runtime.Immutable
import xyz.yophon.gopan.core.data.Node
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 预览里左右翻的一项(从列表带过来,打开前就知道名字,翻页时标题不闪)。
 * [thumbKey]:列表缩略图的缓存 key,大图加载出来之前先拿它垫底。
 * [pdfPage]:按 PDF 显示(含 Office 派生)时先翻到的页,从 1 起(内容搜索的命中页);null 从第一页看。
 */
@Immutable
data class PreviewItem(
    val nodeId: String,
    val name: String,
    val size: Long?,
    val thumbKey: String? = null,
    val pdfPage: Int? = null,
)

fun Node.toPreviewItem(pdfPage: Int? = null): PreviewItem =
    PreviewItem(id, name, size, imageCacheKey.takeIf { hasThumb }, pdfPage?.takeIf { it > 0 })

/**
 * 当前预览会话:从哪个列表打开、可以左右翻哪些文件。导航参数只带起点 nodeId,
 * 列表放在这里(可能有几百项,不适合塞进路由)。进程被回收后列表丢了,就只看当时那一个文件。
 */
@Singleton
class PreviewSessionStore @Inject constructor() {
    @Volatile
    private var items: List<PreviewItem> = emptyList()

    /** [nodes] 里的文件夹会被去掉;[pdfPages]:节点 id → 打开时 PDF 先翻到的页(从 1 起),没有的从第一页看 */
    fun open(nodes: List<Node>, pdfPages: Map<String, Int> = emptyMap()) {
        items = nodes.filter { !it.isFolder }.map { it.toPreviewItem(pdfPages[it.id]) }
    }

    /** 直接给出翻页列表(迷你播放条:整个播放队列) */
    fun openItems(list: List<PreviewItem>) {
        items = list
    }

    /** 含 [startId] 的会话列表;不含(进程重建后)就只有它自己 */
    fun itemsFor(startId: String): List<PreviewItem> {
        val current = items
        return if (current.any { it.nodeId == startId }) current else listOf(PreviewItem(startId, "", null))
    }
}
