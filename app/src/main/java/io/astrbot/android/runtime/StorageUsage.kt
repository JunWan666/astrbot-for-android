package io.astrbot.android.runtime

import io.astrbot.android.AppText
import io.astrbot.android.R

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

enum class StoragePressure(private val titleResource: Int) {
    UNKNOWN(R.string.ui_storage_not_checked), NORMAL(R.string.ui_storage_space_normal), LOW(R.string.ui_storage_space_low), CRITICAL(R.string.ui_storage_critically_low);
    val title: String get() = AppText.get(titleResource)

    companion object {
        private const val MIB = 1024L * 1024
        fun from(bytes: Long?): StoragePressure = when {
            bytes == null || bytes < 0 -> UNKNOWN
            bytes < 128 * MIB -> CRITICAL
            bytes < 512 * MIB -> LOW
            else -> NORMAL
        }
    }
}

data class StorageSpaceReading(val availableBytes: Long? = null, val checkedAtMillis: Long? = null) {
    val pressure get() = StoragePressure.from(availableBytes)
    val warning get() = when (pressure) {
        StoragePressure.LOW, StoragePressure.CRITICAL -> AppText.get(R.string.ui_view_usage_in_settings_logs_and_diagnostics_storage_usage, pressure.title)
        else -> null
    }
}

internal object StorageSpace {
    fun read(context: Context) = StorageSpaceReading(
        runCatching { StatFs(context.filesDir.path).availableBytes }.getOrNull(), System.currentTimeMillis())
    val HELP get() = AppText.get(R.string.ui_a_warning_appears_below_512_mib_of_free_internal) +
        AppText.get(R.string.ui_free_other_phone_files_or_review_and_delete_unneeded) +
        AppText.get(R.string.ui_do_not_use_the_system_clear_data_action_it) +
        AppText.get(R.string.ui_warnings_do_not_automatically_stop_services_or_delete_qq)
}

/** Cheap volume checks have their own timer; a long maintenance lock cannot delay warnings. */
internal class StorageSpaceMonitor(private val read: () -> StorageSpaceReading,
    private val intervalMillis: Long = 60_000) {
    init { require(intervalMillis > 0) }
    suspend fun watch(publish: (StorageSpaceReading) -> Unit, event: (String) -> Unit) {
        var lastKnown = StoragePressure.UNKNOWN
        fun record(message: String) {
            try { event(message) }
            catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { /* Full storage must not stop in-memory warnings. */ }
        }
        while (currentCoroutineContext().isActive) {
            val space = read()
            publish(space)
            if (space.pressure != StoragePressure.UNKNOWN && space.pressure != lastKnown) {
                space.warning?.let(::record)
                if (space.pressure == StoragePressure.NORMAL && lastKnown in setOf(StoragePressure.LOW, StoragePressure.CRITICAL))
                    record(AppText.get(R.string.ui_free_storage_has_recovered_above_the_warning_threshold))
                lastKnown = space.pressure
            }
            delay(intervalMillis)
        }
    }
}

internal data class AppStorageBytes(val app: Long, val data: Long, val cache: Long)
internal data class StorageGroup(val id: String, val title: String, val scope: String,
    val bytes: Long, val files: Int, val complete: Boolean, val skippedLinks: Int, val skippedSpecial: Int)
internal data class StorageUsageReport(val space: StorageSpaceReading, val app: AppStorageBytes?,
    val groups: List<StorageGroup>, val checkedAtMillis: Long, val elapsedMillis: Long)

internal class StorageFileTally {
    private val seen = mutableSetOf<Pair<Long, Long>>()
    var bytes = 0L; private set
    var files = 0; private set
    fun record(device: Long, inode: Long, size: Long): Boolean {
        require(size >= 0)
        if (!seen.add(device to inode)) return false
        bytes = Math.addExact(bytes, size); files++
        return true
    }
}

