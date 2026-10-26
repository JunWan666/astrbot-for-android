package io.astrbot.android.core

data class BackupLimits(
    val automaticCount: Int = 10,
    val automaticBytes: Long = 2L * 1024 * 1024 * 1024,
) {
    init {
        require(automaticCount in 1..100) { "自动快照数量上限应为 1 至 100 份" }
        require(automaticBytes in 64L * 1024 * 1024..64L * 1024 * 1024 * 1024) {
            "自动快照容量上限应为 64 至 65536 MiB"
        }
    }
}

data class SnapshotCapacity(val id: String, val createdAt: Long, val bytes: Long)

/** Plans eviction only; callers commit a verified new snapshot before deleting old snapshots. */
object BackupCapacityPolicy {
    fun plan(automatic: List<SnapshotCapacity>, nextBytes: Long, limits: BackupLimits, protectedIds: Set<String>): List<String> {
        require(automatic.map { it.id }.toSet().size == automatic.size && automatic.all { it.bytes >= 0 }) { "备份容量记录无效" }
        var count = automatic.size
        var bytes = automatic.fold(0L) { sum, entry -> Math.addExact(sum, entry.bytes) }
        val remove = mutableListOf<String>()
        for (entry in automatic.sortedWith(compareBy<SnapshotCapacity> { it.createdAt }.thenBy { it.id })) {
            if (count < limits.automaticCount && bytes <= limits.automaticBytes && nextBytes <= limits.automaticBytes - bytes) break
            if (entry.id in protectedIds) continue
            remove += entry.id
            count--
            bytes -= entry.bytes
        }
        requireRoom(count, bytes, nextBytes, limits)
        return remove
    }

    fun requireRoom(count: Int, bytes: Long, nextBytes: Long, limits: BackupLimits) {
        require(count >= 0 && bytes >= 0 && nextBytes >= 0) { "备份容量记录无效" }
        check(count < limits.automaticCount) {
            "自动快照已达 ${limits.automaticCount} 份上限，未创建新快照。请在设置的备份管理中清理不再需要的快照，或调整自动快照上限后重试"
        }
        // Subtract instead of adding untrusted sizes, including a single snapshot above the cap.
        check(bytes <= limits.automaticBytes && nextBytes <= limits.automaticBytes - bytes) {
            "自动快照将超过 ${limits.automaticBytes / 1024 / 1024} MiB 容量上限，未创建新快照。请在设置的备份管理中清理不再需要的快照，或调整自动快照上限后重试"
        }
    }
}
