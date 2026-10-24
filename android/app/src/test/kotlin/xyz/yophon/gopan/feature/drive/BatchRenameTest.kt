package xyz.yophon.gopan.feature.drive

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 与网页端 batchRename.test.ts 同样的用例:两端规则一致 */
class BatchRenameTest {
    private fun file(id: String, name: String) = NodeLike(id, name, isFolder = false)
    private fun folder(id: String, name: String) = NodeLike(id, name, isFolder = true)

    @Test
    fun `the extension is kept apart only for files with a real one`() {
        assertThat(BatchRename.split("a.tar.gz", false)).isEqualTo("a.tar" to "gz")
        assertThat(BatchRename.split(".bashrc", false)).isEqualTo(".bashrc" to "")
        assertThat(BatchRename.split("README", false)).isEqualTo("README" to "")
        assertThat(BatchRename.split("v1.2", true)).isEqualTo("v1.2" to "")
    }

    @Test
    fun `templates number with padded hashes and star as the old name`() {
        val rule = RenameRule.Template("第##集", 1)
        assertThat(BatchRename.renameOne(file("a", "xx.mkv"), rule, 0)).isEqualTo("第01集.mkv")
        assertThat(BatchRename.renameOne(file("a", "xx.mkv"), rule, 11)).isEqualTo("第12集.mkv")
        assertThat(BatchRename.renameOne(file("a", "xx.mkv"), RenameRule.Template("#", 99), 1)).isEqualTo("100.mkv")
        assertThat(BatchRename.renameOne(file("a", "IMG.jpg"), RenameRule.Template("2024-* (#)", 1), 0)).isEqualTo("2024-IMG (1).jpg")
        assertThat(BatchRename.renameOne(folder("d", "a.b"), RenameRule.Template("*_##", 1), 2)).isEqualTo("a.b_03")
    }

    @Test
    fun `replace touches the name but not the extension, extension changes files only`() {
        assertThat(BatchRename.renameOne(file("a", "mp4-剪辑mp4.mp4"), RenameRule.Replace("mp4", ""), 0)).isEqualTo("-剪辑.mp4")
        assertThat(BatchRename.renameOne(file("a", "abc.txt"), RenameRule.Replace("", "x"), 0)).isEqualTo("abc.txt")
        assertThat(BatchRename.renameOne(file("a", "a.jpeg"), RenameRule.Extension(".jpg"), 0)).isEqualTo("a.jpg")
        assertThat(BatchRename.renameOne(file("a", "a.txt"), RenameRule.Extension(""), 0)).isEqualTo("a")
        assertThat(BatchRename.renameOne(file("a", "notes"), RenameRule.Extension("md"), 0)).isEqualTo("notes.md")
        assertThat(BatchRename.renameOne(folder("d", "dir"), RenameRule.Extension("md"), 0)).isEqualTo("dir")
    }

    @Test
    fun `the plan flags duplicates, clashes and invalid names, and allows swaps`() {
        val items = listOf(file("a", "a.txt"), file("b", "b.txt"), file("c", "c.txt"))
        val same = BatchRename.plan(items, RenameRule.Template("同", 1))
        assertThat(same.all { it.problem == RenameProblem.DUPLICATE }).isTrue()
        assertThat(BatchRename.canApply(same)).isFalse()

        val plan = BatchRename.plan(items, RenameRule.Template("#", 1), listOf("1.txt", "a.txt", "其它.txt"))
        assertThat(plan.map { it.newName }).containsExactly("1.txt", "2.txt", "3.txt").inOrder()
        assertThat(plan[0].problem).isEqualTo(RenameProblem.EXISTS)

        assertThat(BatchRename.plan(listOf(file("a", "a.txt")), RenameRule.Replace("a", "x/y"))[0].problem).isEqualTo(RenameProblem.INVALID)

        val swap = BatchRename.plan(listOf(file("a", "1.txt"), file("b", "2.txt")), RenameRule.Template("#", 2), listOf("1.txt", "2.txt"))
        assertThat(swap.map { it.newName }).containsExactly("2.txt", "3.txt").inOrder()
        assertThat(BatchRename.canApply(swap)).isTrue()

        assertThat(BatchRename.canApply(BatchRename.plan(items, null))).isFalse()
    }

    @Test
    fun `names follow the server limits`() {
        assertThat(BatchRename.isValid("")).isFalse()
        assertThat(BatchRename.isValid("..")).isFalse()
        assertThat(BatchRename.isValid("a/b")).isFalse()
        assertThat(BatchRename.isValid("好".repeat(85))).isTrue()
        assertThat(BatchRename.isValid("好".repeat(86))).isFalse()
    }
}
