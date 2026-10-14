package xyz.yophon.gopan.feature.drive

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import xyz.yophon.gopan.core.data.NodeKind
import xyz.yophon.gopan.core.data.PreviewKind
import xyz.yophon.gopan.core.prefs.DriveSort
import xyz.yophon.gopan.core.prefs.DriveSortOrder
import xyz.yophon.gopan.core.prefs.DriveViewMode

/** 文件页的纯逻辑:排序/视图 reducer、名称校验、图标分类 */
class DriveLogicTest {

    @Test
    fun `selecting the active order flips direction, a new order gets its default direction`() {
        val byName = DriveSort(DriveSortOrder.NAME, desc = false)

        assertThat(DrivePrefsReducer.selectOrder(byName, DriveSortOrder.NAME)).isEqualTo(DriveSort(DriveSortOrder.NAME, desc = true))
        assertThat(DrivePrefsReducer.selectOrder(byName, DriveSortOrder.SIZE)).isEqualTo(DriveSort(DriveSortOrder.SIZE, desc = true))
        assertThat(DrivePrefsReducer.selectOrder(byName, DriveSortOrder.UPDATED_AT)).isEqualTo(DriveSort(DriveSortOrder.UPDATED_AT, desc = true))
        val bySizeDesc = DriveSort(DriveSortOrder.SIZE, desc = true)
        assertThat(DrivePrefsReducer.selectOrder(bySizeDesc, DriveSortOrder.SIZE)).isEqualTo(DriveSort(DriveSortOrder.SIZE, desc = false))
        assertThat(DrivePrefsReducer.selectOrder(bySizeDesc, DriveSortOrder.NAME)).isEqualTo(DriveSort(DriveSortOrder.NAME, desc = false))
    }

    @Test
    fun `view mode toggles between list and grid`() {
        assertThat(DrivePrefsReducer.toggleViewMode(DriveViewMode.LIST)).isEqualTo(DriveViewMode.GRID)
        assertThat(DrivePrefsReducer.toggleViewMode(DriveViewMode.GRID)).isEqualTo(DriveViewMode.LIST)
    }

    @Test
    fun `validateNodeName mirrors the server rules`() {
        assertThat(validateNodeName("报告.pdf")).isNull()
        assertThat(validateNodeName("  a  ")).isNull()
        assertThat(validateNodeName("")).isEqualTo(NameError.EMPTY)
        assertThat(validateNodeName("   ")).isEqualTo(NameError.EMPTY)
        assertThat(validateNodeName("a/b")).isEqualTo(NameError.ILLEGAL_CHAR)
        // 反斜杠服务端与网页端都允许,本地不额外禁
        assertThat(validateNodeName("a\\b")).isNull()
        assertThat(validateNodeName("a\u0000b")).isEqualTo(NameError.ILLEGAL_CHAR)
        assertThat(validateNodeName(".")).isEqualTo(NameError.RESERVED)
        assertThat(validateNodeName("..")).isEqualTo(NameError.RESERVED)
        assertThat(validateNodeName("...")).isNull()
        assertThat(validateNodeName("x".repeat(255))).isNull()
        assertThat(validateNodeName("x".repeat(256))).isEqualTo(NameError.TOO_LONG)
        // 中文按 UTF-8 字节数算
        assertThat(validateNodeName("中".repeat(85))).isNull()
        assertThat(validateNodeName("中".repeat(86))).isEqualTo(NameError.TOO_LONG)
    }

    @Test
    fun `fileCategory prefers preview kind, then mime, then extension`() {
        assertThat(fileCategory(NodeKind.FOLDER, "whatever.zip", "application/zip")).isEqualTo(FileCategory.FOLDER)
        assertThat(fileCategory(NodeKind.FILE, "x.bin", null, PreviewKind.IMAGE)).isEqualTo(FileCategory.IMAGE)
        assertThat(fileCategory(NodeKind.FILE, "x.bin", null, PreviewKind.VIDEO)).isEqualTo(FileCategory.VIDEO)
        assertThat(fileCategory(NodeKind.FILE, "x.bin", null, PreviewKind.AUDIO)).isEqualTo(FileCategory.AUDIO)
        assertThat(fileCategory(NodeKind.FILE, "x.bin", null, PreviewKind.NATIVE_PDF)).isEqualTo(FileCategory.PDF)
        assertThat(fileCategory(NodeKind.FILE, "x.bin", null, PreviewKind.TEXT)).isEqualTo(FileCategory.TEXT)
        assertThat(fileCategory(NodeKind.FILE, "q.xlsx", null, PreviewKind.OFFICE)).isEqualTo(FileCategory.SPREADSHEET)
        assertThat(fileCategory(NodeKind.FILE, "q.pptx", null, PreviewKind.OFFICE)).isEqualTo(FileCategory.PRESENTATION)
        assertThat(fileCategory(NodeKind.FILE, "q.docx", null, PreviewKind.OFFICE)).isEqualTo(FileCategory.DOCUMENT)

        assertThat(fileCategory(NodeKind.FILE, "photo", "image/jpeg")).isEqualTo(FileCategory.IMAGE)
        assertThat(fileCategory(NodeKind.FILE, "clip", "video/mp4; codecs=avc1")).isEqualTo(FileCategory.VIDEO)
        assertThat(fileCategory(NodeKind.FILE, "song", "audio/mpeg")).isEqualTo(FileCategory.AUDIO)
        assertThat(fileCategory(NodeKind.FILE, "doc", "application/pdf")).isEqualTo(FileCategory.PDF)
        assertThat(fileCategory(NodeKind.FILE, "pack", "application/x-7z-compressed")).isEqualTo(FileCategory.ARCHIVE)
        assertThat(fileCategory(NodeKind.FILE, "data.csv", "text/csv")).isEqualTo(FileCategory.SPREADSHEET)
        assertThat(fileCategory(NodeKind.FILE, "notes", "text/plain")).isEqualTo(FileCategory.TEXT)

        assertThat(fileCategory(NodeKind.FILE, "archive.tar.gz", null)).isEqualTo(FileCategory.ARCHIVE)
        assertThat(fileCategory(NodeKind.FILE, "IMG_0001.HEIC", null)).isEqualTo(FileCategory.IMAGE)
        assertThat(fileCategory(NodeKind.FILE, "slides.key", null)).isEqualTo(FileCategory.PRESENTATION)
        assertThat(fileCategory(NodeKind.FILE, "main.kt", "application/octet-stream")).isEqualTo(FileCategory.TEXT)
        assertThat(fileCategory(NodeKind.FILE, "noext", "application/octet-stream")).isEqualTo(FileCategory.OTHER)
        assertThat(fileCategory(NodeKind.FILE, ".hidden", null)).isEqualTo(FileCategory.OTHER)
    }

    @Test
    fun `extensionOf handles dots at the edges`() {
        assertThat(extensionOf("a.TXT")).isEqualTo("txt")
        assertThat(extensionOf("archive.tar.gz")).isEqualTo("gz")
        assertThat(extensionOf(".bashrc")).isEqualTo("")
        assertThat(extensionOf("trailing.")).isEqualTo("")
        assertThat(extensionOf("noext")).isEqualTo("")
    }
}
