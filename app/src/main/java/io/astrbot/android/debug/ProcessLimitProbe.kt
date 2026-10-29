package io.astrbot.android.debug

import android.os.Build
import org.json.JSONObject

/** The same fixed operations are used by the UserService and the compatibility transport. */
internal class ProcessLimitProbe(private val uid: Int, private val appUid: Int = uid, private val command: (Array<out String>) -> String) {
    fun inspect(): JSONObject {
        check(Build.VERSION.SDK_INT >= 31) { "Android 12 or later is required" }
        val report = JSONObject().put("uid", uid).put("sdk", Build.VERSION.SDK_INT)
        val configured = runCatching { command(arrayOf("/system/bin/device_config", "get", "activity_manager", "max_phantom_processes")) }
        report.put("configured", configured.getOrNull() ?: JSONObject.NULL).put("configuredReadable", configured.isSuccess)
        val errors = mutableListOf<String>()
        configured.exceptionOrNull()?.let { errors += "device_config: ${it.message}" }
        val settings = runCatching { command(arrayOf("/system/bin/dumpsys", "activity", "settings")) }
        val processes = runCatching { command(arrayOf("/system/bin/dumpsys", "activity", "processes")) }
        val sources = listOf("settings" to settings, "processes" to processes)
        var effective: Int? = null
        for ((section, result) in sources) {
            result.exceptionOrNull()?.let { errors += "$section: ${it.message}" }
            if (effective == null) {
                effective = result.getOrNull()?.let { effectiveLimit.find(it)?.groupValues?.get(1)?.toIntOrNull() }
                if (effective != null) report.put("effectiveSource", "dumpsys activity $section")
            }
        }
        if (effective == null) errors += "Effective limit not exposed"
        report.put("children", PhantomProcessCensus.parse(processes.getOrNull(), appUid))
        report.put("effective", effective ?: JSONObject.NULL).put("diagnostic", errors.joinToString("; ").take(600))
        val monitor = runCatching { command(arrayOf("/system/bin/settings", "get", "global", "settings_enable_monitor_phantom_procs")) }.getOrNull()
        return report.put("monitorSetting", monitor ?: JSONObject.NULL)
    }

    fun setLimit(restoreDefault: Boolean): JSONObject {
        check(Build.VERSION.SDK_INT >= 31) { "Android 12 or later is required" }
        val write = runCatching {
            command(if (restoreDefault) arrayOf("/system/bin/device_config", "delete", "activity_manager", "max_phantom_processes")
                else arrayOf("/system/bin/device_config", "put", "activity_manager", "max_phantom_processes", "256"))
        }
        var result = inspect()
        if (!restoreDefault && write.isSuccess && !result.isNull("effective")) {
            repeat(5) {
                if (result.optInt("effective", -1) == 256) return applied(result, write, false)
                Thread.sleep(200)
                result = inspect()
            }
        }
        return applied(result, write, restoreDefault)
    }

    private fun applied(result: JSONObject, write: Result<String>, restored: Boolean): JSONObject = result
        .put("writeSucceeded", write.isSuccess).put("restoreRequested", restored)
        .put("writeError", write.exceptionOrNull()?.message ?: JSONObject.NULL)
        .put("configurationConfirmed", write.isSuccess && result.optBoolean("configuredReadable") &&
            result.optString("configured") == if (restored) "null" else "256")
        .put("effectiveConfirmed", !restored && write.isSuccess && result.optInt("effective", -1) == 256)

    companion object {
        private val effectiveLimit = Regex("(?i)\\b(?:mMaxPhantomProcesses|max_phantom_processes)\\s*[:=]\\s*(\\d+)")
    }
}
