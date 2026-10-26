package io.astrbot.android.runtime

import android.content.Context
import io.astrbot.android.AppText
import io.astrbot.android.R
import io.astrbot.android.checkLocalized
import io.astrbot.android.core.RotatingLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

data class CpolarState(val configuration: CpolarConfiguration = CpolarConfiguration(), val running: Boolean = false,
    val desired: Boolean = false, val busy: Boolean = false, val paused: Boolean = false,
    val webUiReady: Boolean = false, val error: String? = null)

object CpolarStore { val state = MutableStateFlow(CpolarState()) }

/** RuntimeService owns the service-mode client, including its official local dashboard and tunnel workers. */
class CpolarEngine(private val context: Context, private val scope: CoroutineScope) {
    private val repository = CpolarRepository(context)
    private val log = RotatingLog(java.io.File(context.filesDir, "logs"), "cpolar", maxBytes = 512 * 1024)
    private val client = OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS).followRedirects(false).build()
    private var process: Process? = null
    private var outputJob: Job? = null
    private var monitorJob: Job? = null

    fun refresh() {
        CpolarStore.state.update { it.copy(configuration = repository.configuration(), desired = repository.desired()) }
    }

    suspend fun start() = withContext(Dispatchers.IO) {
        if (process?.isAlive == true) return@withContext
        if (process != null) stop()
        checkLocalized(java.io.File(BootstrapRuntime(context).rootfs, ".astrbot-bootstrap.json").isFile) {
            AppText.get(R.string.cpolar_initialize_first)
        }
        repository.verifyInstalled()
        repository.ensureConfiguration()
        checkLocalized(runCatching {
            ServerSocket(9200, 1, InetAddress.getByName("127.0.0.1")).use { true }
        }.getOrDefault(false)) { AppText.get(R.string.cpolar_port_in_use) }
        // cpolar's service mode hosts the dashboard before login. Single mode requires an existing token.
        // Our process runner owns the master and workers; no systemd service is installed in PRoot.
        val owned = BootstrapRuntime(context).processBuilder("/manager/extensions/cpolar/cpolar", "start-all",
            "-dashboard=on", "-daemon=on", "-processMode=master",
            "-config=/manager/extensions/cpolar/cpolar.yml", "-log=stdout", "-log-level=INFO").start()
        process = owned
        CpolarStore.state.update { it.copy(running = true, paused = false, webUiReady = false, error = null) }
        outputJob = scope.launch(Dispatchers.IO) {
            runCatching { owned.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                runCatching { log.append(repository.redactLog(line)) }
            } } }
        }
        monitorJob = scope.launch(Dispatchers.IO) {
            var readyBefore = false
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
            while (isActive && process === owned && owned.isAlive) {
                val ready = dashboardReady()
                readyBefore = readyBefore || ready
                if (process === owned) CpolarStore.state.update { it.copy(webUiReady = ready,
                    error = if (ready) null else if (readyBefore || System.nanoTime() > deadline)
                        AppText.get(R.string.cpolar_webui_unavailable) else null) }
                delay(2000)
            }
            if (process === owned) CpolarStore.state.update {
                it.copy(running = false, webUiReady = false, error = AppText.get(R.string.cpolar_exited))
            }
        }
        refresh()
    }

    private fun dashboardReady(): Boolean = runCatching {
        client.newCall(Request.Builder().url(WEB_UI_URL).build()).execute().use { response ->
            // The unsigned-in dashboard may redirect to its own login page.
            response.isSuccessful || response.code in setOf(301, 302, 303, 307, 308) &&
                response.header("Location")?.let { location ->
                    response.request.url.resolve(location)?.let { it.scheme == "http" && it.host == "127.0.0.1" && it.port == 9200 }
                } == true
        }
    }.getOrDefault(false)

    suspend fun stop(paused: Boolean = false) = withContext(NonCancellable + Dispatchers.IO) {
        val owned = process
        process = null
        monitorJob?.cancelAndJoin(); monitorJob = null
        if (owned != null) {
            owned.destroy()
            if (!owned.waitFor(8, TimeUnit.SECONDS)) { owned.destroyForcibly(); owned.waitFor(3, TimeUnit.SECONDS) }
            runCatching { owned.inputStream.close() }
        }
        outputJob?.cancelAndJoin(); outputJob = null
        CpolarStore.state.update { it.copy(running = false, paused = paused, webUiReady = false) }
    }

    suspend fun close() {
        stop()
        client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
    }

    companion object { const val WEB_UI_URL = "http://127.0.0.1:9200" }
}
