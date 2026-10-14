package xyz.yophon.gopan.core.documents

import android.content.Context
import android.provider.DocumentsContract
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import xyz.yophon.gopan.core.auth.AuthState
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.auth.SignOutHook
import xyz.yophon.gopan.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DocumentsModule {
    /** 登出:上一个账号的缓存文件、没写完的新文件都清掉 */
    @Provides
    @IntoSet
    fun provideDocumentsSignOutHook(cache: DocumentCache, pending: PendingDocuments): SignOutHook = SignOutHook {
        cache.clear()
        pending.clear()
    }
}

/**
 * 登录态一变就通知系统刷新「文件」App 里的 gopan 根目录(没登录时不显示,登录后出现,换账号时摘要换成新用户名)。
 * 进程启动时由 GopanApp 调 [start]。
 */
@Singleton
class DocumentsRootNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionStore: SessionStore,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            sessionStore.state
                .map { (it as? AuthState.SignedIn)?.user?.id }
                .distinctUntilChanged()
                .collect {
                    val authority = context.packageName + GopanDocumentsProvider.AUTHORITY_SUFFIX
                    context.contentResolver.notifyChange(DocumentsContract.buildRootsUri(authority), null)
                }
        }
    }
}
