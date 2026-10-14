package xyz.yophon.gopan.core.auth

import net.openid.appauth.connectivity.ConnectionBuilder
import net.openid.appauth.connectivity.DefaultConnectionBuilder
import xyz.yophon.gopan.BuildConfig
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * AppAuth 默认只许 HTTPS:发现文档与换 token 遇到 http 地址直接抛 IllegalArgumentException,
 * 发现文档那一步在 AppAuth 自己的 AsyncTask 里抛,结果是整个进程崩溃。
 * debug 包对接本机 / 模拟器上的账号中心(http://localhost、127.0.0.1、10.0.2.2,与 debug 的
 * network_security_config 放行范围一致)时改用这里的连接器;release 包与其它地址一律走 AppAuth 默认实现。
 */
internal object LoopbackConnections {

    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

    /** issuer 是否是 debug 包可以明文访问的本机地址 */
    fun allowsCleartext(issuer: String, debug: Boolean = BuildConfig.TEST_HOOKS): Boolean {
        if (!debug) return false
        val uri = runCatching { URI(issuer) }.getOrNull() ?: return false
        return uri.scheme == "http" && uri.host in LOOPBACK_HOSTS
    }

    /** loopback 的 http 自己开连接(超时、不跟随跳转与 AppAuth 默认实现相同),其余交还默认实现(仍只许 https) */
    val builder = ConnectionBuilder { uri ->
        if (uri.scheme == "http" && uri.host in LOOPBACK_HOSTS) {
            (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
                connectTimeout = TimeUnit.SECONDS.toMillis(15).toInt()
                readTimeout = TimeUnit.SECONDS.toMillis(10).toInt()
                instanceFollowRedirects = false
            }
        } else {
            DefaultConnectionBuilder.INSTANCE.openConnection(uri)
        }
    }
}
