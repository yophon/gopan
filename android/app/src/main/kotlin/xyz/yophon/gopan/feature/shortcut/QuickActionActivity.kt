package xyz.yophon.gopan.feature.shortcut

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import xyz.yophon.gopan.MainActivity
import xyz.yophon.gopan.R
import xyz.yophon.gopan.core.auth.AuthState
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.transfer.TransferActions
import javax.inject.Inject

/**
 * 长按图标的「上传文件」、小组件的「发照片」「发文件」:透明的一页,只弹系统选择器,
 * 选好交给上传引擎(与文件页、传输助手里的上传同一套,后台传、断了接着传)就关掉。
 * 「上传文件」传到「我的文件」;另外两个发到传输助手。没登录就打开 App 去登录。
 */
@AndroidEntryPoint
class QuickActionActivity : ComponentActivity() {

    @Inject
    lateinit var transfers: TransferActions

    @Inject
    lateinit var session: SessionStore

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { onPicked(it) }
    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_MEDIA)) { onPicked(it) }

    private val toChat: Boolean get() = intent.action == ACTION_SEND_FILES || intent.action == ACTION_SEND_MEDIA

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 选择器开着时这页被重建:等选择器的结果,不再弹一次
        if (savedInstanceState != null) return
        lifecycleScope.launch {
            // 冷启动时登录态还在恢复(Application 里起的),等一下
            val state = withTimeoutOrNull(WAIT_SESSION_MS) { session.state.first { it !is AuthState.Loading } }
            if (state !is AuthState.SignedIn) {
                startActivity(Intent(this@QuickActionActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
                return@launch
            }
            when (intent.action) {
                ACTION_SEND_MEDIA -> pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                ACTION_UPLOAD, ACTION_SEND_FILES -> pickFiles.launch(arrayOf("*/*"))
                else -> finish()
            }
        }
    }

    private fun onPicked(uris: List<Uri>) {
        if (uris.isEmpty()) {
            finish()
            return
        }
        lifecycleScope.launch {
            val result = runCatching { transfers.uploadUris(uris, parentId = null, toChat = toChat) }.getOrNull()
            val text = when {
                result == null || result.queued == 0 -> getString(R.string.quick_action_failed)
                toChat -> getString(R.string.quick_action_sent_to_chat, result.queued)
                else -> getString(R.string.quick_action_uploading, result.queued)
            }
            Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    companion object {
        const val ACTION_UPLOAD = "xyz.yophon.gopan.action.UPLOAD"
        const val ACTION_SEND_FILES = "xyz.yophon.gopan.action.SEND_FILES"
        const val ACTION_SEND_MEDIA = "xyz.yophon.gopan.action.SEND_MEDIA"
        private const val MAX_MEDIA = 100
        private const val WAIT_SESSION_MS = 5_000L
    }
}
