package io.astrbot.android.runtime

import android.content.Context
import io.astrbot.android.AppText
import io.astrbot.android.R
import io.astrbot.android.checkLocalized
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

data class HarnessState(val installed: Boolean = false, val version: String? = null,
    val desired: Boolean = false, val active: Boolean = false, val ready: Boolean = false,
    val busy: Boolean = false, val error: String? = null, val downloaded: Long = 0, val total: Long = 0,
    val installation: HarnessInstallProgress? = null, val lan: Boolean = false, val host: String = "127.0.0.1")

data class HarnessInstallProgress(val stage: String, val step: Int, val elapsed: Long, val stageElapsed: Long,
    val activity: String, val fetched: Int, val quietSeconds: Long, val completed: Int, val entries: Int,
    val receivedAt: Long = android.os.SystemClock.elapsedRealtime()) {
    val label: String get() = AppText.get(when (stage) {
        "download" -> R.string.harness_downloading; "verify" -> R.string.harness_stage_verify
        "extract" -> R.string.harness_stage_extract; "system" -> R.string.harness_stage_system
        "packages" -> R.string.harness_stage_packages; "verify_program" -> R.string.harness_stage_check
        else -> R.string.harness_stage_finished
    })
    companion object {
        fun parse(value: JSONObject): HarnessInstallProgress {
            val stage = value.getString("stage")
            require(stage in setOf("download", "verify", "extract", "system", "packages", "verify_program", "finished"))
            return HarnessInstallProgress(stage, value.getInt("step").coerceIn(1, 6), value.optLong("elapsed").coerceAtLeast(0),
                value.optLong("stageElapsed").coerceAtLeast(0), value.optString("activity"), value.optInt("fetched"),
                value.optLong("quietSeconds"), value.optInt("completed"), value.optInt("entries"))
        }
    }
}
object HarnessStore {
    val state = MutableStateFlow(HarnessState())
    @Volatile internal var entryUrl: String? = null
}

class HarnessRepository(private val context: Context) {
    val directory = File(context.filesDir,"manager/extensions/harness")
    private val preferences = context.getSharedPreferences("manager",Context.MODE_PRIVATE)
    fun desired() = preferences.getBoolean("desired_harness",false)
    fun setDesired(value: Boolean) { check(preferences.edit().putBoolean("desired_harness",value).commit()) }
    fun lan() = preferences.getBoolean("harness_lan", false)
    fun setLan(value: Boolean) { check(preferences.edit().putBoolean("harness_lan", value).commit()); refresh() }
    fun refresh() {
        val marker = runCatching { DurableFiles.read(File(directory,"installed.json")) }.getOrNull()
        HarnessStore.state.update { it.copy(installed = marker != null && File(directory,"program/node/bin/node").isFile &&
            File(directory,"program/node_modules/@deepseek-ai/dsh/lib/bin.js").isFile,
            version = marker?.optString("version"),desired = desired(), lan = lan()) }
    }
    fun prepareAssets() {
        val scripts = File(directory,"scripts").apply { mkdirs() }
        for (name in listOf("install.py","start.sh","sources.json","package.json","package-lock.json")) {
            context.assets.open("runtime/harness/$name").use { input -> File(scripts,name).outputStream().use { input.copyTo(it) } }
        }
    }
    suspend fun install(progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        val runtime = BootstrapRuntime(context)
        checkLocalized(runtime.abi == "arm64-v8a") { AppText.get(R.string.desktop_arm64) }
        checkLocalized(File(runtime.rootfs,".astrbot-bootstrap.json").isFile) { AppText.get(R.string.ui_initialize_the_ubuntu_runtime_first) }
        checkLocalized(context.filesDir.usableSpace >= 1536L * 1024 * 1024) { AppText.get(R.string.harness_space) }
        prepareAssets()
        progress(AppText.get(R.string.harness_downloading))
        val exitCode = coroutineScope {
        val process = runtime.processBuilder("/usr/bin/python3","-u","/manager/extensions/harness/scripts/install.py").start()
        val reader = launch(Dispatchers.IO) { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
            RuntimeStore.log(context,LogCategory.HARNESS).append(line)
            if (line.startsWith("ASTRBOT_HARNESS ")) runCatching {
                val value = JSONObject(line.removePrefix("ASTRBOT_HARNESS "))
                val installation = HarnessInstallProgress.parse(value)
                HarnessStore.state.update { it.copy(installation = installation,
                    downloaded = if (installation.stage == "download") value.optLong("downloaded") else it.downloaded,
                    total = if (installation.stage == "download") value.optLong("total") else it.total) }
                progress(installation.label)
            }
        } } }
        try {
            withTimeout(30 * 60_000L) { while (process.isAlive) delay(250); reader.join() }
            process.exitValue()
        } finally { withContext(NonCancellable + Dispatchers.IO) {
            if (process.isAlive) { process.destroy(); if (!process.waitFor(8,TimeUnit.SECONDS)) process.destroyForcibly() }
            runCatching { process.inputStream.close() }; reader.cancelAndJoin()
        } }
        }
        checkLocalized(exitCode == 0) { AppText.get(R.string.harness_failed) }
        refresh()
        checkLocalized(HarnessStore.state.value.installed) { AppText.get(R.string.harness_failed) }
    }
    fun uninstall() {
        setDesired(false)
        for (name in listOf("program","program.new","program.old","npm-cache","node.tar.xz","installed.json")) DurableFiles.delete(File(directory,name))
        refresh()
    }
}

