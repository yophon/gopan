package xyz.yophon.gopan.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import coil3.SingletonImageLoader
import com.apollographql.apollo.ApolloClient
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import xyz.yophon.gopan.BuildConfig
import xyz.yophon.gopan.core.AppConfig
import xyz.yophon.gopan.core.auth.SessionManager
import xyz.yophon.gopan.core.auth.SignOutHook
import xyz.yophon.gopan.core.network.AuthInterceptor
import xyz.yophon.gopan.core.network.EncryptedPrefsSecureStore
import xyz.yophon.gopan.core.network.GopanCookieJar
import xyz.yophon.gopan.core.network.SecureStore
import xyz.yophon.gopan.core.network.TokenRefresher
import xyz.yophon.gopan.core.network.buildApolloClient
import xyz.yophon.gopan.core.network.buildOkHttpClient
import xyz.yophon.gopan.core.network.withoutRedirects
import xyz.yophon.gopan.core.prefs.userPreferencesDataStore
import javax.inject.Qualifier
import javax.inject.Singleton

/** followRedirects=false 的 OkHttpClient(票据交换用) */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD)
annotation class NoRedirect

/** 与进程同寿命的协程作用域(refresh 单飞、启动续登等不该随调用方取消的工作) */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD)
annotation class ApplicationScope

/**
 * Hilt 根模块:配置、网络、存储单例。各功能线的 Repository 用 @Inject 构造器即可,不需要改这里;
 * 需要新的 @Provides 时在返回结果里写明,由 Integrate 阶段加。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppConfig(): AppConfig = AppConfig(
        baseUrl = BuildConfig.GOPAN_BASE_URL,
        idIssuer = BuildConfig.YOPHON_ID_ISSUER,
        idClientId = BuildConfig.YOPHON_ID_CLIENT_ID,
        idRedirectUri = BuildConfig.YOPHON_ID_REDIRECT,
    )

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideSecureStore(@ApplicationContext context: Context): SecureStore = EncryptedPrefsSecureStore(context)

    @Provides
    @Singleton
    fun provideCookieJar(config: AppConfig, store: SecureStore): GopanCookieJar = GopanCookieJar(config.baseHost, store)

    @Provides
    @Singleton
    fun provideOkHttpClient(cookieJar: GopanCookieJar): OkHttpClient = buildOkHttpClient(cookieJar)

    @Provides
    @Singleton
    @NoRedirect
    fun provideNoRedirectOkHttpClient(client: OkHttpClient): OkHttpClient = client.withoutRedirects()

    @Provides
    @Singleton
    fun provideApolloClient(
        config: AppConfig,
        okHttpClient: OkHttpClient,
        authInterceptor: AuthInterceptor,
    ): ApolloClient = buildApolloClient(config.graphqlUrl, okHttpClient, authInterceptor)

    @Provides
    @Singleton
    fun providePreferencesDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        userPreferencesDataStore(context)

    /** 登出 / 会话失效:清掉上一个账号的缩略图(内存与磁盘) */
    @Provides
    @IntoSet
    fun provideImageCacheSignOutHook(@ApplicationContext context: Context): SignOutHook = SignOutHook {
        withContext(Dispatchers.IO) {
            val loader = SingletonImageLoader.get(context)
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindsModule {
    /** AuthInterceptor 通过 Lazy<TokenRefresher> 拿到 SessionManager,打破 ApolloClient ↔ SessionManager 的环 */
    @Binds
    abstract fun bindTokenRefresher(impl: SessionManager): TokenRefresher
}
