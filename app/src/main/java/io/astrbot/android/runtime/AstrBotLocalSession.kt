package io.astrbot.android.runtime

import android.content.Context
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/** Per-launch capability for AstrBot's upstream desktop-session API, outside user backups. */
internal object AstrBotLocalSession {
    private const val NAME = "astrbot-local-session.json"

    fun prepare(context: Context, nonce: String) {
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val file = File(context.filesDir, "manager/$NAME")
        DurableFiles.write(file, JSONObject().put("schema", 1).put("nonce", nonce).put("secret", secret))
        Os.chmod(file.path, 384)
    }

    fun secret(context: Context, nonce: String?): String? = runCatching {
        require(!nonce.isNullOrBlank())
        val file = File(context.filesDir, "manager/$NAME")
        require(file.isFile && file.length() <= 4096)
        val value = JSONObject(file.readText())
        require(value.getInt("schema") == 1 && value.getString("nonce") == nonce)
        value.getString("secret").also { require(it.matches(Regex("[0-9a-f]{64}"))) }
    }.getOrNull()
}
