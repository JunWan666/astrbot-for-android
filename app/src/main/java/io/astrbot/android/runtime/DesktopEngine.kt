package io.astrbot.android.runtime

import android.content.Context
import io.astrbot.android.AppText
import io.astrbot.android.R
import io.astrbot.android.checkLocalized
import io.astrbot.android.core.SecretRedactor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

data class DesktopState(
    val configuration: DesktopConfiguration = DesktopConfiguration(), val running: Boolean = false,
    val qqRunning: Boolean = false, val webUiReady: Boolean = false, val desired: Boolean = false,
    val busy: Boolean = false, val paused: Boolean = false, val error: String? = null,
)
object DesktopStore { val state = MutableStateFlow(DesktopState()) }

/** The foreground service owns both process trees; closing a browser never closes the desktop. */
class DesktopEngine(private val context: Context, private val scope: CoroutineScope) {
    private val repository = DesktopRepository(context)
    private val runtime = BootstrapRuntime(context)
    private val client = OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS).followRedirects(false).build()
    private var process: Process? = null
    private var qq: Process? = null
    private var output: Job? = null
    private var qqOutput: Job? = null
    private var monitor: Job? = null
    private var qqMonitor: Job? = null

    fun refresh() { DesktopStore.state.update { it.copy(configuration = repository.configuration(), desired = repository.desired()) } }

    private fun drain(owned: Process, tag: String) = scope.launch(Dispatchers.IO) {
        runCatching { owned.inputStream.bufferedReader().useLines { lines -> lines.forEach {
            RuntimeStore.log(context, LogCategory.DESKTOP).append("[$tag] ${SecretRedactor.redact(it)}")
        } } }
    }

    suspend fun start() = withContext(Dispatchers.IO) {
        if (process?.isAlive == true) return@withContext
        stop()
        checkLocalized(repository.configuration().installed) { AppText.get(R.string.desktop_install_first) }
        repository.prepareAccess()
        for (port in listOf(5920, 6081)) checkLocalized(runCatching {
            ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use { true }
        }.getOrDefault(false)) { AppText.get(R.string.ui_local_port_is_in_use_stop_the_service_using, port) }
        val owned = runtime.processBuilder("/bin/bash", "${DesktopRepository.GUEST}/desktop.sh").start()
        process = owned
        DesktopStore.state.update { it.copy(running = true, paused = false, webUiReady = false, error = null) }
        output = drain(owned, "desktop")
        monitor = scope.launch(Dispatchers.IO) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
            while (isActive && process === owned && owned.isAlive) {
                val ready = runCatching { client.newCall(Request.Builder().url(DesktopRepository.WEB_UI_URL).build())
                    .execute().use { it.isSuccessful && it.peekBody(65536).string().contains("noVNC") } }.getOrDefault(false)
                if (process === owned) DesktopStore.state.update { it.copy(webUiReady = ready,
                    error = if (ready) null else if (System.nanoTime() > deadline) AppText.get(R.string.desktop_unavailable) else null) }
                delay(2000)
            }
            if (process === owned) {
                stopQq()
                DesktopStore.state.update { it.copy(running = false, webUiReady = false, error = AppText.get(R.string.desktop_exited)) }
            }
        }
        refresh()
    }

    suspend fun startQq() = withContext(Dispatchers.IO) {
        if (qq?.isAlive == true) return@withContext
        stopQq()
        checkLocalized(repository.configuration().qqInstalled) { AppText.get(R.string.desktop_qq_install_first) }
        start()
        withTimeout(45_000) { while (!DesktopStore.state.value.webUiReady) {
            checkLocalized(process?.isAlive == true) { AppText.get(R.string.desktop_exited) }; delay(300)
        } }
        val owned = runtime.processBuilder("/bin/bash", "${DesktopRepository.GUEST}/qq.sh").start()
        qq = owned
        DesktopStore.state.update { it.copy(qqRunning = true, error = null) }
        qqOutput = drain(owned, "QQ")
        qqMonitor = scope.launch(Dispatchers.IO) {
            while (isActive && qq === owned && owned.isAlive) delay(1500)
            if (qq === owned) DesktopStore.state.update { it.copy(qqRunning = false, error = AppText.get(R.string.desktop_qq_exited)) }
        }
    }

    private fun terminate(owned: Process?) {
        if (owned == null) return
        owned.destroy()
        if (!owned.waitFor(8, TimeUnit.SECONDS)) { owned.destroyForcibly(); owned.waitFor(3, TimeUnit.SECONDS) }
        runCatching { owned.inputStream.close() }
    }

    suspend fun stopQq(): Unit = withContext(NonCancellable + Dispatchers.IO) {
        val owned = qq; qq = null
        qqMonitor?.cancelAndJoin(); qqMonitor = null
        terminate(owned); qqOutput?.cancelAndJoin(); qqOutput = null
        DesktopStore.state.update { it.copy(qqRunning = false) }
    }

    suspend fun stop(paused: Boolean = false): Unit = withContext(NonCancellable + Dispatchers.IO) {
        val owned = process; process = null
        monitor?.cancelAndJoin(); monitor = null
        stopQq()
        terminate(owned); output?.cancelAndJoin(); output = null
        DesktopStore.state.update { it.copy(running = false, webUiReady = false, paused = paused) }
    }

    suspend fun close() { stop(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
}
