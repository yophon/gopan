package xyz.yophon.gopan.feature.preview

import androidx.compose.runtime.Immutable
import xyz.yophon.gopan.core.data.Node
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 预览里左右翻的一项(从列表带过来,打开前就知道名字,翻页时标题不闪)。
 * [thumbKey]:列表缩略图的缓存 key,大图加载出来之前先拿它垫底。
 */
@Immutable
data class PreviewItem(val nodeId: String, val name: String, val size: Long?, val thumbKey: String? = null)

fun Node.toPreviewItem(): PreviewItem = PreviewItem(id, name, size, imageCacheKey.takeIf { hasThumb })

/**
 * 当前预览会话:从哪个列表打开、可以左右翻哪些文件。导航参数只带起点 nodeId,
 * 列表放在这里(可能有几百项,不适合塞进路由)。进程被回收后列表丢了,就只看当时那一个文件。
 */
@Singleton
class PreviewSessionStore @Inject constructor() {
    @Volatile
    private var items: List<PreviewItem> = emptyList()

    /** [nodes] 里的文件夹会被去掉 */
    fun open(nodes: List<Node>) {
        items = nodes.filter { !it.isFolder }.map { it.toPreviewItem() }
    }

    /** 含 [startId] 的会话列表;不含(进程重建后)就只有它自己 */
    fun itemsFor(startId: String): List<PreviewItem> {
        val current = items
        return if (current.any { it.nodeId == startId }) current else listOf(PreviewItem(startId, "", null))
    }
}
