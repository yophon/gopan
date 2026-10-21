package xyz.yophon.gopan.core.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import xyz.yophon.gopan.core.auth.AuthState
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.data.notifications.AppNotification
import xyz.yophon.gopan.core.data.notifications.NotificationsRepository
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.core.transfer.AppVisibility
import xyz.yophon.gopan.di.ApplicationScope
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 发系统通知的那一头(单测换成记录调用的假实现) */
interface InboxPoster {
    fun canPost(): Boolean

    fun post(items: List<AppNotification>, overflow: Int)

    /** 打开了通知列表:通知栏里已发的收起来 */
    fun clearPosted()
}

/**
 * 一次检查:取未读的第一页,挑出比上次提醒到的更新的发系统通知,记下新的游标。
 * App 在前台时不发(底栏角标就能看到),游标照样往前走;没有通知权限时同样只记游标,不在授权后补发一堆旧的。
 */
@Singleton
class InboxChecker @Inject constructor(
    private val repo: NotificationsRepository,
    private val prefs: InboxPrefs,
    private val sessionStore: SessionStore,
    private val poster: InboxPoster,
) {
    /** 测试里替换:App 是否在前台 */
    internal var foreground: () -> Boolean = { AppVisibility.foreground }

    /** 返回本次发了几条(含「另有 N 条」);没登录、关了开关、请求失败返回 0 */
    suspend fun check(): Int {
        if (!prefs.systemAlerts.first()) return 0
        // 进程是 WorkManager 拉起来的:等启动时的续登走完(离线启动也会很快给出上次的用户)
        val auth = withTimeoutOrNull(LOGIN_WAIT_MS) { sessionStore.state.first { it !is AuthState.Loading } }
        val user = (auth as? AuthState.SignedIn)?.user ?: return 0
        val page = gopanResult { repo.list(cursor = null, unreadOnly = true) }
            .onFailure { Timber.w("检查新通知失败:%s", (it as? Exception)?.message) }
            .getOrNull() ?: return 0
        // 请求期间退出了登录:不再写游标(登出钩子刚清过)
        if (sessionStore.currentUser?.id != user.id) return 0
        val selection = InboxLogic.select(page.items, user.id, prefs.cursor())
        var posted = 0
        if (selection.toPost.isNotEmpty() && !foreground() && poster.canPost()) {
            poster.post(selection.toPost, selection.overflow)
            posted = selection.toPost.size + if (selection.overflow > 0) 1 else 0
        }
        prefs.saveCursor(selection.cursor)
        return posted
    }

    companion object {
        private const val LOGIN_WAIT_MS = 30_000L
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface InboxEntryPoint {
    fun checker(): InboxChecker
}

/** 每 15 分钟一次的检查(WorkManager 定期任务,要求联网)。失败不重试:下一轮再查 */
class InboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val checker = EntryPointAccessors.fromApplication(applicationContext, InboxEntryPoint::class.java).checker()
        checker.check()
        return Result.success()
    }
}

/**
 * 按「已登录且开着开关」排 / 撤定期任务(唯一任务,KEEP:重复排不会把周期重置)。
 * 启动中(Loading)不动,免得每次冷启动先撤再排、周期被打乱;登出由登出钩子再撤一次并清游标。
 */
@Singleton
class InboxScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionStore: SessionStore,
    private val prefs: InboxPrefs,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(sessionStore.state, prefs.systemAlerts) { auth, on ->
                when (auth) {
                    AuthState.Loading -> null
                    AuthState.SignedOut -> false
                    is AuthState.SignedIn -> on
                }
            }.filterNotNull().distinctUntilChanged().collect { want ->
                try {
                    if (want) schedule() else cancel()
                } catch (e: IllegalStateException) {
                    // WorkManager 没初始化(极端情况下的测试 / 精简构建)
                    Timber.w("排系统通知检查失败:%s", e.message)
                }
            }
        }
    }

    fun schedule() {
        val request = PeriodicWorkRequestBuilder<InboxWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    companion object {
        const val WORK_NAME = "inbox-check"
        const val INTERVAL_MINUTES = 15L
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class InboxModule {
    @Binds
    abstract fun bindInboxPoster(impl: InboxNotifier): InboxPoster
}
