package xyz.yophon.gopan.feature.transfers

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.yophon.gopan.core.data.NodesRepository
import xyz.yophon.gopan.core.data.jobs.BackgroundJob
import xyz.yophon.gopan.core.data.jobs.JobErrors
import xyz.yophon.gopan.core.data.jobs.JobSample
import xyz.yophon.gopan.core.data.jobs.JobsRepository
import xyz.yophon.gopan.core.data.jobs.isActive
import xyz.yophon.gopan.core.data.jobs.jobLocation
import xyz.yophon.gopan.core.data.jobs.jobSamples
import xyz.yophon.gopan.core.data.jobs.jobSpeeds
import xyz.yophon.gopan.core.data.jobs.jobWorthShowing
import xyz.yophon.gopan.core.data.jobs.justFinished
import xyz.yophon.gopan.core.network.gopanResult
import javax.inject.Inject

@Immutable
data class JobRow(val job: BackgroundJob, /** 两次轮询之间算出的速度(字节每秒);不在下载中为 null */ val speedBps: Long? = null)

@Immutable
data class JobsUiState(
    /** 值得看的任务(进行中、10 分钟内结束的、24 小时内失败的),新的在前 */
    val rows: List<JobRow> = emptyList(),
    /** 第一次拉取结束了(成功或失败) */
    val loaded: Boolean = false,
    /** 拉取失败且手上没有数据 */
    val error: String? = null,
    /** 服务端还有已结束的任务记录(不论显示没显示):「清除已完成」要一并清 */
    val hasFinishedOnServer: Boolean = false,
) {
    val activeCount: Int get() = rows.count { it.job.isActive }
    val hasActive: Boolean get() = rows.any { it.job.isActive }

    /** 列表里有已结束的:顶栏出现「清除已完成」 */
    val hasFinished: Boolean get() = rows.any { !it.job.isActive }
}

/**
 * 传输列表的「后台任务」栏(doc/21 二.3):服务端执行的离线下载与解压。
 * 只在页面可见时拉取:有进行中的任务就每 2 秒轮询,全部结束就停;操作(取消、重试、清除)之后立即拉一次。
 * 任务刚变成已完成时让文件列表回到前台时静默刷新(NodesRepository.markChanged)。
 */
@HiltViewModel
class JobsViewModel @Inject constructor(
    private val repo: JobsRepository,
    private val nodes: NodesRepository,
    tabRequest: TransfersTabRequest,
) : ViewModel() {

    private val _state = MutableStateFlow(JobsUiState())
    val state: StateFlow<JobsUiState> = _state.asStateFlow()

    private val _effects = Channel<TransfersEffect>(Channel.BUFFERED)
    val effects: Flow<TransfersEffect> = _effects.receiveAsFlow()

    /** 这次打开要先停在「后台任务」栏(提交离线下载后点了「查看」) */
    val openJobsFirst: Boolean = tabRequest.consumeJobs()

    /** 时钟:测试里换成虚拟时间 */
    internal var clock: () -> Long = System::currentTimeMillis

    private var visible = false
    private var pollJob: Job? = null
    private var samples: Map<String, JobSample> = emptyMap()

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (value) startPolling() else pollJob?.cancel()
    }

    /** 拉一次;有进行中的就每 2 秒接着拉,直到全部结束或页面不可见 */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                refresh()
                if (!_state.value.hasActive) break
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun retryLoad() {
        _state.update { it.copy(error = null) }
        if (visible) startPolling()
    }

    private suspend fun refresh() {
        gopanResult { repo.myJobs() }
            .onSuccess { all ->
                val now = clock()
                val speeds = jobSpeeds(samples, all, now)
                if (justFinished(samples, all).isNotEmpty()) nodes.markChanged()
                samples = jobSamples(all, now)
                _state.update {
                    it.copy(
                        rows = all.filter { j -> jobWorthShowing(j, now) }.map { j -> JobRow(j, speeds[j.id]) },
                        loaded = true,
                        error = null,
                        hasFinishedOnServer = all.any { j -> !j.isActive },
                    )
                }
            }
            .onFailure { e ->
                _state.update { it.copy(loaded = true, error = if (it.rows.isEmpty()) JobErrors.messageFor(e) else null) }
            }
    }

    fun cancel(job: BackgroundJob) = act { repo.cancel(job.id) }

    fun retry(job: BackgroundJob) = act { repo.retry(job.id) }

    /** 「清除已完成」:服务端有已结束的任务记录时一并清掉(本地上传 / 下载由 TransfersViewModel 清) */
    fun clearFinished() {
        if (!_state.value.hasFinishedOnServer) return
        act { repo.clearFinished() }
    }

    /** 打开所在位置:下载的文件进它的文件夹,解压出来的进新文件夹 */
    fun locate(job: BackgroundJob) {
        val target = jobLocation(job) ?: return
        _effects.trySend(TransfersEffect.OpenFolder(target.folderId))
    }

    /** 操作之后立即刷新(并在有进行中的任务时恢复轮询);失败提示原因 */
    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            gopanResult { block() }.onFailure { e -> _effects.trySend(TransfersEffect.Message(JobErrors.messageFor(e))) }
            if (visible) startPolling()
        }
    }

    companion object {
        const val POLL_INTERVAL_MS = 2_000L
    }
}
