package io.astrbot.android.runtime

import android.content.Context
import io.astrbot.android.AppText
import io.astrbot.android.R
import io.astrbot.android.checkLocalized
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

data class SnowLumaState(
    val directory: String = SnowLumaRepository.DEFAULT_DIRECTORY,
    val installed: Boolean = false,
    val profileReady: Boolean = false,
    val active: Boolean = false,
    val ready: Boolean = false,
    val error: String? = null,
)

object SnowLumaStore {
    val state = MutableStateFlow(SnowLumaState())
    // Startup credentials may be printed by the user's program. Keep output in memory only.
    val output = MutableStateFlow("")
    private val credentials = MutableStateFlow<SnowLumaInitialCredentials?>(null)
    val initialCredentials = credentials.asStateFlow()
    private var credentialSession: Any? = null

    @Synchronized internal fun beginCredentialSession(): Any = Any().also {
        credentialSession = it; credentials.value = null
    }
    @Synchronized internal fun captureCredentials(session: Any, value: SnowLumaInitialCredentials) {
        if (credentialSession === session) credentials.value = value
    }
    @Synchronized internal fun endCredentialSession(session: Any) {
        if (credentialSession === session) forgetInitialCredentials()
    }
    @Synchronized fun forgetInitialCredentials() { credentialSession = null; credentials.value = null }
}

/** Inspects user-installed files; never downloads, unpacks, configures or modifies them. */
class SnowLumaRepository(private val context: Context) {
    private val preferences = context.getSharedPreferences("snowluma-extension", Context.MODE_PRIVATE)
    fun directory(): String = preferences.getString("directory", DEFAULT_DIRECTORY) ?: DEFAULT_DIRECTORY
    fun guideCompleted(): Boolean = preferences.getBoolean("guide-completed", false)
    fun completeGuide() { check(preferences.edit().putBoolean("guide-completed", true).commit()) }

    fun saveDirectory(value: String) {
        validateDirectory(value)
        checkLocalized(!SnowLumaStore.state.value.active) { AppText.get(R.string.snow_stop_before_edit) }
        check(preferences.edit().putString("directory", value).commit())
        refresh()
    }

    fun validateDirectory(value: String): File {
        checkLocalized(value.length <= 512 && value.startsWith("/persistent/") &&
            value.none { it.isISOControl() } && value.split('/').none { it == ".." || it == "." } && !value.endsWith('/')) {
            AppText.get(R.string.snow_invalid_directory)
        }
        val persistent = File(context.filesDir, "persistent").canonicalFile
        val target = File(persistent, value.removePrefix("/persistent/")).canonicalFile
        checkLocalized(target.path.startsWith(persistent.path + File.separator)) { AppText.get(R.string.snow_invalid_directory) }
        return target
    }

    fun refresh() {
        val path = directory()
        val folder = runCatching { validateDirectory(path) }.getOrNull()
        val installed = folder != null && listOf("node", "index.mjs", "native/snowluma-linux-arm64.so", "native/snowluma-linux-arm64.node")
            .all { File(folder, it).isFile }
        val profile = runCatching { DurableFiles.read(File(context.filesDir, "manager/extensions/desktop/compat-profile.json")) }.getOrNull()
        val profileReady = installed && profile?.optString("library") == "$path/native/snowluma-linux-arm64.so" &&
            profile.optString("symbol") == "snowluma_linux_hook_start_dynamic" && profile.optString("waitModule") == "wrapper.node"
        SnowLumaStore.state.update { it.copy(directory = path, installed = installed, profileReady = profileReady) }
    }

    companion object {
        const val DEFAULT_DIRECTORY = "/persistent/SnowLuma"
        const val WEB_UI_URL = "http://127.0.0.1:5099"
        const val OFFICIAL_RELEASE = "https://github.com/SnowLuma/SnowLuma/releases/tag/v2.2.1"
        const val ARCHIVE_URL = "https://github.com/SnowLuma/SnowLuma/releases/download/v2.2.1/SnowLuma-v2.2.1-linux-arm64.tar.gz"
        const val ARCHIVE_SHA256 = "26727d30109a5a7cc19e95f7c7a7ca9cbf9eb17adce7c122129e96bda88a2945"
        fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
        fun profileCommand(directory: String): String = "guest-compat configure --library ${quote("$directory/native/snowluma-linux-arm64.so")} --symbol snowluma_linux_hook_start_dynamic --wait-module wrapper.node"
    }
}

