package xyz.yophon.gopan.core.network

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import timber.log.Timber
import java.security.KeyStore

/**
 * EncryptedSharedPreferences 实现。密钥在本机 Keystore,所以 manifest 里关掉了备份与设备迁移。
 *
 * Keystore 偶发损坏(系统升级、厂商 ROM、恢复出厂后残留的文件)会让 create 抛异常。处理分三级:
 * 1. 删掉加密文件**和** Keystore 里的主密钥后重建(只删文件的话,坏掉的主密钥还在,重建照样失败);
 * 2. 仍然失败就退到进程内存:本次运行能用,只是不跨重启保存登录态(用户下次要重新登录);
 * 3. 任何情况下都不抛出 —— 启动阶段就会读它,抛出就是开机即崩的死循环。
 *
 * 注意:Jetpack Security 已被官方标记为弃用但仍可用;这里只用它做一层静态加密,不依赖其它特性。
 */
@Suppress("DEPRECATION")
class EncryptedPrefsSecureStore(
    private val context: Context,
    private val fileName: String = "gopan_secure",
) : SecureStore {

    private val delegate: SecureStore by lazy { open() }

    private fun open(): SecureStore {
        runCatching { return PrefsStore(create()) }
            .onFailure { Timber.w(it, "加密存储无法打开,删除文件与主密钥后重建") }
        resetStorage()
        runCatching { return PrefsStore(create()) }
            .onFailure { Timber.e(it, "加密存储重建失败,本次运行改用内存存储") }
        return InMemorySecureStore()
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

    private fun resetStorage() {
        runCatching { context.deleteSharedPreferences(fileName) }
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        }.onFailure { Timber.w(it, "删除主密钥失败") }
    }

    override fun get(key: String): String? = runCatching { delegate.get(key) }.getOrNull()

    override fun put(key: String, value: String?) {
        runCatching { delegate.put(key, value) }.onFailure { Timber.w(it, "写加密存储失败") }
    }

    override fun clear() {
        runCatching { delegate.clear() }.onFailure { Timber.w(it, "清空加密存储失败") }
    }

    /** 真正的 SharedPreferences 读写;commit 而非 apply:登录态落盘不能丢,调用方都在 IO 线程 */
    private class PrefsStore(private val prefs: SharedPreferences) : SecureStore {
        override fun get(key: String): String? = prefs.getString(key, null)

        override fun put(key: String, value: String?) {
            prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.commit()
        }

        override fun clear() {
            prefs.edit().clear().commit()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
