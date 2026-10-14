package xyz.yophon.gopan.feature.drive

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.NodesRepository
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.core.network.toGopanException

/** 面包屑 / 目录选择器里的一级;id 为 null 表示根目录 */
@Immutable
data class Crumb(val id: String?, val name: String) {
    val isRoot: Boolean get() = id == null
}

/** 列表的加载状态;"空"由 Content + items 为空推出 */
sealed interface ListStatus {
    data object Loading : ListStatus

    data object Content : ListStatus

    data class Error(val message: String) : ListStatus
}

enum class PickerMode { MOVE, COPY }

/** 文件页 / 搜索页共用的弹层;ViewModel 持有,旋转屏幕不丢 */
sealed interface NodeDialog {
    data class Actions(val node: Node) : NodeDialog

    data class Details(val node: Node) : NodeDialog

    data class Rename(val node: Node) : NodeDialog

    /** singleName:只删一项时的名字(文案用),多项为 null */
    data class ConfirmDelete(val ids: List<String>, val singleName: String?) : NodeDialog

    /** token 区分两次打开:选择器 ViewModel 据此决定要不要回到根目录 */
    data class Picker(val mode: PickerMode, val ids: List<String>, val token: Long = System.nanoTime()) : NodeDialog
}

sealed interface NodeEffect {
    data class Message(val text: UiText) : NodeEffect

    data class OpenFolder(val folderId: String) : NodeEffect
}

/** 输入对话框里要贴着输入框显示的服务端错误:只对当初提交的那个名字有效 */
@Immutable
data class InputError(val name: String, val message: UiText)

/**
 * 节点操作(重命名 / 移动 / 复制 / 删除 / 详情)的公共部分:弹层状态、忙碌标记、一次性消息。
 * 子类只需实现 [afterMutation](成功后刷新自己的列表)。
 */
abstract class NodeActionsViewModel(protected val repo: NodesRepository) : ViewModel() {

    private val _dialog = MutableStateFlow<NodeDialog?>(null)
    val dialog: StateFlow<NodeDialog?> = _dialog.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _inputError = MutableStateFlow<InputError?>(null)
    val inputError: StateFlow<InputError?> = _inputError.asStateFlow()

    /** 移动 / 复制失败的提示:全屏目标选择器会盖住页面的 Snackbar,所以显示在选择器里 */
    private val _pickerError = MutableStateFlow<UiText?>(null)
    val pickerError: StateFlow<UiText?> = _pickerError.asStateFlow()

    private val _effects = Channel<NodeEffect>(Channel.BUFFERED)
    val effects: Flow<NodeEffect> = _effects.receiveAsFlow()

    fun showDialog(dialog: NodeDialog) {
        _inputError.value = null
        _pickerError.value = null
        _dialog.value = dialog
    }

    fun dismissDialog() {
        if (_busy.value) return
        _dialog.value = null
        _inputError.value = null
        _pickerError.value = null
    }

    fun showActions(node: Node) = showDialog(NodeDialog.Actions(node))

    fun showDetails(node: Node) = showDialog(NodeDialog.Details(node))

    fun requestRename(node: Node) = showDialog(NodeDialog.Rename(node))

    fun requestDelete(nodes: List<Node>) {
        if (nodes.isEmpty()) return
        showDialog(NodeDialog.ConfirmDelete(nodes.map { it.id }, nodes.singleOrNull()?.name))
    }

    fun requestMove(ids: Collection<String>) {
        if (ids.isNotEmpty()) showDialog(NodeDialog.Picker(PickerMode.MOVE, ids.toList()))
    }

    fun requestCopy(ids: Collection<String>) {
        if (ids.isNotEmpty()) showDialog(NodeDialog.Picker(PickerMode.COPY, ids.toList()))
    }

    fun rename(id: String, name: String) =
        runMutation(UiText.Res(R.string.drive_msg_renamed), inputName = name) { repo.rename(id, name) }

    fun confirmDelete(ids: List<String>) =
        runMutation(UiText.Res(R.string.drive_msg_deleted, ids.size)) { repo.delete(ids) }

    fun moveTo(ids: List<String>, target: Crumb, rootName: String) =
        runMutation(UiText.Res(R.string.drive_msg_moved, target.name.ifEmpty { rootName })) { repo.move(ids, target.id) }

    fun copyTo(ids: List<String>, target: Crumb, rootName: String) =
        runMutation(UiText.Res(R.string.drive_msg_copied, target.name.ifEmpty { rootName })) { repo.copy(ids, target.id) }

    /** P2/P3 才有的功能(打开、下载、上传):先关掉弹层(否则提示被 BottomSheet 盖住),再给一句提示 */
    fun notYet() {
        if (!_busy.value) {
            _dialog.value = null
            _inputError.value = null
        }
        send(NodeEffect.Message(UiText.Res(R.string.drive_coming_soon)))
    }

    fun message(text: UiText) = send(NodeEffect.Message(text))

    /** 变更成功后由子类刷新列表(静默) */
    protected abstract suspend fun afterMutation()

    /**
     * 跑一次变更:成功 → 关弹层、提示、[afterMutation];失败 → 提示错误。
     * [inputName] 非空表示来自输入对话框:同名冲突 / 输入无效贴在输入框下而不是关掉对话框。
     */
    protected fun runMutation(success: UiText, inputName: String? = null, block: suspend () -> Unit) =
        runMutationFor(inputName, block) { success }

    /** 同 [runMutation],成功文案取决于服务端返回(例如同名时服务端自动改的名字) */
    protected fun <T> runMutationFor(inputName: String? = null, block: suspend () -> T, success: (T) -> UiText) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val result = gopanResult { block() }
            _busy.value = false
            result.onSuccess { value ->
                _dialog.value = null
                _inputError.value = null
                _pickerError.value = null
                send(NodeEffect.Message(success(value)))
                afterMutation()
            }.onFailure { e ->
                val ge = e as? GopanException ?: e.toGopanException()
                when {
                    inputName != null && ge.code in INPUT_ERROR_CODES -> _inputError.value = InputError(inputName, inputMessage(ge))
                    _dialog.value is NodeDialog.Picker -> _pickerError.value = UiText.Plain(ge.message ?: ge.code)
                    else -> send(NodeEffect.Message(UiText.Plain(ge.message ?: ge.code)))
                }
            }
        }
    }

    protected fun send(effect: NodeEffect) {
        _effects.trySend(effect)
    }

    private fun inputMessage(e: GopanException): UiText = when (e.code) {
        GopanErrors.NAME_CONFLICT -> UiText.Res(R.string.drive_name_conflict)
        else -> UiText.Plain(e.message ?: e.code)
    }

    private companion object {
        val INPUT_ERROR_CODES = setOf(GopanErrors.NAME_CONFLICT, GopanErrors.INVALID_INPUT)
    }
}
