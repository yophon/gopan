package xyz.yophon.gopan.core.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class FileSnapshotDiskTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `writes, reads back, removes and clears in call order`() = runTest {
        val root = tmp.newFolder("snapshots")
        val disk = FileSnapshotDisk(root, this, StandardTestDispatcher(testScheduler))

        disk.write("u1", "chat.latest") { "[1]" }
        disk.write("u1", "chat.latest") { "[1,2]" }
        disk.write("u2", "shares?x=1") { "[]" }
        advanceUntilIdle()
        assertThat(disk.read("u1", "chat.latest")).isEqualTo("[1,2]")
        assertThat(disk.read("u2", "shares?x=1")).isEqualTo("[]")
        assertThat(disk.read("u1", "shares?x=1")).isNull()
        // 只留最终文件,没有临时文件
        assertThat(root.walk().filter { it.isFile }.map { it.name }.toList()).doesNotContain("chat.latest.json.tmp")

        disk.remove("u1", "chat.latest")
        advanceUntilIdle()
        assertThat(disk.read("u1", "chat.latest")).isNull()

        // 清空排在写入之后:之前排队的写入不会把文件写回来
        disk.write("u1", "chat.latest") { "[3]" }
        disk.clear()
        advanceUntilIdle()
        assertThat(disk.read("u1", "chat.latest")).isNull()
        assertThat(disk.read("u2", "shares?x=1")).isNull()
    }
}
