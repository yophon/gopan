package xyz.yophon.gopan.core.network

import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Error
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.exception.ApolloException
import com.apollographql.apollo.exception.ApolloHttpException
import com.apollographql.apollo.exception.ApolloNetworkException
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * 所有 Repository 对外抛的统一异常。[code] 是服务端 errors[].extensions.code
 * (UNAUTHENTICATED / NOT_FOUND / …)或本地定义的 NETWORK 等;[message] 是给用户看的中文。
 */
class GopanException(
    val code: String,
    message: String,
    /** 服务端原始 message,调试用;不要直接展示 */
    val serverMessage: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {

    val isUnauthenticated: Boolean get() = code == GopanErrors.UNAUTHENTICATED
    val isNetwork: Boolean get() = code == GopanErrors.NETWORK

    override fun toString(): String = "GopanException($code: $message)"
}

object GopanErrors {
    // ---- 服务端错误码(server/internal/service/errors.go) ----
    const val UNAUTHENTICATED = "UNAUTHENTICATED"
    const val NOT_FOUND = "NOT_FOUND"
    const val INVALID_INPUT = "INVALID_INPUT"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val QUOTA_EXCEEDED = "QUOTA_EXCEEDED"
    const val NAME_CONFLICT = "NAME_CONFLICT"
    const val BAD_CREDENTIALS = "BAD_CREDENTIALS"
    const val USERNAME_TAKEN = "USERNAME_TAKEN"
    const val REGISTER_CLOSED = "REGISTER_CLOSED"
    const val ACCOUNT_DISABLED = "ACCOUNT_DISABLED"
    const val ID_REQUIRED = "ID_REQUIRED"
    const val INTERNAL = "INTERNAL"

    // ---- 本地错误码 ----
    const val NETWORK = "NETWORK"
    const val HTTP = "HTTP"
    const val EMPTY_RESPONSE = "EMPTY_RESPONSE"
    const val UNKNOWN = "UNKNOWN"

    // ---- Yophon ID 登录链路 ----
    const val ID_CANCELLED = "ID_CANCELLED"
    const val ID_DISCOVERY_FAILED = "ID_DISCOVERY_FAILED"
    const val ID_AUTH_FAILED = "ID_AUTH_FAILED"
    const val ID_TICKET_DENIED = "ID_TICKET_DENIED"
    const val ID_BAD_RESPONSE = "ID_BAD_RESPONSE"
    const val ID_ENTRY_MISMATCH = "ID_ENTRY_MISMATCH"
    const val ID_FORBIDDEN = "ID_FORBIDDEN"
    const val ID_UNAVAILABLE = "ID_UNAVAILABLE"
    const val ID_EXCHANGE_FAILED = "ID_EXCHANGE_FAILED"
    const val ID_NO_SESSION = "ID_NO_SESSION"
    const val ID_SESSION_FAILED = "ID_SESSION_FAILED"

    private val messages = mapOf(
        UNAUTHENTICATED to "登录已失效,请重新登录",
        NOT_FOUND to "对象不存在或已被删除",
        INVALID_INPUT to "输入无效",
        RATE_LIMITED to "操作过于频繁,请稍后再试",
        QUOTA_EXCEEDED to "存储空间不足",
        NAME_CONFLICT to "同名文件或文件夹已存在",
        BAD_CREDENTIALS to "用户名或密码错误",
        USERNAME_TAKEN to "用户名已被占用",
        REGISTER_CLOSED to "当前未开放注册",
        ACCOUNT_DISABLED to "账号已被停用",
        ID_REQUIRED to "该账号需要使用 Yophon ID 登录",
        INTERNAL to "服务器开小差了,请稍后再试",
        NETWORK to "网络连接失败,请检查网络后重试",
        EMPTY_RESPONSE to "服务器返回了空数据",
    )

    /** 按错误码给中文;服务端 message 本身是中文时优先用它(INVALID_INPUT 这类码的细节在 message 里)。 */
    fun messageFor(code: String, serverMessage: String?): String {
        val server = serverMessage?.trim().orEmpty()
        if (server.isNotEmpty() && server.any { it.code > 0x7f }) return server
        return messages[code] ?: server.ifEmpty { "请求失败($code)" }
    }

    fun codeOf(error: Error): String? = error.extensions?.get("code") as? String

    fun firstCode(errors: List<Error>?): String? = errors?.firstNotNullOfOrNull(::codeOf)
}

/** 响应里任一错误的 code == UNAUTHENTICATED */
fun <D : Operation.Data> ApolloResponse<D>.isUnauthenticated(): Boolean =
    errors?.any { GopanErrors.codeOf(it) == GopanErrors.UNAUTHENTICATED } == true

/**
 * 取 data,否则抛 [GopanException]:传输层异常 → NETWORK/HTTP;GraphQL errors → 第一条的 code;
 * 既无错误也无 data → EMPTY_RESPONSE。
 */
fun <D : Operation.Data> ApolloResponse<D>.requireData(): D {
    exception?.let { throw it.toGopanException() }
    val errs = errors
    if (!errs.isNullOrEmpty()) {
        val first = errs.first()
        val code = GopanErrors.codeOf(first) ?: GopanErrors.UNKNOWN
        throw GopanException(code, GopanErrors.messageFor(code, first.message), first.message)
    }
    return data ?: throw GopanException(GopanErrors.EMPTY_RESPONSE, GopanErrors.messageFor(GopanErrors.EMPTY_RESPONSE, null))
}

fun Throwable.toGopanException(): GopanException = when (this) {
    is GopanException -> this
    is CancellationException -> throw this
    is ApolloHttpException -> GopanException(GopanErrors.HTTP, "服务器响应异常(HTTP $statusCode)", cause = this)
    is ApolloNetworkException -> GopanException(GopanErrors.NETWORK, GopanErrors.messageFor(GopanErrors.NETWORK, null), cause = this)
    is ApolloException -> GopanException(GopanErrors.NETWORK, "请求失败:${message ?: javaClass.simpleName}", cause = this)
    is IOException -> GopanException(GopanErrors.NETWORK, GopanErrors.messageFor(GopanErrors.NETWORK, null), cause = this)
    else -> GopanException(GopanErrors.UNKNOWN, "出错了:${message ?: javaClass.simpleName}", cause = this)
}

/** suspend 块的统一包装:把任何异常转成 GopanException 的 Result。 */
suspend inline fun <T> gopanResult(crossinline block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e.toGopanException())
}
