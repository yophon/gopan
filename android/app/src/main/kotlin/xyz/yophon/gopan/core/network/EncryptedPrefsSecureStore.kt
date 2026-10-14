package xyz.yophon.gopan.core.network

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import timber.log.Timber

/**
 * EncryptedSharedPreferences 实现。密钥在本机 Keystore,所以 manifest 里关掉了 allowBackup。
 * Keystore 偶发损坏(系统升级、厂商 ROM)会让 create 抛异常,此时删掉旧文件重建:
 * 代价只是让用户重新登录,比 App 一直起不来强。
 *
 * 注意:Jetpack Security 已被官方标记为弃用但仍可用;这里只用它做一层静态加密,不依赖其它特性。
 */
@Suppress("DEPRECATION")
class EncryptedPrefsSecureStore(
    private val context: Context,
    private val fileName: String = "gopan_secure",
) : SecureStore {

    private val prefs: SharedPreferences by lazy { open() }

    private fun open(): SharedPreferences = try {
        create()
    } catch (e: Exception) {
        Timber.w(e, "加密存储无法打开,重建")
        context.deleteSharedPreferences(fileName)
        create()
    }

    private fun create(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun get(key: String): String? = prefs.getString(key, null)

    // commit 而非 apply:登录态落盘不能丢,调用方都在 IO 线程
    override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.commit()
    }

    override fun clear() {
        prefs.edit().clear().commit()
    }
}
