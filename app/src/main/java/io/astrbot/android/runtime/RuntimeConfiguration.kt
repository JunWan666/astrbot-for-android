package io.astrbot.android.runtime

import io.astrbot.android.requireLocalized
import io.astrbot.android.LocalizedStateException

import io.astrbot.android.AppText
import io.astrbot.android.R

import android.content.Context
import android.util.AtomicFile
import android.system.Os
import io.astrbot.android.core.LocalEndpoint
import io.astrbot.android.core.ServiceId
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.SecureRandom

data class WebUiConnection(
    val endpoint: LocalEndpoint? = null,
    val enabled: Boolean = true,
    val note: String? = null,
    val lanEnabled: Boolean = false,
)

/** Seed new installs and migrate an empty OneBot setup once; preserve configured networks/accounts. */
class RuntimeConfiguration(private val persistent: File, private val rootfs: File) {
    constructor(context: Context) : this(File(context.filesDir, "persistent"), File(context.filesDir, "runtime-data/ubuntu"))

    fun prepareDefaults() {
        listOf("home", "astrbot/data", "napcat/config", "manager").forEach { File(persistent, it).mkdirs() }
        val astrbot = File(persistent, "astrbot/data/cmd_config.json")
        if (!astrbot.exists()) {
            writeNew(File(persistent, "manager/astrbot-initial-password"), "Aa7" + secret(20))
            writeNew(astrbot, JSONObject().put("dashboard", JSONObject()
                .put("host", "127.0.0.1").put("port", ServiceId.ASTRBOT.defaultPort)).toString(2))
        }
        writeNew(File(persistent, "napcat/config/webui.json"), JSONObject()
            .put("host", "127.0.0.1").put("port", ServiceId.NAPCAT.defaultPort).put("token", secret(24)).toString(2))
        // NapCat copies this template into onebot11_<uin>.json at first account initialization.
        writeNew(File(persistent, "napcat/config/onebot11.json"), JSONObject().put("network", defaultNetwork()).toString(2))
        seedEmptyOneBotOnce(astrbot)
    }

    private fun defaultNetwork() = JSONObject().put("websocketClients", JSONArray().put(JSONObject()
        .put("name", "AstrBot").put("enable", true).put("url", "ws://localhost:6199/ws")
        .put("messagePostFormat", "array").put("reportSelfMessage", false).put("token", "")
        .put("reconnectInterval", 5000).put("heartInterval", 30000).put("debug", false)))

    private fun seedEmptyOneBotOnce(astrbot: File) {
        val marker = File(persistent, "manager/onebot-defaults-v1.json")
        val record = DurableFiles.read(marker) ?: JSONObject().put("files", JSONArray())
        val seen = record.getJSONArray("files")
        val names = (0 until seen.length()).map { seen.getString(it) }.toMutableSet()
        val files = listOf(astrbot) + File(persistent, "napcat/config").listFiles().orEmpty()
            .filter { it.name == "onebot11.json" || it.name.matches(Regex("onebot11_[0-9]{5,20}\\.json")) }
        for (file in files) {
            val name = file.relativeTo(persistent).invariantSeparatorsPath
            if (name in names) continue
            // Invalid/JSON5/custom configurations are left to the upstream editor.
            runCatching {
                require(file.isFile && file.length() <= 8 * 1024 * 1024 && !java.nio.file.Files.isSymbolicLink(file.toPath()))
                val document = JSONObject(file.readText().removePrefix("\uFEFF"))
                val change = if (file == astrbot) {
                    val platforms = document.optJSONArray("platform")
                    if ((!document.has("platform") || platforms != null && platforms.length() == 0)) {
                        document.put("platform", JSONArray().put(JSONObject().put("id", "napcat").put("type", "aiocqhttp")
                            .put("enable", true).put("ws_reverse_host", "127.0.0.1").put("ws_reverse_port", 6199).put("ws_reverse_token", "")))
                        true
                    } else false
                } else {
                    val network = document.optJSONObject("network")
                    val empty = !document.has("network") || network != null && network.keys().asSequence().all {
                        network.optJSONArray(it)?.length() == 0
                    }
                    if (empty) { document.put("network", defaultNetwork()); true } else false
                }
                if (change) {
                    val mode = Os.stat(file.path).st_mode and 511
                    val atomic = AtomicFile(file)
                    val output = atomic.startWrite()
                    try { output.write(document.toString(2).toByteArray()); Os.fchmod(output.fd, mode); atomic.finishWrite(output) }
                    catch (failure: Throwable) { atomic.failWrite(output); throw failure }
                }
            }.onSuccess { names += name }
        }
        DurableFiles.write(marker, JSONObject().put("files", JSONArray(names.sorted())))
    }

