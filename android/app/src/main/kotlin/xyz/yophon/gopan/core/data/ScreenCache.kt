package xyz.yophon.gopan.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import xyz.yophon.gopan.core.auth.SessionStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 带类型的 [ScreenCache] 键。[id] 全 App 唯一;带参数的页面把参数拼进去(`"notifications?unread=true"`),
 * 同一个键在读写两侧类型一致。
 */
data class ScreenKey<T : Any>(val id: String) {
    override fun toString(): String = id
}

/** 分页列表的快照:已加载的条目 + 下一页游标(null 表示没有更多) */
data class CachedPage<T>(val items: List<T>, val nextCursor: String?)

/**
 * 页面首屏数据的进程内快照,给"进页面先画上次的结果,同时静默联网刷新"(stale-while-revalidate)用:
 * 子页面每次进入都是新的 ViewModel,没有它就每次从整页加载开始。
 *
 * 只在内存里,按当前用户隔离(没登录时读不到也写不进),登出清空;最多 [MAX_ENTRIES] 条,按最近使用淘汰。
 * 存的是界面已经整理好的数据(排好序的列表、加上游标的页),读出来直接放进 UiState。
 */
@Singleton
class ScreenCache @Inject constructor(private val session: SessionStore) {

    private val map = object : LinkedHashMap<String, Any>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?): Boolean = size > MAX_ENTRIES
    }

    operator fun <T : Any> get(key: ScreenKey<T>): T? {
        val k = scoped(key) ?: return null
        @Suppress("UNCHECKED_CAST")
        return synchronized(map) { map[k] } as T?
    }

    fun <T : Any> put(key: ScreenKey<T>, value: T) {
        val k = scoped(key) ?: return
        synchronized(map) { map[k] = value }
    }

    fun remove(key: ScreenKey<*>) {
        val k = scoped(key) ?: return
        synchronized(map) { map.remove(k) }
    }

    fun clear() = synchronized(map) { map.clear() }

    /**
     * 让缓存跟着页面状态走:[select] 从状态里取出要缓存的那份(还在加载或出错时返回 null 不写),
     * 变了就写进去。本地的乐观修改(取消分享、标已读……)因此也会留在缓存里,下次进来不会先闪出旧数据。
     */
    fun <S, T : Any> mirror(scope: CoroutineScope, key: ScreenKey<T>, state: StateFlow<S>, select: (S) -> T?) {
        scope.launch {
            state.map(select).filterNotNull().distinctUntilChanged().collect { put(key, it) }
        }
    }

    /**
     * [mirror] 的变体:键随状态变(比如按筛选各存一份)。[select] 返回 (键, 数据),null 不写。
     * 换筛选时要在同一次状态更新里换掉数据(或离开 Content),否则旧数据会被写到新筛选的键下。
     */
    fun <S, T : Any> mirrorKeyed(scope: CoroutineScope, state: StateFlow<S>, select: (S) -> Pair<ScreenKey<T>, T>?) {
        scope.launch {
            state.map(select).filterNotNull().distinctUntilChanged().collect { (key, value) -> put(key, value) }
        }
    }

    private fun scoped(key: ScreenKey<*>): String? = session.currentUser?.id?.let { "$it/${key.id}" }

    companion object {
        const val MAX_ENTRIES = 64
    }
}
