package io.astrbot.android.runtime

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.runBlocking
import io.astrbot.android.AppText
import io.astrbot.android.R
import io.astrbot.android.LocalizedStateException

/** Startup evidence stays outside the user-data tree and is bound to the actual launch. */
internal class AstrBotStartup(private val context: Context) {
    @Volatile private var nonce: String? = null
    private var consumedRestart: String? = null
    private val directory = File(context.filesDir, "manager")
    fun prepare(launch: String) {
        nonce = null
        consumedRestart = null
        directory.mkdirs()
        for (name in listOf("log_limits.py", "managed_restart.py", "astrbot_instance.py", "launch_astrbot.py")) {
            val script = AtomicFile(File(directory, name))
            val output = script.startWrite()
            try {
                context.assets.open("runtime/$name").use { it.copyTo(output) }
                script.finishWrite(output)
            } catch (failure: Throwable) { script.failWrite(output); throw failure }
        }
        nonce = launch
    }
    /** Runs on the supervisor's IO launcher, before the new service process exists. */
    fun checkInstance(runtime: BootstrapRuntime) {
        val result = runBlocking {
            runtime.execute("/opt/astrbot-venv/bin/python", "/manager/astrbot_instance.py", timeoutSeconds = 40)
        }
        val status = runCatching { JSONObject(result.output.trim()).getString("status") }.getOrDefault("inspection-failed")
        if (result.exitCode == 0 && status in setOf("free", "recovered")) {
            if (status == "recovered") RuntimeStore.log(context, io.astrbot.android.core.ServiceId.ASTRBOT)
                .append(AppText.get(R.string.astrbot_instance_recovered))
            return
        }
        throw LocalizedStateException(AppText.get(when (status) {
            "healthy" -> R.string.astrbot_instance_healthy
            "starting" -> R.string.astrbot_instance_starting
            "unknown-port" -> R.string.astrbot_instance_unknown_port
            "cleanup-failed" -> R.string.astrbot_instance_cleanup_failed
            "configuration" -> R.string.ui_webui_address_or_configuration_is_unrecognized_the_original_file
            else -> R.string.astrbot_instance_busy
        }))
    }
    private fun record(): JSONObject? = runCatching {
        val expected = nonce ?: return null
        val file = File(directory, "astrbot-startup.json")
        require(file.isFile && file.length() <= 4096)
        val record = JSONObject(file.readText())
        require(record.getInt("schema") == 1 && record.getString("nonce") == expected)
        record
    }.getOrNull()
    fun migration(): String = record()?.optString("migration")
        ?.takeIf { it in setOf("pending", "passed", "failed", "unsupported") } ?: "unknown"
    fun launchId(): String? = record()?.optString("launchId")?.takeIf { it.matches(Regex("[0-9a-f]{32}")) }
    fun consumeRestartRequest(exitCode: Int): Boolean {
        if (exitCode != 75) return false
        val value = record() ?: return false
        val launch = value.optString("launchId").takeIf { it.matches(Regex("[0-9a-f]{32}")) } ?: return false
        if (value.optString("migration") != "restart-requested" || value.optString("restartPolicy") != "managed" || consumedRestart == launch) return false
        consumedRestart = launch
        return true
    }
}
