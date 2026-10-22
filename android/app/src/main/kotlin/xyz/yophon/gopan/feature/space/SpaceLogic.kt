package xyz.yophon.gopan.feature.space

import androidx.compose.runtime.Immutable
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.space.DuplicateGroup
import xyz.yophon.gopan.core.data.space.SpaceCategory
import xyz.yophon.gopan.core.data.space.SpaceFile
import xyz.yophon.gopan.core.data.space.SpaceFolderLevel
import xyz.yophon.gopan.core.data.space.SpaceOverview
import kotlin.math.roundToInt

/** 总览里的一条类型条:[fraction] 0..1 给进度条,[percent] 是文案 */
@Immutable
data class CategoryBar(val category: SpaceCategory, val files: Long, val bytes: Long, val fraction: Float, val percent: String)

/** 文件夹逐层看的一行:子文件夹或「直接放在这里的文件」 */
@Immutable
data class FolderShare(val node: Node?, val bytes: Long, val fraction: Float, val percent: String)

/** 空间页的纯函数(单测覆盖):类型名、占比、文件夹分布、重复文件的默认勾选与省下的空间 */
object SpaceLogic {

    fun categoryLabel(category: SpaceCategory): String = when (category) {
        SpaceCategory.IMAGE -> "图片"
        SpaceCategory.VIDEO -> "视频"
        SpaceCategory.AUDIO -> "音频"
        SpaceCategory.DOCUMENT -> "文档"
        SpaceCategory.ARCHIVE -> "压缩包"
        SpaceCategory.TEXT -> "代码与文本"
        SpaceCategory.OTHER -> "其他"
    }

    /** 0..1;[total] ≤ 0 时为 0 */
    fun fraction(bytes: Long, total: Long): Float =
        if (total <= 0) 0f else (bytes.coerceAtLeast(0).toDouble() / total).coerceIn(0.0, 1.0).toFloat()

    /** 整数百分比;不到 1% 但不是 0 的显示「<1%」,免得看着像没占地方 */
    fun percentText(bytes: Long, total: Long): String {
        if (total <= 0 || bytes <= 0) return "0%"
        val p = bytes.toDouble() * 100 / total
        return if (p < 1) "<1%" else "${p.roundToInt().coerceAtMost(100)}%"
    }

    /**
     * 七类各一条,大的在前(一样大按固定顺序),没有的也列出来(0 个文件)。
     * 占比的分母是已用:回收站也占着配额,类型合计 + 回收站 = 已用;已用比合计还小(统计滞后)时按合计算。
     */
    fun categoryBars(overview: SpaceOverview): List<CategoryBar> {
        val byType = overview.byType.associateBy { it.category }
        val sum = overview.byType.sumOf { it.bytes.coerceAtLeast(0) } + overview.trashBytes.coerceAtLeast(0)
        val total = maxOf(overview.usedBytes, sum)
        return SpaceCategory.entries
            .map { c ->
                val u = byType[c]
                val bytes = u?.bytes ?: 0
                CategoryBar(c, u?.files ?: 0, bytes, fraction(bytes, total), percentText(bytes, total))
            }
            .sortedWith(compareByDescending<CategoryBar> { it.bytes }.thenBy { it.category.ordinal })
    }

    /** 回收站在已用里占多少(同一个分母) */
    fun trashPercent(overview: SpaceOverview): String {
        val sum = overview.byType.sumOf { it.bytes.coerceAtLeast(0) } + overview.trashBytes.coerceAtLeast(0)
        return percentText(overview.trashBytes, maxOf(overview.usedBytes, sum))
    }

    /**
     * 这一层的分布:子文件夹(服务端已按子树大小排好)+ 最后一行「直接放在这里的文件」(没有就不列)。
     * 占比的分母是这一层的合计。
     */
    fun folderShares(level: SpaceFolderLevel): List<FolderShare> {
        val sizes = level.folders.map { it.subtreeBytes ?: 0 }
        val total = sizes.sum() + level.looseBytes.coerceAtLeast(0)
        val rows = level.folders.mapIndexed { i, n -> FolderShare(n, sizes[i], fraction(sizes[i], total), percentText(sizes[i], total)) }
        val loose = if (level.looseFiles > 0) {
            listOf(FolderShare(null, level.looseBytes, fraction(level.looseBytes, total), percentText(level.looseBytes, total)))
        } else {
            emptyList()
        }
        return rows + loose
    }

    // ---- 勾选 ----

    /** 勾上的那些加起来多大(文件按大小,文件夹按子树) */
    fun selectedBytes(files: List<SpaceFile>, selection: Set<String>): Long =
        files.filter { it.node.id in selection }.sumOf { it.node.size ?: it.node.subtreeBytes ?: 0 }

    /** 重复文件默认勾选:每组除了第一份(最早的,留着)都勾上 */
    fun defaultDuplicateSelection(groups: List<DuplicateGroup>): Set<String> =
        groups.flatMapTo(LinkedHashSet()) { g -> g.files.drop(1).map { it.node.id } }

    /** 勾上的重复文件删掉能省多少:每份都是这组的大小 */
    fun duplicateSelectedBytes(groups: List<DuplicateGroup>, selection: Set<String>): Long =
        groups.sumOf { g -> g.size * g.files.count { it.node.id in selection } }

    fun duplicateSelectedCount(groups: List<DuplicateGroup>, selection: Set<String>): Int =
        groups.sumOf { g -> g.files.count { it.node.id in selection } }

    /** 每一份都勾上了的组数(删掉后这份内容只剩回收站里的):确认时提醒一句 */
    fun fullySelectedGroups(groups: List<DuplicateGroup>, selection: Set<String>): Int =
        groups.count { g -> g.files.isNotEmpty() && g.files.all { it.node.id in selection } }

    /** 新的一页重复文件拼到后面:按指纹去重(翻页期间删过东西时前后两页可能重叠) */
    fun mergeGroups(old: List<DuplicateGroup>, page: List<DuplicateGroup>): List<DuplicateGroup> {
        val seen = old.mapTo(HashSet()) { it.sha256 }
        return old + page.filter { seen.add(it.sha256) }
    }
}
