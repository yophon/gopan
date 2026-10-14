package xyz.yophon.gopan.core.auth

import kotlinx.serialization.Serializable
import xyz.yophon.gopan.graphql.fragment.UserFields

/** 当前用户摘要(me / login / refresh 返回的字段)。可序列化:离线启动时从加密存储恢复。 */
@Serializable
data class UserSummary(
    val id: String,
    val username: String,
    val quotaBytes: Long,
    val usedBytes: Long,
    val isAdmin: Boolean,
)

fun UserFields.toSummary() = UserSummary(
    id = id,
    username = username,
    quotaBytes = quotaBytes,
    usedBytes = usedBytes,
    isAdmin = isAdmin,
)

sealed interface AuthState {
    /** 启动中:正在用 refresh cookie 换 access token */
    data object Loading : AuthState

    data object SignedOut : AuthState

    data class SignedIn(val user: UserSummary) : AuthState
}