/** Owns only the process tree created here, using the existing native session runner. */
class SnowLumaEngine(private val context: Context, private val scope: CoroutineScope) {
    private val repository = SnowLumaRepository(context)
    private val lifecycle = Mutex()
    private val client = OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS).followRedirects(false).build()
    @Volatile private var process: Process? = null
    private var output: Job? = null
    private var monitor: Job? = null

    suspend fun start() = withContext(Dispatchers.IO) { lifecycle.withLock {
        if (process?.isAlive == true) return@withLock
        stopLocked()
        repository.refresh()
        val state = SnowLumaStore.state.value
        checkLocalized(state.installed && state.profileReady) { AppText.get(R.string.snow_finish_setup) }
        checkLocalized(File(context.filesDir, "manager/extensions/desktop/libguest-compat.so").isFile) { AppText.get(R.string.desktop_install_first) }
        checkLocalized(runCatching { ServerSocket(5099, 1, InetAddress.getByName("127.0.0.1")).use { true } }.getOrDefault(false)) {
            AppText.get(R.string.snow_port_busy)
        }
        // Fixed shell code: the user-selected directory is an argv value, never interpolated.
        val owned = BootstrapRuntime(context).processBuilder("/bin/sh", "-c",
            "cd -- \"\$1\" && exec /usr/bin/env SNOWLUMA_HOOK_AUTOLOAD=0 SNOWLUMA_WEBUI_HOST=127.0.0.1 SNOWLUMA_WEBUI_PORT=5099 /manager/extensions/desktop/guest-compat ./node ./index.mjs",
            "snowluma-session", state.directory).start()
        process = owned
        val credentialSession = SnowLumaStore.beginCredentialSession()
        SnowLumaStore.output.value = ""
        SnowLumaStore.state.update { it.copy(active = true, ready = false, error = null) }
        event("started")
        output = scope.launch(Dispatchers.IO) {
            val parser = SnowLumaCredentialParser()
            runCatching { owned.inputStream.reader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(2048)
                while (isActive) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (process === owned) {
                        val chunk = String(buffer, 0, count)
                        parser.append(chunk)?.let { SnowLumaStore.captureCredentials(credentialSession, it) }
                        SnowLumaStore.output.update { (it + chunk).takeLast(32768) }
                    }
                }
            } }
        }
        monitor = scope.launch(Dispatchers.IO) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (isActive && process === owned && owned.isAlive) {
                val ready = runCatching { client.newCall(Request.Builder().url(SnowLumaRepository.WEB_UI_URL).build()).execute().use {
                    it.isSuccessful || it.code in setOf(301, 302, 303, 307, 308, 401, 403)
                } }.getOrDefault(false)
                if (process === owned) SnowLumaStore.state.update { it.copy(ready = ready,
                    error = if (ready) null else if (System.nanoTime() > deadline) AppText.get(R.string.snow_not_ready) else it.error) }
                delay(1500)
            }
            if (process === owned && !owned.isAlive) {
                SnowLumaStore.endCredentialSession(credentialSession)
                val code = owned.exitValue()
                SnowLumaStore.state.update { it.copy(active = false, ready = false, error = AppText.get(R.string.snow_exited, code)) }
                event("exited code=$code")
            }
        }
    } }

    suspend fun stop() = withContext(NonCancellable + Dispatchers.IO) { lifecycle.withLock { stopLocked() } }

    private suspend fun stopLocked() {
        SnowLumaStore.forgetInitialCredentials()
        val owned = process
        process = null
        monitor?.cancelAndJoin(); monitor = null
        if (owned != null) {
            if (owned.isAlive) {
                owned.destroy()
                if (!owned.waitFor(8, TimeUnit.SECONDS)) { owned.destroyForcibly(); owned.waitFor(3, TimeUnit.SECONDS) }
            }
            runCatching { owned.inputStream.close() }
            event("stopped")
        }
        output?.cancelAndJoin(); output = null
        SnowLumaStore.state.update { it.copy(active = false, ready = false) }
    }

    suspend fun close() { stop(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    private fun event(value: String) { RuntimeStore.log(context, LogCategory.MANAGER).append("${java.time.Instant.now()} snowluma-session $value") }
}
