package io.astrbot.android.runtime

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

    suspend fun export(destination: Uri, snapshot: ManagerState, progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        // Controller serialization guarantees an older folder cannot belong to another export.
        DurableFiles.delete(cache)
        check(cache.mkdirs()) { "无法准备诊断包缓存" }
        try {
            require(cache.usableSpace >= MAX_ARCHIVE + RESERVE) { "诊断包缓存空间不足" }
            progress("正在收集系统、版本和运行状态")
            val report = report(snapshot)
            val entries = mutableListOf<Pair<String, File>>()
            val logs = JSONArray()
            for (category in LogCategory.entries) {
                currentCoroutineContext().ensureActive()
                progress("正在收集${category.label}脱敏日志")
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
                    file.writeText("日志暂无法读取，未附带部分内容或原始异常。\n")
                }
                require(file.length() <= MAX_LOG_OUTPUT) { "诊断日志超出采集上限" }
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
            require(archive.length() in 1..MAX_ARCHIVE) { "诊断包超过大小上限" }
            progress("正在写入所选诊断包文件")
            requireNotNull(context.contentResolver.openOutputStream(destination, "wt")) { "无法写入所选诊断包" }.use { output ->
                archive.inputStream().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer); if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
                output.flush()
            }
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
            .put("androidVersion", safe(Build.VERSION.RELEASE, 64)).put("api", Build.VERSION.SDK_INT)
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
        val network = NetworkConnectivity.snapshot(context).let {
            JSONObject().put("state", it.state.name).put("checkedAt", it.checkedAtMillis ?: JSONObject.NULL)
        }
        val interrupted = JSONObject()
        mapOf("dataRestore" to "restore.json", "programUpdate" to "update.json", "runtimeUpdate" to "runtime-update.json", "personaChange" to "persona.json").forEach { (key, name) ->
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
        return JSONObject().put("schema", 1).put("format", "astrbot-android-diagnostics")
            .put("id", UUID.randomUUID().toString()).put("createdAt", System.currentTimeMillis())
            .put("app", JSONObject().put("package", context.packageName).put("version", BuildConfig.VERSION_NAME)
                .put("versionCode", BuildConfig.VERSION_CODE).put("debug", BuildConfig.DEBUG).put("bundledRuntime", BuildConfig.BUNDLED_RUNTIME))
            .put("system", system).put("versionsStatus", if (versions == null) "unavailable" else "read-from-installed-runtime")
            .put("versions", JSONObject().also { result -> versions?.forEach { (name, value) -> result.put(name, safe(value, 256)) } })
            .put("manager", manager).put("network", network).put("services", services).put("interruptedOperations", interrupted)
            .put("scope", JSONObject().put("businessFilesIncluded", false).put("qqSessionFilesIncluded", false)
                .put("deviceIdentifiersIncluded", false).put("recognizedSecretsRedacted", true).put("logSourceBytesPerFile", LOG_FILE_LIMIT)
                .put("logSourceFilesPerCategory", 3).put("notice", NOTICE))
    }

    companion object {
        private const val LOG_FILE_LIMIT = 512 * 1024
        private const val MAX_LOG_OUTPUT = 8L * 1024 * 1024
        private const val MAX_ARCHIVE = 40L * 1024 * 1024
        private const val RESERVE = 64L * 1024 * 1024
        const val NOTICE = "诊断包包含设备型号、系统/组件版本、运行状态、管理事件和受限大小的脱敏日志。" +
            "不附带配置文件、数据库、插件文件、QQ 登录会话文件或设备唯一标识。" +
            "仅隐藏已识别的凭据和登录二维码；日志仍可能包含聊天内容、账号或其他个人信息，请检查后再分享。" +
            "每类最多采集两份历史日志和当前日志，每文件最近 512 KiB；超长行、截断行及链接文件会省略。" +
            "此文件用于排查问题，不能用于恢复数据。\n"
    }
}
