package io.astrbot.android.runtime

import io.astrbot.android.checkLocalized
import io.astrbot.android.requireLocalized
import io.astrbot.android.requireNotNullLocalized

import io.astrbot.android.AppText
import io.astrbot.android.R

import android.content.Context
import android.net.Uri
import android.system.Os
import io.astrbot.android.BuildConfig
import io.astrbot.android.core.ArchivePaths
import io.astrbot.android.core.ServiceId
import kotlinx.coroutines.*
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

data class UpdateInfo(val version: String, val channel: String, val notes: String, val bytes: Long)
data class RollbackInfo(val version: String, val snapshotId: String)

/** Transactions replace one interpreted program and retain a matching data recovery point. */
class UpdateRepository(private val context: Context, private val component: String = "astrbot") {
    init { require(component in setOf("astrbot", "napcat")) }
    private val label = if (component == "napcat") "NapCat" else "AstrBot"
    private val files = context.filesDir
    private val active = File(files, if (component == "napcat") "runtime-data/ubuntu/opt/qq/resources/app/app_launcher/napcat" else "runtime-data/ubuntu/opt/astrbot")
    private val work = File(files, "staging/$component-update")
    private val staged = File(work, "program")
    private val packageFile = File(work, "package.zip")
    private val prepared = File(work, "verified.json")
    private val journal = File(files, if (component == "astrbot") "operations/update.json" else "operations/napcat-update.json")
    private val rollback = File(files, if (component == "astrbot") "manifests/rollback.json" else "manifests/napcat-rollback.json")
    private val officialProof = File(work, "official-proof.json")
    private val backups = BackupRepository(files)
    val hasPendingUpdate get() = DurableFiles.exists(journal)
    val preparedInfo: UpdateInfo? get() = DurableFiles.read(prepared)?.let(::info)
    val rollbackInfo: RollbackInfo? get() = DurableFiles.read(rollback)?.let {
        RollbackInfo(it.getJSONObject("oldVersions").getString(label), it.getString("snapshotId"))
    }
    val protectedBackupIds: Set<String> get() = listOfNotNull(DurableFiles.read(journal), DurableFiles.read(rollback))
        .map { it.getString("snapshotId") }.toSet()

    suspend fun prepare(uri: Uri, progress: (String) -> Unit): UpdateInfo = prepareExpected(uri, null, progress)

