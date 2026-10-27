package io.astrbot.android.runtime

import android.content.Context
import android.os.StatFs
import android.system.Os
import io.astrbot.android.AppText
import io.astrbot.android.R
import io.astrbot.android.checkLocalized
import io.astrbot.android.core.SecretRedactor
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

data class DesktopConfiguration(val installed: Boolean = false, val qqInstalled: Boolean = false)

/** Generic, optional Linux tools. No third-party bridge or installer is bundled or invoked. */
class DesktopRepository(private val context: Context) {
    val directory = File(context.filesDir, "manager/extensions/desktop")
    private val runtime = BootstrapRuntime(context)
    private val marker get() = File(runtime.rootfs, "var/lib/astrbot-manager/desktop.json")
    private val preferences = context.getSharedPreferences("manager", Context.MODE_PRIVATE)
    private val source by lazy { context.assets.open("runtime/desktop/sources.json").bufferedReader().use { JSONObject(it.readText()) } }
    val qqVersion: String get() = source.getJSONObject("qq").getString("version")

    fun configuration(): DesktopConfiguration {
        val installed = DurableFiles.read(marker)?.optInt("schemaVersion") == 1 &&
            !File(marker.path.removeSuffix(".json") + ".pending").exists() &&
            listOf("Xvfb", "xauth", "fluxbox", "feh", "x11vnc", "websockify", "tmux", "xdpyinfo", "dbus-run-session").all {
                File(runtime.rootfs, "usr/bin/$it").isFile
            } && File(runtime.rootfs, "usr/share/novnc/vnc.html").isFile
        val qq = DurableFiles.read(File(directory, "qq.json"))?.optString("archiveSHA256") ==
            source.getJSONObject("qq").getString("sha256") && File(directory, "qq/opt/QQ/qq").isFile
        return DesktopConfiguration(installed, qq)
    }

    fun desired() = preferences.getBoolean("desired_desktop", false)
    fun qqDesired() = preferences.getBoolean("desired_desktop_qq", false)
    fun setDesired(value: Boolean) { check(preferences.edit().putBoolean("desired_desktop", value).commit()) }
    fun setQqDesired(value: Boolean) { check(preferences.edit().putBoolean("desired_desktop_qq", value).commit()) }

    fun prepareAssets() {
        directory.mkdirs(); Os.chmod(directory.path, 448)
        for (name in listOf("sources.json", "install.py", "desktop.sh", "qq.sh")) {
            val contents = context.assets.open("runtime/desktop/$name").use { it.readBytes() }
            val target = File(directory, name)
            if (!target.isFile || !target.readBytes().contentEquals(contents)) {
                val staged = File(directory, "$name.new")
                staged.writeBytes(contents); Os.chmod(staged.path, 448); Os.rename(staged.path, target.path)
            }
        }
    }

    suspend fun install(progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        checkLocalized(runtime.abi == "arm64-v8a") { AppText.get(R.string.desktop_arm64) }
        checkLocalized(File(runtime.rootfs, ".astrbot-bootstrap.json").isFile) { AppText.get(R.string.cpolar_initialize_first) }
        checkLocalized(StatFs(context.filesDir.path).availableBytes >= 384L * 1024 * 1024) { AppText.get(R.string.desktop_space) }
        prepareAssets()
        progress(AppText.get(R.string.desktop_installing))
        checkLocalized(runInstaller("dependencies", 1200) == 0 && configuration().installed) { AppText.get(R.string.desktop_install_failed) }
        prepareAccess()
        val commands = runtime.execute("/bin/bash", "-lc",
            "ln -sfn '$GUEST/desktop.sh' /usr/local/bin/desktop-start; ln -sfn '$GUEST/qq.sh' /usr/local/bin/qq-desktop")
        checkLocalized(commands.exitCode == 0) { AppText.get(R.string.desktop_install_failed) }
    }

    suspend fun prepareAccess() = withContext(Dispatchers.IO) {
        prepareAssets()
        val secret = File(directory, "password")
        if (!secret.isFile || !File(directory, "vnc.passwd").isFile) {
            val alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val random = SecureRandom()
            val password = buildString { repeat(8) { append(alphabet[random.nextInt(alphabet.length)]) } }
            val result = runtime.execute("/usr/bin/x11vnc", "-storepasswd", password, "$GUEST/vnc.passwd")
            checkLocalized(result.exitCode == 0) { AppText.get(R.string.desktop_access_failed) }
            secret.writeText(password); Os.chmod(secret.path, 384)
            Os.chmod(File(directory, "vnc.passwd").path, 384)
        }
        if (!File(directory, "Xauthority").isFile) {
            val cookie = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val result = runtime.execute("/usr/bin/xauth", "-f", "$GUEST/Xauthority", "add", ":20", ".", cookie)
            checkLocalized(result.exitCode == 0) { AppText.get(R.string.desktop_access_failed) }
            Os.chmod(File(directory, "Xauthority").path, 384)
        }
    }

