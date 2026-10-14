package xyz.yophon.gopan.core.auth

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * 登出、改密后被吊销、账号中心撤销设备(refresh 被拒)时清理本地数据:目录缓存、缩略图缓存、内存里记住的节点等。
 * 各模块用 Hilt `@IntoSet` 多绑定提供实现,[SessionManager] 依次调用;单个失败不影响其余。
 */
fun interface SignOutHook {
    suspend fun onSignedOut()
}

/** 允许一个钩子都没有(测试、精简构建) */
@Module
@InstallIn(SingletonComponent::class)
abstract class SignOutHookModule {
    @Multibinds
    abstract fun signOutHooks(): Set<SignOutHook>
}
