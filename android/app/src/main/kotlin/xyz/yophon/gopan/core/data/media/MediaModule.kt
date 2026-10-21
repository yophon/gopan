package xyz.yophon.gopan.core.data.media

import android.os.SystemClock
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import okhttp3.OkHttpClient
import xyz.yophon.gopan.core.AppConfig
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.auth.SignOutHook
import xyz.yophon.gopan.core.data.preview.PreviewRepository
import xyz.yophon.gopan.core.network.TokenRefresher
import xyz.yophon.gopan.core.transfer.upload.PartUploader
import javax.inject.Named
import javax.inject.Singleton

/** 影音自己的 Hilt 模块(不动 DataModule):仓库绑定、播放器用的 OkHttp、队列地址解析、登出清理 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MediaModule {
    @Binds
    abstract fun bindMediaRepository(impl: ApolloMediaRepository): MediaRepository

    companion object {
        /** 播放器(视频页)用的客户端 */
        const val MEDIA_CLIENT = "media"

        /**
         * 直连对象存储的传输客户端 + 字幕鉴权:预签名地址照旧不带凭证,只有本站 /sub/ 加 Bearer(401 刷新一次)。
         */
        @Provides
        @Singleton
        @Named(MEDIA_CLIENT)
        fun provideMediaClient(
            @Named(PartUploader.TRANSFER_CLIENT) base: OkHttpClient,
            config: AppConfig,
            session: SessionStore,
            refresher: dagger.Lazy<TokenRefresher>,
        ): OkHttpClient = base.newBuilder()
            .addInterceptor(SubtitleAuthInterceptor(config.baseUrl, { session.accessToken }, { refresher.get() }))
            .build()

        @Provides
        @Singleton
        fun provideMediaUrls(previews: PreviewRepository): MediaUrls =
            MediaUrls(fetch = { previews.preview(it).contentUrl }, now = SystemClock::elapsedRealtime)

        /** 登出:停播、清空队列与取过的地址 */
        @Provides
        @IntoSet
        fun provideMediaSignOutHook(nowPlaying: NowPlayingStore, urls: MediaUrls): SignOutHook = SignOutHook {
            nowPlaying.requestStop()
            urls.clear()
        }
    }
}
