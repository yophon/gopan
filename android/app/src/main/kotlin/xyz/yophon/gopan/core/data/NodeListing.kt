package xyz.yophon.gopan.core.data

import xyz.yophon.gopan.core.prefs.DriveSortOrder
import xyz.yophon.gopan.core.ui.format.parseTime

/*
 * 列表相关的纯函数:本地排序(缓存回退时按当前偏好重排)、分页合并(去重)、面包屑链解析。
 * 不碰网络与 IO,直接单测。
 */

/**
 * 与服务端 ListChildren 一致的排序:文件夹永远在前;其后按 [order] / [desc];同值按 id 升序稳定。
 * SIZE 升序时无大小(文件夹)在前,降序时在后;UPDATED_AT 解析失败按最早处理。
 */
fun sortNodes(items: List<Node>, order: DriveSortOrder, desc: Boolean): List<Node> {
    val byField: Comparator<Node> = when (order) {
        DriveSortOrder.NAME -> compareBy { it.name }
        DriveSortOrder.SIZE -> compareBy(nullsFirst()) { it.size }
        DriveSortOrder.UPDATED_AT -> compareBy { parseTime(it.updatedAt)?.toEpochMilli() ?: Long.MIN_VALUE }
    }
    val ordered = if (desc) byField.reversed() else byField
    return items.sortedWith(
        compareByDescending<Node> { it.isFolder }
            .then(ordered)
            .thenBy { it.id },
    )
}

/** 追加下一页:已存在的 id 丢弃(翻页期间列表被改动时服务端可能重复给出同一节点) */
fun mergePages(existing: List<Node>, next: List<Node>): List<Node> {
    if (next.isEmpty()) return existing
    val seen = HashSet<String>(existing.size + next.size)
    val merged = ArrayList<Node>(existing.size + next.size)
    for (n in existing) if (seen.add(n.id)) merged += n
    for (n in next) if (seen.add(n.id)) merged += n
    return merged
}

/** [resolveChain] 的结果 */
sealed interface ChainResult {
    /** 根在前、目标文件夹在后的完整链 */
    data class Complete(val chain: List<Node>) : ChainResult

    /** 链在 [missingId] 处断了(本地不认识这个节点),[known] 是已解析出的下段(目标在最后) */
    data class Missing(val missingId: String, val known: List<Node>) : ChainResult
}

/**
 * 用本地已知节点沿 parentId 回溯到根。遇到环或超过 [maxDepth] 层就当作到根(数据异常不该把界面卡死)。
 */
fun resolveChain(folderId: String, maxDepth: Int = 64, lookup: (String) -> Node?): ChainResult {
    val chain = ArrayList<Node>()
    val seen = HashSet<String>()
    var cur: String? = folderId
    while (cur != null && chain.size < maxDepth && seen.add(cur)) {
        val node = lookup(cur) ?: return ChainResult.Missing(cur, chain.asReversed())
        chain += node
        cur = node.parentId
    }
    return ChainResult.Complete(chain.asReversed())
}
