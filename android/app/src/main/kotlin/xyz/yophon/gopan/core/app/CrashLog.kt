package xyz.yophon.gopan.core.app

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Immutable
import dagger.hilt.android.qualifiers.ApplicationContext
import xyz.yophon.gopan.BuildConfig
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** 一份崩溃记录 */
@Immutable
data class CrashReport(val file: File, val timeMillis: Long, val headline: String)

/**
 * 崩溃日志:未捕获异常写到 filesDir/crash(只留最近 [KEEP] 份),「我的 → 崩溃日志」里查看、导出(系统分享)。
 * 只在本地,不接任何第三方上报。写入前把 URL 的查询串(预签名签名)和 Bearer token 抹掉。
 */
@Singleton
class CrashLog internal constructor(private val dir: File) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "crash"))

    /** 进程启动时装一次:先写文件,再交给原来的处理器(系统照常弹「已停止运行」并结束进程) */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(thread.name, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun record(threadName: String, error: Throwable, now: Instant = Instant.now()): File {
        dir.mkdirs()
        val text = buildString {
            appendLine("gopan ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("时间 ${DISPLAY.format(now.atZone(ZoneId.systemDefault()))}")
            appendLine("线程 $threadName")
            appendLine()
            append(redact(error.stackTraceToString()))
        }
        val file = File(dir, "crash-${FILE.format(now.atZone(ZoneId.systemDefault()))}.txt")
        file.writeText(text)
        trim()
        return file
    }

    /** 最新的在前 */
    fun reports(): List<CrashReport> =
        (dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") && f.name.endsWith(".txt") } ?: emptyArray())
            .sortedByDescending { it.name }
            .map { CrashReport(it, it.lastModified(), headline(it)) }

    fun delete(report: CrashReport) {
        report.file.delete()
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun trim() {
        reports().drop(KEEP).forEach { it.file.delete() }
    }

    /** 异常那一行(第一个以异常类名开头的行) */
    private fun headline(file: File): String = runCatching {
        file.useLines { lines -> lines.drop(5).firstOrNull { it.isNotBlank() } }
    }.getOrNull()?.take(200).orEmpty()

    companion object {
        const val KEEP = 20
        private val FILE = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT)
        private val DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        private val QUERY = Regex("""(https?://[^\s?#"']+)\?[^\s"']*""")
        private val BEARER = Regex("""(?i)(bearer\s+)[A-Za-z0-9._~+/=-]+""")

        /** 抹掉 URL 查询串(预签名签名、token)与 Bearer 凭证 */
        fun redact(text: String): String = text.replace(QUERY, "$1?…").replace(BEARER, "$1…")
    }
}
