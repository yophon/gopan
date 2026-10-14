package xyz.yophon.gopan.core.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class DriveViewMode { LIST, GRID }

/** 与 GraphQL 的 NodeOrder 同名,功能线自己映射,偏好层不依赖生成代码 */
enum class DriveSortOrder { NAME, SIZE, UPDATED_AT }

data class DriveSort(
    val order: DriveSortOrder = DriveSortOrder.NAME,
    val desc: Boolean = false,
)

data class UserPreferences(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val driveViewMode: DriveViewMode = DriveViewMode.LIST,
    val driveSort: DriveSort = DriveSort(),
)

private val Context.prefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

/** 进程内唯一的偏好 DataStore(DI 根用它提供 DataStore<Preferences>) */
fun userPreferencesDataStore(context: Context): DataStore<Preferences> = context.prefsDataStore

/** 用户偏好(DataStore):主题、文件列表的视图模式与排序。读失败按默认值,不让坏文件拖垮启动。 */
@Singleton
class UserPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val preferences: Flow<UserPreferences> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toUserPreferences() }

    suspend fun setTheme(mode: ThemeMode) {
        dataStore.edit { it[Keys.theme] = mode.name }
    }

    suspend fun setDriveViewMode(mode: DriveViewMode) {
        dataStore.edit { it[Keys.driveViewMode] = mode.name }
    }

    suspend fun setDriveSort(sort: DriveSort) {
        dataStore.edit {
            it[Keys.driveSortOrder] = sort.order.name
            it[Keys.driveSortDesc] = sort.desc
        }
    }

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val driveViewMode = stringPreferencesKey("drive_view_mode")
        val driveSortOrder = stringPreferencesKey("drive_sort_order")
        val driveSortDesc = booleanPreferencesKey("drive_sort_desc")
    }

    private fun Preferences.toUserPreferences() = UserPreferences(
        theme = enumOrDefault(this[Keys.theme], ThemeMode.SYSTEM),
        driveViewMode = enumOrDefault(this[Keys.driveViewMode], DriveViewMode.LIST),
        driveSort = DriveSort(
            order = enumOrDefault(this[Keys.driveSortOrder], DriveSortOrder.NAME),
            desc = this[Keys.driveSortDesc] ?: false,
        ),
    )

    private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
