package xyz.yophon.gopan.feature.clips

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.yophon.gopan.core.auth.AuthState
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.data.clips.Clip
import xyz.yophon.gopan.core.data.clips.ClipSubmission
import xyz.yophon.gopan.core.data.clips.ClipsRepository
import xyz.yophon.gopan.core.data.jobs.BackgroundJob
import xyz.yophon.gopan.core.data.jobs.JobErrors
import xyz.yophon.gopan.core.data.jobs.JobStatus
import xyz.yophon.gopan.core.data.jobs.isActive
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.gopanResult
import javax.inject.Inject

/** 分享剪藏走到哪一步 */
sealed interface ClipShareStep {
    /** 等登录态、正在提交 */
    data object Submitting : ClipShareStep

    /** 分享的文字里没有网址 */
    data object NoUrl : ClipShareStep

    /** 没登录 */
    data object NeedLogin : ClipShareStep

    /** 这个网址剪过:打开已有的,或重新剪藏 */
    data class Existing(val clip: Clip) : ClipShareStep

    /** 服务器在剪;[slow] 等了很久或查不到进度(可以先关掉,剪完照样存好) */
    data class Running(val job: BackgroundJob, val slow: Boolean = false) : ClipShareStep

    /** 剪好了;[nodeId] 是那篇 .md */
    data class Done(val job: BackgroundJob, val nodeId: String?) : ClipShareStep

    data class Failed(val message: String) : ClipShareStep
}

@Immutable
data class ClipShareUiState(val url: String? = null, val step: ClipShareStep = ClipShareStep.Submitting)

/**
 * 系统分享菜单的「剪藏到 gopan」(doc/23 五.3):从分享的文字里取网址 → clipUrl;
 * 剪过的给出已有的那篇(打开 / 重新剪藏),否则轮询剪藏任务直到完成或失败。关掉页面不影响服务器上的任务。
 */
@HiltViewModel
class ClipShareViewModel @Inject constructor(
    private val clips: ClipsRepository,
    private val session: SessionStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ClipShareUiState())
    val state: StateFlow<ClipShareUiState> = _state.asStateFlow()

    /** 测试里调小 */
    internal var pollIntervalMs: Long = POLL_MS
    internal var clock: () -> Long = System::currentTimeMillis

    private var started = false
    private var job: Job? = null

    /** 只处理一次(Activity 重建时 ViewModel 还在,不会重复提交) */
    fun start(subject: String?, text: String?) {
        if (started) return
        started = true
        val url = ClipLogic.sharedUrl(subject, text)
        if (url == null) {
            set(ClipShareStep.NoUrl)
            return
        }
        _state.update { it.copy(url = url) }
        job = viewModelScope.launch {
            // 冷启动:等续登有了结果再判断登没登录
            val auth = session.state.first { it !is AuthState.Loading }
            if (auth !is AuthState.SignedIn) {
                set(ClipShareStep.NeedLogin)
                return@launch
            }
            submit(url, force = false)
        }
    }

    /** 已经剪过:按现在的网页重新存一份 */
    fun reclip() = resubmit(force = true)

    /** 提交或剪藏失败后再试一次 */
    fun retry() = resubmit(force = false)

    private fun resubmit(force: Boolean) {
        val url = _state.value.url ?: return
        if (job?.isActive == true) return
        job = viewModelScope.launch { submit(url, force) }
    }

    private suspend fun submit(url: String, force: Boolean) {
        set(ClipShareStep.Submitting)
        gopanResult { clips.clipUrl(url, force) }
            .onSuccess { result ->
                when (result) {
                    is ClipSubmission.Existing -> set(ClipShareStep.Existing(result.clip))
                    is ClipSubmission.Queued -> poll(result.job)
                }
            }
            .onFailure { e ->
                if ((e as? GopanException)?.isUnauthenticated == true) set(ClipShareStep.NeedLogin) else set(ClipShareStep.Failed(JobErrors.messageFor(e)))
            }
    }

    /** 每 [pollIntervalMs] 查一次任务,直到结束;等了很久或连着查不到时提示可以先关掉 */
    private suspend fun poll(initial: BackgroundJob) {
        var current = initial
        var failures = 0
        val startedAt = clock()
        set(ClipShareStep.Running(current))
        while (current.isActive) {
            delay(pollIntervalMs)
            gopanResult { clips.job(current.id) }
                .onSuccess {
                    current = it
                    failures = 0
                }
                .onFailure { failures++ }
            val slow = failures >= SLOW_FAILURES || clock() - startedAt > SLOW_MS
            if (current.isActive) set(ClipShareStep.Running(current, slow))
        }
        set(
            when (current.status) {
                JobStatus.DONE -> ClipShareStep.Done(current, current.result?.id)
                JobStatus.CANCELED -> ClipShareStep.Failed("已取消")
                else -> ClipShareStep.Failed(current.errorMessage?.takeIf { it.isNotBlank() } ?: "剪藏失败")
            },
        )
    }

    private fun set(step: ClipShareStep) {
        _state.update { it.copy(step = step) }
    }

    companion object {
        const val POLL_MS = 1_500L
        const val SLOW_MS = 60_000L
        const val SLOW_FAILURES = 3
    }
}
