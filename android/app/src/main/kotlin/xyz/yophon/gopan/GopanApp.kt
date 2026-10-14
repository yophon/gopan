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

@HiltAndroidApp
class GopanApp : Application(), SingletonImageLoader.Factory {

    @Inject
    lateinit var sessionManager: SessionManager

    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var transferEngine: TransferEngine

    override fun onCreate() {
        super.onCreate()
        // 只在 debug 打日志;任何地方都不许打印 token、cookie、预签名 URL
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        sessionManager.startBootstrap()
        // 上次没传完的(进程被杀、手机重启):登录态就绪后重新排 Worker
        transferEngine.onAppStart()
    }

    /**
     * Coil 复用全局 OkHttpClient(连接池、超时)。缩略图是 15 分钟预签名 URL、走对象存储域名,
     * GopanCookieJar 只服务基址 host 的 /query,Bearer 由 Apollo 拦截器加,所以这里不会把凭证带给图片请求。
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = okHttpClient)) }
        .build()
}
