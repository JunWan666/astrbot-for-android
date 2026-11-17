package io.astrbot.android.runtime

import android.content.Context
import android.system.Os
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

/** Journal before switching any file. Recovery restores old files, including after process death. */
internal class EnvironmentRepairTransaction(context: Context) {
    private val root = File(context.filesDir, "runtime-data/ubuntu")
    private val work = File(context.filesDir, "staging/environment-repair/switch")
    private val journal = File(context.filesDir, "operations/environment-repair.json")
    val pending get() = DurableFiles.exists(journal)
    private val entries = mutableListOf<JSONObject>()

    fun reset() { check(!pending); entries.clear() }

    suspend fun stage(path: String, source: File) {
        check(!pending && entries.none { it.getString("path") == path })
        val target = target(path)
        check(!Files.isSymbolicLink(target.toPath()))
        val index = entries.size
        val staged = File(work, "new/$index")
        staged.parentFile!!.mkdirs()
        if (source.isDirectory) DurableFiles.copyProgram(source, staged) else {
            check(source.isFile && !Files.isSymbolicLink(source.toPath()))
            staged.outputStream().use { out -> source.inputStream().use { it.copyTo(out) }; out.fd.sync() }
            Os.chmod(staged.path, Os.stat(source.path).st_mode and 511)
        }
        entries += JSONObject().put("path", path).put("existed", exists(target)).put("digest", digest(staged))
            .put("originalDigest", if (exists(target)) digest(target) else "")
    }

    fun begin() {
        check(!pending && entries.isNotEmpty() && entries.size <= 50000)
        DurableFiles.write(journal, JSONObject().put("schema", 1).put("phase", "switching").put("entries", JSONArray(entries)))
    }

    suspend fun apply(afterSwitch: (Int) -> Unit = {}) {
        check(pending)
        for ((index, entry) in entries.withIndex()) {
            currentCoroutineContext().ensureActive()
            val target = target(entry.getString("path"))
            check(exists(target) == entry.getBoolean("existed"))
            if (exists(target)) {
                check(digest(target) == entry.getString("originalDigest"))
                DurableFiles.move(target, File(work, "old/$index"))
            }
            DurableFiles.move(File(work, "new/$index"), target)
            afterSwitch(index)
        }
    }

    fun commit() {
        val record = requireNotNull(DurableFiles.read(journal))
        record.put("phase", "committed")
        DurableFiles.write(journal, record)
        finish()
    }

    /** No cancellation during rollback: callers must keep guest processes stopped until this returns. */
    fun recover(): Boolean {
        val record = DurableFiles.read(journal) ?: return false
        require(record.getInt("schema") == 1 && record.getString("phase") in setOf("switching", "committed"))
        if (record.getString("phase") != "committed") {
            val list = record.getJSONArray("entries")
            require(list.length() in 1..50000)
            for (index in list.length() - 1 downTo 0) {
                val entry = list.getJSONObject(index)
                val target = target(entry.getString("path"))
                val old = File(work, "old/$index")
                if (exists(old)) {
                    if (exists(target)) DurableFiles.delete(target)
                    DurableFiles.move(old, target)
                } else if (!entry.getBoolean("existed") && exists(target)) {
                    // A changed/unowned destination is left for diagnosis, rather than deleted.
                    check(digest(target) == entry.getString("digest"))
                    DurableFiles.delete(target)
                } else if (entry.getBoolean("existed")) {
                    check(exists(target) && digest(target) == entry.getString("originalDigest"))
                }
            }
        }
        finish()
        return true
    }

    private fun finish() {
        // A committed record is retained if cleanup fails; the next recovery only repeats cleanup.
        DurableFiles.delete(work)
        DurableFiles.removeRecord(journal)
        entries.clear()
    }

    private fun target(path: String): File {
        require(path.split('/').none { it.isEmpty() || it == "." || it == ".." } && !path.startsWith('/'))
        require(path in EnvironmentRepairRepository.CORE_PATHS || path == EnvironmentRepairRepository.DASHBOARD_PATH ||
            path.startsWith(EnvironmentRepairRepository.SITE_PACKAGES + "/"))
        val file = File(root, path)
        require(file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
        var parent = file.parentFile
        while (parent != null && parent != root) {
            require(!Files.isSymbolicLink(parent.toPath()))
            parent = parent.parentFile
        }
        return file
    }

    companion object {
        fun exists(file: File) = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        fun digest(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            // Do not follow links in web directories or Python distributions.
            if (file.isDirectory) Files.walk(file.toPath()).use { paths ->
                paths.sorted().forEach { path ->
                    require(!Files.isSymbolicLink(path))
                    hash.update(file.toPath().relativize(path).toString().toByteArray())
                    if (Files.isRegularFile(path)) path.toFile().inputStream().use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
                    }
                }
            } else {
                require(file.isFile && !Files.isSymbolicLink(file.toPath()))
                file.inputStream().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
                }
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
