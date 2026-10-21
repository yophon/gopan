package xyz.yophon.gopan.core.notify

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 系统通知栏提醒的本地状态,与用户偏好同一个 DataStore(键都以 inbox_ 开头,不动 UserPreferencesRepository):
 * - 开关(设备级,默认开,doc/22 决策 6);
 * - 提醒到哪条(账号级,登出清掉);
 * - 是否已经申请过通知权限(Android 13+ 只主动问一次)。
 */
@Singleton
class InboxPrefs @Inject constructor(private val dataStore: DataStore<Preferences>) {

    private val data: Flow<Preferences> = dataStore.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }

    val systemAlerts: Flow<Boolean> = data.map { it[Keys.enabled] ?: true }.distinctUntilChanged()

    suspend fun setSystemAlerts(on: Boolean) {
        dataStore.edit { it[Keys.enabled] = on }
    }

    suspend fun cursor(): InboxCursor? {
        val p = data.first()
        val user = p[Keys.cursorUser] ?: return null
        return InboxCursor(user, p[Keys.cursorId].orEmpty(), p[Keys.cursorAt].orEmpty())
    }

    suspend fun saveCursor(cursor: InboxCursor) {
        dataStore.edit {
            it[Keys.cursorUser] = cursor.userId
            it[Keys.cursorId] = cursor.lastId
            it[Keys.cursorAt] = cursor.lastCreatedAt
        }
    }

    suspend fun clearCursor() {
        dataStore.edit {
            it.remove(Keys.cursorUser)
            it.remove(Keys.cursorId)
            it.remove(Keys.cursorAt)
        }
    }

    suspend fun permissionAsked(): Boolean = data.first()[Keys.permissionAsked] ?: false

    suspend fun markPermissionAsked() {
        dataStore.edit { it[Keys.permissionAsked] = true }
    }

    private object Keys {
        val enabled = booleanPreferencesKey("inbox_system_alerts")
        val cursorUser = stringPreferencesKey("inbox_cursor_user")
        val cursorId = stringPreferencesKey("inbox_cursor_id")
        val cursorAt = stringPreferencesKey("inbox_cursor_at")
        val permissionAsked = booleanPreferencesKey("inbox_permission_asked")
    }
}
