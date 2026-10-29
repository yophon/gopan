package xyz.yophon.gopan.core.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 配色:墨黑(默认,与网页版同一套)、青绿(3.5 以前的配色)、跟随壁纸(Material You,Android 12+) */
enum class Palette { GOPAN, CLASSIC, DYNAMIC }

enum class DriveViewMode { LIST, GRID }

/** 与 GraphQL 的 NodeOrder 同名,功能线自己映射,偏好层不依赖生成代码 */
enum class DriveSortOrder { NAME, SIZE, UPDATED_AT }

data class DriveSort(
    val order: DriveSortOrder = DriveSortOrder.NAME,
    val desc: Boolean = false,
)

/** 传输设置(设备级,不随账号清) */
data class TransferPrefs(
    /** 只在不计流量的网络(Wi-Fi 等)上传下载 */
    val wifiOnly: Boolean = false,
    /** 单个文件同时上传的分片数:Wi-Fi / 移动网络 */
    val partsOnWifi: Int = DEFAULT_PARTS_WIFI,
    val partsOnMobile: Int = DEFAULT_PARTS_MOBILE,
    /** 用户选的下载目录(SAF 目录树 URI);null 用系统「下载/gopan」 */
    val downloadTreeUri: String? = null,
    val downloadTreeLabel: String? = null,
) {
    companion object {
        const val DEFAULT_PARTS_WIFI = 3
        const val DEFAULT_PARTS_MOBILE = 2
        val PART_CHOICES = listOf(1, 2, 3, 4, 6)
    }
}

data class UserPreferences(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val palette: Palette = Palette.GOPAN,
    val driveViewMode: DriveViewMode = DriveViewMode.LIST,
    val driveSort: DriveSort = DriveSort(),
    val transfer: TransferPrefs = TransferPrefs(),
)

// 文件损坏(断电、磁盘满)时换成空偏好重写,而不是让每次读写都抛 CorruptionException
private val Context.prefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "user_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** 进程内唯一的偏好 DataStore(DI 根用它提供 DataStore<Preferences>) */
fun userPreferencesDataStore(context: Context): DataStore<Preferences> = context.prefsDataStore

/** 用户偏好(DataStore):主题与配色、文件列表的视图模式与排序、传输设置、传输助手草稿。读失败按默认值,不让坏文件拖垮启动。 */
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

    suspend fun setPalette(palette: Palette) {
        dataStore.edit {
            it[Keys.palette] = palette.name
            // 3.6 以前的「动态取色」开关,由配色取代
            it.remove(Keys.legacyDynamicColor)
        }
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

    suspend fun setWifiOnly(value: Boolean) {
        dataStore.edit { it[Keys.wifiOnly] = value }
    }

    suspend fun setParts(onWifi: Int, onMobile: Int) {
        dataStore.edit {
            it[Keys.partsOnWifi] = onWifi
            it[Keys.partsOnMobile] = onMobile
        }
    }

    /** null 恢复为系统「下载」目录 */
    suspend fun setDownloadTree(uri: String?, label: String?) {
        dataStore.edit {
            if (uri == null) {
                it.remove(Keys.downloadTreeUri)
                it.remove(Keys.downloadTreeLabel)
            } else {
                it[Keys.downloadTreeUri] = uri
                it[Keys.downloadTreeLabel] = label.orEmpty()
            }
        }
    }

    /** 传输助手的草稿(账号相关:登出时清) */
    val chatDraft: Flow<String> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.chatDraft].orEmpty() }

    suspend fun setChatDraft(text: String) {
        dataStore.edit { if (text.isEmpty()) it.remove(Keys.chatDraft) else it[Keys.chatDraft] = text }
    }

    /** 助手的连接方式(doc/33 一):按设备记,不跟账号走;"direct" 直连,别的都当转发 */
    val assistantDirect: Flow<Boolean> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.assistantMode] == "direct" }

    suspend fun setAssistantDirect(direct: Boolean) {
        dataStore.edit { it[Keys.assistantMode] = if (direct) "direct" else "proxy" }
    }

    /** 这台设备上次选的模型服务与模型("服务 id|模型");空串 = 没选过 */
    val assistantPick: Flow<String> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.assistantPick].orEmpty() }

    suspend fun setAssistantPick(value: String) {
        dataStore.edit { it[Keys.assistantPick] = value }
    }

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val palette = stringPreferencesKey("palette")
        val legacyDynamicColor = booleanPreferencesKey("dynamic_color")
        val wifiOnly = booleanPreferencesKey("transfer_wifi_only")
        val partsOnWifi = intPreferencesKey("transfer_parts_wifi")
        val partsOnMobile = intPreferencesKey("transfer_parts_mobile")
        val downloadTreeUri = stringPreferencesKey("download_tree_uri")
        val downloadTreeLabel = stringPreferencesKey("download_tree_label")
        val chatDraft = stringPreferencesKey("chat_draft")
        val driveViewMode = stringPreferencesKey("drive_view_mode")
        val driveSortOrder = stringPreferencesKey("drive_sort_order")
        val driveSortDesc = booleanPreferencesKey("drive_sort_desc")
        val assistantMode = stringPreferencesKey("assistant_mode")
        val assistantPick = stringPreferencesKey("assistant_pick")
    }

    private fun Preferences.toUserPreferences() = UserPreferences(
        theme = enumOrDefault(this[Keys.theme], ThemeMode.SYSTEM),
        palette = enumOrDefault(this[Keys.palette], Palette.GOPAN),
        driveViewMode = enumOrDefault(this[Keys.driveViewMode], DriveViewMode.LIST),
        driveSort = DriveSort(
            order = enumOrDefault(this[Keys.driveSortOrder], DriveSortOrder.NAME),
            desc = this[Keys.driveSortDesc] ?: false,
        ),
        transfer = TransferPrefs(
            wifiOnly = this[Keys.wifiOnly] ?: false,
            partsOnWifi = (this[Keys.partsOnWifi] ?: TransferPrefs.DEFAULT_PARTS_WIFI).coerceIn(1, 8),
            partsOnMobile = (this[Keys.partsOnMobile] ?: TransferPrefs.DEFAULT_PARTS_MOBILE).coerceIn(1, 8),
            downloadTreeUri = this[Keys.downloadTreeUri],
            downloadTreeLabel = this[Keys.downloadTreeLabel],
        ),
    )

    private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
