package io.astrbot.android.runtime

import android.content.Context
import android.os.StatFs
import android.system.Os
import io.astrbot.android.AppText
import io.astrbot.android.R
import io.astrbot.android.checkLocalized
import io.astrbot.android.core.LocalEndpoint
import io.astrbot.android.core.ServiceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

data class CpolarConfiguration(val installed: Boolean = false)

/** The official WebUI owns its configuration, login and tunnels. The APK contains no client binary. */
class CpolarRepository(private val context: Context) {
    private val directory = File(context.filesDir, "manager/extensions/cpolar")
    private val binary = File(directory, "cpolar")
    private val marker = File(directory, "installed.json")
    private val preferences = context.getSharedPreferences("manager", Context.MODE_PRIVATE)
    val configurationFile = File(directory, "cpolar.yml")

    fun configuration() = CpolarConfiguration(binary.isFile && DurableFiles.read(marker)?.optString("sha256") == BINARY_SHA256)
    fun desired() = preferences.getBoolean("desired_cpolar", false)
    fun setDesired(value: Boolean) {
        checkLocalized(preferences.edit().putBoolean("desired_cpolar", value).commit()) { AppText.get(R.string.cpolar_save_error) }
    }

    suspend fun ensureConfiguration() {
        directory.mkdirs()
        val newConfiguration = !configurationFile.exists()
        if (newConfiguration) configurationFile.writeText("client_dashboard_addr: 127.0.0.1:9200\ntunnels: {}\n")
        Os.chmod(directory.path, 448)
        Os.chmod(configurationFile.path, 384)
        val installed = checkNotNull(DurableFiles.read(marker))
        // A user can edit or delete any preset in the official dashboard. Do not recreate it later.
        if (installed.optInt("defaultTunnelsSchema") == 1 && !newConfiguration) return
        val defaults = defaultTunnels()
        val script = context.assets.open("runtime/cpolar_defaults.py").bufferedReader().use { it.readText() }
        val result = BootstrapRuntime(context).execute("/opt/astrbot-venv/bin/python", "-c", script,
            "/manager/extensions/cpolar/cpolar.yml", defaults.toString(), timeoutSeconds = 30)
        checkLocalized(result.exitCode == 0 && result.output.trim() in setOf("created", "preserved")) {
            AppText.get(R.string.cpolar_config_error)
        }
        DurableFiles.write(marker, installed.put("defaultTunnelsSchema", 1))
    }

    private fun defaultTunnels(): JSONObject {
        val result = JSONObject().put("ssh", JSONObject().put("proto", "tcp")
            .put("addr", "127.0.0.1:${SshRepository(context).configuration().port}"))
        val configuration = RuntimeConfiguration(context)
        for (id in ServiceId.entries) {
            val endpoint = configuration.read(id).endpoint ?: LocalEndpoint(id.defaultPort)
            result.put(id.name.lowercase(java.util.Locale.ROOT), JSONObject().put("proto", "http")
                .put("addr", if (endpoint.scheme == "https") endpoint.origin else endpoint.origin.removePrefix("http://")))
        }
        return result
    }

    fun redactLog(line: String): String {
        val token = runCatching {
            if (configurationFile.length() > 1024 * 1024) return@runCatching null
            Regex("(?m)^authtoken:\\s*(.+?)\\s*$").find(configurationFile.readText())?.groupValues?.get(1)?.trim()?.let {
                if (it.startsWith('"')) org.json.JSONTokener(it).nextValue() as? String else it.trim('\'')
            }
        }.getOrNull()
        return io.astrbot.android.core.SecretRedactor.redact(if (!token.isNullOrEmpty()) line.replace(token, "[redacted]") else line)
    }

    suspend fun install(progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        checkLocalized(BootstrapRuntime(context).abi == "arm64-v8a") { AppText.get(R.string.cpolar_arm64_only) }
        checkLocalized(StatFs(context.filesDir.path).availableBytes > 96L * 1024 * 1024) { AppText.get(R.string.cpolar_space_error) }
        directory.mkdirs(); Os.chmod(directory.path, 448)
        val archive = File(directory, "download.zip")
        val staged = File(directory, "cpolar.new")
        val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES).followRedirects(false).build()
        try {
            progress(AppText.get(R.string.cpolar_downloading))
            client.newCall(Request.Builder().url(DOWNLOAD_URL).build()).execute().use { response ->
                checkLocalized(response.isSuccessful) { AppText.get(R.string.cpolar_download_error) }
                val body = checkNotNull(response.body)
                body.byteStream().use { input -> archive.outputStream().use { output ->
                    val buffer = ByteArray(65536); var count = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer); if (read < 0) break
                        count += read
                        checkLocalized(count <= ZIP_SIZE) { AppText.get(R.string.cpolar_integrity_error) }
                        output.write(buffer, 0, read)
                    }
                } }
            }
            checkLocalized(archive.length() == ZIP_SIZE && digest(archive) == ZIP_SHA256) { AppText.get(R.string.cpolar_integrity_error) }
            ZipFile(archive).use { zip ->
                val entries = zip.entries().toList()
                checkLocalized(entries.size == 1 && entries.single().name == "cpolar" && entries.single().size == BINARY_SIZE) {
                    AppText.get(R.string.cpolar_integrity_error)
                }
                zip.getInputStream(entries.single()).use { input -> staged.outputStream().use { output ->
                    val buffer = ByteArray(65536); var count = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer); if (read < 0) break
                        count += read
                        checkLocalized(count <= BINARY_SIZE) { AppText.get(R.string.cpolar_integrity_error) }
                        output.write(buffer, 0, read)
                    }
                } }
            }
            checkLocalized(staged.length() == BINARY_SIZE && digest(staged) == BINARY_SHA256) { AppText.get(R.string.cpolar_integrity_error) }
            Os.chmod(staged.path, 448)
            progress(AppText.get(R.string.cpolar_verifying))
            val result = BootstrapRuntime(context).execute("/manager/extensions/cpolar/cpolar.new", "version", timeoutSeconds = 30)
            checkLocalized(result.exitCode == 0 && result.output.contains(VERSION)) { AppText.get(R.string.cpolar_runtime_error) }
            Os.rename(staged.path, binary.path)
            val installed = DurableFiles.read(marker) ?: JSONObject()
            DurableFiles.write(marker, installed.put("version", VERSION).put("sha256", BINARY_SHA256))
            ensureConfiguration()
        } finally {
            archive.delete(); staged.delete()
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    fun verifyInstalled() {
        checkLocalized(configuration().installed && binary.length() == BINARY_SIZE && digest(binary) == BINARY_SHA256) {
            AppText.get(R.string.cpolar_integrity_error)
        }
    }

    fun uninstall() {
        setDesired(false)
        DurableFiles.delete(directory)
    }

    companion object {
        const val VERSION = "3.3.12"
        const val DOWNLOAD_URL = "https://www.cpolar.com/static/downloads/releases/3.3.12/cpolar-stable-linux-arm64.zip"
        const val ZIP_SHA256 = "b88da7cd71f2fcc1b4189c1a36e9a8893438f5e1691789c07303e83360967ffb"
        const val BINARY_SHA256 = "7b92e60cf2e737df8088fe2bad6764b603020a11cf54979d57cff79f61e78239"
        const val ZIP_SIZE = 6878202L
        const val BINARY_SIZE = 19017169L
        fun digest(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) {
                val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size)
            } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
