package xyz.yophon.gopan.feature.transfers

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.yophon.gopan.core.transfer.DownloadKind
import xyz.yophon.gopan.core.transfer.FileOpener
import xyz.yophon.gopan.core.transfer.LiveProgress
import xyz.yophon.gopan.core.transfer.TaskKey
import xyz.yophon.gopan.core.transfer.TransferEngine
import xyz.yophon.gopan.core.transfer.TransferState
import xyz.yophon.gopan.core.transfer.db.DownloadTaskEntity
import xyz.yophon.gopan.core.transfer.db.UploadTaskEntity
import javax.inject.Inject

@Immutable
data class UploadRow(val task: UploadTaskEntity, val progress: LiveProgress?) {
    val key: TaskKey get() = TaskKey.Upload(task.id)
}

/** 下载行:文件夹批次带子任务的汇总 */
@Immutable
data class DownloadRow(
    val task: DownloadTaskEntity,
    val progress: LiveProgress?,
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val bytesDone: Long = 0,
) {
    val key: TaskKey get() = TaskKey.Download(task.id)
}

@Immutable
data class TransfersUiState(
    val uploads: List<UploadRow> = emptyList(),
    val downloads: List<DownloadRow> = emptyList(),
    val loaded: Boolean = false,
) {
    val activeUploads: Int get() = uploads.count { it.task.state.isActive }
    val activeDownloads: Int get() = downloads.count { it.task.state.isActive }
    val hasDone: Boolean get() = uploads.any { it.task.state == TransferState.DONE } || downloads.any { it.task.state == TransferState.DONE }
}

val TransferState.isActive: Boolean get() = this == TransferState.QUEUED || this == TransferState.RUNNING

sealed interface TransfersEffect {
    data object NoAppToOpen : TransfersEffect

    data class OpenFolder(val folderId: String?) : TransfersEffect
}

/** 传输列表:上传 / 下载两栏,新任务在上;实时进度来自引擎内存(约 3 次/秒) */
@HiltViewModel
class TransfersViewModel @Inject constructor(
    private val engine: TransferEngine,
    private val opener: FileOpener,
) : ViewModel() {

    val state: StateFlow<TransfersUiState> = combine(engine.uploadTasks, engine.downloadTasks, engine.progress) { ups, downs, progress ->
        TransfersUiState(
            uploads = ups.asReversed().map { UploadRow(it, progress[TaskKey.Upload(it.id)]) },
            downloads = downloadRows(downs, progress),
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransfersUiState())

    private val _effects = Channel<TransfersEffect>(Channel.BUFFERED)
    val effects: Flow<TransfersEffect> = _effects.receiveAsFlow()

    fun pause(key: TaskKey) = act { engine.pause(key) }

    fun resume(key: TaskKey) = act { engine.resume(key) }

    fun retry(key: TaskKey) = act { engine.retry(key) }

    fun cancel(key: TaskKey) = act { engine.cancel(key) }

    fun remove(key: TaskKey) = act { engine.remove(key) }

    fun clearFinished() = act { engine.clearFinished() }

    fun open(task: DownloadTaskEntity, chooserTitle: String) {
        if (!opener.openWith(task, chooserTitle)) _effects.trySend(TransfersEffect.NoAppToOpen)
    }

    fun locate(task: UploadTaskEntity) {
        _effects.trySend(TransfersEffect.OpenFolder(task.parentId))
    }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    companion object {
        fun downloadRows(all: List<DownloadTaskEntity>, progress: Map<TaskKey, LiveProgress>): List<DownloadRow> {
            val children = all.filter { it.batchId != null }.groupBy { it.batchId }
            return all.filter { it.batchId == null }.asReversed().map { t ->
                if (t.kind == DownloadKind.FOLDER && t.expanded) {
                    val kids = children[t.id].orEmpty()
                    val bytes = kids.sumOf { k ->
                        when (k.state) {
                            TransferState.DONE -> k.size ?: 0
                            else -> progress[TaskKey.Download(k.id)]?.doneBytes ?: 0
                        }
                    }
                    DownloadRow(
                        task = t,
                        progress = progress[TaskKey.Download(t.id)],
                        filesDone = kids.count { it.state == TransferState.DONE },
                        filesTotal = kids.size,
                        bytesDone = bytes,
                    )
                } else {
                    DownloadRow(t, progress[TaskKey.Download(t.id)])
                }
            }
        }
    }
}
