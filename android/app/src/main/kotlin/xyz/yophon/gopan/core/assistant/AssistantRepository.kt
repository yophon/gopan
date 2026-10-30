package xyz.yophon.gopan.core.assistant

import androidx.compose.runtime.Immutable
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import xyz.yophon.gopan.core.network.requireData
import xyz.yophon.gopan.graphql.AiConversationRunnerQuery
import xyz.yophon.gopan.graphql.AiConversationsQuery
import xyz.yophon.gopan.graphql.AiRunAvailableQuery
import xyz.yophon.gopan.graphql.AiRunQuery
import xyz.yophon.gopan.graphql.AiMemoryQuery
import xyz.yophon.gopan.graphql.AnswerAiRunMutation
import xyz.yophon.gopan.graphql.SetAiMemoryMutation
import xyz.yophon.gopan.graphql.NodesByIdsQuery
import xyz.yophon.gopan.graphql.NodesByPathsQuery
import xyz.yophon.gopan.graphql.SetAiConversationRunnerMutation
import xyz.yophon.gopan.graphql.StartAiRunMutation
import xyz.yophon.gopan.graphql.StopAiRunMutation
import xyz.yophon.gopan.graphql.type.AiRunner
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

/** [runner] 是 DEVICE / SERVER;[runStatus] 是服务器上没跑完的这一轮(QUEUED / RUNNING / WAITING),没有为 null */
@Immutable
data class AiConversationRow(
    val id: String,
    val title: String,
    val providerId: String?,
    val model: String,
    val updatedAt: String,
    val runner: String = "DEVICE",
    val runStatus: String? = null,
)

/** 服务器上跑的一轮(doc/34);[status] 小写(queued / running / waiting / done / failed / stopped),[approval] 是确认卡的 JSON */
@Immutable
data class AiRun(val id: String, val conversationId: String, val status: String, val approval: String?, val error: String)

/** 回答里引用的文件(doc/35 六);[kind] 是 FILE / FOLDER,[previewKind] 是 IMAGE / VIDEO / …,缩略图地址是预签名的 */
@Immutable
data class RefNode(
    val id: String,
    val name: String,
    val kind: String,
    val size: Long?,
    val path: String?,
    val previewKind: String,
    val thumbUrl: String?,
    val largeUrl: String?,
) {
    val folder: Boolean get() = kind == "FOLDER"
    val visual: Boolean get() = previewKind == "IMAGE" || previewKind == "VIDEO"
}

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

    // 服务器上运行(doc/34)
    suspend fun runAvailable(): Boolean
    suspend fun currentRun(conversationId: String): AiRun?
    suspend fun startRun(conversationId: String?, text: String, context: String, providerId: String, model: String, images: List<String> = emptyList()): AiRun
    suspend fun answerRun(runId: String, answer: String)
    suspend fun stopRun(runId: String)
    suspend fun setRunner(conversationId: String, server: Boolean)
    suspend fun runner(conversationId: String): String

    /** 按 id 查几个节点;查不到、没权限的不在结果里 */
    suspend fun nodesByIds(ids: List<String>): List<RefNode>

    /** 按网盘路径查(引用里写的是路径时) */
    suspend fun nodesByPaths(paths: List<String>): List<RefNode>

    // 长期记忆(doc/38):一份 Markdown,助手自己记,这里看、改、清空
    suspend fun memory(): String
    suspend fun setMemory(content: String)
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
            AiConversationRow(it.id, it.title, it.providerId, it.model, it.updatedAt, it.runner.rawValue, it.runStatus?.rawValue)
        }

    override suspend fun deleteConversation(id: String) {
        apollo.mutation(DeleteAiConversationMutation(id)).execute().requireData()
    }

    override suspend fun access(): AssistantAccess = apollo.query(AssistantAccessQuery()).execute().requireData().assistantAccess.let { a ->
        AssistantAccess(a.disabled, a.groups.map { AssistantGroup(it.key, it.label, it.permission.rawValue, it.outbound) })
    }

    override suspend fun runAvailable(): Boolean = apollo.query(AiRunAvailableQuery()).execute().requireData().aiRunAvailable

    override suspend fun currentRun(conversationId: String): AiRun? =
        apollo.query(AiRunQuery(conversationId)).execute().requireData().aiRun?.let {
            AiRun(it.id, it.conversationId, it.status.rawValue.lowercase(), it.approval, it.error)
        }

    override suspend fun startRun(conversationId: String?, text: String, context: String, providerId: String, model: String, images: List<String>): AiRun {
        val r = apollo.mutation(
            StartAiRunMutation(
                Optional.presentIfNotNull(conversationId),
                text,
                Optional.present(context),
                providerId,
                Optional.present(model),
                if (images.isEmpty()) Optional.absent() else Optional.present(images),
            ),
        ).execute().requireData().startAiRun
        return AiRun(r.id, r.conversationId, r.status.rawValue.lowercase(), r.approval, r.error)
    }

    override suspend fun answerRun(runId: String, answer: String) {
        apollo.mutation(AnswerAiRunMutation(runId, answer)).execute().requireData()
    }

    override suspend fun stopRun(runId: String) {
        apollo.mutation(StopAiRunMutation(runId)).execute().requireData()
    }

    override suspend fun setRunner(conversationId: String, server: Boolean) {
        apollo.mutation(SetAiConversationRunnerMutation(conversationId, if (server) AiRunner.SERVER else AiRunner.DEVICE)).execute().requireData()
    }

    override suspend fun runner(conversationId: String): String =
        apollo.query(AiConversationRunnerQuery(conversationId)).execute().requireData().aiConversation.runner.rawValue

    override suspend fun nodesByIds(ids: List<String>): List<RefNode> =
        apollo.query(NodesByIdsQuery(ids)).execute().requireData().nodesByIds.map {
            RefNode(it.id, it.name, it.kind.rawValue, it.size, it.path, it.preview.kind.rawValue, it.preview.thumbUrl, it.preview.largeUrl)
        }

    override suspend fun memory(): String = apollo.query(AiMemoryQuery()).execute().requireData().aiMemory

    override suspend fun setMemory(content: String) {
        apollo.mutation(SetAiMemoryMutation(content)).execute().requireData()
    }

    override suspend fun nodesByPaths(paths: List<String>): List<RefNode> =
        apollo.query(NodesByPathsQuery(paths)).execute().requireData().nodesByPaths.map {
            RefNode(it.id, it.name, it.kind.rawValue, it.size, it.path, it.preview.kind.rawValue, it.preview.thumbUrl, it.preview.largeUrl)
        }

    override suspend fun setAccess(disabled: Boolean, changes: Map<String, String>): AssistantAccess {
        val groups = changes.map { (k, v) -> AssistantGroupInput(key = k, permission = AssistantPermission.safeValueOf(v)) }
        val a = apollo.mutation(SetAssistantAccessMutation(disabled, groups)).execute().requireData().setAssistantAccess
        return AssistantAccess(a.disabled, a.groups.map { AssistantGroup(it.key, it.label, it.permission.rawValue, it.outbound) })
    }
}
