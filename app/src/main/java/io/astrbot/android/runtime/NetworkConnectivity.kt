package io.astrbot.android.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChangedBy

enum class InternetState { UNKNOWN, DISCONNECTED, CAPTIVE_PORTAL, UNVALIDATED, VALIDATED }
internal data class InternetCapabilities(val internet: Boolean, val validated: Boolean = false, val captivePortal: Boolean = false)

data class NetworkSnapshot(val state: InternetState = InternetState.UNKNOWN, val checkedAtMillis: Long? = null) {
    val title: String get() = when (state) {
        InternetState.UNKNOWN -> "网络状态待确认"
        InternetState.DISCONNECTED -> "网络已断开"
        InternetState.CAPTIVE_PORTAL -> "网络需要认证"
        InternetState.UNVALIDATED -> "外网连接待确认"
        InternetState.VALIDATED -> "网络已连通"
    }
    val detail: String get() = when (state) {
        InternetState.UNKNOWN -> "暂时无法确认系统网络，本机后台状态单独检查。"
        InternetState.DISCONNECTED -> "当前没有默认网络，QQ、模型和在线更新可能无法连接；本机后台可继续使用。"
        InternetState.CAPTIVE_PORTAL -> "请先在系统中完成网络认证，再检查 QQ 和模型连接。"
        InternetState.UNVALIDATED -> "系统尚未确认互联网可用，请检查网络；本机后台可继续使用。"
        InternetState.VALIDATED -> "系统已确认互联网连接，QQ、OneBot 和模型需分别检查。"
    }
}

/** System capability observations only: no reachability probes, identifiers or service commands. */
object NetworkConnectivity {
    internal fun classify(hasDefaultNetwork: Boolean, capabilities: InternetCapabilities?): InternetState = when {
        !hasDefaultNetwork -> InternetState.DISCONNECTED
        capabilities == null -> InternetState.UNKNOWN
        capabilities.captivePortal -> InternetState.CAPTIVE_PORTAL
        capabilities.internet && capabilities.validated -> InternetState.VALIDATED
        else -> InternetState.UNVALIDATED
    }
    private fun facts(capabilities: NetworkCapabilities?): InternetCapabilities? = capabilities?.let {
        InternetCapabilities(it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED), it.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL))
    }

    fun snapshot(context: Context): NetworkSnapshot = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork
        NetworkSnapshot(classify(network != null, facts(network?.let(manager::getNetworkCapabilities))), System.currentTimeMillis())
    }.getOrElse { NetworkSnapshot() }

    fun observe(context: Context): Flow<NetworkSnapshot> {
        val application = context.applicationContext
        return callbackFlow {
            val manager = application.getSystemService(ConnectivityManager::class.java)
            val lock = Any()
            var current: Network? = null
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = synchronized(lock) {
                    current = network
                    // Wait for this network's capabilities; do not reuse the old default's state.
                    trySend(NetworkSnapshot(InternetState.UNKNOWN, System.currentTimeMillis()))
                    Unit
                }
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = synchronized(lock) {
                    if (current == network) trySend(NetworkSnapshot(classify(true, facts(capabilities)), System.currentTimeMillis()))
                    Unit
                }
                override fun onLost(network: Network) = synchronized(lock) {
                    // A replacement default may already have arrived before the old one is lost.
                    if (current == network) {
                        current = null
                        trySend(NetworkSnapshot(InternetState.DISCONNECTED, System.currentTimeMillis()))
                    }
                    Unit
                }
            }
            var registered = false
            runCatching {
                synchronized(lock) {
                    manager.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
                    registered = true
                    current = manager.activeNetwork
                    trySend(NetworkSnapshot(classify(current != null, facts(current?.let(manager::getNetworkCapabilities))), System.currentTimeMillis()))
                }
            }.onFailure {
                if (registered) runCatching { manager.unregisterNetworkCallback(callback) }
                registered = false
                trySend(NetworkSnapshot())
                close()
            }
            awaitClose { if (registered) runCatching { manager.unregisterNetworkCallback(callback) } }
        }.conflate().distinctUntilChangedBy { it.state }
    }
}
