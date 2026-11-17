package io.astrbot.android.runtime

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

enum class MessagePlatform(val types: Set<String>, val minimumVersion: String) {
    QQ(setOf("qq_official", "qq_official_webhook"), "4.0.0"),
    WECHAT(setOf("weixin_oc"), "4.22.0")
}

enum class AdapterState { UNCONFIGURED, DISABLED, STOPPED, STARTING, RUNNING, ERROR, UNKNOWN, UNSUPPORTED }
data class MessagePlatformStatus(val platform: MessagePlatform, val state: AdapterState = AdapterState.UNKNOWN,
    val total: Int = 0, val running: Int = 0)

/** Only configuration counts and expiring observer status leave the private data directory. */
internal class MessagePlatformStatusReader(private val context: Context) {
    fun read(nonce: String?, serviceRunning: Boolean, serviceStarting: Boolean): List<MessagePlatformStatus> {
        val file = File(context.filesDir, "persistent/astrbot/data/cmd_config.json")
        if (!file.exists()) return MessagePlatform.entries.map { MessagePlatformStatus(it, AdapterState.UNCONFIGURED) }
        return runCatching {
            require(file.isFile && file.length() <= 8 * 1024 * 1024)
            val bytes = file.readBytes()
            val config = JSONObject(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF"))
            val array = config.optJSONArray("platform")
            require(!config.has("platform") || array != null)
            val configs = (0 until (array?.length() ?: 0)).map { array!!.getJSONObject(it) }
            val observation = runCatching {
                require(!nonce.isNullOrEmpty())
                val snapshot = File(context.filesDir, "persistent/manager/platform-observation.json")
                require(snapshot.isFile && snapshot.length() <= 128 * 1024)
                JSONObject(snapshot.readText()).also {
                    require(it.getInt("schema") == 1 && it.getString("nonce") == nonce)
                    require(SystemClock.elapsedRealtime() - it.getLong("elapsedMs") in 0..12_000)
                    require(it.getString("configDigest") == digest(nonce, bytes))
                }
            }.getOrNull()
            val reports = observation?.optJSONArray("adapters")
            MessagePlatform.entries.map { platform ->
                val selected = configs.filter { it.optString("type") in platform.types }
                val enabled = selected.filter { it.optBoolean("enable", true) }
                val states = enabled.map { item ->
                    val key = digest(nonce.orEmpty(), (item.optString("type") + "\u0000" + item.optString("id")).toByteArray())
                    val matching = (0 until (reports?.length() ?: 0)).mapNotNull { reports?.optJSONObject(it) }
                        .filter { it.optString("key") == key }
                    matching.singleOrNull()?.optString("status") ?: "unknown"
                }
                val running = states.count { it == "running" }
                val sources = File(context.filesDir, "runtime-data/ubuntu/opt/astrbot/astrbot/core/platform/sources")
                val supported = !sources.isDirectory || (if (platform == MessagePlatform.WECHAT)
                    File(sources, "weixin_oc/weixin_oc_adapter.py").isFile
                    else File(sources, "qqofficial").isDirectory || File(sources, "qqofficial_webhook").isDirectory)
                val state = when {
                    !supported -> AdapterState.UNSUPPORTED
                    selected.isEmpty() -> AdapterState.UNCONFIGURED
                    enabled.isEmpty() -> AdapterState.DISABLED
                    serviceStarting -> AdapterState.STARTING
                    !serviceRunning -> AdapterState.STOPPED
                    "error" in states -> AdapterState.ERROR
                    "unknown" in states -> AdapterState.UNKNOWN
                    states.all { it == "running" } -> AdapterState.RUNNING
                    "pending" in states -> AdapterState.STARTING
                    else -> AdapterState.STOPPED
                }
                MessagePlatformStatus(platform, state, selected.size, running)
            }
        }.getOrElse { MessagePlatform.entries.map { MessagePlatformStatus(it) } }
    }

    private fun digest(nonce: String, bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").run {
        update((nonce + ":").toByteArray()); digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