    fun password(): String = File(directory, "password").readText().trim().also { require(it.length == 8) }

    suspend fun installQq(progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        checkLocalized(configuration().installed) { AppText.get(R.string.desktop_install_first) }
        checkLocalized(StatFs(context.filesDir.path).availableBytes >= 1024L * 1024 * 1024) { AppText.get(R.string.desktop_qq_space) }
        prepareAssets()
        val spec = source.getJSONObject("qq")
        val archive = File(directory, "download.deb")
        val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.MINUTES).followRedirects(false).build()
        try {
            progress(AppText.get(R.string.desktop_qq_downloading))
            client.newCall(Request.Builder().url(spec.getString("url")).build()).execute().use { response ->
                checkLocalized(response.isSuccessful) { AppText.get(R.string.desktop_download_failed) }
                requireNotNull(response.body).byteStream().use { input -> archive.outputStream().use { output ->
                    val buffer = ByteArray(65536); var count = 0L; var reported = -1L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val size = input.read(buffer); if (size < 0) break
                        count += size
                        checkLocalized(count <= spec.getLong("bytes")) { AppText.get(R.string.desktop_integrity_failed) }
                        output.write(buffer, 0, size)
                        val step = count / (16 * 1024 * 1024)
                        if (step != reported) { reported = step; progress(AppText.get(R.string.desktop_qq_download_progress, count / (1024 * 1024))) }
                    }
                } }
            }
            checkLocalized(archive.length() == spec.getLong("bytes") && CpolarRepository.digest(archive) == spec.getString("sha256")) {
                AppText.get(R.string.desktop_integrity_failed)
            }
            progress(AppText.get(R.string.desktop_qq_unpacking))
            checkLocalized(runInstaller("qq", 180) == 0 && configuration().qqInstalled) { AppText.get(R.string.desktop_qq_failed) }
        } finally {
            archive.delete()
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    private suspend fun runInstaller(mode: String, seconds: Long): Int = coroutineScope {
        val process = runtime.processBuilder("/usr/bin/python3", "$GUEST/install.py", mode).start()
        val output = launch(Dispatchers.IO) {
            runCatching { process.inputStream.bufferedReader().useLines { lines -> lines.forEach {
                RuntimeStore.log(context, LogCategory.DESKTOP).append(SecretRedactor.redact(it))
            } } }
        }
        try {
            withTimeout(seconds * 1000) { while (process.isAlive) delay(250) }
            output.join()
            process.exitValue()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                if (process.isAlive) {
                    process.destroy()
                    if (!process.waitFor(8, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS) }
                }
                runCatching { process.inputStream.close() }; output.cancelAndJoin()
            }
        }
    }

    fun uninstall() {
        setDesired(false); setQqDesired(false)
        listOf("qq", "qq.installing", "qq.json", "download.deb").forEach { DurableFiles.delete(File(directory, it)) }
        DurableFiles.delete(marker)
        for ((name, script) in listOf("desktop-start" to "desktop.sh", "qq-desktop" to "qq.sh")) {
            val link = File(runtime.rootfs, "usr/local/bin/$name")
            if (runCatching { Os.readlink(link.path) }.getOrNull() == "$GUEST/$script") link.delete()
        }
    }

    fun diagnosticTail(): String = buildString {
        append(RuntimeStore.log(context, LogCategory.DESKTOP).tail())
        listOf("xvfb", "fluxbox", "vnc", "novnc").forEach { name ->
            val file = File(directory, "logs/$name.log")
            if (file.isFile && file.length() <= 2 * 1024 * 1024) append("\n[$name]\n").append(file.readText().takeLast(12000))
        }
    }.takeLast(120000)

    companion object {
        const val GUEST = "/manager/extensions/desktop"
        const val WEB_UI_URL = "http://127.0.0.1:6081/vnc.html?resize=scale&autoconnect=true"
        const val TERMINAL_ENVIRONMENT = "export DISPLAY=:20 XAUTHORITY=$GUEST/Xauthority HOME=$GUEST/qq-home XDG_CONFIG_HOME=$GUEST/qq-home/.config XDG_DATA_HOME=$GUEST/qq-home/.local/share XDG_CACHE_HOME=$GUEST/qq-home/.cache"
    }
}
