package io.astrbot.android.runtime

import android.content.Context
import android.system.Os
import android.util.AtomicFile
import io.astrbot.android.core.ServiceId
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
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
        val username = dashboard.optString("username")
        val generatedUsername = File(persistent, "manager/astrbot-initial-username").takeIf { it.isFile && it.length() <= 4096 }?.readText() ?: "astrbot"
        require(username.isNotBlank() && username == generatedUsername && dashboard.optBoolean("password_change_required"))
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
        Login(username, password, signature(dashboard))
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

    /** Call only after stopping this service; business configuration and QQ sessions stay in place. */
    internal fun reset(persistent: File, id: ServiceId) {
        val file = File(persistent, if (id == ServiceId.NAPCAT) "napcat/config/webui.json" else "astrbot/data/cmd_config.json")
        require(file.isFile && file.length() <= 8 * 1024 * 1024)
        val config = JSONObject(file.readText().removePrefix("\uFEFF"))
        val random = SecureRandom()
        fun randomHex(bytes: Int) = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val password = if (id == ServiceId.ASTRBOT) "Aa7!${randomHex(16)}" else randomHex(16)
        if (id == ServiceId.NAPCAT) {
            config.put("token", password)
            DurableFiles.write(file, config)
            Os.chmod(file.path, 384)
            return
        }
        val dashboard = config.getJSONObject("dashboard")
        require(dashboard.optString("username").isNotBlank())
        val salt = ByteArray(16).also(random::nextBytes)
        val rounds = 260_000
        val spec = PBEKeySpec(password.toCharArray(), salt, rounds, 256)
        val derived = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
        fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
        dashboard.put("password", MessageDigest.getInstance("MD5").digest(password.toByteArray()).hex())
            .put("pbkdf2_password", "pbkdf2_sha256\$$rounds\$${salt.hex()}\$${derived.hex()}")
            .put("password_change_required", true).put("password_storage_upgraded", true)
            .put("jwt_secret", randomHex(32))
        val initial = File(persistent, "manager/astrbot-initial-password")
        val username = File(persistent, "manager/astrbot-initial-username")
        val old = initial.takeIf { it.isFile }?.readBytes()
        val oldUsername = username.takeIf { it.isFile }?.readBytes()
        try {
            writeSecret(username, dashboard.getString("username").toByteArray())
            writeSecret(initial, password.toByteArray())
            DurableFiles.write(file, config)
            Os.chmod(file.path, 384)
        } catch (failure: Throwable) {
            if (old == null) AtomicFile(initial).delete() else writeSecret(initial, old)
            if (oldUsername == null) AtomicFile(username).delete() else writeSecret(username, oldUsername)
            throw failure
        }
    }

    private fun writeSecret(file: File, bytes: ByteArray) {
        file.parentFile!!.mkdirs()
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (failure: Throwable) { atomic.failWrite(output); throw failure }
        Os.chmod(file.path, 384)
        DurableFiles.sync(file.parentFile!!)
    }

    private fun read(persistent: File, id: ServiceId): String? = runCatching {
        val file = File(persistent, if (id == ServiceId.NAPCAT) "napcat/config/webui.json" else "manager/astrbot-initial-password")
        require(file.isFile && file.length() <= if (id == ServiceId.NAPCAT) 8 * 1024 * 1024 else 4096)
        val text = file.readText(Charsets.UTF_8).removePrefix("\uFEFF")
        val value = if (id == ServiceId.NAPCAT) JSONObject(text).getString("token") else text.trim()
        value.takeIf { it.isNotBlank() && it.length <= 4096 }
    }.getOrNull()
}