    internal suspend fun prepareExpected(uri: Uri, expectedVersion: String?, progress: (String) -> Unit): UpdateInfo = withContext(Dispatchers.IO) {
        check(component == "astrbot")
        checkLocalized(!hasPendingUpdate && !backups.hasPendingRestore) { AppText.get(R.string.ui_complete_the_previous_maintenance_operation_first) }
        DurableFiles.delete(work); check(work.mkdirs())
        try {
            progress(AppText.get(R.string.ui_reading_update_package))
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNullLocalized(input) { AppText.get(R.string.ui_cannot_read_the_selected_update_package) }
                FileOutputStream(packageFile).use { output -> boundedCopy(input, output, MAX_PACKAGE); output.fd.sync() }
            }
            verifyAndExtract(progress, expectedVersion)
        } catch (failure: Throwable) { DurableFiles.delete(work); throw failure }
    }

    internal suspend fun prepareNapCat(archive: File, envelope: JSONObject, id: String, progress: (String) -> Unit): UpdateInfo = withContext(Dispatchers.IO) {
        checkLocalized(component == "napcat" && !hasPendingUpdate && !backups.hasPendingRestore) { AppText.get(R.string.ui_complete_the_previous_maintenance_operation_first) }
        DurableFiles.delete(work); check(work.mkdirs())
        try {
            FileOutputStream(packageFile).use { output -> archive.inputStream().use { boundedCopy(it, output, MAX_PACKAGE) }; output.fd.sync() }
            DurableFiles.write(officialProof, JSONObject().put("envelope", envelope).put("id", id))
            verifyAndExtract(progress)
        } catch (failure: Throwable) { DurableFiles.delete(work); throw failure }
    }

    suspend fun verifyPrepared(progress: (String) -> Unit): UpdateInfo = withContext(Dispatchers.IO) {
        checkLocalized(!hasPendingUpdate) { AppText.get(R.string.ui_an_unfinished_update_record_exists) }
        verifyAndExtract(progress)
    }

    private suspend fun verifyAndExtract(progress: (String) -> Unit, expectedVersion: String? = null): UpdateInfo {
        progress(AppText.get(R.string.ui_verifying_update_signature_and_compatibility))
        DurableFiles.delete(staged)
        if (DurableFiles.exists(prepared)) DurableFiles.removeRecord(prepared)
        requireLocalized(packageFile.isFile && packageFile.length() <= MAX_PACKAGE) { AppText.get(R.string.ui_update_package_is_missing_or_too_large) }
        if (component == "napcat") {
            val value = OfficialNapCat.extract(context, packageFile, staged, requireNotNull(DurableFiles.read(officialProof)))
            DurableFiles.write(prepared, value)
            return info(value)
        }
        val manifest = ZipFile(packageFile).use { zip ->
            val entries = zip.entries().asSequence().toList()
            requireLocalized(entries.size == 3 && entries.map { it.name }.toSet() == setOf("manifest.json", "manifest.sig", "program.tar.bin")) { AppText.get(R.string.ui_invalid_update_package_structure) }
            fun read(name: String, limit: Int): ByteArray {
                val entry = zip.getEntry(name)
                requireLocalized(entry.size in 1..limit.toLong()) { AppText.get(R.string.ui_invalid_update_package_field_size) }
                return zip.getInputStream(entry).use { it.readBytesLimited(limit) }
            }
            val bytes = read("manifest.json", 64 * 1024)
            val signature = read("manifest.sig", 256)
            UpdateTrust(context).verify(bytes, signature)
            val value = JSONObject(bytes.toString(Charsets.UTF_8))
            requireLocalized(value.getInt("schema") == 1 && value.getString("component") == "astrbot") { AppText.get(R.string.ui_unsupported_update_format_or_component) }
            requireLocalized(value.getString("version").matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[a-zA-Z0-9.-]+)?"))) { AppText.get(R.string.ui_invalid_update_version) }
            requireLocalized(expectedVersion == null || value.getString("version") == expectedVersion) { AppText.get(R.string.ui_update_package_version_differs_from_the_signed_catalog) }
            requireLocalized(value.getInt("minAppCode") <= BuildConfig.VERSION_CODE) { AppText.get(R.string.ui_this_update_requires_a_newer_manager_apk) }
            val installed = InstalledVersions.read(context)
            requireLocalized(value.getString("abi") == installed.components["架构"] && value.getString("environmentKey") == installed.environmentKey) {
                AppText.get(R.string.ui_this_update_does_not_match_the_current_runtime_or)
            }
            val count = value.getInt("entries")
            val size = value.getLong("unpackedBytes")
            val payloadSize = value.getLong("payloadBytes")
            require(count in 1..100_000 && size in 1..MAX_PROGRAM && payloadSize in 1..MAX_PACKAGE)
            requireLocalized(files.usableSpace >= size * 2 + 64L * 1024 * 1024) { AppText.get(R.string.ui_insufficient_update_staging_space) }
            val payload = File(work, "program.tar.bin")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val entry = zip.getEntry("program.tar.bin")
                requireLocalized(entry.size == payloadSize) { AppText.get(R.string.ui_update_resource_size_mismatch) }
                FileOutputStream(payload).use { output ->
                    zip.getInputStream(entry).use { boundedCopy(it, output, payloadSize, digest) }; output.fd.sync()
                }
                requireLocalized(payload.length() == payloadSize && digest.digest().hex() == value.getString("payloadSha256")) { AppText.get(R.string.ui_update_resource_digest_mismatch_current_version_is_preserved) }
                progress(AppText.get(R.string.ui_extracting_and_verifying_astrbot, value.getString("version")))
                extract(payload, count, size)
                validateProgram(staged, value.getString("version"))
                val source = JSONObject(File(staged, ".astrbot-source.json").readText())
                requireLocalized(canonicalSource(source) == canonicalSource(value.getJSONObject("source"))) { AppText.get(R.string.ui_project_source_record_differs_from_the_signed_manifest) }
            } finally { payload.delete() }
            value
        }
        DurableFiles.write(prepared, manifest)
        return info(manifest)
    }

    private suspend fun extract(payload: File, expectedCount: Int, expectedSize: Long) {
        check(staged.mkdirs())
        val names = HashSet<String>()
        var size = 0L
        var count = 0
        TarArchiveInputStream(GZIPInputStream(payload.inputStream())).use { archive ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = archive.nextEntry ?: break
                val path = ArchivePaths.entry(staged.toPath(), entry.name)
                requireLocalized(path != staged.toPath() && names.add(path.toString()) && ++count <= expectedCount) { AppText.get(R.string.ui_update_archive_contains_duplicate_or_invalid_paths) }
                requireLocalized(!entry.name.split('/').contains("..") && !entry.isSymbolicLink && !entry.isLink) { AppText.get(R.string.ui_update_package_contains_unsupported_links_or_parent_directory_paths) }
                when {
                    entry.isDirectory -> require(path.toFile().isDirectory || path.toFile().mkdirs())
                    entry.isFile -> {
                        require(entry.size in 0..MAX_PROGRAM)
                        size += entry.size; requireLocalized(size <= expectedSize) { AppText.get(R.string.ui_extracted_update_size_exceeds_the_signed_manifest) }
                        path.parent.toFile().mkdirs()
                        FileOutputStream(path.toFile()).use { output ->
                            boundedCopy(archive, output, entry.size); output.fd.sync()
                        }
                        Os.chmod(path.toString(), 420)
                    }
                    else -> error(AppText.get(R.string.ui_update_archive_contains_special_files))
                }
            }
        }
        requireLocalized(count == expectedCount && size == expectedSize) { AppText.get(R.string.ui_extracted_update_count_or_size_mismatch) }
        DurableFiles.sync(staged)
    }

    suspend fun prepareRollback(progress: (String) -> Unit): String = withContext(Dispatchers.IO) {
        check(!hasPendingUpdate && !backups.hasPendingRestore)
        val record = DurableFiles.read(rollback) ?: error(AppText.get(R.string.ui_no_program_and_data_snapshot_available_for_rollback))
        val current = InstalledVersions.read(context)
        val counterpart = if (component == "napcat") "AstrBot" else "NapCat"
        requireLocalized(listOf(counterpart, "Linux QQ", "Python", "运行环境", "架构").all {
            record.getJSONObject("oldVersions").optString(it) == current.components[it]
        }) { AppText.get(R.string.ui_other_components_have_changed_so_this_old_combination_cannot) }
        val previous = previous(record)
        requireLocalized(previous.isDirectory && backups.list().any { it.id == record.getString("snapshotId") }) { AppText.get(R.string.ui_rollback_program_or_data_snapshot_is_missing) }
        val needed = previous.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        requireLocalized(files.usableSpace >= needed + 64L * 1024 * 1024) { AppText.get(R.string.ui_insufficient_rollback_preparation_space) }
        progress(AppText.get(R.string.ui_preparing_previous_program_and_its_data))
        DurableFiles.delete(work); check(work.mkdirs())
        DurableFiles.copyProgram(previous, staged)
        validateProgram(staged, record.getJSONObject("oldVersions").getString(label))
        record.getString("snapshotId")
    }

    internal suspend fun preserveUserPlugins() {
        if (component == "napcat") OfficialNapCat.preservePlugins(active, staged)
    }

    fun begin(snapshot: BackupInfo, versions: RuntimeVersions, wanted: List<ServiceId>) {
        check(!hasPendingUpdate && staged.isDirectory)
        val record = JSONObject().put("schema", 1).put("id", UUID.randomUUID().toString()).put("phase", "prepared")
            .put("snapshotId", snapshot.id).put("oldVersions", versions.json())
            .put("wanted", JSONArray(wanted.map { it.name }))
        DurableFiles.read(rollback)?.let { record.put("previousRollback", it) }
        DurableFiles.write(journal, record)
        DurableFiles.move(active, previous(record))
        DurableFiles.move(staged, active)
        record.put("phase", "switched"); DurableFiles.write(journal, record)
    }

    fun commit() {
        val record = requireNotNull(DurableFiles.read(journal))
        check(record.getString("phase") == "switched")
        record.put("phase", "committed"); DurableFiles.write(journal, record)
        finishCommitted(record)
    }

    /** Idempotent program + corresponding data recovery before any service launch. */
    suspend fun recover(): String? = withContext(Dispatchers.IO) {
        val record = DurableFiles.read(journal) ?: return@withContext null
        requireLocalized(record.getInt("schema") == 1 && record.getString("phase") in setOf("prepared", "switched", "rolling-back", "committed")) { AppText.get(R.string.ui_unrecognized_update_record_all_files_are_preserved) }
        if (record.getString("phase") == "committed") {
            finishCommitted(record); return@withContext AppText.get(R.string.ui_previous_update_commit_and_cleanup_completed)
        }
        val phase = record.getString("phase")
        record.put("phase", "rolling-back"); DurableFiles.write(journal, record)
        backups.recover()
        val old = previous(record)
        if (old.exists()) {
            DurableFiles.delete(active)
            DurableFiles.move(old, active)
        } else {
            requireLocalized(phase == "prepared" || phase == "rolling-back") { AppText.get(R.string.ui_previous_program_required_for_recovery_is_missing_data_is) }
        }
        val versions = InstalledVersions.read(context)
        requireLocalized(versions.compatibilityKey == record.getJSONObject("oldVersions").getString("compatibilityKey")) { AppText.get(R.string.ui_previous_program_version_check_failed_update_snapshot_is_preserved) }
        backups.beginRestore(record.getString("snapshotId"), versions)
        checkDatabase()
        backups.commitRestore()
        DurableFiles.delete(work)
        DurableFiles.removeRecord(journal)
        AppText.get(R.string.ui_update_was_incomplete_previous_program_and_original_data_snapshot)
    }

    suspend fun restoreRollbackData(id: String) {
        backups.beginRestore(id, InstalledVersions.read(context))
        checkDatabase()
        // Data stays uncommitted until service checks pass; recovery first unwinds this restore.
    }
    fun commitRollbackData() { if (backups.hasPendingRestore) backups.commitRestore() }

    internal fun discardForRuntimeReplacement() {
        check(!hasPendingUpdate)
        DurableFiles.read(rollback)?.let { DurableFiles.delete(previous(it)) }
        if (DurableFiles.exists(rollback)) DurableFiles.removeRecord(rollback)
        DurableFiles.delete(work)
    }

    suspend fun checkDatabase() {
        val result = BootstrapRuntime(context).execute("/opt/astrbot-venv/bin/python", "-c", """
            import sqlite3
            from pathlib import Path
            p = Path('/persistent/astrbot/data/data_v4.db')
            if p.is_file():
                with sqlite3.connect(f'file:{p}?mode=ro', uri=True) as c:
                    assert c.execute('PRAGMA quick_check').fetchall() == [('ok',)], 'database check failed'
        """.trimIndent(), timeoutSeconds = 60)
        checkLocalized(result.exitCode == 0) { AppText.get(R.string.ui_sqlite_verification_failed_for_the_data_snapshot_recovery_records) }
    }

    private fun finishCommitted(record: JSONObject) {
        require(active.isDirectory && previous(record).isDirectory)
        DurableFiles.write(rollback, JSONObject(record.toString()).apply { remove("previousRollback") })
        record.optJSONObject("previousRollback")?.let { old ->
            if (old.getString("id") != record.getString("id")) DurableFiles.delete(previous(old))
        }
        DurableFiles.delete(work)
        DurableFiles.removeRecord(journal)
    }
    private fun previous(record: JSONObject): File {
        val id = record.getString("id")
        require(UUID.fromString(id).toString() == id)
        return File(files, "releases/$component-$id")
    }
    private fun info(value: JSONObject) = UpdateInfo(value.getString("version"), value.getString("channel"),
        value.getString("notes").take(8000), value.getLong("unpackedBytes"))
    private fun canonicalSource(source: JSONObject): List<String> = listOf(source.getString("version")) +
        listOf("source", "dashboard").flatMap { name ->
            val record = source.getJSONObject(name)
            listOf(record.getString("url"), record.getString("sha256"))
        }
    private fun validateProgram(program: File, version: String) {
        if (component == "napcat") {
            val source = File(program, "napcat.mjs")
            requireLocalized(source.isFile && source.length() <= 32 * 1024 * 1024 &&
                NapCatVersion.read(program, source.readText()) == version && File(program, "static/index.html").isFile) { AppText.get(R.string.ui_napcat_rollback_program_version_or_dashboard_mismatch) }
            return
        }
        fun text(path: String): String {
            val file = File(program, path)
            requireLocalized(file.isFile && file.length() <= 4 * 1024 * 1024) { AppText.get(R.string.ui_update_lacks_required_program_or_dashboard_files) }
            return file.readText()
        }
        text("main.py")
        requireLocalized(Regex("__version__\\s*=\\s*[\"']([^\"']+)").find(text("astrbot/__init__.py"))?.groupValues?.get(1) == version) { AppText.get(R.string.ui_program_version_mismatch) }
        requireLocalized(text("astrbot/dashboard/dist/assets/version").trim().removePrefix("v").removePrefix("V") == version) { AppText.get(R.string.ui_dashboard_and_program_version_mismatch) }
        val html = text("astrbot/dashboard/dist/index.html")
        val references = Regex("(?:src|href)=[\"']/?(assets/[^\"']+\\.(?:js|css))[\"']").findAll(html).map { it.groupValues[1] }.toList()
        requireLocalized(references.isNotEmpty() && references.all { File(program, "astrbot/dashboard/dist/$it").isFile }) { AppText.get(R.string.ui_dashboard_resources_missing) }
    }
    private suspend fun boundedCopy(input: InputStream, output: java.io.OutputStream, max: Long, digest: MessageDigest? = null) {
        val buffer = ByteArray(128 * 1024)
        var size = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            size += count; requireLocalized(size <= max) { AppText.get(R.string.ui_update_resources_exceed_the_allowed_size) }
            requireLocalized(files.usableSpace >= 16L * 1024 * 1024) { AppText.get(R.string.ui_storage_ran_out_while_reading_update_resources) }
            output.write(buffer, 0, count); digest?.update(buffer, 0, count)
        }
    }
    private fun InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    companion object {
        private const val MAX_PACKAGE = 256L * 1024 * 1024
        private const val MAX_PROGRAM = 512L * 1024 * 1024
    }
}
