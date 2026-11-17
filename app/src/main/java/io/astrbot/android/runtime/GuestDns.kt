package io.astrbot.android.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.util.AtomicFile
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.security.MessageDigest

/** Follow Android's current DNS for the default guest resolver, retaining manual edits. */
internal object GuestDns {
    private const val LEGACY = "nameserver 1.1.1.1\nnameserver 8.8.8.8\n"
    private const val HEADER = "# AstrBot for Android: DNS from the active Android network\n"

    internal fun configuration(servers: List<InetAddress>): String {
        val addresses = (servers.filterNot { it.isAnyLocalAddress || it.isMulticastAddress }
            .mapNotNull { it.hostAddress } + listOf("1.1.1.1", "8.8.8.8")).distinct().take(3)
        val body = addresses.joinToString("") { "nameserver $it\n" } + "options timeout:2 attempts:2\n"
        return HEADER + "# content-sha256: ${digest(body)}\n" + body
    }

    internal fun replaceable(text: String): Boolean {
        if (text.replace("\r\n", "\n").trim() == LEGACY.trim()) return true
        if (!text.startsWith(HEADER)) return false
        val remaining = text.removePrefix(HEADER)
        val checksum = remaining.substringBefore('\n').removePrefix("# content-sha256: ")
        // Editing even a generated resolver opts it out of automatic updates.
        return checksum == digest(remaining.substringAfter('\n', ""))
    }

    @Synchronized fun refresh(context: Context, root: File): Boolean = runCatching {
        val target = File(root, "etc/resolv.conf")
        if (!target.isFile || Files.isSymbolicLink(target.toPath()) || target.length() > 4096) return@runCatching false
        val old = target.readText()
        if (!replaceable(old)) return@runCatching false
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val servers = connectivity?.activeNetwork?.let { connectivity.getLinkProperties(it)?.dnsServers }.orEmpty()
        // A transient network loss should retain the last working resolver.
        if (servers.isEmpty()) return@runCatching false
        val updated = configuration(servers)
        if (updated == old) return@runCatching false
        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try { output.write(updated.toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (failure: Throwable) { atomic.failWrite(output); throw failure }
        true
    }.getOrDefault(false)

    fun changes(context: Context) = callbackFlow<Unit> {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: run { close(); return@callbackFlow }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(Unit) }
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) { trySend(Unit) }
        }
        val registered = runCatching { connectivity.registerDefaultNetworkCallback(callback) }.isSuccess
        trySend(Unit)
        awaitClose { if (registered) runCatching { connectivity.unregisterNetworkCallback(callback) } }
    }.conflate()

    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
