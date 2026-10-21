package xyz.yophon.gopan.feature.archive

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.NodeKind
import xyz.yophon.gopan.core.data.NodePreview
import xyz.yophon.gopan.core.data.PreviewKind
import xyz.yophon.gopan.core.data.archive.ArchiveFlags
import xyz.yophon.gopan.core.data.archive.ArchiveStatus
import xyz.yophon.gopan.core.data.archive.EntryKind
import xyz.yophon.gopan.core.data.archive.archiveParentPath
import xyz.yophon.gopan.core.data.archive.entryKind
import xyz.yophon.gopan.core.data.archive.isArchiveName
import xyz.yophon.gopan.core.data.previewKindOf
import xyz.yophon.gopan.feature.archive.FakeArchiveRepository.Companion.archiveInfo
import xyz.yophon.gopan.feature.archive.FakeArchiveRepository.Companion.dir
import xyz.yophon.gopan.feature.archive.FakeArchiveRepository.Companion.file
import xyz.yophon.gopan.feature.drive.DRIVE_ACTIONS
import xyz.yophon.gopan.feature.drive.FileCategory
import xyz.yophon.gopan.feature.drive.NodeAction
import xyz.yophon.gopan.feature.drive.SEARCH_ACTIONS
import xyz.yophon.gopan.feature.drive.UiText
import xyz.yophon.gopan.feature.drive.appliesTo
import xyz.yophon.gopan.feature.drive.fileCategory

class ArchiveRulesTest {

    @Test
    fun `breadcrumbs and the parent path inside the archive`() {
        assertThat(archiveCrumbs("")).isEmpty()
        assertThat(archiveCrumbs("a/b/c")).containsExactly(
            ArchiveCrumb("a", "a"),
            ArchiveCrumb("b", "a/b"),
            ArchiveCrumb("c", "a/b/c"),
        ).inOrder()
        assertThat(archiveParentPath("a/b/c")).isEqualTo("a/b")
        assertThat(archiveParentPath("a")).isEmpty()
        assertThat(archiveParentPath("")).isEmpty()
        assertThat(formatLabel("tar.gz")).isEqualTo("TAR.GZ")
    }

    @Test
    fun `flag labels match the web, unknown flags show as they are`() {
        assertThat((flagLabel(ArchiveFlags.ENCRYPTED) as UiText.Res).id).isEqualTo(R.string.archive_flag_encrypted)
        assertThat((flagLabel(ArchiveFlags.UNSAFE) as UiText.Res).id).isEqualTo(R.string.archive_flag_unsafe)
        assertThat((flagLabel(ArchiveFlags.LINK) as UiText.Res).id).isEqualTo(R.string.archive_flag_link)
        assertThat((flagLabel(ArchiveFlags.UNSUPPORTED) as UiText.Res).id).isEqualTo(R.string.archive_flag_unsupported)
        assertThat(flagLabel("weird")).isEqualTo(UiText.Plain("weird"))
        assertThat(flagLabel(null)).isNull()
        assertThat(flagLabel("")).isNull()
    }

    @Test
    fun `archive names follow the server rule and only the first rar volume counts`() {
        for (name in listOf("a.zip", "B.7Z", "c.rar", "d.tar.gz", "e.tgz", "f.tar.xz", "g.tar.bz2", "h.tbz2", "i.tar", "j.part1.rar", "k.part01.rar")) {
            assertWithMessage(name).that(isArchiveName(name)).isTrue()
        }
        for (name in listOf("a.docx", "b.gz", "c.part2.rar", "d.part10.rar", "e.7z.001", "zip", "f.apk", "g.zip.txt")) {
            assertWithMessage(name).that(isArchiveName(name)).isFalse()
        }
    }

    @Test
    fun `entry view kind goes by extension`() {
        assertThat(entryKind("a.PNG")).isEqualTo(EntryKind.IMAGE)
        assertThat(entryKind("b.svg")).isEqualTo(EntryKind.IMAGE)
        assertThat(entryKind("c.pdf")).isEqualTo(EntryKind.PDF)
        assertThat(entryKind("d.md")).isEqualTo(EntryKind.TEXT)
        assertThat(entryKind("Makefile")).isEqualTo(EntryKind.TEXT)
    }

