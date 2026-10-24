package xyz.yophon.gopan.core.offline

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import xyz.yophon.gopan.core.transfer.DownloadDest
import xyz.yophon.gopan.core.transfer.DownloadKind
import xyz.yophon.gopan.core.transfer.LiveProgress
import xyz.yophon.gopan.core.transfer.TaskKey
import xyz.yophon.gopan.core.transfer.TransferState
import xyz.yophon.gopan.core.transfer.db.DownloadTaskEntity

class OfflineStatusTest {

    private fun pin(folder: Boolean, taskId: Long? = 1, syncedAt: Long? = null) =
        OfflinePinEntity("u", "n", "名字", folder, taskId = taskId, pinnedAt = 0, roundAt = 0, syncedAt = syncedAt)

    private fun task(id: Long, state: TransferState, kind: DownloadKind = DownloadKind.FILE, batchId: Long? = null, size: Long? = null, expanded: Boolean = false) =
        DownloadTaskEntity(
            id = id, userId = "u", kind = kind, batchId = batchId, nodeId = "x$id", name = "f$id", size = size,
            dest = DownloadDest.OFFLINE, state = state, expanded = expanded, errorMessage = "网络断了", createdAt = 0, updatedAt = 0,
        )

    @Test
    fun `without a task the item is ready only if a round ever finished`() {
        assertThat(OfflineRepository.statusOf(pin(false, taskId = null, syncedAt = 5), null, emptyMap(), emptyMap())).isEqualTo(OfflineStatus.Ready)
        assertThat(OfflineRepository.statusOf(pin(false, taskId = 9), null, emptyMap(), emptyMap())).isEqualTo(OfflineStatus.Incomplete)
    }

    @Test
    fun `a file shows its own progress, waiting while queued`() {
        val running = task(1, TransferState.RUNNING)
        val progress = mapOf<TaskKey, LiveProgress>(TaskKey.Download(1) to LiveProgress("DOWNLOADING", 25, 100))
        assertThat(OfflineRepository.statusOf(pin(false), running, emptyMap(), progress)).isEqualTo(OfflineStatus.Downloading(0.25f))
        assertThat(OfflineRepository.statusOf(pin(false), task(1, TransferState.QUEUED), emptyMap(), emptyMap())).isEqualTo(OfflineStatus.Waiting)
        assertThat(OfflineRepository.statusOf(pin(false), task(1, TransferState.FAILED), emptyMap(), emptyMap())).isEqualTo(OfflineStatus.Failed("网络断了"))
        assertThat(OfflineRepository.statusOf(pin(false), task(1, TransferState.DONE), emptyMap(), emptyMap())).isEqualTo(OfflineStatus.Ready)
    }

    @Test
    fun `a folder counts finished files and bytes including the ones in flight`() {
        val batch = task(1, TransferState.RUNNING, DownloadKind.FOLDER, size = 400, expanded = true)
        val kids = listOf(
            task(2, TransferState.DONE, batchId = 1, size = 100),
            task(3, TransferState.RUNNING, batchId = 1, size = 100),
            task(4, TransferState.QUEUED, batchId = 1, size = 200),
        )
        val progress = mapOf<TaskKey, LiveProgress>(TaskKey.Download(3) to LiveProgress("DOWNLOADING", 100, 100))

        val status = OfflineRepository.statusOf(pin(true), batch, mapOf(1L to kids), progress)

        assertThat(status).isEqualTo(OfflineStatus.Downloading(0.5f, filesDone = 1, filesTotal = 3))
        // 还在展开(列文件夹)时不知道进度
        assertThat(OfflineRepository.statusOf(pin(true), batch.copy(expanded = false), emptyMap(), emptyMap())).isEqualTo(OfflineStatus.Downloading(null))
    }
}
