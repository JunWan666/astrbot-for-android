package io.astrbot.android.runtime

import io.astrbot.android.LocalizedFailure

import io.astrbot.android.AppText
import io.astrbot.android.R

import android.content.Context
import io.astrbot.android.BuildConfig
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class RuntimeVersions(val components: Map<String, String>, val compatibilityKey: String, val environmentKey: String = "",
    val identityDigests: Map<String, String> = emptyMap()) {
    fun json() = JSONObject(components).put("compatibilityKey", compatibilityKey)
}

internal class VersionRecordFailure(val recordPath: String, val issue: String, cause: Throwable? = null) :
    IllegalStateException(AppText.get(R.string.problem_version_record_unreadable, recordPath), cause), LocalizedFailure

object InstalledVersions {
    fun read(context: Context): RuntimeVersions = read(File(context.filesDir, "runtime-data/ubuntu"))

    fun read(root: File): RuntimeVersions {
        fun text(path: String, limit: Long = 32 * 1024 * 1024): String {
            val file = File(root, path)
            if (!file.isFile) throw VersionRecordFailure(path, "missing")
            if (file.length() > limit) throw VersionRecordFailure(path, "too-large")
            return try { file.readText() } catch (failure: java.io.IOException) { throw VersionRecordFailure(path, "unreadable", failure) }
        }
        val bootstrap = JSONObject(text(".astrbot-bootstrap.json"))
        val astrbotSource = text("opt/astrbot/astrbot/__init__.py")
        val python = text("opt/astrbot-venv/pyvenv.cfg")
        val sourceMarker = text("opt/astrbot/.astrbot-source.json")
        val dependencies = text("var/lib/astrbot-manager/astrbot-python.lock.txt")
        val components = linkedMapOf(
            "App" to BuildConfig.VERSION_NAME,
            "运行环境" to bootstrap.getString("id"),
            "架构" to bootstrap.getString("abi"),
            "Python" to (Regex("(?m)^version\\s*=\\s*(.+)$").find(python)?.groupValues?.get(1)?.trim() ?: AppText.get(R.string.ui_unknown)),
            "AstrBot" to (Regex("__version__\\s*=\\s*[\"']([^\"']+)").find(astrbotSource)?.groupValues?.get(1) ?: AppText.get(R.string.ui_unknown)),
        )
        fun digest(parts: List<String>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            parts.forEach { digest.update(it.toByteArray(Charsets.UTF_8)); digest.update(0.toByte()) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        // Keep the exact key unchanged; per-record digests explain a rejected repair without exporting source contents.
        val records = linkedMapOf(
            ".astrbot-bootstrap.json" to bootstrap.getString("sha256"),
            "opt/astrbot/astrbot/__init__.py" to astrbotSource,
            "opt/astrbot-venv/pyvenv.cfg" to python,
            "opt/astrbot/.astrbot-source.json" to sourceMarker,
            "var/lib/astrbot-manager/astrbot-python.lock.txt" to dependencies)
        // Bind backups to the installed combination, independently of App patch releases.
        return RuntimeVersions(components,
            digest(records.values.toList()),
            // The two interpreted programs update independently; neither is a dependency of this key.
            digest(listOf(bootstrap.getString("sha256"), python, dependencies)),
            records.mapValues { digest(listOf(it.value)) })
    }
}
