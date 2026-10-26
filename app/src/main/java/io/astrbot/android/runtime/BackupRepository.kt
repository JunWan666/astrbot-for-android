package io.astrbot.android.runtime

import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import io.astrbot.android.core.BackupCapacityPolicy
import io.astrbot.android.core.BackupLimits
import io.astrbot.android.core.SnapshotCapacity
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

data class BackupInfo(val id: String, val createdAt: Long, val kind: String, val bytes: Long,
                      val count: Int, val versions: Map<String, String>, val storedBytes: Long = bytes)

/** Local snapshots include account sessions. All mutations require the controller's maintenance gate. */
class BackupRepository(private val filesDir: File, private val protectedIds: () -> Set<String> = { emptySet() },
                       private val limits: () -> BackupLimits = { BackupLimits() }) {
    private val persistent = File(filesDir, "persistent")
    private val backups = File(filesDir, "backups")
    private val journal = AtomicFile(File(filesDir, "operations/restore.json"))
    private val retention = AtomicFile(File(filesDir, "operations/backup-retention.json"))
    private val staged = File(filesDir, ".restore-staging")
    private val previous = File(filesDir, ".restore-previous")
    val hasPendingRestore: Boolean get() = journal.baseFile.exists() || File(journal.baseFile.path + ".bak").exists()
    val hasPendingCleanup: Boolean get() = retention.baseFile.exists() || File(retention.baseFile.path + ".bak").exists() ||
        backups.listFiles().orEmpty().any { it.name.startsWith(".creating-") || it.name.startsWith(".deleting-") }

    private data class Entry(val name: String, val kind: String, val bytes: Long, val digest: String, val mode: Int, val target: String) {
        fun json() = JSONObject().put("name", name).put("kind", kind).put("bytes", bytes)
            .put("sha256", digest).put("mode", mode).put("target", target)
        companion object {
            fun parse(value: JSONObject) = Entry(value.getString("name"), value.getString("kind"), value.getLong("bytes"),
                value.getString("sha256"), value.getInt("mode"), value.getString("target"))
        }
    }

    suspend fun create(versions: RuntimeVersions, kind: String = "manual", preserveIds: Set<String> = emptySet()): BackupInfo = withContext(Dispatchers.IO) {
        require(kind in setOf("manual", "before-restore", "before-update"))
        require(preserveIds.all(::validId)) { "备份编号无效" }
        check(!hasPendingRestore && !hasPendingCleanup) { "有未完成的恢复或备份清理，请先处理操作记录" }
        val entries = scan(persistent)
        val size = entries.sumOf { it.bytes }
        val id = UUID.randomUUID().toString()
        val manifest = JSONObject().put("schema", 1).put("id", id).put("createdAt", System.currentTimeMillis())
            .put("kind", kind).put("versions", versions.json()).put("bytes", size)
            .put("entries", JSONArray(entries.map { it.json() }))
        val metadataBytes = manifest.toString().toByteArray(Charsets.UTF_8).size.toLong()
        val remove = if (kind != "manual") capacityPlan(Math.addExact(size, metadataBytes), preserveIds) else emptyList()
        require(filesDir.usableSpace >= size + metadataBytes + 64L * 1024 * 1024) { "空间不足，备份至少需要 ${(size + metadataBytes) / 1024 / 1024 + 64} MB" }
        backups.mkdirs()
        val temporary = File(backups, ".creating-$id")
        check(temporary.mkdir())
        try {
            val tree = File(temporary, "data")
            copyTree(persistent, tree)
            check(scan(tree) == entries) { "备份期间数据发生变化或校验失败，未提交备份" }
            writeAtomic(File(temporary, "metadata.json"), manifest)
            syncDirectory(temporary)
            if (remove.isNotEmpty()) writeAtomic(retention.baseFile, JSONObject().put("schema", 1).put("newId", id)
                .put("remove", JSONArray(remove)).put("preserve", JSONArray((protectedIds() + preserveIds).toList())))
            move(temporary, File(backups, id))
            if (remove.isNotEmpty()) finishRetention()
            info(manifest, metadataBytes)
        } finally {
            if (temporary.exists() && !retention.baseFile.exists() && !File(retention.baseFile.path + ".bak").exists()) deleteTree(temporary)
        }
    }

    fun list(): List<BackupInfo> = backups.listFiles().orEmpty().filter { validId(it.name) && it.isDirectory }
        .mapNotNull { runCatching { info(readMetadata(it), File(it, "metadata.json").length()) }.getOrNull() }.sortedByDescending { it.createdAt }

    /** Cheap preflight before stopping services; create checks again with the exact payload and index. */
    fun checkAutomaticCapacity(nextBytes: Long = 1, preserveIds: Set<String> = emptySet()) {
        capacityPlan(nextBytes, preserveIds)
    }

    private fun capacityPlan(nextBytes: Long, preserveIds: Set<String>): List<String> {
        val existing = list()
        check(existing.all { it.kind in setOf("manual", "before-update", "before-restore") }) { "存在无法识别的备份类型，已保留全部备份" }
        check(existing.size == backups.listFiles().orEmpty().count { validId(it.name) && it.isDirectory }) {
            "存在无法读取的备份记录，已保留全部备份；请导出诊断后处理，未创建新快照"
        }
        val automatic = existing.filter { it.kind != "manual" }
        return BackupCapacityPolicy.plan(automatic.map { SnapshotCapacity(it.id, it.createdAt, it.storedBytes) },
            nextBytes, limits(), protectedIds() + preserveIds)
    }

    fun delete(id: String) {
        check(!journal.baseFile.exists()) { "恢复过程中不能删除备份" }
        require(validId(id))
        deleteTree(File(backups, id))
    }

    suspend fun beginRestore(id: String, versions: RuntimeVersions) = withContext(Dispatchers.IO) {
        require(validId(id)) { "备份编号无效" }
        check(!journal.baseFile.exists() && !previous.exists()) { "存在未处理的恢复记录，已保留现有数据" }
        val backup = File(backups, id)
        val metadata = readMetadata(backup)
        require(metadata.getJSONObject("versions").getString("compatibilityKey") == versions.compatibilityKey) {
            "备份与当前运行版本组合不同，不能仅恢复数据。请先恢复对应程序版本"
        }
        val expected = metadata.getJSONArray("entries").let { array ->
            require(array.length() <= 200_000)
            (0 until array.length()).map { Entry.parse(array.getJSONObject(it)) }
        }
        val tree = File(backup, "data")
        require(scan(tree) == expected) { "备份文件损坏或内容被修改，已保留当前数据" }
        val size = expected.sumOf { it.bytes }
        require(filesDir.usableSpace >= size + 64L * 1024 * 1024) { "恢复暂存空间不足" }
        deleteTree(staged)
        try {
            copyTree(tree, staged)
            check(scan(staged) == expected) { "恢复暂存校验失败" }
            // This record precedes every rename. An interrupted uncommitted restore is rolled back.
            writeJournal(id, "prepared")
            move(persistent, previous)
            move(staged, persistent)
            writeJournal(id, "switched")
        } catch (failure: Throwable) {
            if (!journal.baseFile.exists()) deleteTree(staged)
            throw failure
        }
    }

    fun commitRestore() {
        val record = readJournal() ?: error("恢复记录缺失")
        check(record.getString("phase") == "switched" && persistent.isDirectory && previous.isDirectory)
        writeJournal(record.getString("id"), "committed")
        finishCommitted()
    }

    /** Merge selected portable scopes into a private copy; QQ HOME and other scopes stay local. */
    internal suspend fun beginPortableRestore(beforeId: String, versions: RuntimeVersions, source: File,
                                             validate: suspend (File) -> Unit) = withContext(Dispatchers.IO) {
        require(validId(beforeId)) { "恢复前快照编号无效" }
        check(!hasPendingRestore && !previous.exists()) { "存在未处理的恢复记录，已保留现有数据" }
        require(readMetadata(File(backups, beforeId)).getJSONObject("versions").getString("compatibilityKey") == versions.compatibilityKey) {
            "恢复前快照与当前环境不匹配"
        }
        validate(source)
        val required = scan(persistent).sumOf { it.bytes } + scan(source).sumOf { it.bytes } + 64L * 1024 * 1024
        require(filesDir.usableSpace >= required) { "便携备份恢复暂存空间不足" }
        deleteTree(staged)
        try {
            copyTree(persistent, staged)
            val rootMode = Os.lstat(staged.path).st_mode and 511
            Os.chmod(staged.path, rootMode or 448)
            for (scope in PortableBackupArchive.SCOPES) {
                val target = File(staged, scope)
                val parent = target.parentFile!!
                require(!Files.isSymbolicLink(parent.toPath())) { "当前配置父目录为链接，已保留现有数据" }
                check(parent.isDirectory || parent.mkdirs()) { "无法准备配置目录" }
                val mode = Os.lstat(parent.path).st_mode and 511
                Os.chmod(parent.path, mode or 448)
                DurableFiles.delete(target, writableGuestDirectories = true)
                copyTree(File(source, scope), target)
                Os.chmod(parent.path, mode)
            }
            Os.chmod(staged.path, rootMode)
            validate(staged)
            writeJournal(beforeId, "prepared")
            move(persistent, previous)
            move(staged, persistent)
            writeJournal(beforeId, "switched")
        } catch (failure: Throwable) {
            if (!hasPendingRestore) DurableFiles.delete(staged, writableGuestDirectories = true)
            throw failure
        }
    }

    /** Must run before launching any guest. Returns a user-facing result after a real recovery. */
    fun recover(): String? {
        val record = readJournal()
        if (record == null) {
            check(!previous.exists()) { "存在未识别的旧数据目录，已停止操作并保留文件" }
            deleteTree(staged)
            val cleaned = finishRetention()
            backups.listFiles().orEmpty().filter { it.name.startsWith(".creating-") }.forEach { deleteTree(it) }
            cleanupRetired(protectedIds())
            return if (cleaned) "已完成上次自动快照清理，受保护备份已保留" else null
        }
        require(validId(record.getString("id")) && record.getString("phase") in setOf("prepared", "switched", "committed")) {
            "恢复记录无法识别，已保留全部数据"
        }
        if (record.getString("phase") == "committed") {
            finishCommitted()
            return "已完成上次恢复操作的清理"
        }
        if (previous.exists()) {
            check(previous.isDirectory && !Files.isSymbolicLink(previous.toPath())) { "旧数据目录异常，已保留文件" }
            deleteTree(persistent)
            move(previous, persistent)
        } else {
            check(record.getString("phase") == "prepared" && persistent.isDirectory) { "恢复所需的旧数据缺失，已停止操作" }
        }
        deleteTree(staged)
        journal.delete()
        syncDirectory(File(filesDir, "operations"))
        return "上次恢复未完成，已恢复操作前的数据"
    }

    private fun finishCommitted() {
        check(persistent.isDirectory && !Files.isSymbolicLink(persistent.toPath())) { "恢复后的数据目录缺失，保留旧数据" }
        deleteTree(previous)
        deleteTree(staged)
        journal.delete()
        syncDirectory(File(filesDir, "operations"))
    }

    /** Journal is durable before publishing the verified new snapshot. Deletions are retryable. */
    private fun finishRetention(): Boolean {
        if (!retention.baseFile.exists() && !File(retention.baseFile.path + ".bak").exists()) return false
        val record = retention.openRead().bufferedReader().use { JSONObject(it.readText()) }
        val newId = record.getString("newId")
        val remove = record.getJSONArray("remove").let { values -> (0 until values.length()).map { values.getString(it) } }
        val preserve = record.getJSONArray("preserve").let { values -> (0 until values.length()).map { values.getString(it) }.toSet() }
        require(record.getInt("schema") == 1 && validId(newId) && remove.distinct().size == remove.size &&
            (remove + preserve).all(::validId) && newId !in remove && remove.none { it in preserve }) { "备份清理记录无法识别，已保留备份" }
        val current = File(backups, newId)
        if (!current.exists() && File(backups, ".creating-$newId").isDirectory) {
            deleteTree(File(backups, ".creating-$newId"))
        } else {
            check(current.isDirectory && !Files.isSymbolicLink(current.toPath()) && File(current, "data").isDirectory &&
                !Files.isSymbolicLink(File(current, "data").toPath())) { "新快照缺失，已保留旧备份与清理记录" }
            check(readMetadata(current).getString("kind") in setOf("before-update", "before-restore")) { "新自动快照记录异常，已保留旧备份" }
            val protected = protectedIds() + preserve + newId
            for (id in remove) {
                if (id in protected) continue
                val source = File(backups, id)
                if (source.exists()) {
                    check(source.isDirectory && !Files.isSymbolicLink(source.toPath())) { "待清理快照目录异常，已保留文件" }
                    if (readMetadata(source).getString("kind") !in setOf("before-update", "before-restore")) continue
                    move(source, File(backups, ".deleting-$id"))
                }
                cleanupRetired(protected)
            }
        }
        retention.delete()
        syncDirectory(retention.baseFile.parentFile!!)
        return true
    }

    private fun cleanupRetired(protected: Set<String>) {
        for (file in backups.listFiles().orEmpty().filter { it.name.startsWith(".deleting-") }) {
            val id = file.name.removePrefix(".deleting-")
            require(validId(id)) { "备份清理目录无法识别，已保留文件" }
            check(file.isDirectory && !Files.isSymbolicLink(file.toPath())) { "备份清理目录异常，已保留文件" }
            val keep = File(file, "metadata.json").isFile && readMetadata(file, id).getString("kind") !in setOf("before-update", "before-restore")
            if (id in protected || keep) move(file, File(backups, id))
            else DurableFiles.delete(file, writableGuestDirectories = true)
        }
        if (backups.isDirectory) syncDirectory(backups)
    }

    private fun readJournal(): JSONObject? {
        if (!journal.baseFile.exists() && !File(journal.baseFile.path + ".bak").exists()) return null
        return journal.openRead().bufferedReader().use { JSONObject(it.readText()) }
    }

    private fun writeJournal(id: String, phase: String) = writeAtomic(journal.baseFile,
        JSONObject().put("schema", 1).put("id", id).put("phase", phase))

    private fun readMetadata(directory: File, expectedId: String = directory.name): JSONObject {
        val file = File(directory, "metadata.json")
        require(file.isFile && !Files.isSymbolicLink(directory.toPath()) && file.length() <= 128L * 1024 * 1024) { "备份信息缺失或过大" }
        val metadata = JSONObject(file.readText())
        require(metadata.getInt("schema") == 1 && metadata.getString("id") == expectedId) { "备份格式不支持" }
        return metadata
    }

    private fun info(metadata: JSONObject, metadataBytes: Long): BackupInfo {
        val versions = metadata.getJSONObject("versions")
        val bytes = metadata.getLong("bytes")
        require(bytes >= 0 && metadataBytes >= 0) { "备份容量记录无效" }
        return BackupInfo(metadata.getString("id"), metadata.getLong("createdAt"), metadata.getString("kind"),
            bytes, metadata.getJSONArray("entries").length(),
            versions.keys().asSequence().filter { it != "compatibilityKey" }.associateWith { versions.getString(it) },
            Math.addExact(bytes, metadataBytes))
    }

    private suspend fun scan(directory: File): List<Entry> {
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) { "数据目录缺失或无效" }
        val entries = ArrayList<Entry>()
        val coroutine = currentCoroutineContext()
        val base = directory.toPath()
        Files.walkFileTree(base, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                coroutine.ensureActive()
                if (dir != base) add(dir, "directory", 0, "", "")
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                coroutine.ensureActive()
                when {
                    attrs.isSymbolicLink -> add(file, "symlink", 0, "", Files.readSymbolicLink(file).toString())
                    attrs.isRegularFile -> {
                        val digest = MessageDigest.getInstance("SHA-256")
                        file.toFile().inputStream().use { input ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                coroutine.ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                digest.update(buffer, 0, n)
                            }
                        }
                        add(file, "file", attrs.size(), digest.digest().joinToString("") { "%02x".format(it) }, "")
                    }
                    else -> error("数据中存在无法备份的特殊文件，请停止相关插件后重试")
                }
                return FileVisitResult.CONTINUE
            }
            private fun add(path: Path, kind: String, size: Long, digest: String, target: String) {
                require(entries.size < 200_000) { "数据文件数量超出备份范围" }
                entries += Entry(base.relativize(path).toString(), kind, size, digest,
                    if (kind == "symlink") 0 else Os.lstat(path.toString()).st_mode and 511, target)
            }
        })
        return entries.sortedBy { it.name }
    }

    private suspend fun copyTree(source: File, destination: File) {
        check(!destination.exists())
        val coroutine = currentCoroutineContext()
        val base = source.toPath()
        val output = destination.toPath()
        Files.walkFileTree(base, object : SimpleFileVisitor<Path>() {
            private fun target(path: Path) = output.resolve(base.relativize(path))
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                coroutine.ensureActive()
                Files.createDirectory(target(dir))
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                coroutine.ensureActive()
                val target = target(file)
                if (attrs.isSymbolicLink) Files.createSymbolicLink(target, Files.readSymbolicLink(file))
                else {
                    check(attrs.isRegularFile)
                    file.toFile().inputStream().use { input ->
                        FileOutputStream(target.toFile()).use { stream ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                coroutine.ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                stream.write(buffer, 0, n)
                            }
                            stream.fd.sync()
                        }
                    }
                    Os.chmod(target.toString(), Os.lstat(file.toString()).st_mode and 511)
                }
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) throw exc
                Os.chmod(target(dir).toString(), Os.lstat(dir.toString()).st_mode and 511)
                syncDirectory(target(dir).toFile())
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun move(source: File, destination: File) {
        check(!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS) && source.renameTo(destination)) { "无法切换数据目录，已保留恢复记录" }
        syncDirectory(destination.parentFile!!)
    }

    private fun writeAtomic(file: File, value: JSONObject) {
        file.parentFile!!.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
        syncDirectory(file.parentFile!!)
    }

    private fun syncDirectory(directory: File) {
        check(directory.isDirectory)
        val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }

    private fun deleteTree(directory: File) {
        if (!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun validId(id: String) = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
}
