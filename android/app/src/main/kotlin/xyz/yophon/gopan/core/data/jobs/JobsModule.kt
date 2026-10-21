package xyz.yophon.gopan.core.data.jobs

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import xyz.yophon.gopan.core.auth.SignOutHook

/** 后台任务自己的 Hilt 模块(不动 DataModule):仓库绑定与登出清理 */
@Module
@InstallIn(SingletonComponent::class)
abstract class JobsModule {
    @Binds
    abstract fun bindJobsRepository(impl: ApolloJobsRepository): JobsRepository

    companion object {
        /** 登出:缓存的「离线下载开关」作废,下个账号重新问 */
        @Provides
        @IntoSet
        fun provideJobsSignOutHook(repo: ApolloJobsRepository): SignOutHook = SignOutHook { repo.clearMemory() }
    }
}
