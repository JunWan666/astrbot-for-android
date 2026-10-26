package io.astrbot.android.runtime

import io.astrbot.android.requireLocalized

import io.astrbot.android.AppText
import io.astrbot.android.R

import android.content.Context
import io.astrbot.android.BuildConfig
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class RuntimeVersions(val components: Map<String, String>, val compatibilityKey: String, val environmentKey: String = "") {
    fun json() = JSONObject(components).put("compatibilityKey", compatibilityKey)
}

object InstalledVersions {
    fun read(context: Context): RuntimeVersions = read(File(context.filesDir, "runtime-data/ubuntu"))

    fun read(root: File): RuntimeVersions {
        fun text(path: String, limit: Long = 32 * 1024 * 1024): String {
            val file = File(root, path)
            requireLocalized(file.isFile && file.length() <= limit) { AppText.get(R.string.ui_runtime_version_record_is_missing_complete_initialization_first) }
            return file.readText()
        }
        val bootstrap = JSONObject(text(".astrbot-bootstrap.json"))
        val astrbotSource = text("opt/astrbot/astrbot/__init__.py")
        val napcatSource = text("opt/qq/resources/app/app_launcher/napcat/napcat.mjs")
        val compatibleNapCatSource = NapCatCompatibility.originalBackend(napcatSource)
        val qqPackage = text("opt/qq/resources/app/package.json")
        val python = text("opt/astrbot-venv/pyvenv.cfg")
        val sourceMarker = text("opt/astrbot/.astrbot-source.json")
        val dependencies = text("var/lib/astrbot-manager/astrbot-python.lock.txt")
        val components = linkedMapOf(
            "App" to BuildConfig.VERSION_NAME,
            "运行环境" to bootstrap.getString("id"),
            "架构" to bootstrap.getString("abi"),
            "Python" to (Regex("(?m)^version\\s*=\\s*(.+)$").find(python)?.groupValues?.get(1)?.trim() ?: AppText.get(R.string.ui_unknown)),
            "AstrBot" to (Regex("__version__\\s*=\\s*[\"']([^\"']+)").find(astrbotSource)?.groupValues?.get(1) ?: AppText.get(R.string.ui_unknown)),
            "NapCat" to (NapCatVersion.read(File(root, "opt/qq/resources/app/app_launcher/napcat"), napcatSource) ?: AppText.get(R.string.ui_unknown_unsupported_by_this_build)),
            "Linux QQ" to JSONObject(qqPackage).getString("version"),
        )
        fun digest(parts: List<String>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            parts.forEach { digest.update(it.toByteArray(Charsets.UTF_8)); digest.update(0.toByte()) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        // Bind backups to the installed combination, independently of App patch releases.
        return RuntimeVersions(components,
            digest(listOf(bootstrap.getString("sha256"), astrbotSource, compatibleNapCatSource, qqPackage, python, sourceMarker, dependencies)),
            // The two interpreted programs update independently; neither is a dependency of this key.
            digest(listOf(bootstrap.getString("sha256"), qqPackage, python, dependencies)))
    }
}