/** The foreground runtime service owns this process tree; closing its page keeps it running. */
class HarnessEngine(private val context: Context,private val scope: CoroutineScope) {
    private val repository = HarnessRepository(context)
    private val client = OkHttpClient.Builder().connectTimeout(1,TimeUnit.SECONDS).readTimeout(2,TimeUnit.SECONDS)
        .callTimeout(3,TimeUnit.SECONDS).followRedirects(false).build()
    private var process: Process? = null
    private var output: Job? = null
    private var monitor: Job? = null
    suspend fun start() = withContext(Dispatchers.IO) {
        if (process?.isAlive == true && HarnessStore.state.value.ready) return@withContext
        if (process != null) stop()
        repository.refresh(); checkLocalized(HarnessStore.state.value.installed) { AppText.get(R.string.harness_failed) }
        repository.prepareAssets()
        checkLocalized(runCatching { ServerSocket(3080,1,InetAddress.getByName("127.0.0.1")).use { true } }.getOrDefault(false)) { AppText.get(R.string.harness_port_busy) }
        val host = if (repository.lan()) LanAccess.addresses(context).firstOrNull()
            ?: throw IllegalStateException(AppText.get(R.string.harness_no_lan)) else "127.0.0.1"
        val owned = BootstrapRuntime(context).processBuilder("/bin/sh","/manager/extensions/harness/scripts/start.sh", host).start()
        process = owned
        HarnessStore.state.update { it.copy(active = true,ready = false,error = null, host = host) }
        HarnessStore.entryUrl = null
        output = scope.launch(Dispatchers.IO) { runCatching { owned.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val launch = Regex("dsh web: (http://${Regex.escape(host)}:3080/\\?token=[A-Za-z0-9_-]+)").find(line)?.groupValues?.get(1)
                if (launch != null && process === owned) HarnessStore.entryUrl = launch
                RuntimeStore.log(context,LogCategory.HARNESS).append(line.replace(Regex("([?&]token=)[^\\s&#]+"),"$1[redacted]"))
            }
        } } }
        monitor = scope.launch(Dispatchers.IO) {
            while (isActive && process === owned && owned.isAlive) {
                val entry = HarnessStore.entryUrl
                val ready = entry != null && runCatching { client.newCall(Request.Builder().url(entry).build()).execute().use {
                    it.isSuccessful || it.code in setOf(302,303) && it.header("Location")?.let { location ->
                        it.request.url.resolve(location)?.let { next -> next.host == host && next.port == 3080 && next.scheme == "http" }
                    } == true
                } }.getOrDefault(false)
                if (process === owned) HarnessStore.state.update { it.copy(ready = ready) }
                delay(2000)
            }
            if (process === owned) {
                HarnessStore.entryUrl = null
                HarnessStore.state.update { it.copy(active = false,ready = false,error = AppText.get(R.string.harness_failed)) }
            }
        }
        try {
            withTimeout(90_000) { while (!HarnessStore.state.value.ready) {
                checkLocalized(owned.isAlive) { AppText.get(R.string.harness_failed) }; delay(250)
            } }
        } catch (failure: Throwable) { stop(); throw failure }
    }
    suspend fun stop() = withContext(NonCancellable + Dispatchers.IO) {
        val owned = process; process = null
        HarnessStore.entryUrl = null
        monitor?.cancelAndJoin(); monitor = null
        if (owned != null) {
            owned.destroy()
            if (!owned.waitFor(8,TimeUnit.SECONDS)) { owned.destroyForcibly(); owned.waitFor(3,TimeUnit.SECONDS) }
            runCatching { owned.inputStream.close() }
        }
        output?.cancelAndJoin(); output = null
        HarnessStore.state.update { it.copy(active = false,ready = false) }
    }
    suspend fun close() { stop(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    companion object { const val WEB_UI_URL = "http://127.0.0.1:3080" }
}
