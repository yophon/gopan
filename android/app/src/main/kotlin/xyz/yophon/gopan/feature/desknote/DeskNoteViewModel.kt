package xyz.yophon.gopan.feature.desknote

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
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
import xyz.yophon.gopan.core.data.desknote.DeskNote
import xyz.yophon.gopan.core.data.desknote.DeskNoteOp
import xyz.yophon.gopan.core.data.desknote.DeskNoteSaveResult
import xyz.yophon.gopan.core.data.desknote.DeskNoteSync
import xyz.yophon.gopan.core.data.desknote.DeskNotes
import xyz.yophon.gopan.core.data.text.TextFilesRepository
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.core.network.toGopanException
import xyz.yophon.gopan.feature.drive.UiText

@Immutable
data class DeskNoteUiState(
    val loading: Boolean = true,
    val loadError: UiText? = null,
    /** 界面上显示的(含还没存完的操作) */
    val note: DeskNote? = null,
    /** 还没存完的操作数 */
    val pending: Int = 0,
    val failed: Boolean = false,
)

/**
 * DeskNote 便签(doc/39):预览页里把便签 json 画成清单,勾选、改、删、加都直接写回。
 * 每一步先改显示的内容,再交给 [DeskNoteSync] 排队存;别处改过了它会重读并重放这一步。
 */
@HiltViewModel(assistedFactory = DeskNoteViewModel.Factory::class)
class DeskNoteViewModel @AssistedInject constructor(
    @Assisted private val nodeId: String,
    private val texts: TextFilesRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(nodeId: String): DeskNoteViewModel
    }

    private val _state = MutableStateFlow(DeskNoteUiState())
    val state: StateFlow<DeskNoteUiState> = _state.asStateFlow()

    private val _messages = Channel<UiText>(Channel.BUFFERED)
    val messages: Flow<UiText> = _messages.receiveAsFlow()

    private val sync = DeskNoteSync(
        read = { texts.read(nodeId).let { it.content to it.sha256 } },
        write = { content, base -> texts.save(nodeId, content, base) },
        isConflict = { e -> (e as? GopanException ?: e.toGopanException()).code == EDIT_CONFLICT },
    )

    init {
        load()
    }

    fun load(quiet: Boolean = false) {
        if (!quiet) _state.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            gopanResult { sync.load() }
                .onSuccess { ok ->
                    _state.update { s ->
                        if (!ok) s.copy(loading = false, loadError = UiText.Res(R.string.desknote_not_note))
                        else s.copy(loading = false, loadError = null, note = if (s.pending == 0) sync.server else s.note)
                    }
                }
                .onFailure { e ->
                    if (!quiet) _state.update { it.copy(loading = false, loadError = UiText.Plain(e.message ?: "")) }
                }
        }
    }

    /** 回到这一页时拉一次最新的,桌面端刚改的能看到(有没存完的操作时不拉) */
    fun refresh() {
        val s = _state.value
        if (s.pending == 0 && s.note != null) load(quiet = true)
    }

    fun run(op: DeskNoteOp) {
        val current = _state.value.note ?: return
        val next = DeskNotes.apply(current, op) ?: return
        _state.update { it.copy(note = next, pending = it.pending + 1) }
        viewModelScope.launch {
            val r = sync.persist(op)
            when (r) {
                DeskNoteSaveResult.Saved -> Unit
                DeskNoteSaveResult.Gone -> _messages.trySend(UiText.Res(R.string.desknote_gone))
                is DeskNoteSaveResult.Failed ->
                    _messages.trySend(r.error?.message?.takeIf { it.isNotBlank() }?.let { UiText.Plain(it) } ?: UiText.Res(R.string.desknote_save_failed))
            }
            _state.update { s ->
                val p = s.pending - 1
                s.copy(pending = p, failed = r is DeskNoteSaveResult.Failed, note = if (p == 0) sync.server ?: s.note else s.note)
            }
        }
    }

    private companion object {
        const val EDIT_CONFLICT = "EDIT_CONFLICT"
    }
}
