package io.astrbot.android.runtime

import android.content.Context
import android.app.ActivityManager
import android.os.Process
import android.os.StatFs
import android.system.Os
import io.astrbot.android.core.MemoryReading
import io.astrbot.android.core.ResourceMemoryPolicy
import kotlinx.coroutines.*
import java.io.File

data class ResourceSnapshot(
    val memory: MemoryReading = MemoryReading(null, null, 0, 0, false),
    val storageAvailableBytes: Long? = null,
    val checkedAtMillis: Long? = null,
    val physicalMemoryBytes: Long? = null,
) {
    val memoryPercent: Double? get() = ResourceMemoryPolicy.percentOfPhysical(memory.totalKiB, physicalMemoryBytes)
}

/** Same-UID PSS excludes isolated WebView renderers and does not double-count shared resident pages. */
class ResourceMonitor(private val context: Context) {
    private data class Census(val processes: Map<Int, Long>, val complete: Boolean)

    suspend fun sample(): ResourceSnapshot = withContext(Dispatchers.IO) {
        val before = census()
        val readings = mutableMapOf<Int, Long>()
        for (pid in before.processes.keys.sorted()) {
            currentCoroutineContext().ensureActive()
            runCatching { readPss(pid) }.onSuccess { readings[pid] = it }
        }
        val after = census()
        var memory = ResourceMemoryPolicy.summarize(before.processes, after.processes, readings, Process.myPid())
        if (!before.complete || !after.complete) memory = memory.copy(totalKiB = null, complete = false)
        val physicalMemory = runCatching {
            ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem
        }.getOrNull()?.takeIf { it > 0 }
        ResourceSnapshot(memory, runCatching { StatFs(context.filesDir.path).availableBytes }.getOrNull(),
            System.currentTimeMillis(), physicalMemory)
    }

    private suspend fun census(): Census {
        val files = File("/proc").listFiles() ?: return Census(emptyMap(), false)
        var complete = true
        val processes = mutableMapOf<Int, Long>()
        for (file in files) {
            currentCoroutineContext().ensureActive()
            val pid = file.name.toIntOrNull() ?: continue
            val uid = runCatching { Os.stat(file.path).st_uid }.getOrNull()
            if (uid == null) { complete = false; continue }
            if (uid != Process.myUid()) continue
            runCatching { ResourceMemoryPolicy.residentStartTicks(pid, File(file, "stat").readText()) }
                .onSuccess { ticks -> if (ticks != null) processes[pid] = ticks }.onFailure { complete = false }
        }
        return Census(processes, complete)
    }

    private suspend fun readPss(pid: Int): Long {
        val rollup = File("/proc/$pid/smaps_rollup")
        val single = rollup.exists()
        val file = if (single) rollup else File("/proc/$pid/smaps")
        val coroutine = currentCoroutineContext()
        return file.bufferedReader().use { reader ->
            var characters = 0L
            ResourceMemoryPolicy.pssKiB(reader.lineSequence().map { line ->
                coroutine.ensureActive()
                characters += line.length
                require(characters <= 8L * 1024 * 1024) { "Memory map exceeds sampling budget" }
                line
            }, single)
        }
    }
}
