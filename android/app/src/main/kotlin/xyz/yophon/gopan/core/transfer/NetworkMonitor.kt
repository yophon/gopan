package xyz.yophon.gopan.core.transfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import xyz.yophon.gopan.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

/** 联网状态(传输助手的离线提示与恢复后立即刷新;单测用假实现) */
interface Connectivity {
    val online: StateFlow<Boolean>
}

/** 系统网络状态:是否联网、是否不计流量(Wi-Fi 等)。传输并发、离线提示用。 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext context: Context,
    @ApplicationScope scope: CoroutineScope,
) : Connectivity {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    /**
     * 有带 INTERNET 能力的默认网络。不要求 VALIDATED:系统的联网探测在国内常被墙(连 google 的探测地址),
     * 网络明明能用也会是"未验证",按它判断离线会误报。
     */
    fun isOnline(): Boolean = capabilities()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    fun isUnmetered(): Boolean =
        capabilities()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true

    private fun capabilities(): NetworkCapabilities? = try {
        cm?.getNetworkCapabilities(cm.activeNetwork)
    } catch (e: SecurityException) {
        null
    }

    /** 联网状态(默认网络的回调);App 前台时传输助手据此显示离线、恢复时立即刷新 */
    override val online: StateFlow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(isOnline())
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            }

            override fun onLost(network: Network) {
                trySend(isOnline())
            }
        }
        trySend(isOnline())
        try {
            cm?.registerDefaultNetworkCallback(callback)
        } catch (e: RuntimeException) {
            // 个别 ROM 限制回调数量:退化成只看初值
        }
        awaitClose { runCatching { cm?.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(5_000), true)
}
