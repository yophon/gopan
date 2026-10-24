package xyz.yophon.gopan.feature.offline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import xyz.yophon.gopan.core.offline.OfflineFileEntity

class OfflineLogicTest {

    private fun file(id: String, relDir: String, size: Long = 10) =
        OfflineFileEntity("u", "root", id, "$id.txt", relDir, size, "sha-$id", null, "/offline/root/$id/$id.txt", 0)

    @Test
    fun `the folder item's own name is dropped from the stored directory`() {
        assertThat(offlineRelPath("照片")).isEmpty()
        assertThat(offlineRelPath("照片/2024/春")).isEqualTo("2024/春")
        assertThat(offlineRelPath("")).isEmpty()
    }

    @Test
    fun `a level lists its subfolders with totals underneath and its own files`() {
        val files = listOf(
            file("a", "照片"),
            file("b", "照片/2024", size = 5),
            file("c", "照片/2024/春", size = 7),
            file("d", "照片/Album"),
            file("e", "照片/2024b"),
        )

        val top = listOfflineFolder(files, "")
        assertThat(top.files.map { it.nodeId }).containsExactly("a")
        assertThat(top.folders.map { it.name }).containsExactly("2024", "2024b", "Album").inOrder()
        assertThat(top.folders.first()).isEqualTo(OfflineSubfolder("2024", "2024", files = 2, bytes = 12))

        val year = listOfflineFolder(files, "2024")
        assertThat(year.files.map { it.nodeId }).containsExactly("b")
        assertThat(year.folders).containsExactly(OfflineSubfolder("春", "2024/春", files = 1, bytes = 7))

        val empty = listOfflineFolder(files, "2024/春/没有")
        assertThat(empty.files).isEmpty()
        assertThat(empty.folders).isEmpty()
    }
}
