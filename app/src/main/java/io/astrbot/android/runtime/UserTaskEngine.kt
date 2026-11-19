package io.astrbot.android.runtime

import android.content.Context
import io.astrbot.android.AppText
import io.astrbot.android.R
import io.astrbot.android.checkLocalized
import io.astrbot.android.core.SecretRedactor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class UserTasksState(val loaded: Boolean = false, val tasks: List<UserTaskState> = emptyList(),
    val busy: Boolean = false, val error: String? = null) {
    val active get() = tasks.any { it.phase.active }
    val runningCount get() = tasks.count { it.phase == UserTaskPhase.RUNNING }
}

object UserTaskStore {
    val state = MutableStateFlow(UserTasksState())
    // Only the explicit initial-login password is kept, for the lifetime of the current run.
    private val passwords = MutableStateFlow<Map<String, String>>(emptyMap())
    internal val initialPasswords = passwords.asStateFlow()

    internal fun captureInitialPassword(id: String, line: String) {
        if (!line.contains("password", ignoreCase = true)) return
        val password = SecretRedactor.initialLoginPassword(line) ?: return
        passwords.update { it + (id to password) }
    }

    internal fun forgetInitialPassword(id: String) {
        passwords.update { it - id }
    }

    @Synchronized fun load(context: Context) {
        if (state.value.loaded) return
        runCatching { UserTaskRepository(context).read() }
            .onSuccess { rows -> state.update { it.copy(loaded = true, tasks = rows, error = null) } }
            .onFailure { state.update { it.copy(error = AppText.get(R.string.tasks_read_failed)) } }
    }
    internal fun update(id: String, transform: (UserTaskState) -> UserTaskState) {
        state.update { it.copy(tasks = it.tasks.map { row -> if (row.definition.id == id) transform(row) else row }) }
    }
}

