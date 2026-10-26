package io.astrbot.android.runtime

import io.astrbot.android.checkLocalized
import io.astrbot.android.requireLocalized

import io.astrbot.android.AppText
import io.astrbot.android.R

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
        requireLocalized(preserveIds.all(::validId)) { AppText.get(R.string.ui_invalid_backup_id) }
        checkLocalized(!hasPendingRestore && !hasPendingCleanup) { AppText.get(R.string.ui_a_restore_or_backup_cleanup_is_pending_resolve_its) }
        val entries = scan(persistent)
        val size = entries.sumOf { it.bytes }
        val id = UUID.randomUUID().toString()
        val manifest = JSONObject().put("schema", 1).put("id", id).put("createdAt", System.currentTimeMillis())
            .put("kind", kind).put("versions", versions.json()).put("bytes", size)
            .put("entries", JSONArray(entries.map { it.json() }))
        val metadataBytes = manifest.toString().toByteArray(Charsets.UTF_8).size.toLong()
        val remove = if (kind != "manual") capacityPlan(Math.addExact(size, metadataBytes), preserveIds) else emptyList()
        requireLocalized(filesDir.usableSpace >= size + metadataBytes + 64L * 1024 * 1024) { AppText.get(R.string.ui_insufficient_space_backup_requires_at_least_mb, (size + metadataBytes) / 1024 / 1024 + 64) }
        backups.mkdirs()
        val temporary = File(backups, ".creating-$id")
        check(temporary.mkdir())
        try {
            val tree = File(temporary, "data")
            copyTree(persistent, tree)
            checkLocalized(scan(tree) == entries) { AppText.get(R.string.ui_data_changed_during_backup_or_verification_failed_the_backup) }
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
        checkLocalized(existing.all { it.kind in setOf("manual", "before-update", "before-restore") }) { AppText.get(R.string.ui_unrecognized_backup_type_all_backups_are_preserved) }
        checkLocalized(existing.size == backups.listFiles().orEmpty().count { validId(it.name) && it.isDirectory }) {
            AppText.get(R.string.ui_a_backup_record_is_unreadable_all_backups_are_preserved)
        }
        val automatic = existing.filter { it.kind != "manual" }
        return BackupCapacityPolicy.plan(automatic.map { SnapshotCapacity(it.id, it.createdAt, it.storedBytes) },
            nextBytes, limits(), protectedIds() + preserveIds)
    }

    fun delete(id: String) {
        checkLocalized(!journal.baseFile.exists()) { AppText.get(R.string.ui_cannot_delete_backups_during_restore) }
        require(validId(id))
        deleteTree(File(backups, id))
    }

    suspend fun beginRestore(id: String, versions: RuntimeVersions) = withContext(Dispatchers.IO) {
        requireLocalized(validId(id)) { AppText.get(R.string.ui_invalid_backup_id) }
        checkLocalized(!journal.baseFile.exists() && !previous.exists()) { AppText.get(R.string.ui_an_unresolved_restore_record_exists_current_data_is_preserved) }
        val backup = File(backups, id)
        val metadata = readMetadata(backup)
        requireLocalized(metadata.getJSONObject("versions").getString("compatibilityKey") == versions.compatibilityKey) {
            AppText.get(R.string.ui_the_backup_does_not_match_the_installed_program_versions)
        }
        val expected = metadata.getJSONArray("entries").let { array ->
            require(array.length() <= 200_000)
            (0 until array.length()).map { Entry.parse(array.getJSONObject(it)) }
        }
        val tree = File(backup, "data")
        requireLocalized(scan(tree) == expected) { AppText.get(R.string.ui_the_backup_is_damaged_or_was_modified_current_data) }
        val size = expected.sumOf { it.bytes }
        requireLocalized(filesDir.usableSpace >= size + 64L * 1024 * 1024) { AppText.get(R.string.ui_insufficient_restore_staging_space) }
        deleteTree(staged)
        try {
            copyTree(tree, staged)
            checkLocalized(scan(staged) == expected) { AppText.get(R.string.ui_restore_staging_verification_failed) }
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
        val record = readJournal() ?: error(AppText.get(R.string.ui_restore_record_missing))
        check(record.getString("phase") == "switched" && persistent.isDirectory && previous.isDirectory)
        writeJournal(record.getString("id"), "committed")
        finishCommitted()
    }

    /** Merge selected portable scopes into a private copy; QQ HOME and other scopes stay local. */
    internal suspend fun beginPortableRestore(beforeId: String, versions: RuntimeVersions, source: File,
                                             validate: suspend (File) -> Unit) = withContext(Dispatchers.IO) {
        requireLocalized(validId(beforeId)) { AppText.get(R.string.ui_invalid_pre_restore_snapshot_id) }
        checkLocalized(!hasPendingRestore && !previous.exists()) { AppText.get(R.string.ui_an_unresolved_restore_record_exists_current_data_is_preserved) }
        requireLocalized(readMetadata(File(backups, beforeId)).getJSONObject("versions").getString("compatibilityKey") == versions.compatibilityKey) {
            AppText.get(R.string.ui_the_pre_restore_snapshot_does_not_match_the_current)
        }
        validate(source)
        val required = scan(persistent).sumOf { it.bytes } + scan(source).sumOf { it.bytes } + 64L * 1024 * 1024
        requireLocalized(filesDir.usableSpace >= required) { AppText.get(R.string.ui_insufficient_staging_space_for_portable_backup_restore) }
        deleteTree(staged)
        try {
            copyTree(persistent, staged)
            val rootMode = Os.lstat(staged.path).st_mode and 511
            Os.chmod(staged.path, rootMode or 448)
            for (scope in PortableBackupArchive.SCOPES) {
                val target = File(staged, scope)
                val parent = target.parentFile!!
                requireLocalized(!Files.isSymbolicLink(parent.toPath())) { AppText.get(R.string.ui_the_configuration_parent_directory_is_a_link_current_data) }
                checkLocalized(parent.isDirectory || parent.mkdirs()) { AppText.get(R.string.ui_cannot_prepare_configuration_directory) }
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
            checkLocalized(!previous.exists()) { AppText.get(R.string.ui_an_unrecognized_old_data_directory_exists_operation_stopped_files) }
            deleteTree(staged)
            val cleaned = finishRetention()
            backups.listFiles().orEmpty().filter { it.name.startsWith(".creating-") }.forEach { deleteTree(it) }
            cleanupRetired(protectedIds())
            return if (cleaned) AppText.get(R.string.ui_previous_automatic_snapshot_cleanup_completed_protected_backups_are_pres) else null
        }
        requireLocalized(validId(record.getString("id")) && record.getString("phase") in setOf("prepared", "switched", "committed")) {
            AppText.get(R.string.ui_unrecognized_restore_record_all_data_is_preserved)
        }
        if (record.getString("phase") == "committed") {
            finishCommitted()
            return AppText.get(R.string.ui_cleanup_from_the_previous_restore_completed)
        }
        if (previous.exists()) {
            checkLocalized(previous.isDirectory && !Files.isSymbolicLink(previous.toPath())) { AppText.get(R.string.ui_unexpected_old_data_directory_files_are_preserved) }
            deleteTree(persistent)
            move(previous, persistent)
        } else {
            checkLocalized(record.getString("phase") == "prepared" && persistent.isDirectory) { AppText.get(R.string.ui_old_data_required_for_recovery_is_missing_operation_stopped) }
        }
        deleteTree(staged)
        journal.delete()
        syncDirectory(File(filesDir, "operations"))
        return AppText.get(R.string.ui_the_previous_restore_was_incomplete_original_data_has_been)
    }

    private fun finishCommitted() {
        checkLocalized(persistent.isDirectory && !Files.isSymbolicLink(persistent.toPath())) { AppText.get(R.string.ui_restored_data_directory_is_missing_old_data_is_preserved) }
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
        requireLocalized(record.getInt("schema") == 1 && validId(newId) && remove.distinct().size == remove.size &&
            (remove + preserve).all(::validId) && newId !in remove && remove.none { it in preserve }) { AppText.get(R.string.ui_unrecognized_backup_cleanup_record_backups_are_preserved) }
        val current = File(backups, newId)
        if (!current.exists() && File(backups, ".creating-$newId").isDirectory) {
            deleteTree(File(backups, ".creating-$newId"))
        } else {
            checkLocalized(current.isDirectory && !Files.isSymbolicLink(current.toPath()) && File(current, "data").isDirectory &&
                !Files.isSymbolicLink(File(current, "data").toPath())) { AppText.get(R.string.ui_the_new_snapshot_is_missing_old_backups_and_cleanup) }
            checkLocalized(readMetadata(current).getString("kind") in setOf("before-update", "before-restore")) { AppText.get(R.string.ui_invalid_new_automatic_snapshot_record_old_backups_are_preserved) }
            val protected = protectedIds() + preserve + newId
            for (id in remove) {
                if (id in protected) continue
                val source = File(backups, id)
                if (source.exists()) {
                    checkLocalized(source.isDirectory && !Files.isSymbolicLink(source.toPath())) { AppText.get(R.string.ui_unexpected_snapshot_directory_awaiting_cleanup_files_are_preserved) }
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
            requireLocalized(validId(id)) { AppText.get(R.string.ui_unrecognized_backup_cleanup_directory_files_are_preserved) }
            checkLocalized(file.isDirectory && !Files.isSymbolicLink(file.toPath())) { AppText.get(R.string.ui_unexpected_backup_cleanup_directory_files_are_preserved) }
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
        requireLocalized(file.isFile && !Files.isSymbolicLink(directory.toPath()) && file.length() <= 128L * 1024 * 1024) { AppText.get(R.string.ui_backup_information_is_missing_or_too_large) }
        val metadata = JSONObject(file.readText())
        requireLocalized(metadata.getInt("schema") == 1 && metadata.getString("id") == expectedId) { AppText.get(R.string.ui_unsupported_backup_format) }
        return metadata
    }

    private fun info(metadata: JSONObject, metadataBytes: Long): BackupInfo {
        val versions = metadata.getJSONObject("versions")
        val bytes = metadata.getLong("bytes")
        requireLocalized(bytes >= 0 && metadataBytes >= 0) { AppText.get(R.string.ui_invalid_backup_size_record) }
        return BackupInfo(metadata.getString("id"), metadata.getLong("createdAt"), metadata.getString("kind"),
            bytes, metadata.getJSONArray("entries").length(),
            versions.keys().asSequence().filter { it != "compatibilityKey" }.associateWith { versions.getString(it) },
            Math.addExact(bytes, metadataBytes))
    }

    private suspend fun scan(directory: File): List<Entry> {
        requireLocalized(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) { AppText.get(R.string.ui_data_directory_is_missing_or_invalid) }
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
                    else -> error(AppText.get(R.string.ui_data_contains_special_files_that_cannot_be_backed_up))
                }
                return FileVisitResult.CONTINUE
            }
            private fun add(path: Path, kind: String, size: Long, digest: String, target: String) {
                requireLocalized(entries.size < 200_000) { AppText.get(R.string.ui_too_many_files_for_backup) }
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
        checkLocalized(!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS) && source.renameTo(destination)) { AppText.get(R.string.ui_cannot_switch_data_directories_recovery_records_are_preserved) }
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
