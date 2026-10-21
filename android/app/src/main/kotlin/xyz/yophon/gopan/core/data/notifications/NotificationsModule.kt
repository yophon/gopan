package xyz.yophon.gopan.core.data.notifications

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import xyz.yophon.gopan.core.auth.SignOutHook
import xyz.yophon.gopan.core.notify.InboxPrefs
import xyz.yophon.gopan.core.notify.InboxScheduler

/** 通知自己的 Hilt 模块(不动 DataModule):仓库绑定与登出清理 */
@Module
@InstallIn(SingletonComponent::class)
abstract class NotificationsModule {
    @Binds
    abstract fun bindNotificationsRepository(impl: ApolloNotificationsRepository): NotificationsRepository

    companion object {
        /** 登出:角标清零;系统通知栏提醒的定时检查停掉,「上次提醒到哪条」作废(下个账号从头记) */
        @Provides
        @IntoSet
        fun provideNotificationsSignOutHook(unread: UnreadNotifications, scheduler: InboxScheduler, prefs: InboxPrefs): SignOutHook =
            SignOutHook {
                unread.clear()
                scheduler.cancel()
                prefs.clearCursor()
            }
    }
}
