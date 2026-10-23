package xyz.yophon.gopan.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import timber.log.Timber
import xyz.yophon.gopan.core.auth.SessionStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 带类型的 [ScreenCache] 键。[id] 全 App 唯一;带参数的页面把参数拼进去(`"notifications?unread=true"`),
 * 同一个键在读写两侧类型一致。给了 [persist] 的键还会落盘(见 [SnapshotDisk]),冷启动后也读得回来。
 */
data class ScreenKey<T : Any>(val id: String, val persist: KSerializer<T>? = null) {
    override fun toString(): String = id
}

/** 分页列表的快照:已加载的条目 + 下一页游标(null 表示没有更多) */
data class CachedPage<T>(val items: List<T>, val nextCursor: String?)

/**
 * 页面首屏数据的进程内快照,给"进页面先画上次的结果,同时静默联网刷新"(stale-while-revalidate)用:
 * 子页面每次进入都是新的 ViewModel,没有它就每次从整页加载开始。
 *
 * 按当前用户隔离(没登录时读不到也写不进),登出清空;内存里最多 [MAX_ENTRIES] 条,按最近使用淘汰。
 * 存的是界面已经整理好的数据(排好序的列表、加上游标的页),读出来直接放进 UiState。
 * 带 [ScreenKey.persist] 的键另外写一份到磁盘(内容变了才写),内存里没有时用 [load] 读回来。
 */
@Singleton
class ScreenCache @Inject constructor(
    private val session: SessionStore,
    private val disk: SnapshotDisk = SnapshotDisk.None,
) {

    private val map = object : LinkedHashMap<String, Any>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?): Boolean = size > MAX_ENTRIES
    }

    /** 落盘的键上次写下(或读回)的值:没变就不再写 */
    private val persisted = HashMap<String, Any>()

    operator fun <T : Any> get(key: ScreenKey<T>): T? {
        val k = scoped(key) ?: return null
        @Suppress("UNCHECKED_CAST")
        return synchronized(map) { map[k] } as T?
    }

    fun <T : Any> put(key: ScreenKey<T>, value: T) {
        val uid = session.currentUser?.id ?: return
        val k = "$uid/${key.id}"
        synchronized(map) { map[k] = value }
        val serializer = key.persist ?: return
        synchronized(persisted) {
            if (persisted[k] == value) return
            persisted[k] = value
        }
        disk.write(uid, key.id) { json.encodeToString(serializer, value) }
    }

    /**
     * 内存里有就直接给;没有、而且是落盘的键,就从磁盘读回上次的快照放进内存(冷启动后第一次进页面)。
     * 读盘的途中页面已经写进了更新的数据,或者换了账号,都以内存为准。坏掉的文件当作没有。
     */
    suspend fun <T : Any> load(key: ScreenKey<T>): T? {
        get(key)?.let { return it }
        val serializer = key.persist ?: return null
        val uid = session.currentUser?.id ?: return null
        val text = disk.read(uid, key.id) ?: return null
        val value = runCatching { json.decodeFromString(serializer, text) }
            .onFailure { Timber.w(it, "快照 %s 解析失败,忽略", key) }
            .getOrNull() ?: return null
        if (session.currentUser?.id != uid) return null
        val k = "$uid/${key.id}"
        synchronized(map) { if (map[k] == null) map[k] = value }
        synchronized(persisted) { persisted.putIfAbsent(k, value) }
        return get(key)
    }

    fun remove(key: ScreenKey<*>) {
        val uid = session.currentUser?.id ?: return
        val k = "$uid/${key.id}"
        synchronized(map) { map.remove(k) }
        if (key.persist != null) {
            synchronized(persisted) { persisted.remove(k) }
            disk.remove(uid, key.id)
        }
    }

    fun clear() {
        synchronized(map) { map.clear() }
        synchronized(persisted) { persisted.clear() }
        disk.clear()
    }

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
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
