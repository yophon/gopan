package xyz.yophon.gopan.feature.archive

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.archive.ArchiveRepository
import xyz.yophon.gopan.core.data.archive.ExtractRequest
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.feature.drive.FolderPickerDialog
import xyz.yophon.gopan.feature.drive.PickerMode
import javax.inject.Inject

/** 「解压到…」的目标选择器:[request] 是选好文件夹后要提交的内容;[token] 区分两次打开;[error] 提交失败的原因 */
@Immutable
data class ExtractPicker(val request: ExtractRequest, val token: Long, val error: String? = null)

@Immutable
data class ExtractState(val busy: Boolean = false, val picker: ExtractPicker? = null)

sealed interface ExtractEvent {
    /** 后台任务已建好,进度在「传输」的后台任务里 */
    data class Started(val jobId: String) : ExtractEvent

    data class Failed(val message: String) : ExtractEvent
}

/**
 * 提交解压的公共部分(预览页的压缩包卡片、压缩包浏览页、文件页长按菜单共用):忙碌标记、「解压到…」选择器、结果回调。
 * 选择器开着时失败的原因显示在选择器里(全屏对话框盖住了页面的 Snackbar),否则回调 [ExtractEvent.Failed]。
 */
class ExtractSubmitter(
    private val archives: ArchiveRepository,
    private val scope: CoroutineScope,
    private val onEvent: (ExtractEvent) -> Unit,
) {
    private val _state = MutableStateFlow(ExtractState())
    val state: StateFlow<ExtractState> = _state.asStateFlow()

    fun submit(request: ExtractRequest) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        scope.launch {
            gopanResult { archives.extract(request) }
                .onSuccess { jobId ->
                    _state.value = ExtractState()
                    onEvent(ExtractEvent.Started(jobId))
                }
                .onFailure { e ->
                    val message = e.message ?: "解压失败"
                    val picker = _state.value.picker
                    if (picker != null) {
                        _state.value = ExtractState(picker = picker.copy(error = message))
                    } else {
                        _state.value = ExtractState()
                        onEvent(ExtractEvent.Failed(message))
                    }
                }
        }
    }

    /**
     * 打开目标选择器;选好后用 [request] 换上目标文件夹提交。
     * token 用时间戳:平板侧栏预览与文件页共用同一个选择器 ViewModel,各自计数会撞号(撞了就不回到根目录)
     */
    fun pick(request: ExtractRequest) {
        if (_state.value.busy) return
        _state.value = ExtractState(picker = ExtractPicker(request, token = System.nanoTime()))
    }

    /** 选择器里选了「选择此文件夹」;[parentId] 为 null = 根目录 */
    fun onPicked(parentId: String?) {
        val picker = _state.value.picker ?: return
        submit(picker.request.copy(parentId = parentId))
    }

    fun dismissPicker() {
        if (!_state.value.busy) _state.update { it.copy(picker = null) }
    }
}

/** 文件页 / 搜索页长按菜单的「解压到这里」「解压到…」:节点操作的 ViewModel 是各页自己的,解压单独放一个 */
@HiltViewModel
class ExtractViewModel @Inject constructor(archives: ArchiveRepository) : ViewModel() {
    private val _events = Channel<ExtractEvent>(Channel.BUFFERED)
    val events: Flow<ExtractEvent> = _events.receiveAsFlow()

    val extract = ExtractSubmitter(archives, viewModelScope) { _events.trySend(it) }
}

/** 「解压到…」的目标选择器(复用移动 / 复制的全屏选择器) */
@Composable
fun ExtractPickerHost(submitter: ExtractSubmitter) {
    val state by submitter.state.collectAsStateWithLifecycle()
    val picker = state.picker ?: return
    FolderPickerDialog(
        mode = PickerMode.EXTRACT,
        token = picker.token,
        excludeIds = emptyList(),
        busy = state.busy,
        error = picker.error,
        onPick = { target -> submitter.onPicked(target.id) },
        onDismiss = submitter::dismissPicker,
    )
}

/** 解压已开始的 Snackbar,带「查看」(去「传输」看进度) */
suspend fun SnackbarHostState.showExtractStarted(context: Context, onView: () -> Unit) {
    val result = showSnackbar(
        message = context.getString(R.string.archive_msg_extract_started),
        actionLabel = context.getString(R.string.archive_msg_view_transfers),
        duration = SnackbarDuration.Short,
    )
    if (result == SnackbarResult.ActionPerformed) onView()
}
