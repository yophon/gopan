package xyz.yophon.gopan.core.transfer

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import xyz.yophon.gopan.core.prefs.UserPreferencesRepository
import xyz.yophon.gopan.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TransferEntryPoint {
    fun engine(): TransferEngine

    fun notifications(): TransferNotifications
}

/**
 * 执行传输队列的 Worker:提升为前台服务(dataSync,通知栏显示进度),跑 [TransferEngine.drain] 直到队列空。
 * 网络约束不满足(断网 / 只用 Wi-Fi 时离开 Wi-Fi)时 WorkManager 会停掉它,恢复后再启动;
 * 进程被杀后 WorkManager 自己重新调度。后台启动前台服务被系统拒绝时(Android 12+),退化成普通 Worker 继续跑。
 */
class TransferWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val entry: TransferEntryPoint
        get() = EntryPointAccessors.fromApplication(applicationContext, TransferEntryPoint::class.java)

    override suspend fun doWork(): Result {
        val engine = entry.engine()
        val notifications = entry.notifications()
        notifications.ensureChannels()
        var foreground = try {
            setForeground(notifications.foregroundInfo(TransferSummary.STARTING))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("无法进入前台服务(%s),按普通后台任务继续", e.javaClass.simpleName)
            false
        }
        val report = engine.drain { summary ->
            if (foreground && summary.active > 0) {
                foreground = try {
                    setForeground(notifications.foregroundInfo(summary))
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
            }
        }
        notifications.postResult(report)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notifications = entry.notifications()
        notifications.ensureChannels()
        return notifications.foregroundInfo(TransferSummary.STARTING)
    }
}

/** 排 / 撤传输 Worker。唯一任务名;REPLACE:只在引擎没在跑时调用,替换掉的是正在收尾或在等网络的那一个 */
@Singleton
class TransferScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: UserPreferencesRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun schedule() {
        scope.launch {
            val wifiOnly = try {
                prefs.preferences.first().transfer.wifiOnly
            } catch (e: Exception) {
                false
            }
            val request = OneTimeWorkRequestBuilder<TransferWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .build(),
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    companion object {
        const val WORK_NAME = "gopan-transfers"
    }
}
