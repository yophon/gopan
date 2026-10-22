package xyz.yophon.gopan.core.data.activity

import androidx.compose.runtime.Immutable
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import xyz.yophon.gopan.core.data.Node
import xyz.yophon.gopan.core.data.toNode
import xyz.yophon.gopan.core.network.requireData
import xyz.yophon.gopan.graphql.ActivitiesQuery
import xyz.yophon.gopan.graphql.ActivityActorsQuery
import xyz.yophon.gopan.graphql.UndoActivitiesMutation
import xyz.yophon.gopan.graphql.UndoPreviewQuery
import xyz.yophon.gopan.graphql.fragment.ActivityFields
import xyz.yophon.gopan.graphql.fragment.UndoOutcomeFields
import javax.inject.Inject
import javax.inject.Singleton
import xyz.yophon.gopan.graphql.type.ActivityAction as GqlAction
import xyz.yophon.gopan.graphql.type.ActivityFilter as GqlFilter
import xyz.yophon.gopan.graphql.type.ActorKind as GqlActorKind
import xyz.yophon.gopan.graphql.type.NodeKind as GqlNodeKind
import xyz.yophon.gopan.graphql.type.UndoSelection as GqlSelection

/** 改动的来源(与 GraphQL ActorKind 同名;服务端 actor_kind 是它的小写) */
enum class ActorKind {
    WEB, ANDROID, WEBDAV, AGENT, IMG, COLLECT, JOB, FEED, CLIP, SYSTEM;

    companion object {
        /** 大小写都认(GraphQL 是大写,通知目标 id 里是小写);认不出返回 null */
        fun of(raw: String?): ActorKind? = raw?.trim()?.let { r -> entries.firstOrNull { it.name.equals(r, ignoreCase = true) } }
    }
}

/** 与 GraphQL ActivityAction 同名;新版服务端加的操作按 [OTHER] */
enum class ActivityAction { MKDIR, UPLOAD, OVERWRITE, RENAME, MOVE, COPY, DELETE, RESTORE, PURGE, EXTRACT, OTHER }

/**
 * 一个来源:[ref] 是登录会话 / 应用密码 / 凭据(api_key:… / oauth:…)/ 图床密钥 / 收集链接 / 任务 / 订阅的 id,系统为空;
 * [label] 是当时的名字快照(设备名、应用密码名、Key 名、「收集标题 · 访客名」……)
 */
@Immutable
data class ActivityActor(val kind: ActorKind, val ref: String = "", val label: String = "") {
    /** 同一个来源的判定:种类 + ref(名字可能改过) */
    fun sameSource(other: ActivityActor?): Boolean = other != null && other.kind == kind && other.ref == ref
}

/** 动态里的一条(doc/23 二.1):谁、什么时候、对哪个节点做了什么 */
@Immutable
data class Activity(
    val id: String,
    val at: String,
    val actor: ActivityActor,
    /** 同一次操作共用:一次多选、一次 Agent 工具调用、一个后台任务、一次撤回 */
    val batchId: String,
    val action: ActivityAction,
    val nodeId: String? = null,
    /** 节点还在网盘里(不在回收站)时才有 */
    val node: Node? = null,
    val isFolder: Boolean = false,
    val name: String,
    /** 当时所在的文件夹(移动的是移动后的) */
    val parentPath: String = "/",
    val fromPath: String? = null,
    val toPath: String? = null,
    val oldName: String? = null,
    val newName: String? = null,
    /** 涉及的文件数与字节(删除 / 复制 / 解压是整棵子树) */
    val files: Long = 0,
    val bytes: Long = 0,
    val note: String? = null,
    /** 这条是对哪条的撤回 */
    val undoOf: String? = null,
    val undoneAt: String? = null,
    /** 这类操作原则上能撤回(覆盖、彻底删除、已撤回过的为 false);现在能不能真撤看预览 */
    val undoable: Boolean = false,
) {
    val undone: Boolean get() = undoneAt != null
}

@Immutable
data class ActivityPage(val items: List<Activity>, val nextCursor: String?)

/** 近 90 天出现过的来源(筛选用) */
@Immutable
data class ActorSummary(val actor: ActivityActor, val total: Long, val lastAt: String)

/** 列表筛选:来源([actorRef] 为空 = 这一类来源都算)、从什么时候起(RFC 3339) */
@Immutable
data class ActivityQuery(val actorKind: ActorKind? = null, val actorRef: String? = null, val since: String? = null) {
    val isEmpty: Boolean get() = actorKind == null && since == null
}

/** 撤回哪些:几条、一批、或按筛选(某个来源某段时间,一次最多最近的 1000 条) */
sealed interface UndoTarget {
    data class Ids(val ids: List<String>) : UndoTarget

    data class Batch(val batchId: String) : UndoTarget

