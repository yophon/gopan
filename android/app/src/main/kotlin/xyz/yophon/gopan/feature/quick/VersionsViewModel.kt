package xyz.yophon.gopan.feature.quick

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.quick.FileVersion
import xyz.yophon.gopan.core.data.quick.QuickRepository
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.feature.drive.UiText
import javax.inject.Inject

sealed interface VersionConfirm {
    data class Restore(val version: FileVersion) : VersionConfirm

    data class Delete(val version: FileVersion) : VersionConfirm

    data object Purge : VersionConfirm
}

@Immutable
data class VersionsUiState(
    val loading: Boolean = true,
    val items: List<FileVersion> = emptyList(),
    /** 保留策略:(每个文件最多几个, 留几天);没加载出来为 null */
    val policy: Pair<Int, Int>? = null,
    val error: String? = null,
    val confirm: VersionConfirm? = null,
    val busy: Boolean = false,
)

sealed interface VersionsEvent {
    /** 恢复 / 删除成功:文件页要刷新 */
    data class Changed(val message: UiText) : VersionsEvent

    data class Failed(val message: UiText) : VersionsEvent
}

/** 历史版本面板(doc/24):加载、恢复、删除、清空,每个动作先确认 */
@HiltViewModel
class VersionsViewModel @Inject constructor(private val quick: QuickRepository) : ViewModel() {

    private val _state = MutableStateFlow(VersionsUiState())
    val state: StateFlow<VersionsUiState> = _state.asStateFlow()

    private val _events = Channel<VersionsEvent>(Channel.BUFFERED)
    val events: Flow<VersionsEvent> = _events.receiveAsFlow()

    private var nodeId: String? = null

    fun load(nodeId: String) {
        if (this.nodeId == nodeId && !_state.value.loading) return
        this.nodeId = nodeId
        refresh()
    }

    private fun refresh() {
        val id = nodeId ?: return
        viewModelScope.launch {
            gopanResult { quick.versions(id) }
                .onSuccess { list ->
                    _state.update { it.copy(loading = false, items = list.items, policy = list.keep to list.keepDays, error = null) }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: "加载失败") } }
        }
    }

    fun askRestore(v: FileVersion) = _state.update { it.copy(confirm = VersionConfirm.Restore(v)) }

    fun askDelete(v: FileVersion) = _state.update { it.copy(confirm = VersionConfirm.Delete(v)) }

    fun askPurge() = _state.update { it.copy(confirm = VersionConfirm.Purge) }

    fun cancelConfirm() {
        if (!_state.value.busy) _state.update { it.copy(confirm = null) }
    }

    fun confirm() {
        val c = _state.value.confirm ?: return
        val id = nodeId ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val (result, message) = when (c) {
                is VersionConfirm.Restore -> gopanResult { quick.restoreVersion(c.version.id) } to UiText.Res(R.string.versions_msg_restored)
                is VersionConfirm.Delete -> gopanResult { quick.deleteVersions(listOf(c.version.id)) } to UiText.Res(R.string.versions_msg_deleted)
                VersionConfirm.Purge -> gopanResult { quick.purgeVersions(id) } to UiText.Res(R.string.versions_msg_purged)
            }
            _state.update { it.copy(busy = false, confirm = null) }
            result
                .onSuccess {
                    _events.trySend(VersionsEvent.Changed(message))
                    refresh()
                }
                .onFailure { e -> _events.trySend(VersionsEvent.Failed(UiText.Plain(e.message ?: "操作失败"))) }
        }
    }
}
