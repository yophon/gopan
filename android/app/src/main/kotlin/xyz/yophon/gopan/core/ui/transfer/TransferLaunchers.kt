package xyz.yophon.gopan.core.ui.transfer

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import xyz.yophon.gopan.core.transfer.SourceStore
import java.io.File

/** 选文件上传的三个入口:系统文件选择器(多选)、相册(照片和视频,多选)、拍照 */
@Stable
class UploadPickers internal constructor(
    private val launchFiles: () -> Unit,
    private val launchMedia: () -> Unit,
    private val launchCamera: () -> Unit,
) {
    fun pickFiles() = launchFiles()

    fun pickMedia() = launchMedia()

    fun takePhoto() = launchCamera()
}

/**
 * [onUris]:选中的 content URI(调用方负责持久化授权与入队);[onPhoto]:拍好的私有文件。
 * 拍照落点放在 filesDir/transfers/camera(FileProvider 交给相机写),路径存进 saveable 状态,
 * 相机期间进程被回收也能接上。[onNoApp]:没有能处理的应用(没装相机等)。
 */
@Composable
fun rememberUploadPickers(
    onUris: (List<Uri>) -> Unit,
    onPhoto: (File) -> Unit,
    onNoApp: () -> Unit = {},
): UploadPickers {
    val context = LocalContext.current
    val currentOnUris by rememberUpdatedState(onUris)
    val currentOnPhoto by rememberUpdatedState(onPhoto)
    val currentOnNoApp by rememberUpdatedState(onNoApp)
    var pendingPhoto by rememberSaveable { mutableStateOf<String?>(null) }

    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) currentOnUris(uris)
    }
    val media = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_MEDIA)) { uris ->
        if (uris.isNotEmpty()) currentOnUris(uris)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = pendingPhoto
        pendingPhoto = null
        val file = path?.let(::File)
        if (ok && file != null && file.length() > 0) currentOnPhoto(file) else file?.delete()
    }
    return remember(files, media, camera) {
        UploadPickers(
            launchFiles = {
                try {
                    files.launch(arrayOf("*/*"))
                } catch (e: ActivityNotFoundException) {
                    currentOnNoApp()
                }
            },
            launchMedia = {
                try {
                    media.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                } catch (e: ActivityNotFoundException) {
                    currentOnNoApp()
                }
            },
            launchCamera = {
                val file = newCameraFile(context)
                pendingPhoto = file.absolutePath
                try {
                    camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
                } catch (e: ActivityNotFoundException) {
                    pendingPhoto = null
                    file.delete()
                    currentOnNoApp()
                }
            },
        )
    }
}

private fun newCameraFile(context: Context): File {
    val dir = File(File(context.filesDir, "transfers"), "camera")
    return SourceStore.uniqueFile(dir.apply { mkdirs() }, SourceStore.timestampName("拍照", "jpg"))
}

/** 相册选择器一次最多选这么多(系统上限通常是 100) */
private const val MAX_MEDIA = 100

/**
 * 传输相关的运行时权限:Android 13+ 第一次有传输时申请通知权限(进度通知);
 * Android 9 及以下保存到公共「下载」目录前申请存储权限。
 */
@Stable
class TransferPermissions internal constructor(
    private val requestNotifications: () -> Unit,
    private val requestStorage: () -> Unit,
) {
    fun onTransferQueued() = requestNotifications()

    fun requestStoragePermission() = requestStorage()
}

@Composable
fun rememberTransferPermissions(onStorageResult: (Boolean) -> Unit): TransferPermissions {
    val context = LocalContext.current
    val currentOnStorage by rememberUpdatedState(onStorageResult)
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val storage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        currentOnStorage(granted)
    }
    return remember(notifications, storage) {
        TransferPermissions(
            requestNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedNotifications &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    askedNotifications = true
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            requestStorage = { storage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) },
        )
    }
}
