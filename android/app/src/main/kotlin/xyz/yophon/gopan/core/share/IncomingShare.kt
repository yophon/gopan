package xyz.yophon.gopan.core.share

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import xyz.yophon.gopan.core.transfer.SourceStore
import xyz.yophon.gopan.di.ApplicationScope
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 待处理的分享内容:文本进传输助手草稿,文件直接发 */
data class SharePayload(
    val text: String?,
    /** 已经拷进私有目录的文件 */
    val files: List<File>,
    /** 读取失败、被跳过的文件数 */
    val failed: Int,
) {
    val isEmpty: Boolean get() = text.isNullOrBlank() && files.isEmpty() && failed == 0
}

/** 正在拷贝分享来的文件 */
data class ShareReceiving(val count: Int, val bytes: Long)

/** 传输助手取分享内容的接口(单测用假实现) */
interface ShareInbox {
    val pending: StateFlow<SharePayload?>
    val receiving: StateFlow<ShareReceiving?>

    /** 取走全部待处理内容 */
    fun take(): SharePayload?
}

/**
 * 系统分享(SEND / SEND_MULTIPLE)的接收与暂存。分享授予的 URI 读权限只在接收页所在任务存活期间有效,
 * 所以一收到就在应用作用域里把文件拷进 filesDir/transfers/incoming,之后由传输助手页取走:
 * 文本接到草稿后面(不自动发),文件入上传队列。未登录时先存着,登录后传输助手再处理,不丢
 * (所以不挂登出钩子;拷贝放在 transfers 目录之外,冷启动时的"无 cookie → 清本地数据"不会误删正在拷的文件)。
 * 多次分享在被取走前合并。
 */
@Singleton
class IncomingShareStore @Inject constructor(
    private val sources: SourceStore,
    @ApplicationScope private val scope: CoroutineScope,
) : ShareInbox {
    private val _pending = MutableStateFlow<SharePayload?>(null)
    override val pending: StateFlow<SharePayload?> = _pending.asStateFlow()

    private val _receiving = MutableStateFlow<ShareReceiving?>(null)
    override val receiving: StateFlow<ShareReceiving?> = _receiving.asStateFlow()

    private val mutex = Mutex()

    /** 是分享 Intent 就接下来并返回 true */
    fun accept(intent: Intent): Boolean {
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return false
        val text = shareText(
            intent.getStringExtra(Intent.EXTRA_SUBJECT),
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
        )
        val uris = streams(intent)
        if (text == null && uris.isEmpty()) return true
        scope.launch {
            mutex.withLock {
                val files = mutableListOf<File>()
                var failed = 0
                var copied = 0L
                _receiving.value = ShareReceiving(uris.size, 0)
                for ((i, uri) in uris.withIndex()) {
                    try {
                        val base = copied
                        val f = sources.copyIn(uri, sources.incomingDir, "分享文件 ${i + 1}") { n: Long ->
                            _receiving.value = ShareReceiving(uris.size, base + n)
                        }
                        copied += f.length()
                        if (f.length() == 0L) {
                            f.delete()
                            failed++
                        } else {
                            files += f
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Timber.w("读取分享的文件失败:%s", e.javaClass.simpleName)
                        failed++
                    }
                }
                _receiving.value = null
                _pending.update { old -> merge(old, SharePayload(text, files, failed)) }
            }
        }
        return true
    }

    override fun take(): SharePayload? {
        val p = _pending.value ?: return null
        _pending.value = null
        return p.takeUnless { it.isEmpty }
    }

    /** 还在等处理的文件(启动时清理孤儿副本要避开它们) */
    fun pendingFiles(): Set<String> = _pending.value?.files?.map { it.absolutePath }?.toSet().orEmpty()

    /** 正在拷贝(清理孤儿要等它结束) */
    val busy: Boolean get() = mutex.isLocked

    private fun streams(intent: Intent): List<Uri> {
        val out = LinkedHashSet<Uri>()
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { out += it }
        } else {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { out += it }
        }
        // 有的应用只放 ClipData(授权也挂在 ClipData 上)
        if (out.isEmpty()) {
            intent.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { out += it }
            }
        }
        return out.toList()
    }

    companion object {
        /**
         * 主题与正文去重后换行拼接(与网页端 recordToShare 一致):一段被另一段完整包含的丢掉,相同的只留一个。
         * 典型:浏览器分享网页时 subject 是标题、text 是"标题 链接"。
         */
        fun shareText(subject: String?, text: String?): String? {
            val parts = listOfNotNull(subject?.trim(), text?.trim()).filter { it.isNotEmpty() }
            val kept = parts.filterIndexed { i, p ->
                parts.withIndex().none { (j, q) -> j != i && (q.contains(p) && (q.length > p.length || j < i)) }
            }
            return kept.joinToString("\n").ifEmpty { null }
        }

        fun merge(old: SharePayload?, new: SharePayload): SharePayload {
            if (old == null) return new
            val text = listOfNotNull(old.text, new.text).joinToString("\n").ifEmpty { null }
            return SharePayload(text, old.files + new.files, old.failed + new.failed)
        }
    }
}

/** 应用内导航请求(通知点按、系统分享):主界面收集后跳转并清空。OPEN_JOBS = 传输列表并停在「后台任务」栏 */
enum class NavRequest { OPEN_CHAT, OPEN_TRANSFERS, OPEN_JOBS }

@Singleton
class AppNavRequests @Inject constructor() {
    private val _request = MutableStateFlow<NavRequest?>(null)
    val request: StateFlow<NavRequest?> = _request.asStateFlow()

    fun post(request: NavRequest) {
        _request.value = request
    }

    fun consume(request: NavRequest) {
        _request.compareAndSet(request, null)
    }
}
