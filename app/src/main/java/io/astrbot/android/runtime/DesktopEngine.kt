package io.astrbot.android.runtime

import android.content.Context
import io.astrbot.android.AppText
import io.astrbot.android.R
import io.astrbot.android.checkLocalized
import io.astrbot.android.core.SecretRedactor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.UUID

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
    private data class Owned(val process: Process, val session: String)
    private val lifecycle = Mutex()
    private var process: Owned? = null
    private var qq: Owned? = null
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

    private fun launch(role: String, script: String): Owned {
        val session = UUID.randomUUID().toString().replace("-", "")
        return Owned(runtime.processBuilder("/usr/bin/env", "ASTRBOT_DESKTOP_OWNER=${DesktopRepository.GUEST}",
            "ASTRBOT_DESKTOP_ROLE=$role", "ASTRBOT_DESKTOP_SESSION=$session", "/bin/bash", script).start(), session)
    }

    suspend fun start() = withContext(Dispatchers.IO) { lifecycle.withLock { startLocked() } }

    private suspend fun startLocked() {
        if (process?.process?.isAlive == true) return
        checkLocalized(repository.configuration().installed) { AppText.get(R.string.desktop_install_first) }
        stopLocked()
        repository.prepareAccess()
        for (port in listOf(5920, 6081)) checkLocalized(runCatching {
            ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use { true }
        }.getOrDefault(false)) { AppText.get(R.string.ui_local_port_is_in_use_stop_the_service_using, port) }
        val owned = launch("desktop", "${DesktopRepository.GUEST}/desktop.sh")
        process = owned
        DesktopStore.state.update { it.copy(running = true, paused = false, webUiReady = false, error = null) }
        output = drain(owned.process, "desktop")
        monitor = scope.launch(Dispatchers.IO) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
            while (isActive && process === owned && owned.process.isAlive) {
                val ready = runCatching { client.newCall(Request.Builder().url(DesktopRepository.WEB_UI_URL).build())
                    .execute().use { it.isSuccessful && it.peekBody(65536).string().contains("noVNC") } }.getOrDefault(false)
                if (process === owned) DesktopStore.state.update { it.copy(webUiReady = ready,
                    error = if (ready) null else if (System.nanoTime() > deadline) AppText.get(R.string.desktop_unavailable) else null) }
                delay(2000)
            }
            lifecycle.withLock {
                if (process === owned) {
                    process = null; monitor = null
                    val code = owned.process.exitValue()
                    RuntimeStore.log(context, LogCategory.DESKTOP).append("[desktop] launcher exited: $code")
                    val previousOutput = output; output = null
                    val error = try {
                        try { stopQqLocked() } finally { finish("desktop", owned, previousOutput) }
                        AppText.get(R.string.desktop_exited_code, code)
                    } catch (failure: CancellationException) { throw failure }
                    catch (_: Exception) { AppText.get(R.string.desktop_process_recovery_failed) }
                    DesktopStore.state.update { it.copy(running = false, webUiReady = false, error = error) }
                }
            }
        }
        refresh()
    }

    suspend fun startQq() = withContext(Dispatchers.IO) { lifecycle.withLock { startQqLocked() } }

    private suspend fun startQqLocked() {
        if (qq?.process?.isAlive == true) return
        stopQqLocked()
        checkLocalized(repository.configuration().qqInstalled) { AppText.get(R.string.desktop_qq_install_first) }
        startLocked()
        withTimeout(45_000) { while (!DesktopStore.state.value.webUiReady) {
            checkLocalized(process?.process?.isAlive == true) { AppText.get(R.string.desktop_exited) }; delay(300)
        } }
        val owned = launch("qq", "${DesktopRepository.GUEST}/qq.sh")
        qq = owned
        DesktopStore.state.update { it.copy(qqRunning = true, error = null) }
        qqOutput = drain(owned.process, "QQ")
        qqMonitor = scope.launch(Dispatchers.IO) {
            while (isActive && qq === owned && owned.process.isAlive) delay(1500)
            lifecycle.withLock {
                if (qq === owned) {
                    qq = null; qqMonitor = null
                    RuntimeStore.log(context, LogCategory.DESKTOP).append("[QQ] launcher exited: ${owned.process.exitValue()}")
                    val previousOutput = qqOutput; qqOutput = null
                    val error = try {
                        finish("qq", owned, previousOutput)
                        AppText.get(R.string.desktop_qq_exited)
                    } catch (failure: CancellationException) { throw failure }
                    catch (_: Exception) { AppText.get(R.string.desktop_process_recovery_failed) }
                    DesktopStore.state.update { it.copy(qqRunning = false, error = error) }
                }
            }
        }
    }

    private fun terminate(owned: Process?) {
        if (owned == null) return
        owned.destroy()
        if (!owned.waitFor(8, TimeUnit.SECONDS)) { owned.destroyForcibly(); owned.waitFor(3, TimeUnit.SECONDS) }
    }

    private suspend fun finish(role: String, owned: Owned?, previousOutput: Job?) {
        try {
            terminate(owned?.process)
            repository.recoverProcesses(role, owned?.session)
        } finally {
            runCatching { owned?.process?.inputStream?.close() }
            previousOutput?.cancel()
        }
    }

    suspend fun stopQq(): Unit = withContext(NonCancellable + Dispatchers.IO) { lifecycle.withLock { stopQqLocked() } }

    private suspend fun stopQqLocked() {
        val owned = qq; qq = null
        qqMonitor?.cancelAndJoin(); qqMonitor = null
        val previousOutput = qqOutput; qqOutput = null
        try { finish("qq", owned, previousOutput) }
        finally { DesktopStore.state.update { it.copy(qqRunning = false) } }
    }

    suspend fun stop(paused: Boolean = false): Unit = withContext(NonCancellable + Dispatchers.IO) { lifecycle.withLock { stopLocked(paused) } }

    private suspend fun stopLocked(paused: Boolean = false) {
        val owned = process; process = null
        monitor?.cancelAndJoin(); monitor = null
        val previousOutput = output; output = null
        try { stopQqLocked() }
        finally {
            try { finish("desktop", owned, previousOutput) }
            finally { DesktopStore.state.update { it.copy(running = false, webUiReady = false, paused = paused) } }
        }
    }

    suspend fun close() { stop(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
}
