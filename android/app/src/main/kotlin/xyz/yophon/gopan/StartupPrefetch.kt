package xyz.yophon.gopan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.data.ScreenCache
import xyz.yophon.gopan.core.data.ScreenKey
import xyz.yophon.gopan.core.data.chat.ChatRepository
import xyz.yophon.gopan.core.data.quick.QuickRepository
import xyz.yophon.gopan.core.data.shares.SharesRepository
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.di.ApplicationScope
import xyz.yophon.gopan.feature.chat.ChatViewModel
import xyz.yophon.gopan.feature.shares.SharesViewModel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 进主界面后顺手把另外几个 tab 的首屏取回来(传输助手最新一页、我的分享、收藏与最近),写进 [ScreenCache],
 * 第一次切过去时直接有数据。先把手机上存的上次那份读进内存(冷启动时网络还没回来也有东西画),
 * 再联网取新的。和文件列表并行发出,HTTP/2 下共用一条连接,几乎不多花时间。
 * 每个账号每个进程只取一次;失败不重试、不提示,页面自己加载时会再来。
 */
@Singleton
class StartupPrefetch @Inject constructor(
    private val session: SessionStore,
    private val cache: ScreenCache,
    private val chat: ChatRepository,
    private val shares: SharesRepository,
    private val quick: QuickRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private var doneFor: String? = null

    fun start() {
        val uid = session.currentUser?.id ?: return
        synchronized(this) {
            if (doneFor == uid) return
            doneFor = uid
        }
        prefetch(uid, ChatViewModel.LATEST_CACHE_KEY) { chat.latest(ChatRepository.LATEST_LIMIT) }
        prefetch(uid, SharesViewModel.CACHE_KEY) { SharesViewModel.sortShares(shares.myShares()) }
        // 文件页顶上的「收藏」「最近」
        quick.prefetch()
    }

    private fun <T : Any> prefetch(uid: String, key: ScreenKey<T>, fetch: suspend () -> T) {
        if (cache[key] != null) return
        scope.launch {
            val stale = cache.load(key)
            gopanResult { fetch() }
                // 取的途中换了账号:这份数据不属于现在的用户;页面已经写进更新的数据(不再是读盘那份):不覆盖
                .onSuccess { if (session.currentUser?.id == uid && cache[key] === stale) cache.put(key, it) }
                .onFailure { Timber.d("预取 %s 失败:%s", key, it.message) }
        }
    }
}
