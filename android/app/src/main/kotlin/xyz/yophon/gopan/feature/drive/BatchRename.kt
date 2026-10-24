package xyz.yophon.gopan.feature.drive

import androidx.compose.runtime.Immutable

/**
 * 批量改名的规则(doc/27):在客户端算出每一项的新名字,服务端只收「哪个改成什么」。
 * 网页端 web/src/utils/batchRename.ts 是同一套规则,改这里记得一起改。
 */
sealed interface RenameRule {
    /** 按模板命名:`*` 是原来的名字(不含扩展名),`#` 是序号(几个 # 就至少几位,不够补 0);扩展名保留 */
    data class Template(val template: String, val start: Int) : RenameRule

    /** 名字里(不含扩展名)的 [find] 全部换成 [replace],区分大小写 */
    data class Replace(val find: String, val replace: String) : RenameRule

    /** 文件的扩展名换成 [ext](空 = 去掉扩展名);文件夹不动 */
    data class Extension(val ext: String) : RenameRule
}

enum class RenameProblem { INVALID, DUPLICATE, EXISTS }

@Immutable
data class RenamePlanItem(
    val id: String,
    val oldName: String,
    val newName: String,
    val changed: Boolean,
    val problem: RenameProblem? = null,
)

object BatchRename {
    private val HASHES = Regex("#+")

    /** 拆成主名与扩展名:文件夹、以点开头的名字(.bashrc)、没有点的名字没有扩展名 */
    fun split(name: String, isFolder: Boolean): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        if (isFolder || dot <= 0 || dot == name.length - 1) return name to ""
        return name.substring(0, dot) to name.substring(dot + 1)
    }

    /** 与服务端 validateName 一致:非空、不超过 255 字节、不含 / 与 NUL、不是 . 或 .. */
    fun isValid(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name &&
            name.toByteArray(Charsets.UTF_8).size <= 255

    private fun withExt(base: String, ext: String) = if (ext.isEmpty()) base else "$base.$ext"

    /** 一项按规则得到的新名字(还没检查冲突);[index] 是它在这批里的位置(从 0 起) */
    fun renameOne(node: NodeLike, rule: RenameRule, index: Int): String {
        val (base, ext) = split(node.name, node.isFolder)
        return when (rule) {
            is RenameRule.Template -> {
                val n = rule.start + index
                val named = HASHES.replace(rule.template) { m -> n.toString().padStart(m.value.length, '0') }.replace("*", base)
                withExt(named, ext).trim()
            }
            is RenameRule.Replace -> if (rule.find.isEmpty()) node.name else withExt(base.replace(rule.find, rule.replace), ext).trim()
            is RenameRule.Extension -> if (node.isFolder) node.name else withExt(base, rule.ext.trim().trimStart('.'))
        }
    }

    /**
     * 整批的改名计划:按 [items] 的顺序编号;[siblings] 是同一个文件夹里其它条目的名字(提前发现和这批之外的同名)。
     * 这批里原来的名字改完后会腾出来,不算占用。
     */
    fun plan(items: List<NodeLike>, rule: RenameRule?, siblings: Collection<String> = emptyList()): List<RenamePlanItem> {
        val raw = items.mapIndexed { i, node ->
            val newName = if (rule == null) node.name else renameOne(node, rule, i)
            RenamePlanItem(node.id, node.name, newName, changed = newName != node.name)
        }
        val others = siblings.toHashSet().apply { removeAll(items.map { it.name }.toSet()) }
        val count = raw.groupingBy { it.newName }.eachCount()
        return raw.map { p ->
            val problem = when {
                !isValid(p.newName) -> RenameProblem.INVALID
                (count[p.newName] ?: 0) > 1 -> RenameProblem.DUPLICATE
                p.changed && p.newName in others -> RenameProblem.EXISTS
                else -> null
            }
            p.copy(problem = problem)
        }
    }

    /** 能不能提交:没有问题,而且至少有一项变了 */
    fun canApply(plan: List<RenamePlanItem>): Boolean = plan.any { it.changed } && plan.all { it.problem == null }
}

/** 改名规则只看 id、名字、是不是文件夹 */
data class NodeLike(val id: String, val name: String, val isFolder: Boolean)
