package io.astrbot.android.core

data class HealthPollingInput(
    val process: ProcessState,
    val health: HealthState,
    val generation: Long,
    val visible: Boolean,
    val maintenance: MaintenanceState,
)

/** Deadlines follow the last completed probe, so unrelated state emissions cannot postpone it. */
object HealthPollingPolicy {
    fun delayMillis(input: HealthPollingInput, lastGeneration: Long?, completedAtMillis: Long?, nowMillis: Long): Long? {
        if (input.process != ProcessState.RUNNING) return null
        if (completedAtMillis == null || lastGeneration != input.generation) return 0
        val stable = input.health in setOf(HealthState.AVAILABLE, HealthState.DISABLED)
        val interval = if (!input.visible && input.maintenance == MaintenanceState.IDLE && stable) 30_000L else 5_000L
        return (interval - (nowMillis - completedAtMillis).coerceAtLeast(0)).coerceAtLeast(0)
    }
}
