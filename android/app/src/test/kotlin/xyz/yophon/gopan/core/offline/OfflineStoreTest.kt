package xyz.yophon.gopan.core.offline

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.yophon.gopan.core.transfer.DownloadDest
import xyz.yophon.gopan.core.transfer.DownloadKind
import xyz.yophon.gopan.core.transfer.db.DownloadTaskEntity
import java.io.File

/** 内存里的离线登记(OfflineStore 的单测用) */
private class FakeOfflineDao : OfflineDao {
    val pins = MutableStateFlow<List<OfflinePinEntity>>(emptyList())
    val files = MutableStateFlow<List<OfflineFileEntity>>(emptyList())

    override fun observePins(userId: String): Flow<List<OfflinePinEntity>> = pins.map { l -> l.filter { it.userId == userId } }
    override suspend fun pin(userId: String, nodeId: String) = pins.value.firstOrNull { it.userId == userId && it.nodeId == nodeId }
    override suspend fun putPin(pin: OfflinePinEntity) {
        pins.value = pins.value.filterNot { it.userId == pin.userId && it.nodeId == pin.nodeId } + pin
    }
    override suspend fun setTask(userId: String, nodeId: String, taskId: Long?) = update(userId, nodeId) { it.copy(taskId = taskId) }
    override suspend fun markSynced(userId: String, nodeId: String, at: Long) = update(userId, nodeId) { it.copy(syncedAt = at) }
    override suspend fun deletePin(userId: String, nodeId: String) {
        pins.value = pins.value.filterNot { it.userId == userId && it.nodeId == nodeId }
    }
    override fun observeFiles(userId: String): Flow<List<OfflineFileEntity>> = files.map { l -> l.filter { it.userId == userId } }
    override fun observeFilesOf(userId: String, rootId: String): Flow<List<OfflineFileEntity>> = files.map { l -> l.filter { it.userId == userId && it.rootId == rootId } }
    override suspend fun filesOf(userId: String, rootId: String) = files.value.filter { it.userId == userId && it.rootId == rootId }
    override suspend fun file(userId: String, rootId: String, nodeId: String) = files.value.firstOrNull { it.userId == userId && it.rootId == rootId && it.nodeId == nodeId }
    override suspend fun byNode(userId: String, nodeId: String) = files.value.filter { it.userId == userId && it.nodeId == nodeId }.sortedByDescending { it.savedAt }
    override suspend fun putFile(file: OfflineFileEntity) {
        files.value = files.value.filterNot { it.userId == file.userId && it.rootId == file.rootId && it.nodeId == file.nodeId } + file
    }
    override suspend fun deleteFiles(userId: String, rootId: String, nodeIds: List<String>) {
        files.value = files.value.filterNot { it.userId == userId && it.rootId == rootId && it.nodeId in nodeIds }
    }
    override suspend fun deleteFilesOf(userId: String, rootId: String) {
        files.value = files.value.filterNot { it.userId == userId && it.rootId == rootId }
    }
    override suspend fun deleteAllPins() {
        pins.value = emptyList()
    }
    override suspend fun deleteAllFiles() {
        files.value = emptyList()
    }

    private fun update(userId: String, nodeId: String, f: (OfflinePinEntity) -> OfflinePinEntity) {
        pins.value = pins.value.map { if (it.userId == userId && it.nodeId == nodeId) f(it) else it }
    }
}

class OfflineStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dao = FakeOfflineDao()
    private val store by lazy { OfflineStore(dao, { File(tmp.root, "offline") }) }
    private val inbox get() = File(File(tmp.root, "offline"), OfflineStore.INBOX).apply { mkdirs() }

    private fun task(id: Long, nodeId: String, name: String, kind: DownloadKind = DownloadKind.FILE, batchId: Long? = null, relDir: String = "", sha: String = "sha-$nodeId") =
        DownloadTaskEntity(
            id = id, userId = "u", kind = kind, batchId = batchId, nodeId = nodeId, name = name, relDir = relDir,
            sha256 = sha, dest = DownloadDest.OFFLINE, createdAt = 0, updatedAt = 0,
        )

    private suspend fun pin(nodeId: String, folder: Boolean, taskId: Long) =
        dao.putPin(OfflinePinEntity("u", nodeId, nodeId, folder, taskId = taskId, pinnedAt = 0, roundAt = 0))

    private fun inboxFile(name: String, text: String) = File(inbox, name).apply { writeText(text) }

    @Test
    fun `a downloaded file is moved into place, registered, and the single-file item is marked done`() = runTest {
        pin("f", folder = false, taskId = 1)
        val t = task(1, "f", "笔记.md")

        val path = store.saved(t, t, inboxFile("1.part", "hello").path)

        assertThat(path).endsWith("offline${File.separator}f${File.separator}f${File.separator}笔记.md")
        assertThat(File(path!!).readText()).isEqualTo("hello")
        assertThat(inbox.listFiles()).isEmpty()
        assertThat(dao.files.value.single().size).isEqualTo(5)
        assertThat(dao.pins.value.single().syncedAt).isNotNull()
        assertThat(store.localCopy("u", "f")?.path).isEqualTo(path)
    }

    @Test
    fun `a removed item or an old round drops the file`() = runTest {
        val t = task(1, "f", "a.txt")
        assertThat(store.saved(t, t, inboxFile("1.part", "x").path)).isNull()

        pin("f", folder = false, taskId = 2)
        assertThat(store.saved(t, t, inboxFile("1.part", "x").path)).isNull()
        assertThat(inbox.listFiles()).isEmpty()
        assertThat(dao.files.value).isEmpty()
    }

    @Test
    fun `an update renames in place, copies another item's copy, and prunes files gone from the folder`() = runTest {
        pin("d", folder = true, taskId = 10)
        val batch = task(10, "d", "照片", kind = DownloadKind.FOLDER)
        val first = store.saved(task(11, "a", "旧名.jpg", batchId = 10, relDir = "照片"), batch, inboxFile("11.part", "A").path)!!
        store.saved(task(12, "b", "b.jpg", batchId = 10, relDir = "照片/2024"), batch, inboxFile("12.part", "B").path)
        store.roundDone(batch, setOf("a", "b"))
        assertThat(dao.files.value.map { it.nodeId }).containsExactly("a", "b")

        // 新的一轮:a 在云端改了名(内容没变,用的是自己原来的副本),b 被删了
        dao.setTask("u", "d", 20)
        val batch2 = batch.copy(id = 20)
        val renamed = store.saved(task(21, "a", "新名.jpg", batchId = 20, relDir = "照片"), batch2, first)!!
        store.roundDone(batch2, setOf("a"))
        assertThat(File(renamed).readText()).isEqualTo("A")
        assertThat(File(first).exists()).isFalse()
        assertThat(dao.files.value.map { it.name }).containsExactly("新名.jpg")

        // 同一个文件又单独加了一次:从文件夹项那份拷一份,两边各留一份
        pin("a", folder = false, taskId = 30)
        val single = task(30, "a", "新名.jpg")
        val copied = store.saved(single, single, renamed)!!
        assertThat(copied).isNotEqualTo(renamed)
        assertThat(File(renamed).exists()).isTrue()
        assertThat(File(copied).readText()).isEqualTo("A")
    }

    @Test
    fun `an existing copy is offered only for the same content and size`() = runTest {
        pin("f", folder = false, taskId = 1)
        val t = task(1, "f", "a.txt", sha = "s1")
        store.saved(t, t, inboxFile("1.part", "abc").path)

        assertThat(store.existing("u", "f", "s1", 3)).isNotNull()
        assertThat(store.existing("u", "f", "s2", 3)).isNull()
        assertThat(store.existing("u", "f", "s1", 4)).isNull()
        assertThat(store.existing("other", "f", "s1", 3)).isNull()
    }

    @Test
    fun `removing an item deletes its files, wiping clears everything`() = runTest {
        pin("f", folder = false, taskId = 1)
        val t = task(1, "f", "a.txt")
        val path = store.saved(t, t, inboxFile("1.part", "abc").path)!!

        store.remove("u", "f")
        assertThat(File(path).exists()).isFalse()
        assertThat(dao.pins.value).isEmpty()
        assertThat(dao.files.value).isEmpty()

        pin("g", folder = false, taskId = 2)
        val g = task(2, "g", "b.txt")
        store.saved(g, g, inboxFile("2.part", "b").path)
        store.wipe()
        assertThat(File(tmp.root, "offline").exists()).isFalse()
        assertThat(dao.files.value).isEmpty()
    }
}