    fun read(id: ServiceId, recoverPendingWrite: Boolean = true): WebUiConnection = try {
        val file = File(persistent, if (id == ServiceId.ASTRBOT) "astrbot/data/cmd_config.json" else "napcat/config/webui.json")
        val document = if (file.exists() || File(file.path + ".bak").exists()) {
            // AtomicFile on older Android versions can leave the original in .bak.
            val backup = File(file.path + ".bak")
            if (recoverPendingWrite) AtomicFile(file).openRead().close()
            val selected = if (!recoverPendingWrite && backup.exists()) backup else file
            requireLocalized(selected.length() <= 8 * 1024 * 1024) { AppText.get(R.string.ui_configuration_file_too_large) }
            JSONObject(selected.inputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }.removePrefix("\uFEFF"))
        } else JSONObject()
        val config = if (id == ServiceId.ASTRBOT) {
            if (document.has("dashboard")) document.getJSONObject("dashboard") else JSONObject()
        } else document
        val enabled = if (id == ServiceId.ASTRBOT) !config.has("enable") || config.getBoolean("enable")
            else !config.has("disableWebUI") || !config.getBoolean("disableWebUI")
        if (!enabled) WebUiConnection(enabled = false, note = AppText.get(R.string.ui_webui_is_disabled_in_project_configuration_the_service_process))
        else {
            val host = if (config.has("host")) config.getString("host").trim() else "127.0.0.1"
            val loopback = when (host) {
                "0.0.0.0", "127.0.0.1", "" -> "127.0.0.1"
                "::", "::1", "[::]", "[::1]" -> "::1"
                "localhost" -> "localhost"
                else -> error(AppText.get(R.string.ui_the_listen_address_cannot_be_used_as_a_local))
            }
            val rawPort = if (config.has("port")) config.get("port").toString() else id.defaultPort.toString()
            val port = rawPort.toIntOrNull() ?: error(AppText.get(R.string.ui_webui_port_must_be_an_integer_existing_configuration_is))
            val https = if (id == ServiceId.NAPCAT) {
                File(persistent, "napcat/config/cert.pem").isFile && File(persistent, "napcat/config/key.pem").isFile
            } else {
                val ssl = config.optJSONObject("ssl")
                ssl?.optBoolean("enable") == true && listOf("cert_file", "key_file").all {
                    guestFile(ssl.optString(it))?.isFile == true
                } && (ssl.optString("ca_certs").isBlank() || guestFile(ssl.optString("ca_certs"))?.isFile == true)
            }
            WebUiConnection(LocalEndpoint(port, loopback, if (https) "https" else "http"), note =
                if (https) AppText.get(R.string.ui_https_uses_system_certificate_verification_for_untrusted_certificates_ch) else null,
                lanEnabled = host == "0.0.0.0")
        }
    } catch (_: Exception) {
        // Never expose raw JSON parse failures: the source line can contain credentials.
        WebUiConnection(note = AppText.get(R.string.ui_webui_address_or_configuration_is_unrecognized_the_original_file))
    }

    /** One atomic file commit: interrupted changes leave either the old or requested host. */
    fun setLanAccess(id: ServiceId, enabled: Boolean) {
        try {
            val connection = read(id)
            require(connection.enabled && connection.endpoint != null)
            val file = File(persistent, if (id == ServiceId.ASTRBOT) "astrbot/data/cmd_config.json" else "napcat/config/webui.json")
            require(file.isFile && file.length() <= 8 * 1024 * 1024 && !java.nio.file.Files.isSymbolicLink(file.toPath()))
            val mode = Os.stat(file.path).st_mode and 511
            val document = JSONObject(file.readText(Charsets.UTF_8).removePrefix("\uFEFF"))
            val config = if (id == ServiceId.ASTRBOT) document.optJSONObject("dashboard")
                ?: JSONObject().also { document.put("dashboard", it) } else document
            config.put("host", if (enabled) "0.0.0.0" else "127.0.0.1")
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                output.write(document.toString(2).toByteArray(Charsets.UTF_8))
                Os.fchmod(output.fd, mode)
                atomic.finishWrite(output)
            } catch (failure: Throwable) { atomic.failWrite(output); throw failure }
            require(JSONObject(file.readText()).toString() == document.toString())
        } catch (_: Exception) {
            // JSON errors can include the line holding a credential.
            throw LocalizedStateException(AppText.get(R.string.ui_unable_to_save_lan_settings_check_webui_configuration_file))
        }
    }

    private fun guestFile(path: String): File? {
        if (path.isBlank()) return null
        val normalized = if (path.startsWith("~/")) "/persistent/home/" + path.removePrefix("~/") else path
        val file = if (normalized.startsWith("/persistent/")) File(persistent, normalized.removePrefix("/persistent/"))
            else File(rootfs, if (normalized.startsWith('/')) normalized.removePrefix("/") else "opt/astrbot/$normalized")
        val canonical = file.canonicalFile
        return canonical.takeIf { it.toPath().startsWith(persistent.canonicalFile.toPath()) || it.toPath().startsWith(rootfs.canonicalFile.toPath()) }
    }

    private fun secret(size: Int) = ByteArray(size).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun writeNew(file: File, value: String) {
        if (file.exists()) return
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(value.toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (failure: Throwable) { atomic.failWrite(output); throw failure }
    }
}
