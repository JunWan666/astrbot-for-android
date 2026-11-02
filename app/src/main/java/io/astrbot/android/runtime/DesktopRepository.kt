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
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class DesktopConfiguration(val installed: Boolean = false, val qqInstalled: Boolean = false, val qqUpdateAvailable: Boolean = false)

/** Generic, optional Linux tools. No third-party bridge or installer is bundled or invoked. */
class DesktopRepository(private val context: Context) {
    val directory = File(context.filesDir, "manager/extensions/desktop")
    private val runtime = BootstrapRuntime(context)
    private val marker get() = File(runtime.rootfs, "var/lib/astrbot-manager/desktop.json")
    private val preferences = context.getSharedPreferences("manager", Context.MODE_PRIVATE)
    private val source by lazy { context.assets.open("runtime/desktop/sources.json").bufferedReader().use { JSONObject(it.readText()) } }
    val qqVersion: String get() = source.getJSONObject("qq").getString("version")
    private val compatibilityDigest by lazy {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        for (name in listOf("guest_compat.c", "proc_locks.py", "guest-compat"))
            context.assets.open("runtime/desktop/$name").use { digest.update(it.readBytes()) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun configuration(): DesktopConfiguration {
        val desktopMarker = DurableFiles.read(marker)
        val installed = desktopMarker?.optInt("schemaVersion") == 1 &&
            desktopMarker.optString("compatibilitySHA256") == compatibilityDigest &&
            File(directory, "libguest-compat.so").isFile &&
            !File(marker.path.removeSuffix(".json") + ".pending").exists() &&
            listOf("Xvfb", "xauth", "fluxbox", "feh", "x11vnc", "websockify", "tmux", "xdpyinfo", "dbus-run-session", "gcc", "g++", "make", "python3", "curl").all {
                File(runtime.rootfs, "usr/bin/$it").isFile
            } && File(runtime.rootfs, "usr/share/novnc/vnc.html").isFile
        val qqMarker = DurableFiles.read(File(directory, "qq.json"))
        val qqPresent = qqMarker?.optInt("schemaVersion") == 1 && File(directory, "qq/opt/QQ/qq").isFile
        val qq = qqPresent && qqMarker?.optString("archiveSHA256") == source.getJSONObject("qq").getString("sha256")
        return DesktopConfiguration(installed, qq, qqPresent && !qq)
    }

    fun desired() = preferences.getBoolean("desired_desktop", false)
    fun qqDesired() = preferences.getBoolean("desired_desktop_qq", false)
    fun setDesired(value: Boolean) { check(preferences.edit().putBoolean("desired_desktop", value).commit()) }
    fun setQqDesired(value: Boolean) { check(preferences.edit().putBoolean("desired_desktop_qq", value).commit()) }
    fun lanEnabled() = preferences.getBoolean("desktop_lan", false)
    fun lanPort() = preferences.getInt("desktop_lan_port", 6082)
    fun saveLan(enabled: Boolean, port: Int) {
        require(port in 1024..65535 && port !in listOf(5920, 6081))
        check(preferences.edit().putBoolean("desktop_lan", enabled).putInt("desktop_lan_port", port).commit())
    }

    fun prepareAssets() {
        directory.mkdirs(); Os.chmod(directory.path, 448)
        for (name in listOf("sources.json", "install.py", "desktop.sh", "qq.sh", "lan.sh", "processes.py", "guest_compat.c", "proc_locks.py", "guest-compat")) {
            val contents = context.assets.open("runtime/desktop/$name").use { it.readBytes() }
            val target = File(directory, name)
            if (!target.isFile || !target.readBytes().contentEquals(contents)) {
                val staged = File(directory, "$name.new")
                staged.writeBytes(contents); Os.chmod(staged.path, 448); Os.rename(staged.path, target.path)
            }
        }
    }

    suspend fun recoverProcesses(role: String, session: String? = null) {
        require(role in listOf("desktop", "qq", "lan"))
        if (!File(runtime.rootfs, "usr/bin/python3").isFile ||
            !File(directory, "processes").isDirectory && !File(directory, "logs").isDirectory) return
        prepareAssets()
        val command = listOf("/usr/bin/python3", "$GUEST/processes.py", "cleanup", role) +
            (session?.let { listOf("--session", it) } ?: emptyList())
        val result = runtime.execute(*command.toTypedArray(), timeoutSeconds = 20)
        RuntimeStore.log(context, LogCategory.DESKTOP).append(SecretRedactor.redact(result.output.trim()).take(4096))
        checkLocalized(result.exitCode == 0) { AppText.get(R.string.desktop_process_recovery_failed) }
    }

    suspend fun install(progress: (String) -> Unit) = withContext(Dispatchers.IO) { coroutineScope {
        val started = android.os.SystemClock.elapsedRealtime()
        val phase = AtomicReference(AppText.get(R.string.qq_preparing_bundled))
        fun publish() {
            val elapsed = (android.os.SystemClock.elapsedRealtime() - started) / 1000
            progress(AppText.get(R.string.qq_desktop_elapsed, phase.get(), elapsed / 60, elapsed % 60))
        }
        fun report(value: String) { phase.set(value); publish() }
        val heartbeat = launch { while (isActive) { delay(1000); publish() } }
        try {
            checkLocalized(runtime.abi == "arm64-v8a") { AppText.get(R.string.desktop_arm64) }
            checkLocalized(File(runtime.rootfs, ".astrbot-bootstrap.json").isFile) { AppText.get(R.string.cpolar_initialize_first) }
            prepareAssets()
            publish()
            if (DesktopInstallStore.state.value.let { it.active && it.kind == "desktop" }) DesktopInstallStore.phase(DesktopInstallStage.INSTALLING)
            // A preconfigured snapshot is only ever applied to bootstrap staging.
            // Probe its exact package state before deciding whether to copy .debs.
            val bundleDirectory = File(directory, "bundled-packages").apply { mkdirs() }
            context.assets.open("desktop-packages/$abiName/manifest.json").use {
                val atomic = android.util.AtomicFile(File(bundleDirectory, "manifest.json"))
                val stream = atomic.startWrite()
                try { it.copyTo(stream); atomic.finishWrite(stream) }
                catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
            }
            val activated = runInstaller("activate", 180, ::report)
            if (activated == 75) {
                checkLocalized(StatFs(context.filesDir.path).availableBytes >= 1024L * 1024 * 1024) { AppText.get(R.string.desktop_space) }
                prepareBundledPackages(::report)
                checkLocalized(runInstaller("dependencies", 1200, ::report) == 0) { AppText.get(R.string.desktop_install_failed) }
            } else checkLocalized(activated == 0) { AppText.get(R.string.desktop_install_failed) }
            checkLocalized(configuration().installed) { AppText.get(R.string.desktop_install_failed) }
            DurableFiles.delete(File(directory, "bundled-packages"))
            report(AppText.get(R.string.qq_desktop_access))
            prepareAccess()
            val commands = runtime.execute("/bin/bash", "-lc",
                "ln -sfn '$GUEST/desktop.sh' /usr/local/bin/desktop-start; ln -sfn '$GUEST/qq.sh' /usr/local/bin/qq-desktop; ln -sfn '$GUEST/guest-compat' /usr/local/bin/guest-compat")
            checkLocalized(commands.exitCode == 0) { AppText.get(R.string.desktop_install_failed) }
        } finally { heartbeat.cancelAndJoin() }
    } }

    private suspend fun prepareBundledPackages(progress: (String) -> Unit) {
        val base = "desktop-packages/$abiName"
        val bundle = runCatching { context.assets.open("$base/manifest.json").bufferedReader().use { JSONObject(it.readText()) } }
            .getOrElse { throw io.astrbot.android.LocalizedStateException(AppText.get(R.string.qq_bundle_missing), it) }
        checkLocalized(bundle.getString("architecture") == "arm64" && bundle.getInt("schemaVersion") == 1) { AppText.get(R.string.qq_bundle_missing) }
        val packages = bundle.getJSONArray("packages")
        val targetDirectory = File(directory, "bundled-packages").apply { mkdirs() }
        for (index in 0 until packages.length()) {
            currentCoroutineContext().ensureActive()
            val item = packages.getJSONObject(index)
            progress(AppText.get(R.string.qq_desktop_copy, index + 1, packages.length(), item.getString("name")))
            val name = item.getString("file")
            checkLocalized(name.matches(Regex("[A-Za-z0-9.+_~:%-]+\\.deb"))) { AppText.get(R.string.qq_bundle_missing) }
            val target = File(targetDirectory, name)
            if (target.length() == item.getLong("bytes") && CpolarRepository.digest(target) == item.getString("sha256")) continue
            val staged = File(targetDirectory, "$name.part")
            try {
                context.assets.open("$base/$name").use { input -> staged.outputStream().use { input.copyTo(it) } }
                checkLocalized(staged.length() == item.getLong("bytes") && CpolarRepository.digest(staged) == item.getString("sha256")) { AppText.get(R.string.qq_bundle_missing) }
                Os.rename(staged.path, target.path)
            } finally { staged.delete() }
        }
        DurableFiles.write(File(targetDirectory, "manifest.json"), bundle)
    }

    private val abiName get() = runtime.abi

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
        val resume = File(directory,"download-source.json")
        if (DurableFiles.read(resume)?.optString("sha256") != spec.getString("sha256") || archive.length() > spec.getLong("bytes")) archive.delete()
        DurableFiles.write(resume,JSONObject().put("sha256",spec.getString("sha256")))
        val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.MINUTES).followRedirects(false).build()
        try {
            progress(AppText.get(R.string.desktop_qq_downloading))
            DesktopDownload.download(client,spec.getString("url"),archive,spec.getLong("bytes"))
            DesktopInstallStore.phase(DesktopInstallStage.VERIFYING)
            val verified = archive.length() == spec.getLong("bytes") && CpolarRepository.digest(archive) == spec.getString("sha256")
            if (!verified) archive.delete()
            checkLocalized(verified) {
                AppText.get(R.string.desktop_integrity_failed)
            }
            DesktopInstallStore.phase(DesktopInstallStage.INSTALLING)
            progress(AppText.get(R.string.desktop_qq_unpacking))
            checkLocalized(runInstaller("qq", 180) == 0 && configuration().qqInstalled) { AppText.get(R.string.desktop_qq_failed) }
            archive.delete(); DurableFiles.removeRecord(resume)
        } finally {
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    private suspend fun runInstaller(mode: String, seconds: Long, progress: ((String) -> Unit)? = null): Int = coroutineScope {
        val process = runtime.processBuilder("/usr/bin/python3", "$GUEST/install.py", mode).start()
        var packageTotal = 0
        val unpacked = mutableSetOf<String>()
        val configured = mutableSetOf<String>()
        val packageLine = Regex("^(Unpacking|Setting up|Processing triggers for) ([A-Za-z0-9.+:~-]+)(?: |$)")
        val output = launch(Dispatchers.IO) {
            runCatching { process.inputStream.bufferedReader().useLines { lines -> lines.forEach {
                if (it.startsWith("ASTRBOT_TRANSFER ")) runCatching {
                    DesktopInstallStore.guestProgress(JSONObject(it.removePrefix("ASTRBOT_TRANSFER ")))
                } else if (it.startsWith("ASTRBOT_DESKTOP ")) runCatching {
                    val item = JSONObject(it.removePrefix("ASTRBOT_DESKTOP "))
                    val label = when (item.getString("phase")) {
                        "VERIFYING" -> AppText.get(R.string.qq_desktop_verify, item.getInt("current"), item.getInt("total"), item.getString("package"))
                        "UNPACKING" -> {
                            packageTotal = item.getInt("total")
                            unpacked.clear(); configured.clear()
                            AppText.get(R.string.qq_desktop_install)
                        }
                        "RECOVERING" -> AppText.get(R.string.qq_desktop_recover)
                        "CONFIGURING" -> AppText.get(R.string.qq_desktop_configure)
                        "CHECKING" -> AppText.get(R.string.qq_desktop_check)
                        "COMPATIBILITY" -> AppText.get(R.string.qq_desktop_compat)
                        else -> null
                    }
                    label?.let { labelText -> progress?.invoke(labelText) }
                } else {
                    RuntimeStore.log(context, LogCategory.DESKTOP).append(SecretRedactor.redact(it))
                    if (mode == "dependencies") packageLine.find(it)?.let { match ->
                        val name = match.groupValues[2]
                        val label = when (match.groupValues[1]) {
                            "Unpacking" -> { unpacked += name; AppText.get(R.string.qq_desktop_unpack_package, unpacked.size, packageTotal, name) }
                            "Setting up" -> {
                                configured += name
                                if (packageTotal > 0 && configured.size <= packageTotal)
                                    AppText.get(R.string.qq_desktop_configure_package, configured.size, packageTotal, name)
                                else AppText.get(R.string.qq_desktop_configure_name, name)
                            }
                            else -> AppText.get(R.string.qq_desktop_cache, name)
                        }
                        progress?.invoke(label)
                    }
                }
            } } }
        }
        try {
            var remaining = seconds * 1000
            var previous = android.os.SystemClock.elapsedRealtime()
            while (process.isAlive) {
                delay(250)
                val now = android.os.SystemClock.elapsedRealtime()
                if (DesktopInstallStore.state.value.stage != DesktopInstallStage.PAUSED) remaining -= now - previous
                previous = now
                checkLocalized(remaining > 0) { AppText.get(R.string.desktop_install_failed) }
            }
            output.join()
            if (process.exitValue() == 130) throw DesktopInstallStopped()
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
        listOf("qq", "qq.installing", "qq.previous", "qq.json", "download.deb", "download-source.json", "download-control.json", "qq-runtime", "libguest-compat.so", "libguest-compat.so.new", "compat-profile.json").forEach { DurableFiles.delete(File(directory, it)) }
        DurableFiles.delete(marker)
        for ((name, script) in listOf("desktop-start" to "desktop.sh", "qq-desktop" to "qq.sh", "guest-compat" to "guest-compat")) {
            val link = File(runtime.rootfs, "usr/local/bin/$name")
            if (runCatching { Os.readlink(link.path) }.getOrNull() == "$GUEST/$script") link.delete()
        }
    }

    fun uninstallQq() {
        setQqDesired(false)
        listOf("qq", "qq.installing", "qq.previous", "qq.json", "download.deb", "download-source.json", "download-control.json", "qq-runtime")
            .forEach { DurableFiles.delete(File(directory, it)) }
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