/** RuntimeService owns each process-runner and its entire guest tree; Activities own no task processes. */
class UserTaskEngine(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val repository = UserTaskRepository(app)
    private val runtime = BootstrapRuntime(app)
    private class Session(val process: Process) {
        @Volatile var stopping = false
        var reader: Job? = null
    }
    private val sessions = ConcurrentHashMap<String, Session>()
    private val historyGate = Any()
    private val outputGate = Any()

    private fun requireLoaded() {
        UserTaskStore.load(app)
        checkLocalized(UserTaskStore.state.value.loaded) { AppText.get(R.string.tasks_read_failed) }
    }

    fun save(id: String?, name: String, directory: String, command: String) {
        requireLoaded()
        val task = UserTaskDefinition(id ?: UserTaskRepository.newId(), name.trim(), directory.trim(), command)
        UserTaskRepository.validate(task)
        val rows = UserTaskStore.state.value.tasks
        val previous = rows.firstOrNull { it.definition.id == task.id }
        checkLocalized(previous?.phase?.active != true) { AppText.get(R.string.tasks_stop_before_edit) }
        checkLocalized(id == null || previous != null) { AppText.get(R.string.tasks_not_found) }
        val updated = if (previous == null) rows + UserTaskState(task) else rows.map { if (it === previous) it.copy(definition = task) else it }
        repository.save(updated.map { it.definition })
        // Readers can complete while definitions are written; preserve their latest run status.
        UserTaskStore.state.update { current -> current.copy(tasks = if (previous == null) current.tasks + UserTaskState(task)
            else current.tasks.map { if (it.definition.id == task.id) it.copy(definition = task) else it }, error = null) }
    }

    fun start(id: String) {
        requireLoaded()
        if (sessions[id]?.process?.isAlive == true) return
        checkLocalized(File(runtime.rootfs, ".astrbot-bootstrap.json").isFile) { AppText.get(R.string.ui_initialize_the_ubuntu_runtime_first) }
        val task = UserTaskStore.state.value.tasks.firstOrNull { it.definition.id == id }?.definition
        checkLocalized(task != null) { AppText.get(R.string.tasks_not_found) }
        requireNotNull(task)
        checkLocalized(sessions.size < UserTaskRepository.MAX_RUNNING) { AppText.get(R.string.tasks_running_limit, UserTaskRepository.MAX_RUNNING) }
        UserTaskStore.update(id) { UserTaskState(task, UserTaskPhase.STARTING, startedAt = System.currentTimeMillis()) }
        record(id)
        val current = try {
            // Arguments remain separate from shell source. The user's script is interpreted exactly once.
            Session(runtime.processBuilder("/bin/bash", "-lc", "cd -- \"\$1\" || exit 72; eval -- \"\$2\"", "user-task", task.directory, task.command).start())
        } catch (failure: Exception) {
            UserTaskStore.update(id) { it.copy(phase = UserTaskPhase.FAILED, finishedAt = System.currentTimeMillis(), detail = AppText.get(R.string.tasks_start_failed)) }
            record(id)
            throw io.astrbot.android.LocalizedStateException(AppText.get(R.string.tasks_start_failed))
        }
        synchronized(outputGate) {
            UserTaskStore.forgetInitialPassword(id)
            sessions[id] = current
        }
        runCatching { current.process.outputStream.close() } // Non-interactive tasks never wait for terminal input.
        UserTaskStore.update(id) { it.copy(phase = UserTaskPhase.RUNNING) }
        record(id)
        current.reader = scope.launch(Dispatchers.IO) {
            var readFailed = false
            try {
                val log = repository.log(id)
                fun append(line: String) = synchronized(outputGate) {
                    log.append(line)
                    if (sessions[id] === current && !current.stopping) UserTaskStore.captureInitialPassword(id, line)
                }
                current.process.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(4096)
                    val line = StringBuilder()
                    var omitted = false
                    while (isActive) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        for (index in 0 until count) {
                            val char = buffer[index]
                            if (char == '\n' || char == '\r') {
                                if (line.isNotEmpty()) append(line.toString())
                                line.setLength(0); omitted = false
                            } else if (!omitted) {
                                line.append(char)
                                if (line.length > 16_000) {
                                    append(AppText.get(R.string.tasks_long_line))
                                    line.setLength(0); omitted = true
                                }
                            }
                        }
                    }
                    if (line.isNotEmpty()) append(line.toString())
                }
            } catch (_: Exception) {
                readFailed = !current.stopping
            } finally {
                val cancelled = !currentCoroutineContext().isActive
                withContext(NonCancellable + Dispatchers.IO) {
                    if (readFailed || cancelled) terminate(current)
                    val code = current.process.waitFor()
                    val ended = synchronized(outputGate) {
                        sessions.remove(id, current).also { if (it) UserTaskStore.forgetInitialPassword(id) }
                    }
                    if (ended) {
                        UserTaskStore.update(id) { it.copy(
                            phase = if (current.stopping) UserTaskPhase.STOPPED else if (code == 0 && !readFailed) UserTaskPhase.EXITED else UserTaskPhase.FAILED,
                            finishedAt = System.currentTimeMillis(), exitCode = code,
                            detail = if (readFailed) AppText.get(R.string.tasks_output_failed) else it.detail) }
                        record(id)
                    }
                }
            }
        }
    }

    suspend fun stop(id: String, reason: String? = null) {
        val current = sessions[id] ?: return
        current.stopping = true
        UserTaskStore.update(id) { it.copy(phase = UserTaskPhase.STOPPING, detail = reason) }
        withContext(NonCancellable + Dispatchers.IO) { terminate(current); current.reader?.join() }
    }

    suspend fun stopAll(reason: String? = null) {
        sessions.keys.toList().forEach { stop(it, reason) }
    }

    suspend fun delete(id: String) {
        requireLoaded()
        stop(id)
        repository.save(UserTaskStore.state.value.tasks.filterNot { it.definition.id == id }.map { it.definition })
        UserTaskStore.state.update { it.copy(tasks = it.tasks.filterNot { row -> row.definition.id == id }) }
        repository.removeHistory(id)
    }

    fun clearLog(id: String) = synchronized(outputGate) {
        repository.log(id).clear()
        UserTaskStore.forgetInitialPassword(id)
    }

    private fun record(id: String) = synchronized(historyGate) {
        UserTaskStore.state.value.tasks.firstOrNull { it.definition.id == id }?.let { task ->
            runCatching { repository.history(task) }.onFailure {
                UserTaskStore.state.update { it.copy(error = AppText.get(R.string.tasks_history_failed)) }
            }
        }
    }

    private fun terminate(session: Session) {
        if (session.process.isAlive) {
            session.process.destroy()
            if (!session.process.waitFor(8, TimeUnit.SECONDS)) {
                session.process.destroyForcibly()
                session.process.waitFor(3, TimeUnit.SECONDS)
            }
        }
        runCatching { session.process.inputStream.close() }
    }
}
