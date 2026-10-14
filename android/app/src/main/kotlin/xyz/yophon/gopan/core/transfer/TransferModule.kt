package xyz.yophon.gopan.core.transfer

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import xyz.yophon.gopan.core.auth.SignOutHook
import xyz.yophon.gopan.core.transfer.download.AndroidDownloadPublisher
import xyz.yophon.gopan.core.transfer.download.DownloadApi
import xyz.yophon.gopan.core.transfer.download.DownloadPublisher
import xyz.yophon.gopan.core.transfer.download.GopanDownloadApi
import xyz.yophon.gopan.core.transfer.download.RangeFetcher
import xyz.yophon.gopan.core.transfer.download.RangeSource
import xyz.yophon.gopan.core.transfer.upload.ApolloUploadApi
import xyz.yophon.gopan.core.transfer.upload.PartSender
import xyz.yophon.gopan.core.transfer.upload.PartUploader
import xyz.yophon.gopan.core.transfer.upload.UploadApi
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TransferModule {

    /**
     * 直连对象存储的客户端(分片 PUT、预签名 GET、/pack):从全局客户端派生共享连接池,
     * 但不带 CookieJar(生产里预签名与 API 同域名,cookie 的 Path=/query 本来也带不过去,这里再显式断开),
     * 读写超时放宽到 60 秒,整次调用不限时(大分片在慢网下可能要几分钟)。
     */
    @Provides
    @Singleton
    @Named(PartUploader.TRANSFER_CLIENT)
    fun provideTransferClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    /** 登出 / 会话失效:停掉传输、清空队列与私有文件 */
    @Provides
    @IntoSet
    fun provideTransferSignOutHook(engine: TransferEngine): SignOutHook = SignOutHook { engine.wipe() }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TransferBindsModule {
    @Binds
    abstract fun bindUploadApi(impl: ApolloUploadApi): UploadApi

    @Binds
    abstract fun bindPartSender(impl: PartUploader): PartSender

    @Binds
    abstract fun bindSourceReader(impl: SourceStore): SourceReader

    @Binds
    abstract fun bindDownloadApi(impl: GopanDownloadApi): DownloadApi

    @Binds
    abstract fun bindRangeSource(impl: RangeFetcher): RangeSource

    @Binds
    abstract fun bindPublisher(impl: AndroidDownloadPublisher): DownloadPublisher

    @Binds
    abstract fun bindTransferActions(impl: DefaultTransferActions): TransferActions

    @Binds
    abstract fun bindConnectivity(impl: NetworkMonitor): Connectivity

    @Binds
    abstract fun bindShareInbox(impl: xyz.yophon.gopan.core.share.IncomingShareStore): xyz.yophon.gopan.core.share.ShareInbox
}