/** Metadata only. Never opens file contents, changes permissions, recovers journals or removes files. */
internal class StorageUsageRepository(private val context: Context,
    private val maxEntries: Int = 100_000, private val maxDurationMillis: Long = 8_000,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime) {
    init { require(maxEntries > 0 && maxDurationMillis > 0) }

    fun appStorage(): AppStorageBytes? = runCatching {
        val manager = context.getSystemService(StorageStatsManager::class.java)
        val storage = context.getSystemService(StorageManager::class.java)
        val volume = storage.getUuidForPath(context.filesDir)
        manager.queryStatsForPackage(volume, context.packageName, Process.myUserHandle()).let {
            require(it.appBytes >= 0 && it.dataBytes >= 0 && it.cacheBytes in 0..it.dataBytes)
            Math.addExact(it.appBytes, it.dataBytes)
            AppStorageBytes(it.appBytes, it.dataBytes, it.cacheBytes)
        }
    }.getOrNull()

    suspend fun scan(progress: (String) -> Unit = {}): StorageUsageReport = withContext(Dispatchers.IO) {
        val started = elapsed()
        val coroutine = currentCoroutineContext()
        var visited = 0
        data class Category(val id: String, val title: String, val scope: String, val roots: List<File>)
        fun category(id: String, title: String, scope: String, vararg paths: String) =
            Category(id, title, scope, paths.map { File(context.filesDir, it) })
        val categories = listOf(
            category("astrbot", AppText.get(R.string.ui_astrbot_data), AppText.get(R.string.ui_configuration_plugins_databases_trace_data_and_upstream_logs), "persistent/astrbot"),
            category("napcat", AppText.get(R.string.ui_napcat_data), AppText.get(R.string.ui_dashboard_configuration_and_upstream_logs), "persistent/napcat"),
            category("qq-home", AppText.get(R.string.ui_qq_and_user_directory), AppText.get(R.string.ui_qq_sessions_cache_attachments_and_the_ubuntu_user_directory), "persistent/home", "persistent/qq"),
            category("backups", AppText.get(R.string.ui_local_backups), AppText.get(R.string.ui_manual_backups_and_automatic_snapshots_including_protected_snapshots), "backups"),
            category("staging", AppText.get(R.string.ui_update_and_import_staging), AppText.get(R.string.ui_downloaded_apks_programs_and_backups_awaiting_import), "staging"),
            category("extensions", AppText.get(R.string.cpolar_extension_storage), AppText.get(R.string.cpolar_extension_storage_hint), "extensions", "manager/extensions"),
            category("logs", AppText.get(R.string.ui_manager_output_logs), AppText.get(R.string.ui_rotated_output_from_the_manager_runtime_and_both_services), "logs"),
            Category("cache", AppText.get(R.string.ui_app_cache), AppText.get(R.string.ui_private_app_cache_including_exports_and_browser_data), listOf(context.cacheDir)),
            // Enumerate the large guest last so its budget cannot hide cache, logs or downloads.
            category("runtime", AppText.get(R.string.ui_runtime_resources_and_rollback), AppText.get(R.string.ui_current_and_rollback_runtime_and_program_resources), "runtime-data", "releases"),
        )
        val groups = categories.map { category ->
            coroutine.ensureActive()
            progress(AppText.get(R.string.ui_scanning, category.title))
            val tally = StorageFileTally()
            var complete = true
            var links = 0
            var special = 0
            fun withinBudget(): Boolean {
                coroutine.ensureActive()
                return visited < maxEntries && elapsed() - started < maxDurationMillis
            }
            for (root in category.roots) {
                if (!withinBudget()) { complete = false; break }
                try {
                    // Reject linked ancestors as well as linked leaves; Files.walkFileTree does not
                    // by itself prevent following a symlink in a starting path's parent.
                    val boundary = if (root == context.cacheDir) context.cacheDir.parentFile!! else context.filesDir
                    var current: File? = root
                    var missing = false
                    while (current != null && current.toPath().startsWith(boundary.toPath())) {
                        val info = try { Os.lstat(current.path) } catch (failure: ErrnoException) {
                            if (failure.errno == OsConstants.ENOENT) { missing = true; break } else throw failure
                        }
                        if (OsConstants.S_ISLNK(info.st_mode)) { links++; missing = true; break }
                        if (current == boundary) break
                        current = current.parentFile
                    }
                    if (missing) continue
                    Files.walkFileTree(root.toPath(), setOf(), 64, object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                            if (!withinBudget()) { complete = false; return FileVisitResult.TERMINATE }
                            visited++
                            return FileVisitResult.CONTINUE
                        }
                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                            if (!withinBudget()) { complete = false; return FileVisitResult.TERMINATE }
                            visited++
                            when {
                                attrs.isSymbolicLink -> links++
                                attrs.isDirectory -> complete = false // depth budget reached
                                attrs.isRegularFile -> {
                                    val stat = Os.lstat(file.toString())
                                    if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size < 0 || stat.st_size != attrs.size()) {
                                        complete = false
                                    } else tally.record(stat.st_dev, stat.st_ino, stat.st_size)
                                }
                                else -> special++
                            }
                            return FileVisitResult.CONTINUE
                        }
                        override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                            complete = false
                            return if (withinBudget()) FileVisitResult.CONTINUE else FileVisitResult.TERMINATE
                        }
                    })
                } catch (failure: CancellationException) { throw failure }
                catch (_: Exception) { complete = false }
            }
            StorageGroup(category.id, category.title, category.scope, tally.bytes, tally.files, complete, links, special)
        }
        coroutine.ensureActive()
        StorageUsageReport(StorageSpace.read(context), appStorage(), groups, System.currentTimeMillis(), elapsed() - started)
    }
}
