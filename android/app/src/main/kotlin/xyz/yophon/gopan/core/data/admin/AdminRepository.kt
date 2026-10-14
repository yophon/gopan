package xyz.yophon.gopan.core.data.admin

import androidx.compose.runtime.Immutable
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import xyz.yophon.gopan.core.network.requireData
import xyz.yophon.gopan.graphql.AdminCreateUserMutation
import xyz.yophon.gopan.graphql.AdminOverviewQuery
import xyz.yophon.gopan.graphql.AdminResetPasswordMutation
import xyz.yophon.gopan.graphql.AdminRetryFailedTasksMutation
import xyz.yophon.gopan.graphql.AdminSetDisabledMutation
import xyz.yophon.gopan.graphql.AdminSetQuotaMutation
import xyz.yophon.gopan.graphql.AdminUsersQuery
import javax.inject.Inject
import javax.inject.Singleton

@Immutable
data class AdminUser(
    val id: String,
    val username: String,
    val isAdmin: Boolean,
    val disabled: Boolean,
    val quotaBytes: Long,
    val usedBytes: Long,
    val createdAt: String,
)

@Immutable
data class AdminOverview(
    val userCount: Int,
    val totalUsedBytes: Long,
    val blobCount: Int,
    val blobBytes: Long,
    /** 任务队列按状态计数(pending / running / failed / done …) */
    val taskCounts: List<Pair<String, Int>>,
) {
    val failedTasks: Int get() = taskCounts.firstOrNull { it.first == "failed" }?.second ?: 0
}

/** 管理端(所有 admin* 字段服务端要求 is_admin)。失败抛 GopanException。 */
interface AdminRepository {
    suspend fun users(): List<AdminUser>

    suspend fun overview(): AdminOverview

    suspend fun createUser(username: String, password: String, quotaBytes: Long): AdminUser

    suspend fun setQuota(userId: String, quotaBytes: Long)

    suspend fun setDisabled(userId: String, disabled: Boolean)

    /** 生成随机新密码并吊销该用户全部会话;明文只在返回值出现一次 */
    suspend fun resetPassword(userId: String): String

    /** 返回重排的任务数 */
    suspend fun retryFailedTasks(): Int
}

@Singleton
class ApolloAdminRepository @Inject constructor(private val apollo: ApolloClient) : AdminRepository {

    override suspend fun users(): List<AdminUser> =
        apollo.query(AdminUsersQuery()).execute().requireData().adminUsers.map {
            AdminUser(it.id, it.username, it.isAdmin, it.disabled, it.quotaBytes, it.usedBytes, it.createdAt)
        }

    override suspend fun overview(): AdminOverview {
        val o = apollo.query(AdminOverviewQuery()).execute().requireData().adminOverview
        return AdminOverview(o.userCount, o.totalUsedBytes, o.blobCount, o.blobBytes, o.taskCounts.map { it.status to it.count })
    }

    override suspend fun createUser(username: String, password: String, quotaBytes: Long): AdminUser {
        val u = apollo.mutation(AdminCreateUserMutation(username, password, Optional.present(quotaBytes))).execute().requireData().adminCreateUser
        return AdminUser(u.id, u.username, isAdmin = false, disabled = u.disabled, quotaBytes = u.quotaBytes, usedBytes = u.usedBytes, createdAt = "")
    }

    override suspend fun setQuota(userId: String, quotaBytes: Long) {
        apollo.mutation(AdminSetQuotaMutation(userId, quotaBytes)).execute().requireData()
    }

    override suspend fun setDisabled(userId: String, disabled: Boolean) {
        apollo.mutation(AdminSetDisabledMutation(userId, disabled)).execute().requireData()
    }

    override suspend fun resetPassword(userId: String): String =
        apollo.mutation(AdminResetPasswordMutation(userId)).execute().requireData().adminResetPassword

    override suspend fun retryFailedTasks(): Int =
        apollo.mutation(AdminRetryFailedTasksMutation()).execute().requireData().adminRetryFailedTasks
}
