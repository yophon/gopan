package xyz.yophon.gopan.feature.space

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.NodeKind
import xyz.yophon.gopan.core.data.space.CategoryUsage
import xyz.yophon.gopan.core.data.space.FakeSpaceRepository.Companion.group
import xyz.yophon.gopan.core.data.space.FakeSpaceRepository.Companion.spaceFile
import xyz.yophon.gopan.core.data.space.SpaceCategory
import xyz.yophon.gopan.core.data.space.SpaceFolderLevel
import xyz.yophon.gopan.core.data.space.SpaceOverview

/** 类型名、占比、文件夹分布、重复文件的默认勾选与省下的空间 */
class SpaceLogicTest {

    @Test
    fun `every category has a chinese label`() {
        val labels = SpaceCategory.entries.associateWith(SpaceLogic::categoryLabel)
        assertThat(labels).containsExactly(
            SpaceCategory.IMAGE, "图片",
            SpaceCategory.VIDEO, "视频",
            SpaceCategory.AUDIO, "音频",
            SpaceCategory.DOCUMENT, "文档",
            SpaceCategory.ARCHIVE, "压缩包",
            SpaceCategory.TEXT, "代码与文本",
            SpaceCategory.OTHER, "其他",
        )
        assertThat(SpaceCategory.of("VIDEO")).isEqualTo(SpaceCategory.VIDEO)
        assertThat(SpaceCategory.of("HOLOGRAM")).isNull()
    }

    @Test
    fun `percent and fraction`() {
        assertThat(SpaceLogic.percentText(250, 1000)).isEqualTo("25%")
        assertThat(SpaceLogic.percentText(1, 1000)).isEqualTo("<1%")
        assertThat(SpaceLogic.percentText(0, 1000)).isEqualTo("0%")
        assertThat(SpaceLogic.percentText(5, 0)).isEqualTo("0%")
        assertThat(SpaceLogic.percentText(1005, 1000)).isEqualTo("100%")
        assertThat(SpaceLogic.percentText(995, 1000)).isEqualTo("100%")
        assertThat(SpaceLogic.percentText(994, 1000)).isEqualTo("99%")
        assertThat(SpaceLogic.fraction(250, 1000)).isWithin(1e-6f).of(0.25f)
        assertThat(SpaceLogic.fraction(2000, 1000)).isEqualTo(1f)
        assertThat(SpaceLogic.fraction(-5, 1000)).isEqualTo(0f)
        assertThat(SpaceLogic.fraction(5, 0)).isEqualTo(0f)
    }

    private fun overview(used: Long, trash: Long, vararg usage: CategoryUsage) =
        SpaceOverview(used, 10_000, 1, trash, usage.toList(), 0, 0)

    @Test
    fun `category bars list all seven, biggest first, against used space`() {
        val o = overview(
            1000, 100,
            CategoryUsage(SpaceCategory.IMAGE, 10, 300),
            CategoryUsage(SpaceCategory.VIDEO, 2, 600),
        )
        val bars = SpaceLogic.categoryBars(o)
        assertThat(bars.map { it.category }).containsExactly(
            SpaceCategory.VIDEO, SpaceCategory.IMAGE,
            // 没有的按固定顺序排在后面
            SpaceCategory.AUDIO, SpaceCategory.DOCUMENT, SpaceCategory.ARCHIVE, SpaceCategory.TEXT, SpaceCategory.OTHER,
        ).inOrder()
        assertThat(bars[0].percent).isEqualTo("60%")
        assertThat(bars[0].files).isEqualTo(2)
        assertThat(bars[1].percent).isEqualTo("30%")
        assertThat(bars[1].fraction).isWithin(1e-6f).of(0.3f)
        assertThat(bars.last().percent).isEqualTo("0%")
        // 回收站也占着已用
        assertThat(SpaceLogic.trashPercent(o)).isEqualTo("10%")
    }

    @Test
    fun `category bars fall back to the sum when used lags behind`() {
        val o = overview(0, 0, CategoryUsage(SpaceCategory.TEXT, 1, 50), CategoryUsage(SpaceCategory.AUDIO, 1, 150))
        val bars = SpaceLogic.categoryBars(o)
        assertThat(bars.first { it.category == SpaceCategory.AUDIO }.percent).isEqualTo("75%")
        assertThat(bars.first { it.category == SpaceCategory.TEXT }.percent).isEqualTo("25%")
    }

    private fun folder(id: String, bytes: Long?) = Node(id = id, name = id, kind = NodeKind.FOLDER, subtreeBytes = bytes, subtreeCount = 3)

    @Test
    fun `folder shares include loose files against the level total`() {
        val level = SpaceFolderLevel("/", listOf(folder("a", 600), folder("b", 300), folder("c", null)), looseFiles = 4, looseBytes = 100)
        val shares = SpaceLogic.folderShares(level)
        assertThat(shares.map { it.node?.id }).containsExactly("a", "b", "c", null).inOrder()
        assertThat(shares.map { it.percent }).containsExactly("60%", "30%", "0%", "10%").inOrder()
        assertThat(shares.last().bytes).isEqualTo(100)
        // 没有直接放着的文件就不列那一行
        assertThat(SpaceLogic.folderShares(level.copy(looseFiles = 0, looseBytes = 0)).map { it.node?.id }).containsExactly("a", "b", "c").inOrder()
        assertThat(SpaceLogic.folderShares(SpaceFolderLevel("/", emptyList(), 0, 0))).isEmpty()
    }

    @Test
    fun `duplicates keep the earliest copy by default`() {
        val groups = listOf(group("s1", 100, "a1", "a2", "a3"), group("s2", 10, "b1", "b2"))
        val selection = SpaceLogic.defaultDuplicateSelection(groups)
        assertThat(selection).containsExactly("a2", "a3", "b2").inOrder()
        assertThat(SpaceLogic.duplicateSelectedCount(groups, selection)).isEqualTo(3)
        assertThat(SpaceLogic.duplicateSelectedBytes(groups, selection)).isEqualTo(210)
        assertThat(SpaceLogic.fullySelectedGroups(groups, selection)).isEqualTo(0)
        // 每一份都勾上了:要提醒
        assertThat(SpaceLogic.fullySelectedGroups(groups, selection + "b1")).isEqualTo(1)
        assertThat(groups[0].reclaimable).isEqualTo(200)
    }

    @Test
    fun `next duplicate page is merged without repeats`() {
        val old = listOf(group("s1", 100, "a1", "a2"), group("s2", 10, "b1", "b2"))
        val page = listOf(group("s2", 10, "b1", "b2"), group("s3", 5, "c1", "c2"))
        assertThat(SpaceLogic.mergeGroups(old, page).map { it.sha256 }).containsExactly("s1", "s2", "s3").inOrder()
    }

    @Test
    fun `selected bytes count files by size and folders by subtree`() {
        val files = listOf(spaceFile("a", 100), spaceFile("b", 50), spaceFile("d", folder = true))
        assertThat(SpaceLogic.selectedBytes(files, setOf("a", "b"))).isEqualTo(150)
        assertThat(SpaceLogic.selectedBytes(files, setOf("d"))).isEqualTo(0)
        assertThat(SpaceLogic.selectedBytes(files, emptySet())).isEqualTo(0)
    }
}
