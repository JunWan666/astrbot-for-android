package io.astrbot.android.runtime

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
        check(!hasPendingUpdate && !backups.hasPendingRestore && !UpdateRepository(context).hasPendingUpdate && !UpdateRepository(context, "napcat").hasPendingUpdate) { "请先完成上次维护操作" }
        if (isRollback) {
            val record = requireNotNull(DurableFiles.read(rollback)) { "没有可回退的运行环境" }
            require(InstalledVersions.read(previous(record)).compatibilityKey == record.getJSONObject("oldVersions").getString("compatibilityKey")) {
                "旧环境校验失败，已保留文件"
            }
            require(backups.list().any { it.id == record.getString("snapshotId") }) { "对应环境数据快照缺失" }
            record.getString("snapshotId")
        } else {
            if (!repair) require(available != null) { "当前已经使用此 APK 的运行环境" }
            runtime.prepareBundled(staged, progress)
            require(InstalledVersions.read(staged).components["架构"] == runtime.abi) { "新环境版本无法识别" }
            if (repair) require(InstalledVersions.read(active).compatibilityKey == InstalledVersions.read(staged).compatibilityKey) {
                "当前组件组合与内置资源不同，已保留原环境；请使用匹配的运行环境升级或维护包"
            }
            null
        }
    }

    fun begin(snapshot: BackupInfo, versions: RuntimeVersions, wanted: List<ServiceId>, isRollback: Boolean, repair: Boolean = false) {
        check(!hasPendingUpdate)
        val retained = DurableFiles.read(rollback)
        val candidate = if (isRollback) previous(requireNotNull(retained)) else staged
        val next = InstalledVersions.read(candidate)
        require(!repair || !isRollback && versions.compatibilityKey == next.compatibilityKey) {
            "修复期间组件版本发生变化，原环境与快照已保留，请重新检查"
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
        require(InstalledVersions.read(context).compatibilityKey == record.getJSONObject("newVersions").getString("compatibilityKey")) {
            "新环境版本在维护期间发生变化，已保留恢复记录"
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
        require(record.getInt("schema") == 1 && record.getString("phase") in setOf("prepared", "switched", "recovering", "committed")) {
            "运行环境升级记录无法识别，已保留全部文件"
        }
        if (record.getString("phase") == "committed") {
            finishCommitted(record)
            return@withContext "已完成上次运行环境升级的提交与清理"
        }
        record.put("phase", "recovering"); DurableFiles.write(journal, record)
        backups.recover()
        val old = previous(record)
        if (old.exists()) {
            if (active.exists()) {
                if (record.getBoolean("isRollback")) {
                    val target = previous(record.getJSONObject("previousRollback"))
                    check(!target.exists()) { "回退环境目录冲突，已保留恢复记录" }
                    DurableFiles.move(active, target)
                } else DurableFiles.delete(active, writableGuestDirectories = true)
            }
            DurableFiles.move(old, active)
        }
        val versions = InstalledVersions.read(context)
        require(versions.compatibilityKey == record.getJSONObject("oldVersions").getString("compatibilityKey")) {
            "恢复所需的原环境缺失或版本不符，已保留全部文件与快照"
        }
        backups.beginRestore(record.getString("snapshotId"), versions)
        UpdateRepository(context).checkDatabase()
        backups.commitRestore()
        DurableFiles.delete(staged, writableGuestDirectories = true)
        DurableFiles.removeRecord(journal)
        "运行环境升级未完成，已恢复原环境与对应数据快照"
    }

    private fun finishCommitted(record: JSONObject) {
        require(active.isDirectory && previous(record).isDirectory) { "环境提交所需文件缺失，已保留恢复记录" }
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
        require(UUID.fromString(id).toString() == id) { "运行环境记录编号无效" }
        return File(files, "runtime-data/rollback-$id")
    }
}
