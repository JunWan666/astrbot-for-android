package io.astrbot.android.runtime

import android.os.Process
import android.system.Os
import android.system.OsConstants
import org.json.JSONObject
import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

internal data class LogRetentionResult(val deletedFiles: Int, val deletedBytes: Long, val deferredGroups: Int)

/** Prune identified historical logs only. Open files, mmap caches and databases are never truncated. */
internal class UpstreamLogRetention(
    private val filesDir: File,
    private val maxBytes: Long = 64L * 1024 * 1024,
    private val maxFiles: Int = 128,
) {
    private val persistent = File(filesDir, "persistent")
    private val textName = Regex(".+\\.log(?:\\.[0-9]+)?")
    private data class Candidate(val path: Path, val bytes: Long, val modified: Long, val dev: Long, val ino: Long)

    @Synchronized fun enforce(active: Set<io.astrbot.android.core.ServiceId>, now: Long = System.currentTimeMillis()): LogRetentionResult {
        val openFiles = openFiles() ?: return LogRetentionResult(0, 0, 3)
        val groups = linkedMapOf(
            "astrbot" to mutableListOf<Candidate>(), "napcat" to mutableListOf(), "qq" to mutableListOf(),
        )
        var incomplete = 0
        fun collect(group: String, relative: String, accepts: (String) -> Boolean) {
            val directory = File(persistent, relative)
            if (!safe(directory.toPath()) || !Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return
            var count = 0
            try {
                Files.walkFileTree(directory.toPath(), setOf(), 8, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        count++
                        return if (count > 10_000) { incomplete++; FileVisitResult.TERMINATE } else FileVisitResult.CONTINUE
                    }
                    override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                        count++
                        if (count > 10_000) { incomplete++; return FileVisitResult.TERMINATE }
                        if (attrs.isRegularFile && !attrs.isSymbolicLink && accepts(path.fileName.toString()))
                            candidate(path)?.let { groups.getValue(group).add(it) }
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                        incomplete++; return FileVisitResult.CONTINUE
                    }
                })
            } catch (_: Exception) { incomplete++ }
        }
        collect("astrbot", "astrbot/data/logs") { textName.matches(it) }
        // Custom log paths are supported only inside AstrBot's persistent tree and with a .log name.
        runCatching {
            val configFile = File(persistent, "astrbot/data/cmd_config.json")
            if (safe(configFile.toPath()) && configFile.length() <= 8L * 1024 * 1024) {
                val config = JSONObject(configFile.readText().removePrefix("\uFEFF"))
                val legacy = config.optJSONObject("log_file")
                listOf(legacy?.optString("path"), config.optString("log_file_path"), config.optString("trace_log_path"))
                    .filterNotNull().filter { it.isNotBlank() }.forEach { configured ->
                        val relative = if (configured.startsWith("/persistent/astrbot/")) configured.removePrefix("/persistent/astrbot/")
                            else if (!configured.startsWith('/')) "data/$configured" else return@forEach
                        val file = File(persistent, "astrbot/$relative").toPath().toAbsolutePath().normalize().toFile()
                        val astrbotRoot = File(persistent, "astrbot").toPath().toAbsolutePath().normalize()
                        if (file.toPath().startsWith(astrbotRoot) && safe(file.toPath()) && file.name.endsWith(".log")) {
                            val family = Regex(Regex.escape(file.name.removeSuffix(".log")) + "(?:\\.[0-9_.-]+)?\\.log")
                            file.parentFile?.listFiles()?.filter { it.name == file.name || family.matches(it.name) }
                                ?.forEach { candidate(it.toPath())?.let { entry -> groups.getValue("astrbot").add(entry) } }
                        }
                    }
            }
        }
        collect("napcat", "napcat/logs") { textName.matches(it) }
        val qq = File(persistent, "home/.config/QQ")
        if (safe(qq.toPath())) qq.listFiles().orEmpty().filter { it.name == "global" || it.name.matches(Regex("[0-9]{5,20}")) }
            .forEach { account -> collect("qq", "home/.config/QQ/${account.name}/nt_data/Log/log") { it.endsWith(".qqxlog") } }

        var removed = 0
        var bytes = 0L
        var deferred = incomplete
        groups.forEach { (group, candidates) ->
            val files = candidates.distinctBy { it.path.toString() }.sortedBy { it.modified }
            var total = files.sumOf { it.bytes }
            var count = files.size
            val running = if (group == "astrbot") io.astrbot.android.core.ServiceId.ASTRBOT in active
                else io.astrbot.android.core.ServiceId.NAPCAT in active
            for (file in files) {
                if (total <= maxBytes && count <= maxFiles) break
                // QQ writes encrypted hourly archives separately from mmap caches. A two-hour grace
                // period also protects files whose descriptors are opened only for short writes.
                if (running && now - file.modified < if (group == "qq") 2 * 60 * 60_000L else 10 * 60_000L) continue
                if ((file.dev to file.ino) in openFiles) continue
                if (runCatching {
                    val current = candidate(file.path)
                    if (current != file || !safe(file.path)) false
                    else { Files.delete(file.path); true }
                }.getOrDefault(false)) {
                    removed++; bytes += file.bytes; total -= file.bytes; count--
                }
            }
            if (total > maxBytes || count > maxFiles) deferred++
        }
        return LogRetentionResult(removed, bytes, deferred)
    }

    private fun safe(path: Path): Boolean = runCatching {
        val base = persistent.absoluteFile.toPath().normalize()
        val absolute = path.toAbsolutePath().normalize()
        if (!absolute.startsWith(base)) return false
        var current = base
        if (Files.isSymbolicLink(current)) return false
        for (part in base.relativize(absolute)) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) return false
        }
        true
    }.getOrDefault(false)

    private fun candidate(path: Path): Candidate? = runCatching {
        if (!safe(path)) return null
        val before = Os.lstat(path.toString())
        if (!OsConstants.S_ISREG(before.st_mode) || before.st_nlink != 1L) return null
        val descriptor = Os.open(path.toString(), OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
        val header = try {
            val actual = Os.fstat(descriptor)
            if (actual.st_ino != before.st_ino || actual.st_dev != before.st_dev) return null
            ByteArray(16).also { buffer ->
                var offset = 0
                while (offset < buffer.size) {
                    val read = Os.read(descriptor, buffer, offset, buffer.size - offset)
                    if (read <= 0) break
                    offset += read
                }
            }
        } finally { Os.close(descriptor) }
        if (header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))) return null
        Candidate(path.toAbsolutePath().normalize(), before.st_size, Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis(), before.st_dev, before.st_ino)
    }.getOrNull()

    private fun openFiles(): Set<Pair<Long, Long>>? = runCatching {
        val result = mutableSetOf<Pair<Long, Long>>()
        val dataRoot = persistent.canonicalFile.toPath()
        for (process in File("/proc").listFiles() ?: return null) {
            if (process.name.toIntOrNull() == null) continue
            if (runCatching { Os.stat(process.path).st_uid != Process.myUid() }.getOrDefault(true)) continue
            val descriptors = File(process, "fd").listFiles()
            if (descriptors == null) { if (process.exists()) return null else continue }
            for (descriptor in descriptors) {
                val stat = try {
                    val target = Os.readlink(descriptor.path)
                    // Android may deny stat on inherited system descriptors, even for our UID.
                    // Only a descriptor into this persistent tree can reference a pruning candidate.
                    if (!target.startsWith('/') || !File(target.removeSuffix(" (deleted)")).canonicalFile.toPath().startsWith(dataRoot)) continue
                    Os.stat(descriptor.path)
                }
                catch (failure: android.system.ErrnoException) {
                    if (failure.errno != OsConstants.ENOENT && failure.errno != OsConstants.ESRCH) return null
                    continue
                }
                if (OsConstants.S_ISREG(stat.st_mode)) result.add(stat.st_dev to stat.st_ino)
            }
        }
        result
    }.getOrNull()
}
