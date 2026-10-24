package xyz.yophon.gopan.core.data

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import xyz.yophon.gopan.core.auth.SignOutHook
import xyz.yophon.gopan.core.data.db.CachedListingDao
import xyz.yophon.gopan.core.data.db.GopanDatabase
import xyz.yophon.gopan.core.data.db.RoomListingCache
import javax.inject.Singleton

/** core.data 自己的 Hilt 模块:Room 与仓库绑定。DI 根(di/AppModule)不用改。 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): GopanDatabase =
        Room.databaseBuilder(context, GopanDatabase::class.java, GopanDatabase.NAME)
            // 只从版本 1(只有可丢的目录缓存)破坏性升级;以后的版本必须写 Migration,保住队列里的任务
            .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)
            .addMigrations(GopanDatabase.MIGRATION_2_3, GopanDatabase.MIGRATION_3_4)
            .build()

    @Provides
    fun provideCachedListingDao(db: GopanDatabase): CachedListingDao = db.cachedListingDao()

    /** 登出 / 会话失效:上一个账号的目录缓存与内存里记住的节点一并清掉 */
    @Provides
    @IntoSet
    fun provideListingSignOutHook(cache: ListingCache, repo: NodesRepositoryImpl): SignOutHook = SignOutHook {
        repo.clearMemory()
        cache.clear()
    }

    /** 登出 / 会话失效:各页面的首屏快照一并清掉(内存里的与落盘的;本来就按用户隔离,这里是不让它留在手机上) */
    @Provides
    @IntoSet
    fun provideScreenCacheSignOutHook(cache: ScreenCache): SignOutHook = SignOutHook { cache.clear() }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindsModule {
    @Binds
    abstract fun bindListingCache(impl: RoomListingCache): ListingCache

    @Binds
    abstract fun bindSnapshotDisk(impl: FileSnapshotDisk): SnapshotDisk

    @Binds
    abstract fun bindNodesRepository(impl: NodesRepositoryImpl): NodesRepository

    @Binds
    abstract fun bindChatRepository(impl: xyz.yophon.gopan.core.data.chat.ApolloChatRepository): xyz.yophon.gopan.core.data.chat.ChatRepository

    @Binds
    abstract fun bindPreviewRepository(impl: xyz.yophon.gopan.core.data.preview.DefaultPreviewRepository): xyz.yophon.gopan.core.data.preview.PreviewRepository

    @Binds
    abstract fun bindSharesRepository(impl: xyz.yophon.gopan.core.data.shares.ApolloSharesRepository): xyz.yophon.gopan.core.data.shares.SharesRepository

    @Binds
    abstract fun bindFileRequestsRepository(impl: xyz.yophon.gopan.core.data.collect.ApolloFileRequestsRepository): xyz.yophon.gopan.core.data.collect.FileRequestsRepository

    @Binds
    abstract fun bindSecurityRepository(impl: xyz.yophon.gopan.core.data.security.ApolloSecurityRepository): xyz.yophon.gopan.core.data.security.SecurityRepository

    @Binds
    abstract fun bindAdminRepository(impl: xyz.yophon.gopan.core.data.admin.ApolloAdminRepository): xyz.yophon.gopan.core.data.admin.AdminRepository

    @Binds
    abstract fun bindUpdateRepository(impl: xyz.yophon.gopan.core.app.DefaultUpdateRepository): xyz.yophon.gopan.core.app.UpdateRepository
}
