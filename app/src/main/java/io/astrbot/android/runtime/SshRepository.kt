package io.astrbot.android.runtime

import io.astrbot.android.checkLocalized
import io.astrbot.android.requireLocalized

import io.astrbot.android.AppText
import io.astrbot.android.R

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SshConfiguration(val installed: Boolean = true, val port: Int = 8022, val lanEnabled: Boolean = false)

/** Called under RuntimeStore.operationLock. Credentials never enter public manager state or logs. */
class SshRepository(context: Context) {
    private val preferences = context.getSharedPreferences("manager", Context.MODE_PRIVATE)
    private val directory = File(context.filesDir, "extensions/ssh")
    private val settings = File(directory, "settings.json")
    private val keyFile = File(directory, "host-key.json")
    private val alias = "astrbot.ssh.password.v1"
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)

    fun configuration(): SshConfiguration {
        if (!preferences.getBoolean(INSTALLED, true)) return SshConfiguration(installed = false)
        if (!DurableFiles.exists(settings)) return SshConfiguration()
        val data = readSettings()
        return SshConfiguration(port = data.getInt("port"), lanEnabled = data.getBoolean("lanEnabled"))
    }

    fun install() {
        if (!preferences.getBoolean(INSTALLED, true)) {
            DurableFiles.delete(directory)
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias(alias)) store.deleteEntry(alias)
        }
        // Mark explicit installs only after a complete settings record is durable.
        if (!DurableFiles.exists(settings)) {
            val password = encode(ByteArray(18).also { SecureRandom().nextBytes(it) })
            writeSettings(SshConfiguration(), password)
        }
        checkLocalized(preferences.edit().putBoolean(INSTALLED, true).commit()) { AppText.get(R.string.ui_cannot_save_ssh_installation_state) }
    }

    fun ensureDefault() { if (preferences.getBoolean(INSTALLED, true) && !DurableFiles.exists(settings)) install() }

    fun save(port: Int, lanEnabled: Boolean, password: String?) {
        requireLocalized(configuration().installed) { AppText.get(R.string.ui_install_the_ssh_extension_first) }
        requireLocalized(port in 1024..65535) { AppText.get(R.string.ui_ssh_port_must_be_between_1024_and_65535) }
        if (password != null) requireLocalized(password.length in 12..128 && password.none { it.isISOControl() }) {
            AppText.get(R.string.ui_password_must_contain_12_128_characters_without_newlines_or)
        }
        writeSettings(SshConfiguration(port = port, lanEnabled = lanEnabled), password ?: password())
    }

    fun password(): String {
        val data = readSettings()
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, decode(data.getString("iv"))))
            updateAAD(alias.toByteArray(Charsets.UTF_8))
            doFinal(decode(data.getString("password"))).toString(Charsets.UTF_8)
        }
    }

    fun hostKey(): KeyPair {
        if (DurableFiles.exists(keyFile)) {
            require(keyFile.length() <= 16 * 1024 && File(keyFile.path + ".bak").length() <= 16 * 1024)
            val record = requireNotNull(DurableFiles.read(keyFile))
            val factory = KeyFactory.getInstance("RSA")
            return KeyPair(factory.generatePublic(X509EncodedKeySpec(decode(record.getString("public")))),
                factory.generatePrivate(PKCS8EncodedKeySpec(decode(record.getString("private")))))
        }
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        DurableFiles.write(keyFile, JSONObject().put("public", encode(pair.public.encoded)).put("private", encode(pair.private.encoded)))
        android.system.Os.chmod(keyFile.path, 384)
        return pair
    }

    fun uninstall() {
        // This tombstone prevents default provisioning after an interrupted uninstall.
        checkLocalized(preferences.edit().putBoolean(INSTALLED, false).putBoolean(DESIRED, false).commit()) { AppText.get(R.string.ui_cannot_save_ssh_uninstallation_state) }
        DurableFiles.delete(directory)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    fun desired(): Boolean = preferences.getBoolean(INSTALLED, true) && preferences.getBoolean(DESIRED, false)
    fun setDesired(value: Boolean) { checkLocalized(preferences.edit().putBoolean(DESIRED, value).commit()) { AppText.get(R.string.ui_cannot_save_ssh_running_preference) } }

    private fun readSettings(): JSONObject {
        requireLocalized(DurableFiles.exists(settings) && settings.length() <= 16 * 1024 && File(settings.path + ".bak").length() <= 16 * 1024) {
            AppText.get(R.string.ui_ssh_settings_unavailable_configure_or_reinstall_the_extension)
        }
        return requireNotNull(DurableFiles.read(settings)).also {
            requireLocalized(it.getInt("schemaVersion") == 1 && it.getInt("port") in 1024..65535) { AppText.get(R.string.ui_unrecognized_ssh_settings_format) }
        }
    }

    private fun writeSettings(config: SshConfiguration, password: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(create = true))
        cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
        DurableFiles.write(settings, JSONObject().put("schemaVersion", 1).put("port", config.port)
            .put("lanEnabled", config.lanEnabled).put("iv", encode(cipher.iv)).put("password", encode(cipher.doFinal(password.toByteArray(Charsets.UTF_8)))))
        android.system.Os.chmod(directory.path, 448)
        android.system.Os.chmod(settings.path, 384)
    }

    private fun encryptionKey(create: Boolean = false): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        checkLocalized(create) { AppText.get(R.string.ui_ssh_key_unavailable_reset_the_password_or_reinstall_the) }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    companion object { const val INSTALLED = "ssh_installed"; const val DESIRED = "desired_ssh" }
}
