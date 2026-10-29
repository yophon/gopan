package xyz.yophon.gopan.core.assistant

import androidx.compose.runtime.Immutable
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import xyz.yophon.gopan.core.network.requireData
import xyz.yophon.gopan.graphql.AiConversationsQuery
import xyz.yophon.gopan.graphql.AiProvidersQuery
import xyz.yophon.gopan.graphql.AssistantAccessQuery
import xyz.yophon.gopan.graphql.CreateAiProviderMutation
import xyz.yophon.gopan.graphql.DeleteAiConversationMutation
import xyz.yophon.gopan.graphql.DeleteAiProviderMutation
import xyz.yophon.gopan.graphql.SetAssistantAccessMutation
import xyz.yophon.gopan.graphql.UpdateAiProviderMutation
import xyz.yophon.gopan.graphql.type.AiProviderInput
import xyz.yophon.gopan.graphql.type.AiProviderKind
import xyz.yophon.gopan.graphql.type.AssistantGroupInput
import xyz.yophon.gopan.graphql.type.AssistantPermission
import javax.inject.Inject
import javax.inject.Singleton

/** 模型服务(doc/33 三):[site] 是站点提供的,只能经服务器转发 */
@Immutable
data class AiProvider(
    val id: String,
    val name: String,
    /** GraphQL 的写法:OPENAI / ANTHROPIC / GOOGLE / OPENAI_RESPONSES */
    val kind: String,
    val baseUrl: String,
    val models: List<String>,
    val hasKey: Boolean,
    val site: Boolean,
    val dailyTokens: Long,
    val usedTokens: Long,
)

@Immutable
data class AiConversationRow(val id: String, val title: String, val providerId: String?, val model: String, val updatedAt: String)

/** 助手权限表的一行;[permission] 是 ALLOW / ASK / DENY */
@Immutable
data class AssistantGroup(val key: String, val label: String, val permission: String, val outbound: Boolean)

@Immutable
data class AssistantAccess(val disabled: Boolean, val groups: List<AssistantGroup>)

/** 新建 / 修改模型服务;[key] 为 null 时修改不动 key */
data class AiProviderDraft(val name: String, val kind: String, val baseUrl: String, val models: List<String>, val key: String?)

interface AssistantRepository {
    suspend fun providers(): List<AiProvider>
    suspend fun createProvider(draft: AiProviderDraft)
    suspend fun updateProvider(id: String, draft: AiProviderDraft)
    suspend fun deleteProvider(id: String)
    suspend fun conversations(): List<AiConversationRow>
    suspend fun deleteConversation(id: String)
    suspend fun access(): AssistantAccess
    suspend fun setAccess(disabled: Boolean, changes: Map<String, String>): AssistantAccess
}

@Singleton
class ApolloAssistantRepository @Inject constructor(private val apollo: ApolloClient) : AssistantRepository {
    override suspend fun providers(): List<AiProvider> = apollo.query(AiProvidersQuery()).execute().requireData().aiProviders.map {
        AiProvider(it.id, it.name, it.kind.rawValue, it.baseUrl, it.models, it.hasKey, it.site, it.dailyTokens, it.usedTokens)
    }

    private fun AiProviderDraft.input() = AiProviderInput(
        name = name,
        kind = AiProviderKind.safeValueOf(kind),
        baseUrl = baseUrl,
        models = models,
        key = Optional.presentIfNotNull(key),
    )

    override suspend fun createProvider(draft: AiProviderDraft) {
        apollo.mutation(CreateAiProviderMutation(draft.input())).execute().requireData()
    }

    override suspend fun updateProvider(id: String, draft: AiProviderDraft) {
        apollo.mutation(UpdateAiProviderMutation(id, draft.input())).execute().requireData()
    }

    override suspend fun deleteProvider(id: String) {
        apollo.mutation(DeleteAiProviderMutation(id)).execute().requireData()
    }

    override suspend fun conversations(): List<AiConversationRow> =
        apollo.query(AiConversationsQuery(limit = Optional.present(100))).execute().requireData().aiConversations.map {
            AiConversationRow(it.id, it.title, it.providerId, it.model, it.updatedAt)
        }

    override suspend fun deleteConversation(id: String) {
        apollo.mutation(DeleteAiConversationMutation(id)).execute().requireData()
    }

    override suspend fun access(): AssistantAccess = apollo.query(AssistantAccessQuery()).execute().requireData().assistantAccess.let { a ->
        AssistantAccess(a.disabled, a.groups.map { AssistantGroup(it.key, it.label, it.permission.rawValue, it.outbound) })
    }

    override suspend fun setAccess(disabled: Boolean, changes: Map<String, String>): AssistantAccess {
        val groups = changes.map { (k, v) -> AssistantGroupInput(key = k, permission = AssistantPermission.safeValueOf(v)) }
        val a = apollo.mutation(SetAssistantAccessMutation(disabled, groups)).execute().requireData().setAssistantAccess
        return AssistantAccess(a.disabled, a.groups.map { AssistantGroup(it.key, it.label, it.permission.rawValue, it.outbound) })
    }
}
