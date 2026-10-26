package io.astrbot.android.runtime

import android.content.Context
import io.astrbot.android.core.ServiceId
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Read for credential controls or local login assistance; never put in logs or saved Activity state. */
object WebUiCredentials {
    internal data class Login(val username: String, val password: String, val signature: String? = null)

    /** Only the generated first-login AstrBot account; never retain a user's replacement password. */
    internal fun initialLogin(context: Context, id: ServiceId): Login? = initialLogin(File(context.filesDir, "persistent"), id)
    internal fun initialLogin(persistent: File, id: ServiceId): Login? = runCatching {
        val password = read(persistent, id) ?: return null
        if (id == ServiceId.NAPCAT) return Login("napcat-webui", password)
        val file = File(persistent, "astrbot/data/cmd_config.json")
        require(file.isFile && file.length() <= 8 * 1024 * 1024)
        val dashboard = JSONObject(file.readText().removePrefix("\uFEFF")).getJSONObject("dashboard")
        require(dashboard.optString("username") == "astrbot" && dashboard.optBoolean("password_change_required"))
        val md5 = MessageDigest.getInstance("MD5").digest(password.toByteArray()).joinToString("") { "%02x".format(it) }
        require(dashboard.optString("password").equals(md5, ignoreCase = true))
        val pbkdf2 = dashboard.optString("pbkdf2_password")
        if (pbkdf2.isNotEmpty()) {
            val parts = pbkdf2.split('$')
            require(parts.size == 4 && parts[0] == "pbkdf2_sha256")
            val rounds = parts[1].toInt()
            require(rounds in 1..1_000_000 && parts[2].matches(Regex("[0-9a-fA-F]{32}")) && parts[3].matches(Regex("[0-9a-fA-F]{64}")))
            val salt = parts[2].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val spec = PBEKeySpec(password.toCharArray(), salt, rounds, 256)
            val actual = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
            val expected = parts[3].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            require(MessageDigest.isEqual(actual, expected))
        }
        Login("astrbot", password, signature(dashboard))
    }.getOrNull()

    /** Cheap per-attempt revalidation also covers SPA setup/logout without a document reload. */
    internal fun isCurrent(context: Context, id: ServiceId, login: Login): Boolean =
        isCurrent(File(context.filesDir, "persistent"), id, login)

    internal fun isCurrent(persistent: File, id: ServiceId, login: Login): Boolean = runCatching {
        if (id == ServiceId.NAPCAT) read(persistent, id) == login.password
        else {
            val file = File(persistent, "astrbot/data/cmd_config.json")
            require(file.isFile && file.length() <= 8 * 1024 * 1024)
            login.signature != null && signature(JSONObject(file.readText().removePrefix("\uFEFF")).getJSONObject("dashboard")) == login.signature
        }
    }.getOrDefault(false)

    private fun signature(dashboard: JSONObject): String {
        val fields = JSONObject()
        listOf("username", "password", "pbkdf2_password", "password_change_required").forEach { fields.put(it, dashboard.opt(it)) }
        return MessageDigest.getInstance("SHA-256").digest(fields.toString().toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun read(context: Context, id: ServiceId): String? = read(File(context.filesDir, "persistent"), id)
    private fun read(persistent: File, id: ServiceId): String? = runCatching {
        val file = File(persistent, if (id == ServiceId.NAPCAT) "napcat/config/webui.json" else "manager/astrbot-initial-password")
        require(file.isFile && file.length() <= if (id == ServiceId.NAPCAT) 8 * 1024 * 1024 else 4096)
        val text = file.readText(Charsets.UTF_8).removePrefix("\uFEFF")
        val value = if (id == ServiceId.NAPCAT) JSONObject(text).getString("token") else text.trim()
        value.takeIf { it.isNotBlank() && it.length <= 4096 }
    }.getOrNull()
}
