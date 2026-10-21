package xyz.yophon.gopan.feature.notifications

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * 系统通知栏提醒要的权限:Android 13+ 运行时申请 POST_NOTIFICATIONS(做法同传输通知,见 core.ui.transfer);
 * 用户在系统设置里关了本 App 的通知时 [granted] 同样为 false,[openSettings] 带到 App 的通知设置。
 * 回到前台时重新判断(用户可能刚从系统设置回来)。
 */
@Stable
class NotificationPermission internal constructor(
    private val context: Context,
    private val grantedState: MutableState<Boolean>,
    private val launch: () -> Unit,
) {
    val granted: Boolean get() = grantedState.value

    /** 还能弹系统授权框(13+ 且没授权);否则只能去系统设置 */
    val canRequest: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !runtimeGranted(context)

    fun request() {
        if (canRequest) launch()
    }

    fun openSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // 个别 ROM 没有这个页面
        }
    }

    companion object {
        private fun runtimeGranted(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        fun check(context: Context): Boolean = runtimeGranted(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}

@Composable
fun rememberNotificationPermission(): NotificationPermission {
    val context = LocalContext.current
    val granted = remember(context) { mutableStateOf(NotificationPermission.check(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted.value = NotificationPermission.check(context)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted.value = NotificationPermission.check(context) }
    return remember(context, launcher) {
        NotificationPermission(context, granted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
