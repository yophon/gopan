package xyz.yophon.gopan.core.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import xyz.yophon.gopan.core.prefs.DriveSortOrder

class NodeListingTest {

    private fun file(id: String, name: String = id, size: Long? = 0, updatedAt: String = "2026-10-14T00:00:00Z", parentId: String? = null) =
        Node(id = id, parentId = parentId, name = name, kind = NodeKind.FILE, size = size, updatedAt = updatedAt)

    private fun folder(id: String, name: String = id, updatedAt: String = "2026-10-14T00:00:00Z", parentId: String? = null) =
        Node(id = id, parentId = parentId, name = name, kind = NodeKind.FOLDER, updatedAt = updatedAt)

    @Test
    fun `sortNodes keeps folders first and sorts by name both ways`() {
        val items = listOf(file("b"), folder("z"), file("a"), folder("y"))

        assertThat(sortNodes(items, DriveSortOrder.NAME, desc = false).map { it.id }).containsExactly("y", "z", "a", "b").inOrder()
        assertThat(sortNodes(items, DriveSortOrder.NAME, desc = true).map { it.id }).containsExactly("z", "y", "b", "a").inOrder()
    }

    @Test
    fun `sortNodes by size puts missing sizes first ascending and last descending`() {
        val items = listOf(file("big", size = 300), file("none", size = null), file("small", size = 10), folder("dir"))

        assertThat(sortNodes(items, DriveSortOrder.SIZE, desc = false).map { it.id }).containsExactly("dir", "none", "small", "big").inOrder()
        assertThat(sortNodes(items, DriveSortOrder.SIZE, desc = true).map { it.id }).containsExactly("dir", "big", "small", "none").inOrder()
    }

    @Test
    fun `sortNodes by updatedAt parses time and breaks ties by id`() {
        val items = listOf(
            file("old", updatedAt = "2026-01-01T00:00:00Z"),
            file("new", updatedAt = "2026-10-14T08:00:00+08:00"), // 00:00Z
            file("new2", updatedAt = "2026-10-14T00:00:00Z"),
            file("bad", updatedAt = "garbage"),
        )

        // new 与 new2 同一时刻:按 id 升序兜底
        assertThat(sortNodes(items, DriveSortOrder.UPDATED_AT, desc = true).map { it.id }).containsExactly("new", "new2", "old", "bad").inOrder()
        assertThat(sortNodes(items, DriveSortOrder.UPDATED_AT, desc = false).map { it.id }).containsExactly("bad", "old", "new", "new2").inOrder()
    }

    @Test
    fun `mergePages appends and drops duplicate ids`() {
        val first = listOf(file("a"), file("b"))
        val next = listOf(file("b", name = "b-again"), file("c"), file("c"))

        val merged = mergePages(first, next)

        assertThat(merged.map { it.id }).containsExactly("a", "b", "c").inOrder()
        assertThat(merged[1].name).isEqualTo("b") // 保留先到的那份
        assertThat(mergePages(first, emptyList())).isSameInstanceAs(first)
    }

    @Test
    fun `resolveChain walks parentId up to the root`() {
        val known = mapOf(
            "c" to folder("c", parentId = "b"),
            "b" to folder("b", parentId = "a"),
            "a" to folder("a", parentId = null),
        )

        val result = resolveChain("c") { known[it] }

        assertThat(result).isInstanceOf(ChainResult.Complete::class.java)
        assertThat((result as ChainResult.Complete).chain.map { it.id }).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun `resolveChain reports the first unknown ancestor`() {
        val known = mapOf("c" to folder("c", parentId = "b"))

        val result = resolveChain("c") { known[it] }

        assertThat(result).isEqualTo(ChainResult.Missing("b", listOf(known.getValue("c"))))
        assertThat(resolveChain("x") { null }).isEqualTo(ChainResult.Missing("x", emptyList()))
    }

    @Test
    fun `resolveChain stops on cycles instead of looping forever`() {
        val known = mapOf(
            "a" to folder("a", parentId = "b"),
            "b" to folder("b", parentId = "a"),
        )

        val result = resolveChain("a") { known[it] }

        assertThat(result).isInstanceOf(ChainResult.Complete::class.java)
        assertThat((result as ChainResult.Complete).chain.map { it.id }).containsExactly("b", "a").inOrder()
    }
}
