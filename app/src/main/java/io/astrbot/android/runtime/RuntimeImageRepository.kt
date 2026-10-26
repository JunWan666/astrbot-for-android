package io.astrbot.android.runtime

import io.astrbot.android.checkLocalized
import io.astrbot.android.requireLocalized
import io.astrbot.android.requireNotNullLocalized

import io.astrbot.android.AppText
import io.astrbot.android.R

import android.content.Context
import io.astrbot.android.core.ServiceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class RuntimeImageInfo(val name: String, val snapshotId: String? = null)

/** APK-authenticated whole guests. Every switched image is paired with a stopped-data snapshot. */
class RuntimeImageRepository(private val context: Context) {
    private val files = context.filesDir
    private val runtime = BootstrapRuntime(context)
    private val active = runtime.rootfs
    private val staged = File(files, "runtime-data/ubuntu.image-staging")
    private val journal = File(files, "operations/runtime-update.json")
    private val rollback = File(files, "manifests/runtime-rollback.json")
    private val backups = BackupRepository(files)
    val hasPendingUpdate get() = DurableFiles.exists(journal)
    val hasCommittedUpdate get() = DurableFiles.read(journal)?.optString("phase") == "committed"
    val available: RuntimeImageInfo? get() = runCatching {
        val installed = DurableFiles.read(File(active, ".astrbot-bootstrap.json")) ?: return@runCatching null
        val bundled = runtime.bundledManifest()
        if (bundled.optString("kind") == "complete" && bundled.getString("sha256") != installed.getString("sha256"))
            RuntimeImageInfo(bundled.getString("id")) else null
    }.getOrNull()
    val rollbackInfo: RuntimeImageInfo? get() = DurableFiles.read(rollback)?.let {
        RuntimeImageInfo(it.getJSONObject("oldVersions").getString("运行环境"), it.getString("snapshotId"))
    }
    val protectedBackupIds: Set<String> get() = listOfNotNull(DurableFiles.read(journal), DurableFiles.read(rollback))
        .map { it.getString("snapshotId") }.toSet()

    suspend fun prepare(isRollback: Boolean, progress: (String) -> Unit): String? = prepareInternal(isRollback, false, progress)

    /** Restore missing runtime files only when the clean image has the exact same maintenance key. */
    internal suspend fun prepareRepair(progress: (String) -> Unit) { prepareInternal(false, true, progress) }

    private suspend fun prepareInternal(isRollback: Boolean, repair: Boolean, progress: (String) -> Unit): String? = withContext(Dispatchers.IO) {
        checkLocalized(!hasPendingUpdate && !backups.hasPendingRestore && !UpdateRepository(context).hasPendingUpdate && !UpdateRepository(context, "napcat").hasPendingUpdate) { AppText.get(R.string.ui_complete_the_previous_maintenance_operation_first) }
        if (isRollback) {
            val record = requireNotNullLocalized(DurableFiles.read(rollback)) { AppText.get(R.string.ui_no_runtime_available_for_rollback) }
            requireLocalized(InstalledVersions.read(previous(record)).compatibilityKey == record.getJSONObject("oldVersions").getString("compatibilityKey")) {
                AppText.get(R.string.ui_old_runtime_verification_failed_files_are_preserved)
            }
            requireLocalized(backups.list().any { it.id == record.getString("snapshotId") }) { AppText.get(R.string.ui_the_corresponding_runtime_data_snapshot_is_missing) }
            record.getString("snapshotId")
        } else {
            if (!repair) requireLocalized(available != null) { AppText.get(R.string.ui_this_apk_s_runtime_is_already_installed) }
            runtime.prepareBundled(staged, progress)
            requireLocalized(InstalledVersions.read(staged).components["架构"] == runtime.abi) { AppText.get(R.string.ui_cannot_identify_the_new_runtime_version) }
            if (repair) requireLocalized(InstalledVersions.read(active).compatibilityKey == InstalledVersions.read(staged).compatibilityKey) {
                AppText.get(R.string.ui_installed_components_differ_from_bundled_resources_the_original_runtime)
            }
            null
        }
    }

