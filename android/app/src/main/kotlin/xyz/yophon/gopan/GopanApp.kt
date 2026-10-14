package xyz.yophon.gopan

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import xyz.yophon.gopan.core.auth.SessionManager
import javax.inject.Inject

@HiltAndroidApp
class GopanApp : Application() {

    @Inject
    lateinit var sessionManager: SessionManager

    override fun onCreate() {
        super.onCreate()
        // 只在 debug 打日志;任何地方都不许打印 token、cookie、预签名 URL
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        sessionManager.startBootstrap()
    }
}
