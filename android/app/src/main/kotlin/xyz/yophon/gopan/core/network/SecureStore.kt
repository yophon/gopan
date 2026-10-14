package xyz.yophon.gopan.core.network

import java.util.concurrent.ConcurrentHashMap

/**
 * 小型加密键值存储:refresh cookie 与上次登录的用户摘要放这里。
 * 生产实现是 [EncryptedPrefsSecureStore](EncryptedSharedPreferences),测试用 [InMemorySecureStore]。
 */
interface SecureStore {
    fun get(key: String): String?

    /** value 为 null 表示删除 */
    fun put(key: String, value: String?)

    fun clear()
}

class InMemorySecureStore : SecureStore {
    private val map = ConcurrentHashMap<String, String>()

    override fun get(key: String): String? = map[key]

    override fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }

    override fun clear() = map.clear()
}