    data class Filter(val query: ActivityQuery) : UndoTarget
}

@Immutable
data class UndoItem(val activityId: String, val name: String, val action: ActivityAction, val ok: Boolean, val reason: String? = null)

/** 撤回(或预览)的结果:[items] 从新到旧 */
@Immutable
data class UndoOutcome(val done: Int, val failed: Int, val items: List<UndoItem>)

/** 动态接口(server/internal/service/activity_undo.go)。失败抛 GopanException。 */
interface ActivityRepository {
    /** 新的在前,一页 50 条 */
    suspend fun list(query: ActivityQuery, cursor: String?): ActivityPage

    suspend fun actors(): List<ActorSummary>

    /** 会撤回哪些、哪些撤不了(不改任何东西) */
    suspend fun preview(target: UndoTarget): UndoOutcome

    /** 从新到旧逐条撤回,各自成败;撤回本身也记进动态 */
    suspend fun undo(target: UndoTarget): UndoOutcome
}

@Singleton
class ApolloActivityRepository @Inject constructor(private val apollo: ApolloClient) : ActivityRepository {

    override suspend fun list(query: ActivityQuery, cursor: String?): ActivityPage {
        val page = apollo.query(
            ActivitiesQuery(filter = Optional.presentIfNotNull(query.toGql()), cursor = Optional.present(cursor), limit = Optional.present(PAGE_SIZE)),
        ).execute().requireData().activities
        return ActivityPage(page.items.map { it.activityFields.toDomain() }, page.nextCursor)
    }

    override suspend fun actors(): List<ActorSummary> =
        apollo.query(ActivityActorsQuery()).execute().requireData().activityActors.mapNotNull { s ->
            val kind = s.actor.kind.toDomain() ?: return@mapNotNull null
            ActorSummary(ActivityActor(kind, s.actor.ref, s.actor.label), s.total, s.lastAt)
        }

    override suspend fun preview(target: UndoTarget): UndoOutcome =
        apollo.query(UndoPreviewQuery(target.toGql())).execute().requireData().undoPreview.undoOutcomeFields.toDomain()

    override suspend fun undo(target: UndoTarget): UndoOutcome =
        apollo.mutation(UndoActivitiesMutation(target.toGql())).execute().requireData().undoActivities.undoOutcomeFields.toDomain()

    companion object {
        const val PAGE_SIZE = 50
    }
}

/** 动态自己的 Hilt 模块(不动 DataModule) */
@Module
@InstallIn(SingletonComponent::class)
abstract class ActivityModule {
    @Binds
    abstract fun bindActivityRepository(impl: ApolloActivityRepository): ActivityRepository
}

// ---- 映射 ----

internal fun ActivityQuery.toGql(): GqlFilter? {
    if (isEmpty) return null
    return GqlFilter(
        actorKind = Optional.presentIfNotNull(actorKind?.let { GqlActorKind.safeValueOf(it.name) }),
        actorRef = Optional.presentIfNotNull(actorRef?.takeIf { it.isNotEmpty() }),
        since = Optional.presentIfNotNull(since),
    )
}

internal fun UndoTarget.toGql(): GqlSelection = when (this) {
    is UndoTarget.Ids -> GqlSelection(ids = Optional.present(ids))
    is UndoTarget.Batch -> GqlSelection(batchId = Optional.present(batchId))
    is UndoTarget.Filter -> GqlSelection(filter = Optional.presentIfNotNull(query.toGql()))
}

private fun GqlActorKind.toDomain(): ActorKind? = ActorKind.of(rawValue)

private fun GqlAction.toDomain(): ActivityAction = ActivityAction.entries.firstOrNull { it.name == rawValue } ?: ActivityAction.OTHER

internal fun ActivityFields.toDomain(): Activity = Activity(
    id = id,
    at = at,
    // 服务端认不出的来源已经归成 SYSTEM;这里再兜一层
    actor = ActivityActor(actor.kind.toDomain() ?: ActorKind.SYSTEM, actor.ref, actor.label),
    batchId = batchId,
    action = action.toDomain(),
    nodeId = nodeId,
    node = node?.nodeItem?.toNode(),
    isFolder = nodeKind == GqlNodeKind.FOLDER,
    name = name,
    parentPath = parentPath.ifEmpty { "/" },
    fromPath = fromPath,
    toPath = toPath,
    oldName = oldName,
    newName = newName,
    files = files,
    bytes = bytes,
    note = note,
    undoOf = undoOf,
    undoneAt = undoneAt,
    undoable = undoable,
)

internal fun UndoOutcomeFields.toDomain(): UndoOutcome = UndoOutcome(
    done = done,
    failed = failed,
    items = items.map { UndoItem(it.activity.id, it.activity.name, it.activity.action.toDomain(), it.ok, it.reason) },
)
