package io.astrbot.android.core

/** Interface observations never request a process restart or establish business health. */
class InterfaceHealthPolicy(
    private val failureLimit: Int = 3,
    private val startupGraceMillis: Long = 300_000,
) {
    init { require(failureLimit >= 2 && startupGraceMillis >= 0) }
    private var startedAt: Long? = null
    private var confirmed = false
    private var failures = 0

    fun reset(nowMillis: Long? = null) {
        startedAt = nowMillis
        confirmed = false
        failures = 0
    }

    fun observe(enabled: Boolean, reachable: Boolean, nowMillis: Long): HealthState {
        if (!enabled) {
            reset()
            return HealthState.DISABLED
        }
        val start = startedAt ?: nowMillis.also { startedAt = it }
        if (reachable) {
            confirmed = true
            failures = 0
            return HealthState.AVAILABLE
        }
        failures = (failures + 1).coerceAtMost(failureLimit)
        if (!confirmed) return if ((nowMillis - start).coerceAtLeast(0) < startupGraceMillis) HealthState.WAITING else HealthState.UNRESPONSIVE
        return if (failures < failureLimit) HealthState.RECHECKING else HealthState.UNRESPONSIVE
    }
}
