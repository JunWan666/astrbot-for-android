package io.astrbot.android.debug

import org.json.JSONObject

/** Fixed device-idle operations for this application only. Does not disable system-wide idle. */
internal class BackgroundExecutionProbe(private val packageName: String, private val command: (Array<out String>) -> String) {
    init { require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+"))) }

    fun inspect(): JSONObject {
        val result = runCatching { command(arrayOf("/system/bin/dumpsys", "deviceidle", "whitelist")) }
        val rows = result.getOrNull()?.lineSequence()?.map { it.trim().split(',').map(String::trim) }?.toList()
        // An unfamiliar response must not be reported as a confirmed absence.
        val readable = rows != null && rows.any { it.size == 3 && it[0] in setOf("system", "system-excidle", "user") && it[2].toIntOrNull() != null }
        fun contains(kind: String): Any = if (!readable) JSONObject.NULL else rows!!.any {
            it.size == 3 && it[0] == kind && it[1] == packageName && it[2].toIntOrNull() != null
        }
        return JSONObject().put("package", packageName).put("whitelistReadable", readable)
            .put("userWhitelisted", contains("user")).put("systemWhitelisted", contains("system"))
            .put("diagnostic", result.exceptionOrNull()?.message?.take(240) ?: if (readable) "" else "Whitelist format not exposed")
    }

    fun setExemption(enabled: Boolean): JSONObject {
        val write = runCatching {
            command(arrayOf("/system/bin/dumpsys", "deviceidle", "whitelist", (if (enabled) "+" else "-") + packageName))
        }
        return inspect().put("writeSucceeded", write.isSuccess).put("requestedExemption", enabled)
            .put("writeError", write.exceptionOrNull()?.message?.take(240) ?: JSONObject.NULL).let { report ->
                report.put("configurationConfirmed", write.isSuccess && !report.isNull("userWhitelisted") && report.getBoolean("userWhitelisted") == enabled)
            }
    }
}
