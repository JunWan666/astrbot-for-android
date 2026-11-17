package io.astrbot.android.runtime

import io.astrbot.android.checkLocalized
import io.astrbot.android.requireLocalized
import io.astrbot.android.requireNotNullLocalized

import io.astrbot.android.AppText
import io.astrbot.android.R
import io.astrbot.android.SharedExports
import io.astrbot.android.debug.DebugAccess

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.webkit.WebViewCompat
import io.astrbot.android.BuildConfig
import io.astrbot.android.core.SecretRedactor
import io.astrbot.android.core.ServiceId
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Read-only collection. Does not initialize guests, recover journals, or copy business files. */
class DiagnosticsRepository(private val context: Context) {
    private val cache = File(context.cacheDir, "diagnostics-export")

    suspend fun export(destination: Uri, snapshot: ManagerState, progress: (String) -> Unit) = archive(snapshot, progress) { archive ->
        progress(AppText.get(R.string.ui_writing_diagnostics_to_the_selected_file))
        requireNotNullLocalized(context.contentResolver.openOutputStream(destination, "wt")) { AppText.get(R.string.ui_cannot_write_the_selected_diagnostic_file) }.use { output ->
            archive.inputStream().use { input -> copy(input, output) }
        }
    }

    suspend fun prepareShare(snapshot: ManagerState, progress: (String) -> Unit): File = archive(snapshot, progress) { archive ->
        val destination = SharedExports.destination(context, "astrbot-diagnostics-${System.currentTimeMillis()}.zip")
        try {
            destination.outputStream().use { output -> archive.inputStream().use { input -> copy(input, output) } }
            destination
        } catch (failure: Throwable) { destination.parentFile?.deleteRecursively(); throw failure }
    }

