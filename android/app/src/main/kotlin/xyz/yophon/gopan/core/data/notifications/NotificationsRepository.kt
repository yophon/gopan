package xyz.yophon.gopan.core.data.notifications

import androidx.compose.runtime.Immutable
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import xyz.yophon.gopan.core.network.gopanResult
import xyz.yophon.gopan.core.network.requireData
import xyz.yophon.gopan.graphql.ClearNotificationsMutation
import xyz.yophon.gopan.graphql.MarkNotificationsReadMutation
import xyz.yophon.gopan.graphql.NotificationsQuery
import xyz.yophon.gopan.graphql.UnreadNotificationsQuery
import xyz.yophon.gopan.graphql.fragment.InboxNotificationFields
import javax.inject.Inject
import javax.inject.Singleton
import xyz.yophon.gopan.graphql.type.NotificationLevel as GqlLevel
import xyz.yophon.gopan.graphql.type.NotificationTargetKind as GqlTargetKind

enum class NotificationLevel { INFO, WARN, ALERT }

/** 点开通知跳到哪(与 GraphQL NotificationTargetKind 同名) */
enum class NotificationTargetKind { NODE, FILE_REQUEST, LINK, JOBS, SESSIONS, CREDENTIALS, ADMIN }

/**
 * NODE:[id] 是文件 / 文件夹,[parentId] 是所在文件夹(null = 根目录);FILE_REQUEST / LINK:[id] 是收集链接 / 直链;
 * CREDENTIALS:[id] 是凭据种类(webdav / mcp / img);其余没有 id
 */
@Immutable
data class NotificationTarget(val kind: NotificationTargetKind, val id: String? = null, val parentId: String? = null)

/** 一条站内通知(doc/22 三)。同一件事 30 分钟内重复发生时服务端合并成一条,[count] 是合并了几次 */
@Immutable
data class AppNotification(
    val id: String,
    /** 事件类型:collect_upload / job_failed / login …(见 notifyEventKinds) */
    val kind: String,
    val level: NotificationLevel,
    val title: String,
    val body: String,
    val count: Int = 1,
    /** null = 没有可跳的地方(或新版服务端加了 App 不认识的目标) */
    val target: NotificationTarget? = null,
    val createdAt: String,
    /** 最后一次合并的时刻;没合并过等于 createdAt */
    val updatedAt: String = createdAt,
    val read: Boolean = false,
)

@Immutable
data class NotificationPage(val items: List<AppNotification>, val nextCursor: String?)

/** 通知接口(server/internal/service/notify.go)。失败抛 GopanException。 */
interface NotificationsRepository {
    /** 新的在前;[unreadOnly] 只要未读 */
    suspend fun list(cursor: String?, unreadOnly: Boolean): NotificationPage

    suspend fun unreadCount(): Int

    /** [ids] 为 null = 全部标为已读;返回标记的条数 */
    suspend fun markRead(ids: List<String>?): Int

    /** 删除全部通知,返回删除的条数 */
    suspend fun clear(): Int
}

@Singleton
class ApolloNotificationsRepository @Inject constructor(private val apollo: ApolloClient) : NotificationsRepository {

    override suspend fun list(cursor: String?, unreadOnly: Boolean): NotificationPage {
        val page = apollo.query(NotificationsQuery(cursor = Optional.present(cursor), unreadOnly = Optional.present(unreadOnly)))
            .execute().requireData().notifications
        return NotificationPage(page.items.map { it.inboxNotificationFields.toDomain() }, page.nextCursor)
    }

    override suspend fun unreadCount(): Int =
        apollo.query(UnreadNotificationsQuery()).execute().requireData().me.unreadNotifications

    override suspend fun markRead(ids: List<String>?): Int =
        apollo.mutation(MarkNotificationsReadMutation(ids = Optional.present(ids))).execute().requireData().markNotificationsRead

    override suspend fun clear(): Int =
        apollo.mutation(ClearNotificationsMutation()).execute().requireData().clearNotifications
}

internal fun InboxNotificationFields.toDomain() = AppNotification(
    id = id,
    kind = kind,
    level = when (level) {
        GqlLevel.WARN -> NotificationLevel.WARN
        GqlLevel.ALERT -> NotificationLevel.ALERT
        else -> NotificationLevel.INFO
    },
    title = title,
    body = body,
    count = count,
    target = target?.let { t ->
        val kind = when (t.kind) {
            GqlTargetKind.NODE -> NotificationTargetKind.NODE
            GqlTargetKind.FILE_REQUEST -> NotificationTargetKind.FILE_REQUEST
            GqlTargetKind.LINK -> NotificationTargetKind.LINK
            GqlTargetKind.JOBS -> NotificationTargetKind.JOBS
            GqlTargetKind.SESSIONS -> NotificationTargetKind.SESSIONS
            GqlTargetKind.CREDENTIALS -> NotificationTargetKind.CREDENTIALS
            GqlTargetKind.ADMIN -> NotificationTargetKind.ADMIN
            // 新版服务端加了目标而 App 没跟上:当作没有目标,点了只标已读
            else -> null
        }
        kind?.let { NotificationTarget(it, t.id, t.parentId) }
    },
    createdAt = createdAt,
    updatedAt = updatedAt,
    read = read,
)

/**
 * 未读数:底栏「我的」与「我的 → 通知」的角标。主界面在前台时每分钟拉一次(随 Me 的轻量查询),
 * 通知页标已读、清空后立即更新;登出清零。
 */
@Singleton
class UnreadNotifications @Inject constructor(private val repo: NotificationsRepository) {
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()

    /** 拉一次;失败(断网、旧服务端)保持原值,返回 null */
    suspend fun refresh(): Int? = gopanResult { repo.unreadCount() }.onSuccess { _count.value = it.coerceAtLeast(0) }.getOrNull()

    fun set(count: Int) {
        _count.value = count.coerceAtLeast(0)
    }

    /** 本地先减(点开一条未读),下次拉取再校正 */
    fun decrement(by: Int = 1) {
        _count.update { (it - by).coerceAtLeast(0) }
    }

    fun clear() {
        _count.value = 0
    }
}
