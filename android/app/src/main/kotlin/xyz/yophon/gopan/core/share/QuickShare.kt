package xyz.yophon.gopan.core.share

import android.Manifest
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.apollographql.apollo.ApolloClient
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.yophon.gopan.MainActivity
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.AppConfig
import xyz.yophon.gopan.core.data.NodesRepository
import xyz.yophon.gopan.core.data.shares.SharesRepository
import xyz.yophon.gopan.core.network.requireData
import xyz.yophon.gopan.core.transfer.TransferActions
import xyz.yophon.gopan.core.transfer.TransferEvent
import xyz.yophon.gopan.core.transfer.TransferNotifications
import xyz.yophon.gopan.di.ApplicationScope
import xyz.yophon.gopan.graphql.QuickShareFolderMutation
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「生成分享链接」(doc/31 四):别的 App 分享进来的文件传进云端根目录的「快传」,传完建一个 7 天的分享,
 * 链接放进剪贴板,再发一条通知(点一下直接选 App 发出去)。
 * 一个文件分享它本身;多个先在「快传」里建一个文件夹装这一批,全部传完再分享,对方打开时不会看到传了一半的。
 */
interface QuickShare {
    /** 开始上传;返回这一批的称呼(「报告.pdf 等 3 项」),提示用 */
    suspend fun start(files: List<File>): String
}

@Module
@InstallIn(SingletonComponent::class)
abstract class QuickShareModule {
    @Binds
    abstract fun bindQuickShare(impl: QuickShareCoordinator): QuickShare
}

/** 一批的称呼,也是多个文件时那个文件夹的名字 */
fun quickShareLabel(names: List<String>): String =
    if (names.size > 1) "${names.first()} 等 ${names.size} 项" else names.firstOrNull().orEmpty()

@Singleton
class QuickShareCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apollo: ApolloClient,
    private val nodes: NodesRepository,
    private val shares: SharesRepository,
    private val transfers: TransferActions,
    private val notifications: TransferNotifications,
    private val config: AppConfig,
    @ApplicationScope private val scope: CoroutineScope,
) : QuickShare {

    private class Job(val tasks: MutableSet<Long>, var node: String?, val folder: Boolean, val label: String) {
        var failed = false
    }

    private val mutex = Mutex()
    private val jobs = mutableListOf<Job>()
    private val notifyIds = AtomicInteger(0)

    init {
        scope.launch {
            transfers.events.collect { e ->
                val (taskId, node) = when (e) {
                    is TransferEvent.UploadDone -> e.task.id to e.node.id
                    is TransferEvent.UploadFailed -> e.task.id to null
                    else -> return@collect
                }
                val done = mutex.withLock {
                    val job = jobs.firstOrNull { taskId in it.tasks } ?: return@withLock null
                    job.tasks.remove(taskId)
                    if (node == null) job.failed = true else if (!job.folder) job.node = node
                    if (job.tasks.isEmpty()) job.also { jobs.remove(it) } else null
                } ?: return@collect
                launch { finish(done) }
            }
        }
    }

    override suspend fun start(files: List<File>): String {
        val label = quickShareLabel(files.map { it.name })
        val root = apollo.mutation(QuickShareFolderMutation()).execute().requireData().quickShareFolder.id
        val folder = files.size > 1
        val parent = if (folder) nodes.createFolder(root, label).id else root
        // 入队和登记在同一把锁里:小文件可能一入队就传完,完成事件要等登记好了才查得到
        mutex.withLock {
            val r = transfers.uploadOwnedFiles(files, parent)
            if (r.ids.isNotEmpty()) jobs += Job(r.ids.toMutableSet(), if (folder) parent else null, folder, label)
        }
        return label
    }

    private suspend fun finish(job: Job) {
        val id = NOTIFY_BASE + notifyIds.incrementAndGet() % 100
        val node = job.node
        if (job.failed || node == null) {
            post(id, context.getString(R.string.quick_share_failed_title), context.getString(R.string.quick_share_failed_text, job.label), openTransfers())
            return
        }
        val share = try {
            shares.create(node, null, Instant.now().plus(Duration.ofDays(SHARE_DAYS)))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            post(id, context.getString(R.string.quick_share_failed_title), e.message ?: job.label, openTransfers())
            return
        }
        val url = "${config.baseUrl.trimEnd('/')}/s/${share.token}"
        val copied = withContext(Dispatchers.Main) {
            try {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(job.label, url)) != null
            } catch (e: SecurityException) {
                false
            }
        }
        val send = Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url),
            context.getString(R.string.quick_share_send),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val intent = PendingIntent.getActivity(context, id, send, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        post(
            id,
            context.getString(if (copied) R.string.quick_share_done_title else R.string.quick_share_done_title_plain),
            context.getString(R.string.quick_share_done_text, job.label, SHARE_DAYS),
            intent,
        )
    }

    private fun openTransfers(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_TRANSFERS)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, NOTIFY_BASE, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun post(id: Int, title: String, text: String, intent: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notifications.ensureChannels()
        val n = NotificationCompat.Builder(context, TransferNotifications.CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (e: SecurityException) {
            // 通知权限在这期间被收回
        }
    }

    private companion object {
        const val SHARE_DAYS = 7L
        const val NOTIFY_BASE = 4300
    }
}
