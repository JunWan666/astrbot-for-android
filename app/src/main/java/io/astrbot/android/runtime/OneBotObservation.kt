package io.astrbot.android.runtime

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import io.astrbot.android.core.OneBotState
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

/** A private, expiring observation of actual aiocqhttp socket objects, bound to one launch. */
internal class OneBotObservation(private val context: Context) {
    @Volatile var nonce: String? = null
        private set
    private val snapshot = File(context.filesDir, "persistent/manager/onebot-observation.json")

    fun prepareLaunch(): String {
        nonce = null
        installObserver()
        return UUID.randomUUID().toString().also { nonce = it }
    }

    fun query(account: String?): OneBotState = runCatching {
        val launch = nonce ?: return OneBotState.UNKNOWN
        require(snapshot.isFile && snapshot.length() <= 64 * 1024)
        val value = JSONObject(snapshot.readText())
        require(value.getInt("schema") == 1 && value.getString("nonce") == launch)
        // Include suspend time so reopening after sleep cannot reuse an old connection report.
        val age = SystemClock.elapsedRealtime() - value.getLong("elapsedMs")
        require(age in 0..12_000)
        val file = File(context.filesDir, "persistent/astrbot/data/cmd_config.json")
        require(file.length() <= 8 * 1024 * 1024)
        val platforms = JSONObject(file.readText()).optJSONArray("platform") ?: return OneBotState.UNKNOWN
        val fingerprints = (0 until platforms.length()).map { platforms.getJSONObject(it) }
            .filter { it.optString("type") == "aiocqhttp" && it.optBoolean("enable") }
            .map { config -> hash(launch, listOf(config.getString("id"), config.getString("ws_reverse_host"),
                config.getInt("ws_reverse_port").toString(), config.optString("ws_reverse_token").takeUnless { it == "null" }.orEmpty()).joinToString("\u0000")) }
        val entries = value.getJSONArray("adapters")
        val observed = (0 until entries.length()).map { entries.getJSONObject(it) }
        // A configuration edit or adapter restart must first produce a matching live observation.
        require(fingerprints.size == observed.size && fingerprints.toSet().size == fingerprints.size)
        require(observed.map { it.getString("fingerprint") }.toSet() == fingerprints.toSet())
        if (fingerprints.isEmpty()) return OneBotState.UNCONFIGURED
        require(observed.all { it.getBoolean("supported") })
        val accounts = observed.flatMap { entry -> entry.getJSONArray("universal").let { a -> (0 until a.length()).map { a.getString(it) } } }
        if (!account.isNullOrBlank() && hash(launch, account) in accounts) return OneBotState.CONNECTED
        if (accounts.isNotEmpty() || observed.any { it.getBoolean("partial") }) return OneBotState.UNKNOWN
        OneBotState.DISCONNECTED
    }.getOrDefault(OneBotState.UNKNOWN)

    private fun installObserver() {
        runCatching {
            val directory = File(context.filesDir, "persistent/astrbot/data/plugins/astrbot_plugin_android_observer")
            require(!Files.isSymbolicLink(directory.toPath()))
            // Android aliases /data/user/0 to /data/data on this device.
            require(directory.canonicalFile.toPath().startsWith(File(context.filesDir, "persistent").canonicalFile.toPath()))
            val marker = File(directory, ".android-manager-owned.json")
            val assets = listOf("main.py", "metadata.yaml").associateWith { context.assets.open("observer/$it").use { input -> input.readBytes() } }
            if (directory.exists()) {
                require(marker.isFile && marker.length() <= 4096)
                val previous = JSONObject(marker.readText())
                require(assets.keys.all { name ->
                    val file = File(directory, name)
                    !Files.isSymbolicLink(file.toPath()) && file.isFile && file.length() <= 64 * 1024 &&
                        sha256(file.readBytes()) == previous.getString(name)
                })
            } else require(directory.mkdirs())
            for ((name, content) in assets) {
                val atomic = AtomicFile(File(directory, name))
                val stream = atomic.startWrite()
                try { stream.write(content); atomic.finishWrite(stream) }
                catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
            }
            DurableFiles.write(marker, JSONObject().apply { assets.forEach { (name, content) -> put(name, sha256(content)) } })
        }.onFailure {
            RuntimeStore.log(context, LogCategory.MANAGER).append("连接观察插件未安装或已被修改，连接状态可能显示未知；现有插件及启用设置已保留")
        }
    }

    companion object {
        internal fun hash(nonce: String, value: String) = sha256("$nonce:$value".toByteArray(Charsets.UTF_8))
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
