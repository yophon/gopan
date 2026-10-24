package xyz.yophon.gopan

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import xyz.yophon.gopan.core.offline.OfflineMarks
import xyz.yophon.gopan.core.offline.OfflineRepository
import xyz.yophon.gopan.core.share.AppNavRequests
import xyz.yophon.gopan.core.share.NavRequest
import xyz.yophon.gopan.core.transfer.AppVisibility
import xyz.yophon.gopan.core.transfer.FileOpener
import xyz.yophon.gopan.core.transfer.OpenRequests
import xyz.yophon.gopan.core.transfer.TransferEngine
import xyz.yophon.gopan.core.transfer.TransferEvent
import xyz.yophon.gopan.feature.transfers.TransfersTabRequest
import javax.inject.Inject

/**
 * 主界面的全局事务:外部入口的跳转请求(系统分享 → 传输助手、通知 → 传输列表),
 * 以及"用其他应用打开"的下载完成后弹选择器(App 在前台时;在后台时由结果通知负责);
 * 进来时启动 [StartupPrefetch]。
 */
@HiltViewModel
class MainScaffoldViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val navRequests: AppNavRequests,
    engine: TransferEngine,
    private val openRequests: OpenRequests,
    private val opener: FileOpener,
    private val tabRequest: TransfersTabRequest,
    prefetch: StartupPrefetch,
    offline: OfflineRepository,
) : ViewModel() {

    /** 列表上的离线标记(doc/26) */
    val offlineMarks: StateFlow<OfflineMarks> = offline.marks

    val navRequest: StateFlow<NavRequest?> = navRequests.request

    fun consume(request: NavRequest) = navRequests.consume(request)

    /** 下次打开传输列表停在「后台任务」栏(解压、离线下载提交后点「查看」) */
    fun requestJobsTab() = tabRequest.requestJobs()

    init {
        // 文件页在加载自己的列表,另外几个 tab 的首屏这时并行取回来
        prefetch.start()
        viewModelScope.launch {
            engine.events.collect { event ->
                when (event) {
                    is TransferEvent.DownloadDone -> if (openRequests.consume(event.task.id) && AppVisibility.foreground) {
                        if (!opener.openWith(event.task, context.getString(R.string.drive_action_open))) {
                            Toast.makeText(context, R.string.transfer_msg_no_app, Toast.LENGTH_SHORT).show()
                        }
                    }
                    is TransferEvent.DownloadFailed -> if (openRequests.consume(event.task.id)) {
                        Toast.makeText(context, event.error.message ?: context.getString(R.string.transfer_state_failed), Toast.LENGTH_LONG).show()
                    }
                    else -> Unit
                }
            }
        }
    }
}
