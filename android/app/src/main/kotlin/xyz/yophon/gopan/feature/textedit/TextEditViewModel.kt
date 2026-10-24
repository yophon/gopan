package xyz.yophon.gopan.feature.textedit

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
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
import xyz.yophon.gopan.core.data.text.TextFilesRepository
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.core.network.toGopanException
import xyz.yophon.gopan.feature.drive.UiText
import javax.inject.Inject

@Immutable
data class TextEditUiState(
    val name: String,
    val loading: Boolean = true,
    /** 读不出来(太大、不是文本、断网):整页显示原因 */
    val loadError: UiText? = null,
    val content: String = "",
    val original: String = "",
    val saving: Boolean = false,
    /** 保存时发现在别处改过了:问要不要仍然保存 */
    val conflict: Boolean = false,
    /** 有没保存的修改时按了返回:问要不要放弃 */
    val confirmLeave: Boolean = false,
) {
    val dirty: Boolean get() = !loading && loadError == null && content != original
}

sealed interface TextEditEffect {
    data class Message(val text: UiText) : TextEditEffect

    data object Close : TextEditEffect
}

/**
 * 在线编辑文本(doc/25):整份读进来改,保存时带上打开时的 sha256。App 上只编辑不超过 [MAX_APP_EDIT] 的文件
 * (Compose 的输入框更长就卡了),再大的请到网页上改。
 */
@HiltViewModel
class TextEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val texts: TextFilesRepository,
) : ViewModel() {

    private val nodeId: String = requireNotNull(savedStateHandle[NODE_ID_ARG]) { "缺少 nodeId" }

    private val _state = MutableStateFlow(TextEditUiState(name = savedStateHandle[NAME_ARG] ?: ""))
    val state: StateFlow<TextEditUiState> = _state.asStateFlow()

    private val _effects = Channel<TextEditEffect>(Channel.BUFFERED)
    val effects: Flow<TextEditEffect> = _effects.receiveAsFlow()

    /** 打开时(上次保存后)内容的 sha256 */
    private var base: String? = null

    /** 保存途中按了返回:存完再决定(存成功就直接走,不问放弃) */
    private var closeAfterSave = false

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            gopanResult { texts.read(nodeId) }
                .onSuccess { t ->
                    if (t.size > MAX_APP_EDIT) {
                        _state.update { it.copy(loading = false, loadError = UiText.Res(R.string.textedit_too_large_app)) }
                        return@onSuccess
                    }
                    base = t.sha256
                    _state.update { it.copy(loading = false, content = t.content, original = t.content) }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, loadError = UiText.Plain(e.message ?: "读取失败")) } }
        }
    }

    fun onChange(text: String) = _state.update { it.copy(content = text) }

    fun save(force: Boolean = false) {
        val s = _state.value
        if (s.saving || s.loading || s.loadError != null || (!s.dirty && !force)) return
        _state.update { it.copy(saving = true, conflict = false) }
        val content = s.content
        viewModelScope.launch {
            gopanResult { texts.save(nodeId, content, if (force) null else base) }
                .onSuccess { sha ->
                    base = sha
                    _state.update { it.copy(saving = false, original = content) }
                    _effects.trySend(TextEditEffect.Message(UiText.Res(R.string.textedit_saved)))
                    closePending()
                }
                .onFailure { e ->
                    val ge = e as? GopanException ?: e.toGopanException()
                    if (ge.code == EDIT_CONFLICT) {
                        closeAfterSave = false
                        _state.update { it.copy(saving = false, conflict = true) }
                    } else {
                        _state.update { it.copy(saving = false) }
                        _effects.trySend(TextEditEffect.Message(UiText.Plain(ge.message ?: "保存失败")))
                        closePending()
                    }
                }
        }
    }

    fun dismissConflict() = _state.update { it.copy(conflict = false) }

    /** 返回:正在保存就等存完;有没保存的修改先问一下 */
    fun requestClose() {
        val s = _state.value
        when {
            s.saving -> closeAfterSave = true
            s.dirty -> _state.update { it.copy(confirmLeave = true) }
            else -> _effects.trySend(TextEditEffect.Close)
        }
    }

    private fun closePending() {
        if (!closeAfterSave) return
        closeAfterSave = false
        requestClose()
    }

    fun dismissLeave() = _state.update { it.copy(confirmLeave = false) }

    fun discardAndClose() {
        _state.update { it.copy(confirmLeave = false) }
        _effects.trySend(TextEditEffect.Close)
    }

    companion object {
        /** 与 Routes.TextEdit 的属性名一致 */
        const val NODE_ID_ARG = "nodeId"
        const val NAME_ARG = "name"
        const val MAX_APP_EDIT = 512L * 1024
        const val EDIT_CONFLICT = "EDIT_CONFLICT"
    }
}
