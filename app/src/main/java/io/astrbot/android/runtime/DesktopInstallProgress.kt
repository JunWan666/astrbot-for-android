package io.astrbot.android.runtime

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Call
import org.json.JSONObject
import java.io.File

enum class DesktopInstallStage { PREPARING, DOWNLOADING, PAUSED, VERIFYING, INSTALLING, FINISHED, STOPPED, FAILED }
data class DesktopInstallProgress(
    val kind: String? = null, val active: Boolean = false, val stage: DesktopInstallStage = DesktopInstallStage.FINISHED,
    val downloaded: Long = 0, val total: Long? = null, val bytesPerSecond: Long = 0, val stopping: Boolean = false,
) {
    val canPause get() = active && stage == DesktopInstallStage.DOWNLOADING && !stopping
    val canStop get() = active && stage in setOf(DesktopInstallStage.PREPARING, DesktopInstallStage.DOWNLOADING, DesktopInstallStage.PAUSED) && !stopping
}
internal class DesktopInstallStopped : Exception()

/** Shared by the service and screen; leaving the screen does not stop an installation. */
object DesktopInstallStore {
    val state = MutableStateFlow(DesktopInstallProgress())
    private val gate = Any()
    private var control: File? = null
    private var paused = false
    private var stopped = false
    private var call: Call? = null

    private fun persist(nextPaused: Boolean = paused, nextStopped: Boolean = stopped) {
        control?.let { DurableFiles.write(it, JSONObject().put("paused", nextPaused).put("stopped", nextStopped)) }
    }
    fun begin(context: Context, kind: String) = synchronized(gate) {
        paused = false; stopped = false; call = null
        control = File(context.filesDir,"manager/extensions/desktop/download-control.json")
        persist()
        state.value = DesktopInstallProgress(kind = kind, active = true, stage = DesktopInstallStage.PREPARING)
    }
    fun pause() = synchronized(gate) {
        if (state.value.canPause) {
            persist(nextPaused = true); paused = true; call?.cancel()
            state.update { it.copy(stage = DesktopInstallStage.PAUSED, bytesPerSecond = 0) }
        }
    }
    fun resume() = synchronized(gate) {
        if (state.value.active && paused && !stopped) {
            persist(nextPaused = false); paused = false
            state.update { it.copy(stage = DesktopInstallStage.DOWNLOADING) }
        }
    }
    fun stop() = synchronized(gate) {
        if (state.value.canStop) {
            persist(nextPaused = false, nextStopped = true); stopped = true; paused = false; call?.cancel()
            state.update { it.copy(stopping = true, bytesPerSecond = 0) }
        }
    }
    internal fun attach(value: Call?) = synchronized(gate) {
        call = value
        if (paused || stopped) call?.cancel()
    }
    internal fun interrupted() = synchronized(gate) { paused || stopped }
    internal suspend fun checkpoint() {
        while (true) {
            currentCoroutineContext().ensureActive()
            val wait = synchronized(gate) { if (stopped) throw DesktopInstallStopped(); paused }
            if (!wait) return
            delay(150)
        }
    }
    internal fun phase(stage: DesktopInstallStage) = synchronized(gate) {
        if (stopped) throw DesktopInstallStopped()
        state.update { it.copy(stage = stage, bytesPerSecond = 0) }
    }
    internal fun progress(downloaded: Long, total: Long?, speed: Long) = synchronized(gate) {
        state.update { it.copy(downloaded = downloaded, total = total, bytesPerSecond = if (paused) 0 else speed,
            stage = if (paused) DesktopInstallStage.PAUSED else DesktopInstallStage.DOWNLOADING) }
    }
    internal fun guestProgress(value: JSONObject) = synchronized(gate) {
        val stage = runCatching { DesktopInstallStage.valueOf(value.getString("stage")) }.getOrNull() ?: return@synchronized
        state.update { it.copy(stage = if (paused && stage == DesktopInstallStage.DOWNLOADING) DesktopInstallStage.PAUSED else stage,
            downloaded = value.optLong("downloaded", it.downloaded), total = if (value.has("total")) value.optLong("total") else it.total,
            bytesPerSecond = if (paused) 0 else value.optLong("speed",0),
            stopping = it.stopping && stage !in setOf(DesktopInstallStage.VERIFYING, DesktopInstallStage.INSTALLING)) }
    }
    internal fun finish(stage: DesktopInstallStage) = synchronized(gate) {
        call = null
        state.update { it.copy(active = false, stage = stage, bytesPerSecond = 0, stopping = false) }
    }
    internal suspend fun <T> install(context: Context, kind: String, action: suspend () -> T): T {
        begin(context,kind)
        try { return action().also { finish(DesktopInstallStage.FINISHED) } }
        catch (failure: DesktopInstallStopped) { finish(DesktopInstallStage.STOPPED); throw failure }
        catch (failure: Throwable) { finish(DesktopInstallStage.FAILED); throw failure }
    }
}
