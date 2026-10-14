package xyz.yophon.gopan.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 只保存服务端基址 host 的 cookie(实际上只有 refresh token `gopan_rt`,Path=/query),
 * 持久化到 [SecureStore]。预签名 URL 走对象存储域名,不会带上任何 cookie。
 *
 * 懒加载:首次用到时才从存储读取,避免在主线程构造时碰 Keystore 与磁盘。
 */
class GopanCookieJar(
    private val allowedHost: String,
    private val store: SecureStore,
) : CookieJar {

    private val lock = Any()
    private val cookies = LinkedHashMap<String, Cookie>()
    private var loaded = false

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!url.host.equals(allowedHost, ignoreCase = true)) return
        synchronized(lock) {
            ensureLoaded()
            var changed = false
            val now = System.currentTimeMillis()
            for (cookie in cookies) {
                val key = keyOf(cookie)
                // Max-Age=0 / 空值 = 服务端要求删除(logout 时会这样)
                if (cookie.expiresAt <= now || cookie.value.isEmpty()) {
                    if (this.cookies.remove(key) != null) changed = true
                } else {
                    this.cookies[key] = cookie
                    changed = true
                }
            }
            if (changed) persist()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!url.host.equals(allowedHost, ignoreCase = true)) return emptyList()
        synchronized(lock) {
            ensureLoaded()
            dropExpired()
            return cookies.values.filter { it.matches(url) }
        }
    }

    /** 是否持有一枚未过期的同名 cookie(不校验路径) */
    fun hasCookie(name: String): Boolean = synchronized(lock) {
        ensureLoaded()
        dropExpired()
        cookies.values.any { it.name == name }
    }

    /** 未过期的同名 cookie 的值(登出时拿它去通知服务端吊销) */
    fun valueOf(name: String): String? = synchronized(lock) {
        ensureLoaded()
        dropExpired()
        cookies.values.firstOrNull { it.name == name }?.value
    }

    fun clear() {
        synchronized(lock) {
            loaded = true
            cookies.clear()
            store.put(STORE_KEY, null)
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val raw = store.get(STORE_KEY) ?: return
        val stored = runCatching { json.decodeFromString<List<StoredCookie>>(raw) }.getOrNull() ?: return
        for (s in stored) {
            val cookie = s.toCookie() ?: continue
            cookies[keyOf(cookie)] = cookie
        }
    }

    private fun dropExpired() {
        val now = System.currentTimeMillis()
        val expired = cookies.values.filter { it.expiresAt <= now }
        if (expired.isEmpty()) return
        expired.forEach { cookies.remove(keyOf(it)) }
        persist()
    }

    private fun persist() {
        if (cookies.isEmpty()) {
            store.put(STORE_KEY, null)
        } else {
            store.put(STORE_KEY, json.encodeToString(cookies.values.map { StoredCookie.from(it) }))
        }
    }

    private fun keyOf(cookie: Cookie) = cookie.name + "|" + cookie.path

    @Serializable
    private data class StoredCookie(
        val name: String,
        val value: String,
        val expiresAt: Long,
        val domain: String,
        val path: String,
        val secure: Boolean,
        val httpOnly: Boolean,
        val hostOnly: Boolean,
    ) {
        fun toCookie(): Cookie? = runCatching {
            Cookie.Builder()
                .name(name)
                .value(value)
                .expiresAt(expiresAt)
                .path(path)
                .apply {
                    if (hostOnly) hostOnlyDomain(domain) else domain(domain)
                    if (secure) secure()
                    if (httpOnly) httpOnly()
                }
                .build()
        }.getOrNull()

        companion object {
            fun from(c: Cookie) = StoredCookie(
                name = c.name,
                value = c.value,
                expiresAt = c.expiresAt,
                domain = c.domain,
                path = c.path,
                secure = c.secure,
                httpOnly = c.httpOnly,
                hostOnly = c.hostOnly,
            )
        }
    }

    companion object {
        const val STORE_KEY = "cookies"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
