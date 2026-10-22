package xyz.yophon.gopan.core.app

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.yophon.gopan.core.AppConfig
import xyz.yophon.gopan.core.network.GopanErrors
import xyz.yophon.gopan.core.network.GopanException
import xyz.yophon.gopan.core.network.await
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** 站点发布的安卓安装包信息(`GET /api/app/android/latest`,见服务端 httpx.AndroidRelease) */
@Serializable
data class AppRelease(
    val versionCode: Long,
    val versionName: String,
    val apk: String,
    val sha256: String = "",
    val size: Long = 0,
    val notes: String = "",
    val publishedAt: String? = null,
    /** 本站的下载地址(相对路径) */
    val url: String,
)

sealed interface UpdateCheck {
    data class Available(val release: AppRelease) : UpdateCheck

    data class UpToDate(val latest: AppRelease) : UpdateCheck

    /** 本站没配发版目录或还没发过版 */
    data object NotConfigured : UpdateCheck
}

/**
 * 检查更新与下载安装包。仓库是私有的,App 不能匿名读 GitHub Releases:安装包由站点自己发
 * (`GOPAN_APP_RELEASE_DIR`,见 doc/09),下到 cacheDir/update 并校验 SHA-256 后交给系统安装器。
 */
interface UpdateRepository {
    suspend fun check(currentVersionCode: Long): UpdateCheck

    /** 下载并校验;同一个包下过就直接用 */
    suspend fun download(release: AppRelease, onProgress: (Long, Long?) -> Unit = { _, _ -> }): File

    /** 站点发布页(功能介绍、安装包、更新日志)上的更新日志,用浏览器打开 */
    val changelogUrl: String
}

@Singleton
class DefaultUpdateRepository internal constructor(
    private val dir: File,
    private val client: OkHttpClient,
    private val config: AppConfig,
) : UpdateRepository {

    @Inject
    constructor(@ApplicationContext context: Context, client: OkHttpClient, config: AppConfig) :
        this(File(context.cacheDir, "update"), client, config)

    override val changelogUrl: String get() = config.baseUrl.trimEnd('/') + CHANGELOG_PATH

    override suspend fun check(currentVersionCode: Long): UpdateCheck = withContext(Dispatchers.IO) {
        val url = config.baseUrl.trimEnd('/') + LATEST_PATH
        val response = try {
            client.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).await()
        } catch (e: IOException) {
            throw GopanException(GopanErrors.NETWORK, GopanErrors.messageFor(GopanErrors.NETWORK, null), cause = e)
        }
        response.use { resp ->
            if (resp.code == 404) return@withContext UpdateCheck.NotConfigured
            if (!resp.isSuccessful) throw GopanException(GopanErrors.HTTP, "检查更新失败(HTTP ${resp.code})")
            val release = parse(requireNotNull(resp.body).string())
            if (release.versionCode > currentVersionCode) UpdateCheck.Available(release) else UpdateCheck.UpToDate(release)
        }
    }

    override suspend fun download(release: AppRelease, onProgress: (Long, Long?) -> Unit): File = withContext(Dispatchers.IO) {
        // 没有校验值就不装:下下来的是要交给系统安装器的可执行文件
        val expected = release.sha256.lowercase()
        if (!SHA256_HEX.matches(expected)) throw GopanException(GopanErrors.UNKNOWN, "更新信息缺少安装包校验值")
        dir.mkdirs()
        val target = File(dir, safeName(release))
        if (target.isFile && sha256(target) == expected) return@withContext target
        // 旧版本的包没用了
        dir.listFiles()?.filter { it.name != target.name }?.forEach { it.delete() }
        val tmp = File(dir, "${target.name}.part")
        val url = config.baseUrl.trimEnd('/').toHttpUrl().resolve(release.url) ?: throw GopanException(GopanErrors.UNKNOWN, "安装包地址无效")
        val response = try {
            client.newCall(Request.Builder().url(url).build()).await()
        } catch (e: IOException) {
            throw GopanException(GopanErrors.NETWORK, GopanErrors.messageFor(GopanErrors.NETWORK, null), cause = e)
        }
        response.use { resp ->
            if (!resp.isSuccessful) throw GopanException(GopanErrors.HTTP, "下载安装包失败(HTTP ${resp.code})")
            val body = requireNotNull(resp.body)
            val total = body.contentLength().takeIf { it > 0 } ?: release.size.takeIf { it > 0 }
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buffer = ByteArray(256 * 1024)
                        var done = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                }
            } catch (e: IOException) {
                tmp.delete()
                throw GopanException(GopanErrors.NETWORK, GopanErrors.messageFor(GopanErrors.NETWORK, null), cause = e)
            }
            val actual = digest.digest().toHex()
            if (actual != expected) {
                tmp.delete()
                throw GopanException(GopanErrors.HASH_MISMATCH, "安装包校验失败,请重试")
            }
        }
        if (!tmp.renameTo(target)) throw GopanException(GopanErrors.UNKNOWN, "保存安装包失败")
        target
    }

    companion object {
        const val LATEST_PATH = "/api/app/android/latest"
        const val CHANGELOG_PATH = "/app#changelog"
        private val SHA256_HEX = Regex("[0-9a-f]{64}")
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(body: String): AppRelease = try {
            json.decodeFromString(AppRelease.serializer(), body)
        } catch (e: Exception) {
            throw GopanException(GopanErrors.UNKNOWN, "更新信息格式不对", cause = e)
        }

        /** 本地文件名:只留安全字符 */
        fun safeName(release: AppRelease): String {
            val base = release.apk.filter { it.isLetterOrDigit() || it in "._-" }.ifEmpty { "gopan-${release.versionCode}.apk" }
            return if (base.endsWith(".apk")) base else "$base.apk"
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().toHex()
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