    private suspend fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(128 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer); if (count < 0) break
            output.write(buffer, 0, count)
        }
        output.flush()
    }

    private suspend fun <T> archive(snapshot: ManagerState, progress: (String) -> Unit, consume: suspend (File) -> T): T = withContext(Dispatchers.IO) {
        // Controller serialization guarantees an older folder cannot belong to another export.
        DurableFiles.delete(cache)
        checkLocalized(cache.mkdirs()) { AppText.get(R.string.ui_cannot_prepare_diagnostic_cache) }
        try {
            requireLocalized(cache.usableSpace >= MAX_ARCHIVE + RESERVE) { AppText.get(R.string.ui_insufficient_diagnostic_cache_space) }
            progress(AppText.get(R.string.ui_collecting_system_version_and_runtime_information))
            val report = report(snapshot)
            val entries = mutableListOf<Pair<String, File>>()
            val logs = JSONArray()
            for (category in LogCategory.entries) {
                currentCoroutineContext().ensureActive()
                progress(AppText.get(R.string.ui_collecting_redacted_logs, category.label))
                val name = "logs/${category.name.lowercase()}.log"
                val file = File(cache, name).apply { parentFile!!.mkdirs() }
                var status = "collected"
                try {
                    file.bufferedWriter(Charsets.UTF_8).use { target ->
                        val coroutine = currentCoroutineContext()
                        val checked = object : FilterWriter(target) {
                            override fun write(value: String, offset: Int, count: Int) {
                                coroutine.ensureActive(); super.write(value, offset, count)
                            }
                        }
                        RuntimeStore.log(context, category).exportTo(checked, maxFileBytes = LOG_FILE_LIMIT)
                    }
                } catch (failure: CancellationException) { throw failure }
                catch (_: Exception) {
                    // Discard partial output and exception text, which can contain source data.
                    status = "unavailable"
                    file.writeText(AppText.get(R.string.ui_some_logs_are_unreadable_partial_content_and_original_errors))
                }
                requireLocalized(file.length() <= MAX_LOG_OUTPUT) { AppText.get(R.string.ui_diagnostic_logs_exceed_the_collection_limit) }
                entries += name to file
                logs.put(JSONObject().put("category", category.name).put("entry", name).put("status", status).put("bytes", file.length()))
            }
            report.put("logs", logs)
            val archive = File(cache, "diagnostics.zip")
            ZipOutputStream(BufferedOutputStream(FileOutputStream(archive))).use { zip ->
                fun text(name: String, value: String) { zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
                text("report.json", report.toString(2))
                text("README.txt", NOTICE)
                val buffer = ByteArray(128 * 1024)
                entries.forEach { (name, file) ->
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { input -> while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer); if (count < 0) break
                        zip.write(buffer, 0, count)
                    } }
                    zip.closeEntry()
                }
            }
            requireLocalized(archive.length() in 1..MAX_ARCHIVE) { AppText.get(R.string.ui_diagnostic_archive_exceeds_the_size_limit) }
            consume(archive)
        } finally { DurableFiles.delete(cache) }
    }

    private fun report(snapshot: ManagerState): JSONObject {
        fun safe(text: String, limit: Int = 2048) = SecretRedactor.redact(text).take(limit)
        val runtimeRoot = File(context.filesDir, "runtime-data/ubuntu")
        val inspection = if (File(runtimeRoot, ".astrbot-bootstrap.json").isFile)
            InstalledVersionInspection.read(runtimeRoot) else null
        val versions = inspection?.components
        val storageSpace = StorageSpace.read(context)
        val system = JSONObject().put("manufacturer", safe(Build.MANUFACTURER, 128)).put("model", safe(Build.MODEL, 128))
            .put("brand", safe(Build.BRAND, 128)).put("device", safe(Build.DEVICE, 128))
            .put("androidVersion", safe(Build.VERSION.RELEASE, 64)).put("api", Build.VERSION.SDK_INT)
            .put("securityPatch", safe(Build.VERSION.SECURITY_PATCH, 64))
            .put("kernelVersion", safe(System.getProperty("os.version").orEmpty(), 256))
            .put("timeZone", java.util.TimeZone.getDefault().id)
            .put("utcOffsetSeconds", java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000)
            .put("availableProcessors", Runtime.getRuntime().availableProcessors())
            .put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("availableStorageBytes", storageSpace.availableBytes ?: JSONObject.NULL)
            .put("storagePressure", storageSpace.pressure.name)
        StorageUsageRepository(context).appStorage()?.let { usage ->
            system.put("appStorageBytes", JSONObject().put("app", usage.app).put("data", usage.data).put("cacheIncludedInData", usage.cache))
        }
        runCatching { WebViewCompat.getCurrentWebViewPackage(context)?.versionName }.getOrNull()?.let { system.put("webViewVersion", safe(it, 128)) }
        runCatching { context.getSystemService(PowerManager::class.java) }.getOrNull()?.let { power ->
            system.put("interactive", power.isInteractive).put("powerSaveMode", power.isPowerSaveMode)
                .put("ignoringBatteryOptimizations", power.isIgnoringBatteryOptimizations(context.packageName))
        }
        runCatching {
            ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        }.getOrNull()?.let { system.put("totalMemoryBytes", it.totalMem).put("availableMemoryBytes", it.availMem).put("lowMemory", it.lowMemory) }
        val manager = JSONObject().put("environmentInstalled", snapshot.environmentInstalled).put("componentsReady", snapshot.componentsReady)
            .put("maintenance", snapshot.maintenance.name).put("phase", safe(snapshot.phase))
            .put("versionNotice", inspection?.notice ?: JSONObject.NULL)
            .put("lastError", snapshot.error?.let { safe(it) } ?: JSONObject.NULL)
            .put("lastProblem", snapshot.currentProblem()?.json() ?: JSONObject.NULL)
        val network = NetworkConnectivity.snapshot(context).let {
            JSONObject().put("state", it.state.name).put("checkedAt", it.checkedAtMillis ?: JSONObject.NULL)
        }
        val interrupted = JSONObject()
        mapOf("dataRestore" to "restore.json", "programUpdate" to "update.json", "runtimeUpdate" to "runtime-update.json", "environmentRepair" to "environment-repair.json", "personaChange" to "persona.json").forEach { (key, name) ->
            interrupted.put(key, DurableFiles.exists(File(context.filesDir, "operations/$name")))
        }
        val services = JSONObject()
        ServiceId.entries.forEach { id ->
            val state = snapshot.services.getValue(id)
            val connection = snapshot.webUis.getValue(id)
            services.put(id.name, JSONObject().put("desiredRunning", state.desiredRunning).put("process", state.process.name)
                .put("health", state.health.name).put("qq", state.qq.name).put("oneBot", state.oneBot.name)
                .put("checkedAt", state.checkedAtMillis ?: JSONObject.NULL)
                .put("lastAnomaly", state.history.lastAnomaly?.let { JSONObject().put("atMillis", it.atMillis).put("summary", safe(it.summary)) } ?: JSONObject.NULL)
                .put("lastRecovery", state.history.lastRecovery?.let { JSONObject().put("atMillis", it.atMillis).put("summary", safe(it.summary)) } ?: JSONObject.NULL)
                .put("processGeneration", state.processGeneration).put("detail", safe(state.detail))
                .put("webUiEnabled", connection.enabled).put("webUiRecognized", connection.endpoint != null)
                .put("webUiPort", connection.endpoint?.port ?: JSONObject.NULL)
                .put("webUiScheme", connection.endpoint?.scheme ?: JSONObject.NULL))
        }
        val dashboard = AstrBotDashboard.inspect(runtimeRoot)
        val assets = JSONObject().put("status", if (dashboard?.readable == true) "readable" else "missing-or-unreadable")
            .put("source", dashboard?.source ?: JSONObject.NULL).put("version", dashboard?.version ?: JSONObject.NULL)
            .put("candidates", JSONArray().also { candidates -> AstrBotDashboard.candidates(runtimeRoot).forEach { (source, directory) ->
                val index = File(directory, "index.html")
                candidates.put(JSONObject().put("source", source).put("directoryExists", directory.isDirectory)
                    .put("indexReadable", index.isFile && index.canRead()))
            } })
        val environmentCheck = snapshot.environmentCheck?.let { check ->
            JSONObject().put("checkedAt", check.checkedAtMillis).put("canRepair", check.canRepair)
                .put("automaticRepairTargets", JSONArray(check.repairTargets.map { it.name }))
                .put("repairExplanation", safe(check.repairExplanation))
                .put("repairProblem", check.repairProblem?.json() ?: JSONObject.NULL)
                .put("items", JSONArray().also { items -> check.items.forEach {
                    items.put(JSONObject().put("title", safe(it.title)).put("status", it.status.name).put("detail", safe(it.detail)))
                } })
        }
        val exits = JSONArray()
        var exitStatus = if (Build.VERSION.SDK_INT >= 30) "unavailable" else "unsupported"
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 8)
                .forEach { exit -> exits.put(JSONObject().put("timestamp", exit.timestamp).put("reason", exit.reason)
                    .put("status", exit.status).put("importance", exit.importance).put("pssKiB", exit.pss).put("rssKiB", exit.rss)
                    .put("processName", safe(exit.processName, 256))) }
            exitStatus = "collected"
        }
        val terminal = TerminalStore.state.value
        return JSONObject().put("schema", 1).put("format", "astrbot-android-diagnostics")
            .put("id", UUID.randomUUID().toString()).put("createdAt", System.currentTimeMillis())
            .put("app", JSONObject().put("package", context.packageName).put("version", BuildConfig.VERSION_NAME)
                .put("versionCode", BuildConfig.VERSION_CODE).put("debug", BuildConfig.DEBUG).put("bundledRuntime", BuildConfig.BUNDLED_RUNTIME))
            .put("system", system).put("versionsStatus", if (versions == null) "unavailable" else "read-from-installed-runtime")
            .put("versions", JSONObject().also { result -> versions?.forEach { (name, value) -> result.put(name, safe(value, 256)) } })
            .put("manager", manager).put("network", network).put("services", services).put("interruptedOperations", interrupted)
            .put("dashboardAssets", assets).put("environmentCheck", environmentCheck ?: JSONObject.NULL)
            .put("environmentCheckStatus", if (environmentCheck == null) "not-run" else "last-check")
            .put("processExits", JSONObject().put("supported", Build.VERSION.SDK_INT >= 30).put("status", exitStatus).put("records", exits))
            .put("permissionBackend", JSONObject().put("shizuku", DebugAccess.status(context).name)
                .put("processLimits", DebugAccess.lastInspection ?: JSONObject.NULL))
            .put("terminals", JSONArray().also { sessions -> terminal.sessions.forEach {
                sessions.put(JSONObject().put("id", it.id).put("running", it.running).put("exitCode", it.exitCode ?: JSONObject.NULL))
            } })
            .put("scope", JSONObject().put("businessFilesIncluded", false).put("qqSessionFilesIncluded", false)
                .put("deviceIdentifiersIncluded", false).put("recognizedSecretsRedacted", true).put("logSourceBytesPerFile", LOG_FILE_LIMIT)
                .put("logSourceFilesPerCategory", 3).put("notice", NOTICE))
    }

    companion object {
        private const val LOG_FILE_LIMIT = 512 * 1024
        private const val MAX_LOG_OUTPUT = 8L * 1024 * 1024
        private const val MAX_ARCHIVE = 40L * 1024 * 1024
        private const val RESERVE = 64L * 1024 * 1024
        val NOTICE get() = AppText.get(R.string.ui_diagnostics_include_device_model_system_and_component_versions_runtime) +
            AppText.get(R.string.ui_configuration_files_databases_plugins_qq_login_session_files_and) +
            AppText.get(R.string.ui_only_recognized_credentials_and_login_qr_codes_are_hidden) +
            AppText.get(R.string.ui_up_to_two_historical_logs_and_the_current_log) +
            AppText.get(R.string.ui_this_file_is_for_troubleshooting_and_cannot_restore_data)
    }
}
