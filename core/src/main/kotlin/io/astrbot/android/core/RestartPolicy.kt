package io.astrbot.android.core

sealed interface RestartDecision {
    data object Suppressed : RestartDecision
    data object Exhausted : RestartDecision
    data class Retry(val delayMillis: Long) : RestartDecision
}

/** Uses a monotonic clock supplied by the caller, independent of wall-clock changes. */
class RestartPolicy(
    private val limit: Int = 3,
    private val windowMillis: Long = 300_000,
    private val initialDelayMillis: Long = 2_000,
    private val maximumDelayMillis: Long = 30_000,
) {
    private val attempts = ArrayDeque<Long>()
    init {
        require(limit in 1..30 && windowMillis > 0)
        require(initialDelayMillis > 0 && maximumDelayMillis >= initialDelayMillis)
    }

    @Synchronized
    fun onUnexpectedExit(nowMillis: Long, desiredRunning: Boolean, maintenance: MaintenanceState): RestartDecision {
        if (!desiredRunning || maintenance != MaintenanceState.IDLE) return RestartDecision.Suppressed
        while (attempts.isNotEmpty() && nowMillis - attempts.first() >= windowMillis) attempts.removeFirst()
        if (attempts.size >= limit) return RestartDecision.Exhausted
        val delay = (initialDelayMillis * (1L shl attempts.size)).coerceAtMost(maximumDelayMillis)
        attempts.addLast(nowMillis)
        return RestartDecision.Retry(delay)
    }

    @Synchronized fun reset() = attempts.clear()
}
