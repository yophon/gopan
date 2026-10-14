package xyz.yophon.gopan.core.documents

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DocumentCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(dir: File, name: String, size: Int, age: Long): File =
        File(dir, name).apply {
            writeBytes(ByteArray(size))
            setLastModified(1_700_000_000_000L - age)
        }

    @Test
    fun `evicts least recently used files until under the limit`() {
        val dir = tmp.newFolder("documents")
        val a = file(dir, "a", 100, age = 3000)
        val b = file(dir, "b", 100, age = 2000)
        val c = file(dir, "c", 100, age = 1000)
        val part = file(dir, "d.part", 500, age = 5000)
        DocumentCache.trimDir(dir, keep = c, max = 250)
        assertThat(a.exists()).isFalse()
        assertThat(b.exists()).isTrue()
        assertThat(c.exists()).isTrue()
        // 正在下载的 .part 不算、也不删
        assertThat(part.exists()).isTrue()
    }

    @Test
    fun `a single file larger than the limit survives its own download`() {
        val dir = tmp.newFolder("documents")
        val old = file(dir, "old", 100, age = 2000)
        val huge = file(dir, "huge", 400, age = 0)
        DocumentCache.trimDir(dir, keep = huge, max = 250)
        assertThat(huge.exists()).isTrue()
        assertThat(old.exists()).isFalse()
    }
}
