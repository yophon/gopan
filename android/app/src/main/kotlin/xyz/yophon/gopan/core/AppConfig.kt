package xyz.yophon.gopan.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * 运行配置:服务端与账号中心地址。生产值来自 BuildConfig(di/AppModule),
 * 测试里直接构造一个指向 MockWebServer 的实例。
 */
data class AppConfig(
    /** gopan 服务端基址,如 https://drive.979687.xyz(末尾斜杠可有可无) */
    val baseUrl: String,
    /** Yophon ID issuer,如 https://id.979687.xyz */
    val idIssuer: String,
    /** 账号中心里本 App 的 OAuth 客户端 id(公开客户端,无 secret) */
    val idClientId: String,
    /** 授权码回调地址,必须与账号中心登记的完全一致 */
    val idRedirectUri: String,
    /** 移动票据的 client_id:账号中心里"gopan"这个被委托的 web 客户端,不是上面的 OAuth 客户端 */
    val ticketClientId: String = "gopan",
) {
    private val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()

    /** 只有这个 host 的 cookie 会被保存与发送 */
    val baseHost: String get() = base.host

    val graphqlUrl: String get() = base.newBuilder().encodedPath("/query").build().toString()

    /** 票据交换入口;账号中心返回的 entry 必须与它完全相等 */
    val mobileEntryUrl: String get() = base.newBuilder().encodedPath("/api/auth/id/mobile").build().toString()

    val ticketUrl: String get() = idIssuer.trimEnd('/') + "/api/mobile/ticket"

    /** 分享链接(访客页),与网页端 `${location.origin}/s/${token}` 一致 */
    fun shareUrl(token: String): String = base.newBuilder().encodedPath("/s/").addPathSegment(token).build().toString()

    /** WebDAV 挂载地址 */
    val davUrl: String get() = base.newBuilder().encodedPath("/dav").build().toString()

    /** Agent 接入的 MCP 地址(管理员另有 /mcp/admin) */
    val mcpUrl: String get() = base.newBuilder().encodedPath("/mcp").build().toString()
}