    fun begin(snapshot: BackupInfo, versions: RuntimeVersions, wanted: List<ServiceId>, isRollback: Boolean, repair: Boolean = false) {
        check(!hasPendingUpdate)
        val retained = DurableFiles.read(rollback)
        val candidate = if (isRollback) previous(requireNotNull(retained)) else staged
        val next = InstalledVersions.read(candidate)
        requireLocalized(!repair || !isRollback && versions.compatibilityKey == next.compatibilityKey) {
            AppText.get(R.string.ui_component_versions_changed_during_repair_original_runtime_and_snapshot)
        }
        val record = JSONObject().put("schema", 1).put("id", UUID.randomUUID().toString()).put("phase", "prepared")
            .put("snapshotId", snapshot.id).put("oldVersions", versions.json()).put("newVersions", next.json())
            .put("isRollback", isRollback).put("wanted", JSONArray(wanted.map { it.name }))
        retained?.let { record.put("previousRollback", it) }
        DurableFiles.write(journal, record)
        DurableFiles.move(active, previous(record))
        DurableFiles.move(candidate, active)
        record.put("phase", "switched"); DurableFiles.write(journal, record)
    }

    fun commit() {
        val record = requireNotNull(DurableFiles.read(journal))
        check(record.getString("phase") == "switched")
        requireLocalized(InstalledVersions.read(context).compatibilityKey == record.getJSONObject("newVersions").getString("compatibilityKey")) {
            AppText.get(R.string.ui_new_runtime_version_changed_during_maintenance_recovery_records_are)
        }
        record.put("phase", "committed"); DurableFiles.write(journal, record)
        finishCommitted(record)
    }

    /** Called before other repositories: a nested data restore belongs to this image transaction. */
    suspend fun recover(): String? = withContext(Dispatchers.IO) {
        val record = DurableFiles.read(journal)
        if (record == null) {
            DurableFiles.delete(staged, writableGuestDirectories = true)
            return@withContext null
        }
        requireLocalized(record.getInt("schema") == 1 && record.getString("phase") in setOf("prepared", "switched", "recovering", "committed")) {
            AppText.get(R.string.ui_unrecognized_runtime_upgrade_record_all_files_are_preserved)
        }
        if (record.getString("phase") == "committed") {
            finishCommitted(record)
            return@withContext AppText.get(R.string.ui_previous_runtime_upgrade_commit_and_cleanup_completed)
        }
        record.put("phase", "recovering"); DurableFiles.write(journal, record)
        backups.recover()
        val old = previous(record)
        if (old.exists()) {
            if (active.exists()) {
                if (record.getBoolean("isRollback")) {
                    val target = previous(record.getJSONObject("previousRollback"))
                    checkLocalized(!target.exists()) { AppText.get(R.string.ui_rollback_runtime_directory_conflict_recovery_records_are_preserved) }
                    DurableFiles.move(active, target)
                } else DurableFiles.delete(active, writableGuestDirectories = true)
            }
            DurableFiles.move(old, active)
        }
        val versions = InstalledVersions.read(context)
        requireLocalized(versions.compatibilityKey == record.getJSONObject("oldVersions").getString("compatibilityKey")) {
            AppText.get(R.string.ui_original_runtime_required_for_recovery_is_missing_or_has)
        }
        backups.beginRestore(record.getString("snapshotId"), versions)
        UpdateRepository(context).checkDatabase()
        backups.commitRestore()
        DurableFiles.delete(staged, writableGuestDirectories = true)
        DurableFiles.removeRecord(journal)
        AppText.get(R.string.ui_runtime_upgrade_was_incomplete_original_runtime_and_its_data)
    }

    private fun finishCommitted(record: JSONObject) {
        requireLocalized(active.isDirectory && previous(record).isDirectory) { AppText.get(R.string.ui_files_required_to_commit_the_runtime_are_missing_recovery) }
        DurableFiles.write(rollback, JSONObject(record.toString()).apply { remove("previousRollback") })
        record.optJSONObject("previousRollback")?.let { prior ->
            if (prior.getString("id") != record.getString("id")) DurableFiles.delete(previous(prior), writableGuestDirectories = true)
        }
        // AstrBot-only rollback belongs to the former whole guest; it must not be offered here.
        UpdateRepository(context).discardForRuntimeReplacement()
        UpdateRepository(context, "napcat").discardForRuntimeReplacement()
        DurableFiles.delete(staged, writableGuestDirectories = true)
        DurableFiles.removeRecord(journal)
    }

    private fun previous(record: JSONObject): File {
        val id = record.getString("id")
        requireLocalized(UUID.fromString(id).toString() == id) { AppText.get(R.string.ui_invalid_runtime_record_id) }
        return File(files, "runtime-data/rollback-$id")
    }
}
