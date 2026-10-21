package xyz.yophon.gopan

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import timber.log.Timber
import xyz.yophon.gopan.core.auth.SessionManager
import xyz.yophon.gopan.core.transfer.TransferEngine
import javax.inject.Inject
import xyz.yophon.gopan.core.app.CrashLog
import xyz.yophon.gopan.core.documents.DocumentsRootNotifier
import xyz.yophon.gopan.core.notify.InboxScheduler

@HiltAndroidApp
class GopanApp : Application(), SingletonImageLoader.Factory {

    @Inject
    lateinit var sessionManager: SessionManager

    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var transferEngine: TransferEngine

    @Inject
    lateinit var crashLog: CrashLog

    @Inject
    lateinit var documentsRoots: DocumentsRootNotifier

    @Inject
    lateinit var inboxScheduler: InboxScheduler

    override fun onCreate() {
        super.onCreate()
        // 最先装:后面任何一步崩了都能留下日志(只写本地,「我的 → 崩溃日志」里导出)
        crashLog.install()
        // 只在 debug 打日志;任何地方都不许打印 token、cookie、预签名 URL
        if (BuildConfig.TEST_HOOKS) Timber.plant(Timber.DebugTree())
        sessionManager.startBootstrap()
        // 上次没传完的(进程被杀、手机重启):登录态就绪后重新排 Worker
        transferEngine.onAppStart()
        // 登录 / 登出时让「文件」App 里的 gopan 根目录跟着出现 / 消失
        documentsRoots.start()
        // 系统通知栏提醒(doc/22 决策 6):登录且开着开关时排每 15 分钟一次的检查,登出 / 关掉时撤掉
        inboxScheduler.start()
    }

    /**
     * Coil 复用全局 OkHttpClient(连接池、超时)。缩略图是 15 分钟预签名 URL、走对象存储域名,
     * GopanCookieJar 只服务基址 host 的 /query,Bearer 由 Apollo 拦截器加,所以这里不会把凭证带给图片请求。
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = okHttpClient)) }
        .build()
}
