package io.astrbot.android.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

/** Serializes lifecycle commands; exit observers carry a generation to reject stale events. */
class ProcessSupervisor(
    private val id: ServiceId,
    private val scope: CoroutineScope,
    private val launcher: () -> Process,
    private val onLog: (String) -> Unit,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val restartPolicy: RestartPolicy = RestartPolicy(),
    private val expectedRestart: (Int) -> Boolean = { false },
    initialHistory: ServiceHistory = ServiceHistory(),
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
) {
    private sealed interface Event {
        data class Start(val reply: CompletableDeferred<Unit>, val manual: Boolean = true) : Event
        data class Stop(val reply: CompletableDeferred<Unit>) : Event
        data class Exited(val generation: Long, val exitCode: Int) : Event
        data class Retry(val generation: Long) : Event
        data class Observe(val generation: Long, val health: HealthState, val qq: QqState, val oneBot: OneBotState, val timestamp: Long) : Event
        data class Maintenance(val state: MaintenanceState, val reply: CompletableDeferred<Unit>) : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val history = ServiceHistoryTracker(initialHistory)
    private val mutableState = MutableStateFlow(ServiceStatus(id, history = initialHistory))
    val state: StateFlow<ServiceStatus> = mutableState.asStateFlow()
    private var process: Process? = null
    private var generation = 0L
    private var maintenance = MaintenanceState.IDLE
    private var outputReader: Job? = null
    private val actor = scope.launch {
        for (event in events) {
            when (event) {
                is Event.Start -> runCatching { startInternal(event.manual) }
                    .onSuccess { event.reply.complete(Unit) }.onFailure { event.reply.completeExceptionally(it) }
                is Event.Stop -> runCatching { stopInternal() }
                    .onSuccess { event.reply.complete(Unit) }.onFailure { event.reply.completeExceptionally(it) }
                is Event.Exited -> onExited(event)
                is Event.Retry -> if (event.generation == generation && mutableState.value.desiredRunning && maintenance == MaintenanceState.IDLE) {
                    runCatching { startInternal(false) }.onFailure { onExited(Event.Exited(generation, -1)) }
                }
                is Event.Maintenance -> { maintenance = event.state; event.reply.complete(Unit) }
                is Event.Observe -> if (event.generation == generation && mutableState.value.process == ProcessState.RUNNING) {
                    history.observe(event.health, event.qq, event.oneBot, event.timestamp)
                    mutableState.value = mutableState.value.copy(health = event.health, qq = event.qq,
                        oneBot = event.oneBot, checkedAtMillis = event.timestamp, history = history.history)
                }
            }
        }
    }

    suspend fun start() { val reply = CompletableDeferred<Unit>(); events.send(Event.Start(reply)); reply.await() }
    suspend fun stop() { val reply = CompletableDeferred<Unit>(); events.send(Event.Stop(reply)); reply.await() }
    suspend fun setMaintenance(value: MaintenanceState) {
        val reply = CompletableDeferred<Unit>(); events.send(Event.Maintenance(value, reply)); reply.await()
    }
    suspend fun observe(expectedGeneration: Long, health: HealthState, qq: QqState = QqState.UNKNOWN, oneBot: OneBotState = OneBotState.UNKNOWN, timestamp: Long) {
        events.send(Event.Observe(expectedGeneration, health, qq, oneBot, timestamp))
    }
    suspend fun close() { stop(); events.close(); actor.join() }

    private suspend fun startInternal(manual: Boolean) {
        check(maintenance == MaintenanceState.IDLE) { "维护操作进行中" }
        if (process?.isAlive == true) return
        if (manual) restartPolicy.reset()
        generation++
        val currentGeneration = generation
        history.newProcess()
        mutableState.value = ServiceStatus(id, desiredRunning = true, process = ProcessState.STARTING,
            health = HealthState.WAITING, processGeneration = currentGeneration, history = history.history)
        val started = try { withContext(Dispatchers.IO) { launcher() } }
        catch (failure: Exception) {
            if (failure !is CancellationException) history.processFailed(failure.message ?: "启动失败", wallClockMillis())
            mutableState.value = mutableState.value.copy(process = ProcessState.CRASHED,
                detail = SecretRedactor.redact(failure.message ?: "启动失败"), history = history.history)
            throw failure
        }
        process = started
        mutableState.value = mutableState.value.copy(process = ProcessState.RUNNING)
        onLog("${id.label} 已启动")
        outputReader = scope.launch(Dispatchers.IO) {
            try {
                started.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { onLog(SecretRedactor.redact(it)) }
                }
            } catch (_: java.io.IOException) { /* A controlled stop can close the pipe. */ }
        }
        scope.launch(Dispatchers.IO) {
            val exitCode = started.waitFor()
            events.trySend(Event.Exited(currentGeneration, exitCode))
        }
    }

    private suspend fun stopInternal() {
        generation++ // Invalidates pending exits and retry timers before signaling the process.
        mutableState.value = mutableState.value.copy(desiredRunning = false, process = ProcessState.STOPPING)
        val stopping = process
        if (stopping != null) withContext(Dispatchers.IO) {
            stopping.destroy()
            if (!stopping.waitFor(10, TimeUnit.SECONDS)) {
                stopping.destroyForcibly()
                check(stopping.waitFor(5, TimeUnit.SECONDS)) { "服务进程未能退出" }
            }
        }
        withTimeoutOrNull(3000) { outputReader?.join() }
        outputReader?.cancel()
        outputReader = null
        process = null
        history.stopped()
        mutableState.value = ServiceStatus(id, history = history.history)
        onLog("${id.label} 已停止")
    }

    private suspend fun onExited(event: Event.Exited) {
        if (event.generation != generation || !mutableState.value.desiredRunning) return
        process = null
        if (maintenance == MaintenanceState.IDLE && runCatching { expectedRestart(event.exitCode) }.getOrDefault(false)) {
            mutableState.value = mutableState.value.copy(process = ProcessState.STARTING, health = HealthState.WAITING,
                qq = QqState.UNKNOWN, oneBot = OneBotState.UNKNOWN, checkedAtMillis = null, detail = "上游请求重启，正在恢复服务")
            onLog("${id.label} 上游请求重启，已清理旧进程组，正在重新启动")
            scope.launch { delay(500); events.trySend(Event.Retry(event.generation)) }
            return
        }
        history.processFailed("进程意外退出，代码 ${event.exitCode}", wallClockMillis())
        mutableState.value = mutableState.value.copy(process = ProcessState.CRASHED,
            health = HealthState.UNKNOWN, qq = QqState.UNKNOWN, oneBot = OneBotState.UNKNOWN,
            checkedAtMillis = null, detail = "进程退出，代码 ${event.exitCode}", history = history.history)
        when (val decision = restartPolicy.onUnexpectedExit(monotonicMillis(), true, maintenance)) {
            RestartDecision.Suppressed -> Unit
            RestartDecision.Exhausted -> {
                mutableState.value = mutableState.value.copy(detail = "连续异常，已暂停自动重启")
                onLog("${id.label} 连续异常，暂停自动重启")
            }
            is RestartDecision.Retry -> {
                onLog("${id.label} 意外退出，${decision.delayMillis} 毫秒后尝试恢复")
                scope.launch { delay(decision.delayMillis); events.trySend(Event.Retry(event.generation)) }
            }
        }
    }
}
