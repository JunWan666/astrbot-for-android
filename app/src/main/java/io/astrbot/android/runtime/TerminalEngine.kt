package io.astrbot.android.runtime

import android.content.Context
import android.util.Base64
import io.astrbot.android.AppText
import io.astrbot.android.R
import io.astrbot.android.checkLocalized
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class TerminalSessionState(val id: Long, val running: Boolean = false, val exitCode: Int? = null, val error: String? = null)
data class TerminalState(val sessions: List<TerminalSessionState> = emptyList(), val selectedId: Long? = null, val error: String? = null) {
    val active get() = sessions.firstOrNull { it.id == selectedId }
    val running get() = sessions.any { it.running }
    val runningCount get() = sessions.count { it.running }
    val exitCode get() = active?.exitCode
}

/** Each session has a bounded, memory-only transcript. Commands and output never enter manager logs. */
object TerminalStore {
    const val MAX_SESSIONS = 8
    val state = MutableStateFlow(TerminalState())
    @Volatile internal var owner: TerminalEngine? = null
    private val nextId = AtomicLong()
    private data class Chunk(val sequence: Long, val bytes: ByteArray)
    private class Transcript {
        val chunks = ArrayDeque<Chunk>()
        var generation = 0L
        var sequence = 0L
        var size = 0
    }
    private val gate = Any()
    private val transcripts = mutableMapOf<Long, Transcript>()

    internal fun create(): Long = synchronized(gate) {
        checkLocalized(state.value.sessions.size < MAX_SESSIONS) { AppText.get(R.string.terminal_session_limit, MAX_SESSIONS) }
        val id = nextId.incrementAndGet()
        transcripts[id] = Transcript()
        state.update { it.copy(sessions = it.sessions + TerminalSessionState(id), selectedId = id, error = null) }
        id
    }

    fun select(id: Long) { state.update { if (it.sessions.any { row -> row.id == id }) it.copy(selectedId = id, error = null) else it } }
    internal fun update(id: Long, change: (TerminalSessionState) -> TerminalSessionState) {
        state.update { it.copy(sessions = it.sessions.map { row -> if (row.id == id) change(row) else row }) }
    }
    internal fun remove(id: Long) = synchronized(gate) {
        transcripts.remove(id)
        state.update { previous ->
            val index = previous.sessions.indexOfFirst { it.id == id }
            val remaining = previous.sessions.filterNot { it.id == id }
            previous.copy(sessions = remaining, selectedId = if (previous.selectedId == id)
                remaining.getOrNull(index.coerceAtMost(remaining.lastIndex))?.id else previous.selectedId, error = null)
        }
    }
    internal fun clear(id: Long? = state.value.selectedId) = synchronized(gate) {
        transcripts[id]?.let { it.generation++; it.sequence = 0; it.size = 0; it.chunks.clear() }
    }
    internal fun append(id: Long, bytes: ByteArray) = synchronized(gate) {
        transcripts[id]?.let { transcript ->
            transcript.chunks.addLast(Chunk(++transcript.sequence, bytes)); transcript.size += bytes.size
            while ((transcript.size > 512 * 1024 || transcript.chunks.size > 512) && transcript.chunks.size > 1)
                transcript.size -= transcript.chunks.removeFirst().bytes.size
        }
    }

    /** IDs accompany every bridge message so delayed batches and input cannot cross between tabs. */
    fun output(previousGeneration: Long, after: Long, id: Long? = state.value.selectedId): JSONObject = synchronized(gate) {
        val transcript = transcripts[id]
        val result = JSONObject().put("id", id ?: -1)
        if (transcript == null) return@synchronized result.put("generation", -1).put("sequence", 0).put("reset", true).put("data", "")
        val reset = previousGeneration != transcript.generation || after > transcript.sequence ||
            after < (transcript.chunks.peekFirst()?.sequence ?: 1L) - 1
        val selected = transcript.chunks.filter { reset || it.sequence > after }.take(8)
        val bytes = selected.fold(java.io.ByteArrayOutputStream()) { stream, chunk -> stream.apply { write(chunk.bytes) } }.toByteArray()
        result.put("generation", transcript.generation).put("sequence", selected.lastOrNull()?.sequence ?: transcript.sequence)
            .put("reset", reset).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    fun input(text: String, id: Long? = state.value.selectedId) { if (id != null) owner?.input(id, text) }
    fun resize(columns: Int, rows: Int, id: Long? = state.value.selectedId) { if (id != null) owner?.resize(id, columns, rows) }
    fun clearScreen(id: Long? = state.value.selectedId) { clear(id); input("\u000c", id) }
}

/** The foreground service owns all PTY/PRoot trees; changing tabs or destroying the Activity stops none. */
class TerminalEngine(context: Context, private val scope: CoroutineScope) {
    private val runtime = BootstrapRuntime(context.applicationContext)
    private data class Frame(val type: Int, val bytes: ByteArray)
    private class Session(val id: Long, val process: Process) {
        val frames = Channel<Frame>(32)
        var writer: Job? = null
        var reader: Job? = null
    }
    private val gate = Any()
    private val sessions = mutableMapOf<Long, Session>()

