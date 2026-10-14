package xyz.yophon.gopan.core.data.security

import androidx.compose.runtime.Immutable
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import xyz.yophon.gopan.core.network.requireData
import xyz.yophon.gopan.graphql.AppPasswordsQuery
import xyz.yophon.gopan.graphql.CreateAppPasswordMutation
import xyz.yophon.gopan.graphql.CreateMCPAPIKeyMutation
import xyz.yophon.gopan.graphql.MCPAPIKeysQuery
import xyz.yophon.gopan.graphql.McpAccessRootsQuery
import xyz.yophon.gopan.graphql.McpAuditQuery
import xyz.yophon.gopan.graphql.OAuthGrantsQuery
import xyz.yophon.gopan.graphql.RevokeAppPasswordMutation
import xyz.yophon.gopan.graphql.RevokeMCPAPIKeyMutation
import xyz.yophon.gopan.graphql.RevokeOAuthGrantMutation
import xyz.yophon.gopan.graphql.RevokeSessionMutation
import xyz.yophon.gopan.graphql.SessionsQuery
import xyz.yophon.gopan.graphql.SetMcpAccessRootMutation
import javax.inject.Inject
import javax.inject.Singleton

/** 一个登录会话(一族 refresh token):[current] 是本机,吊销后那台设备下次刷新被登出 */
@Immutable
data class DeviceSession(
    val familyId: String,
    val userAgent: String,
    val ip: String,
    val createdAt: String,
    val lastSeenAt: String,
    val active: Boolean,
    val current: Boolean,
)

/** WebDAV 应用密码(明文只在创建时返回一次) */
@Immutable
data class AppPassword(val id: String, val name: String, val createdAt: String, val lastUsedAt: String?)

/** Agent(MCP)凭据的两种:API Key 与 OAuth 授权;目录范围按类型 + id 记 */
enum class CredentialType(val wire: String) {
    API_KEY("api_key"),
    OAUTH("oauth"),
    ;

    companion object {
        fun of(wire: String): CredentialType? = entries.firstOrNull { it.wire == wire }
    }
}

@Immutable
data class ApiKey(val id: String, val name: String, val scopes: List<String>, val createdAt: String, val lastUsedAt: String?) {
    val isAdmin: Boolean get() = scopes.any { it.startsWith("admin:") }
}

@Immutable
data class OAuthGrant(val id: String, val clientName: String, val scopes: List<String>, val createdAt: String, val updatedAt: String) {
    val isAdmin: Boolean get() = scopes.any { it.startsWith("admin:") }
}

@Immutable
data class AccessRoot(val credentialId: String, val type: CredentialType, val rootId: String, val rootName: String)

@Immutable
data class AuditEvent(
    val id: Long,
    val credentialId: String,
    val credentialType: String,
    val endpoint: String,
    val tool: String,
    val status: String,
    val errorCode: String?,
    val createdAt: String,
)

/** 新建的 API Key:[token] 明文只此一次 */
data class CreatedApiKey(val token: String, val key: ApiKey)

/**
 * 账户安全相关:登录设备、WebDAV 应用密码、Agent 接入(API Key、OAuth 授权、目录范围、操作记录)。
 * 失败抛 GopanException。
 */
interface SecurityRepository {
    suspend fun sessions(): List<DeviceSession>

    suspend fun revokeSession(familyId: String)

    suspend fun appPasswords(): List<AppPassword>

    /** 返回明文密码(只此一次) */
    suspend fun createAppPassword(name: String): String

    suspend fun revokeAppPassword(id: String)

    suspend fun apiKeys(): List<ApiKey>

    suspend fun createApiKey(name: String, scopes: List<String>): CreatedApiKey

    suspend fun revokeApiKey(id: String)

    suspend fun oauthGrants(): List<OAuthGrant>

    suspend fun revokeOAuthGrant(id: String)

    suspend fun accessRoots(): List<AccessRoot>

    /** [rootPath] 为 null:清除范围(全盘) */
    suspend fun setAccessRoot(credentialId: String, type: CredentialType, rootPath: String?)

    /** 操作记录,按 id 倒序;[beforeId] 为 null 取最新一页 */
    suspend fun audit(beforeId: Long?, limit: Int = AUDIT_PAGE): List<AuditEvent>

    companion object {
        const val AUDIT_PAGE = 50
    }
}

@Singleton
class ApolloSecurityRepository @Inject constructor(private val apollo: ApolloClient) : SecurityRepository {

    override suspend fun sessions(): List<DeviceSession> =
        apollo.query(SessionsQuery()).execute().requireData().sessions.map {
            DeviceSession(it.familyId, it.userAgent, it.ip, it.createdAt, it.lastSeenAt, it.active, it.current)
        }

    override suspend fun revokeSession(familyId: String) {
        apollo.mutation(RevokeSessionMutation(familyId)).execute().requireData()
    }

    override suspend fun appPasswords(): List<AppPassword> =
        apollo.query(AppPasswordsQuery()).execute().requireData().appPasswords.map {
            AppPassword(it.id, it.name, it.createdAt, it.lastUsedAt)
        }

    override suspend fun createAppPassword(name: String): String =
        apollo.mutation(CreateAppPasswordMutation(name)).execute().requireData().createAppPassword

    override suspend fun revokeAppPassword(id: String) {
        apollo.mutation(RevokeAppPasswordMutation(id)).execute().requireData()
    }

    override suspend fun apiKeys(): List<ApiKey> =
        apollo.query(MCPAPIKeysQuery()).execute().requireData().mcpAPIKeys.map {
            ApiKey(it.id, it.name, it.scopes, it.createdAt, it.lastUsedAt)
        }

    override suspend fun createApiKey(name: String, scopes: List<String>): CreatedApiKey {
        val created = apollo.mutation(CreateMCPAPIKeyMutation(name, scopes)).execute().requireData().createMCPAPIKey
        val c = created.credential
        return CreatedApiKey(created.token, ApiKey(c.id, c.name, c.scopes, c.createdAt, c.lastUsedAt))
    }

    override suspend fun revokeApiKey(id: String) {
        apollo.mutation(RevokeMCPAPIKeyMutation(id)).execute().requireData()
    }

    override suspend fun oauthGrants(): List<OAuthGrant> =
        apollo.query(OAuthGrantsQuery()).execute().requireData().oauthGrants.map {
            OAuthGrant(it.id, it.clientName, it.scopes, it.createdAt, it.updatedAt)
        }

    override suspend fun revokeOAuthGrant(id: String) {
        apollo.mutation(RevokeOAuthGrantMutation(id)).execute().requireData()
    }

    override suspend fun accessRoots(): List<AccessRoot> =
        apollo.query(McpAccessRootsQuery()).execute().requireData().mcpAccessRoots.mapNotNull { r ->
            CredentialType.of(r.credentialType)?.let { AccessRoot(r.credentialId, it, r.rootId, r.rootName) }
        }

    override suspend fun setAccessRoot(credentialId: String, type: CredentialType, rootPath: String?) {
        apollo.mutation(SetMcpAccessRootMutation(credentialId, type.wire, Optional.present(rootPath))).execute().requireData()
    }

    override suspend fun audit(beforeId: Long?, limit: Int): List<AuditEvent> =
        apollo.query(McpAuditQuery(Optional.presentIfNotNull(beforeId), Optional.present(limit))).execute().requireData().mcpAudit.map {
            AuditEvent(it.id, it.credentialId, it.credentialType, it.endpoint, it.tool, it.status, it.errorCode, it.createdAt)
        }
}