    @Test
    fun `what a tap does and why a file cannot be opened`() {
        assertThat(dir(0, "docs").tapAction()).isEqualTo(EntryTap.OPEN_DIR)
        assertThat(dir(0, "docs").block()).isNull()
        assertThat(file(1, "a.png").tapAction()).isEqualTo(EntryTap.PREVIEW)
        assertThat(file(2, "a.exe", previewable = false).tapAction()).isEqualTo(EntryTap.FILE_ACTIONS)

        val flagged = file(3, "secret.txt", flag = ArchiveFlags.UNSAFE)
        assertThat(flagged.selectable).isFalse()
        assertThat(flagged.block()).isEqualTo(EntryBlock.FLAGGED)
        assertThat(flagged.tapAction()).isEqualTo(EntryTap.BLOCKED)
        assertThat((flagged.blockMessage() as UiText.Res).id).isEqualTo(R.string.archive_flag_msg_unsafe)

        val big = file(4, "big.iso", size = 101L * 1024 * 1024, previewable = false, downloadable = false)
        assertThat(big.block()).isEqualTo(EntryBlock.TOO_LARGE)
        assertThat((big.blockMessage() as UiText.Res).id).isEqualTo(R.string.archive_entry_too_large)

        val deep = file(5, "deep.txt", size = 10, previewable = false, downloadable = false)
        assertThat(deep.block()).isEqualTo(EntryBlock.TOO_DEEP)
        assertThat(deep.tapAction()).isEqualTo(EntryTap.BLOCKED)
        // 能勾选:解压不受预览限度的约束
        assertThat(deep.selectable).isTrue()
    }

    @Test
    fun `the extract button label`() {
        assertThat(extractLabel("", 0)).isEqualTo(ExtractLabel.All)
        assertThat(extractLabel("docs", 0)).isEqualTo(ExtractLabel.CurrentDir)
        assertThat(extractLabel("docs", 3)).isEqualTo(ExtractLabel.Selected(3))
        assertThat((ExtractLabel.Selected(3).text as UiText.Res).id).isEqualTo(R.string.archive_extract_selected)
        assertThat((ExtractLabel.All.text as UiText.Res).id).isEqualTo(R.string.archive_extract_all)
        assertThat((ExtractLabel.CurrentDir.text as UiText.Res).id).isEqualTo(R.string.archive_extract_dir)
    }

    @Test
    fun `the preview summary for each archive state`() {
        assertThat(archiveInfo(ArchiveStatus.PENDING).summary()).isEqualTo(ArchiveSummary.Pending)
        assertThat(archiveInfo(ArchiveStatus.DONE, format = "zip", fileCount = 12, totalSize = 2048).summary())
            .isEqualTo(ArchiveSummary.Ready("ZIP", 12, 2048, truncated = false, encrypted = false))
        assertThat(archiveInfo(ArchiveStatus.TRUNCATED, format = "7z", encrypted = true).summary())
            .isEqualTo(ArchiveSummary.Ready("7Z", 3, 4096, truncated = true, encrypted = true))
        assertThat(archiveInfo(ArchiveStatus.UNSUPPORTED, error = "这种 rar 读不了").summary())
            .isEqualTo(ArchiveSummary.Unavailable("这种 rar 读不了"))
        assertThat(archiveInfo(ArchiveStatus.FAILED, error = " ").summary()).isEqualTo(ArchiveSummary.Unavailable(null))
    }

    @Test
    fun `unknown preview kinds from a newer server fall back to none`() {
        assertThat(previewKindOf("ARCHIVE")).isEqualTo(PreviewKind.ARCHIVE)
        assertThat(previewKindOf("HOLOGRAM")).isEqualTo(PreviewKind.NONE)
        assertThat(fileCategory(NodeKind.FILE, "x.bin", null, PreviewKind.ARCHIVE)).isEqualTo(FileCategory.ARCHIVE)
    }

    @Test
    fun `the long press sheet offers extraction only for archives`() {
        val zip = Node(id = "z", parentId = "p", name = "a.zip", kind = NodeKind.FILE)
        val indexed = Node(id = "x", name = "download", kind = NodeKind.FILE, preview = NodePreview(PreviewKind.ARCHIVE))
        val doc = Node(id = "d", name = "a.docx", kind = NodeKind.FILE)
        val folder = Node(id = "f", name = "x.zip", kind = NodeKind.FOLDER)
        assertThat(isArchiveNode(zip)).isTrue()
        assertThat(isArchiveNode(indexed)).isTrue()
        assertThat(isArchiveNode(doc)).isFalse()
        assertThat(isArchiveNode(folder)).isFalse()
        for (action in listOf(NodeAction.EXTRACT_HERE, NodeAction.EXTRACT_TO)) {
            assertThat(action.appliesTo(zip)).isTrue()
            assertThat(action.appliesTo(doc)).isFalse()
            assertThat(action.appliesTo(folder)).isFalse()
            assertThat(DRIVE_ACTIONS).contains(action)
            assertThat(SEARCH_ACTIONS).contains(action)
        }
    }
}