    init { TerminalStore.owner = this }

    fun start(id: Long? = TerminalStore.state.value.selectedId, newSession: Boolean = false) {
        checkLocalized(File(runtime.rootfs, ".astrbot-bootstrap.json").isFile) { AppText.get(R.string.ui_initialize_the_ubuntu_runtime_first) }
        val target = if (newSession || id == null) TerminalStore.create() else id
        check(TerminalStore.state.value.sessions.any { it.id == target })
        synchronized(gate) { if (sessions[target]?.process?.isAlive == true) return }
        val process = try { runtime.terminalProcessBuilder(80, 24, "/bin/bash", "-l").start() }
            catch (failure: Exception) { TerminalStore.update(target) { it.copy(error = AppText.get(R.string.terminal_start_failed)) }; throw failure }
        val current = Session(target, process)
        synchronized(gate) {
            sessions[target] = current
            TerminalStore.clear(target)
            TerminalStore.update(target) { TerminalSessionState(target, running = true) }
            TerminalStore.state.update { it.copy(error = null) }
        }
        current.writer = scope.launch(Dispatchers.IO) {
            try {
                val stream = DataOutputStream(current.process.outputStream)
                for (frame in current.frames) {
                    if (frame.type == 0) {
                        var offset = 0
                        while (offset < frame.bytes.size) {
                            val count = minOf(8192, frame.bytes.size - offset)
                            stream.writeByte(0); stream.writeInt(count); stream.write(frame.bytes, offset, count)
                            offset += count
                        }
                    } else { stream.writeByte(frame.type); stream.writeInt(frame.bytes.size); stream.write(frame.bytes) }
                    stream.flush()
                }
            } catch (_: java.io.IOException) { /* the shell or service closed its pipes */ }
        }
        current.reader = scope.launch(Dispatchers.IO) {
            try {
                current.process.inputStream.use { stream ->
                    val buffer = ByteArray(8192)
                    while (isActive) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        synchronized(gate) { if (sessions[target] === current) TerminalStore.append(target, buffer.copyOf(count)) }
                    }
                }
                val code = current.process.waitFor()
                synchronized(gate) {
                    if (sessions[target] === current) TerminalStore.update(target) { it.copy(running = false, exitCode = code) }
                }
            } catch (_: java.io.IOException) { /* explicit stop closes the owned stream */ }
            finally { current.frames.close() }
        }
    }

    private fun enqueue(id: Long, frame: Frame) = synchronized(gate) {
        val current = sessions[id] ?: return@synchronized
        if (current.process.isAlive && current.frames.trySend(frame).isFailure)
            TerminalStore.update(id) { it.copy(error = AppText.get(R.string.terminal_input_busy)) }
    }
    fun input(id: Long, text: String) {
        if (text.isNotEmpty() && text.length <= 65552) enqueue(id, Frame(0, text.toByteArray(Charsets.UTF_8)))
    }
    fun resize(id: Long, columns: Int, rows: Int) {
        if (columns in 2..500 && rows in 1..500) enqueue(id, Frame(1,
            byteArrayOf((columns shr 8).toByte(), columns.toByte(), (rows shr 8).toByte(), rows.toByte())))
    }

    private suspend fun dispose(current: Session) = withContext(Dispatchers.IO) {
        current.frames.close()
        current.process.destroy()
        if (!current.process.waitFor(8, TimeUnit.SECONDS)) { current.process.destroyForcibly(); current.process.waitFor(3, TimeUnit.SECONDS) }
        runCatching { current.process.outputStream.close() }
        runCatching { current.process.inputStream.close() }
        current.writer?.cancelAndJoin(); current.reader?.cancelAndJoin()
    }
    suspend fun stopSession(id: Long?, remove: Boolean = false) {
        if (id == null) return
        val current = synchronized(gate) { sessions.remove(id) }
        if (current != null) dispose(current)
        if (remove) TerminalStore.remove(id) else TerminalStore.update(id) { it.copy(running = false, exitCode = null, error = null) }
    }
    suspend fun stop(reason: String? = null) {
        val owned = synchronized(gate) { sessions.values.toList().also { sessions.clear() } }
        coroutineScope { owned.map { current -> async { dispose(current) } }.awaitAll() }
        TerminalStore.state.update { state -> state.copy(error = reason, sessions = state.sessions.map {
            if (it.running) it.copy(running = false, exitCode = null, error = reason) else it
        }) }
    }
    suspend fun close() {
        stop()
        if (TerminalStore.owner === this) TerminalStore.owner = null
    }
}
