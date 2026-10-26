package io.astrbot.android.runtime

import io.astrbot.android.BuildConfig
import org.json.JSONObject
import java.io.File

data class VersionInspection(val components: Map<String, String>, val notice: String, val checkedAtMillis: Long)

/** Display-only inspection survives partial upstream replacements. Maintenance keys remain strict. */
object InstalledVersionInspection {
    const val MATCHED_NOTICE = "组件版本匹配"
    internal val paths = listOf(".astrbot-bootstrap.json", "opt/astrbot/astrbot/__init__.py",
        "opt/astrbot/astrbot/dashboard/dist/assets/version", "opt/astrbot/.astrbot-source.json",
        "opt/qq/resources/app/app_launcher/napcat/napcat.mjs", "opt/qq/resources/app/package.json",
        "opt/astrbot-venv/pyvenv.cfg", "var/lib/astrbot-manager/astrbot-python.lock.txt",
        "opt/qq/resources/app/app_launcher/napcat/.astrbot-napcat.json")

    fun read(root: File): VersionInspection {
        fun text(path: String, max: Long = 32L * 1024 * 1024): String? = runCatching {
            val file = File(root, path)
            require(file.isFile && file.length() <= max)
            file.readText()
        }.getOrNull()
        fun json(path: String) = runCatching { JSONObject(requireNotNull(text(path, 64 * 1024))) }.getOrNull()
        fun version(value: String?) = value?.trim()?.takeIf { it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+[a-zA-Z0-9.+_-]{0,48}")) }
        val bootstrap = json(paths[0])
        val astrbot = version(Regex("__version__\\s*=\\s*[\"']([^\"']+)").find(text(paths[1]).orEmpty())?.groupValues?.get(1))
        val dashboard = version(text(paths[2], 1024)?.trim()?.removePrefix("v")?.removePrefix("V"))
        val source = json(paths[3])
        val napcat = version(NapCatVersion.read(File(root, "opt/qq/resources/app/app_launcher/napcat"), text(paths[4]).orEmpty()))
        val qq = version(json(paths[5])?.optString("version"))
        val python = version(Regex("(?m)^version\\s*=\\s*(.+)$").find(text(paths[6], 64 * 1024).orEmpty())?.groupValues?.get(1))
        val components = linkedMapOf("App" to BuildConfig.VERSION_NAME,
            "运行环境" to (bootstrap?.optString("id")?.takeIf { it.matches(Regex("[a-zA-Z0-9._-]{1,160}")) } ?: "未知"),
            "架构" to (bootstrap?.optString("abi")?.takeIf { it in setOf("arm64-v8a", "x86_64") } ?: "未知"),
            "Python" to (python ?: "未知"), "AstrBot" to (astrbot ?: "未知"), "AstrBot WebUI" to (dashboard ?: "未知"),
            "NapCat" to (napcat ?: "未知（当前代码未适配）"), "Linux QQ" to (qq ?: "未知"))
        val declared = bootstrap?.optJSONObject("components")
        val matches = declared != null && astrbot != null && dashboard == astrbot && napcat != null && qq != null &&
            python == "3.12.3" && source?.optString("version") == astrbot &&
            (declared.optJSONObject("astrbot")?.optString("version") == astrbot || source?.optString("version") == astrbot) &&
            (declared.optJSONObject("napcat")?.optString("version") == napcat || json(paths[8])?.optString("version") == napcat) &&
            declared.optJSONObject("qq")?.optString("version") == qq
        val notice = when {
            listOf(astrbot, dashboard, napcat, qq, python).any { it == null } -> "版本组合待验证：部分组件版本无法识别，请查看日志或导出诊断包；现有数据已保留"
            dashboard != astrbot -> "版本组合待验证：AstrBot 与 WebUI 版本不同，请在维护页使用匹配的更新包"
            !matches -> "版本组合待验证：检测到独立更新或版本声明不同，请通过维护页使用匹配的运行包"
            else -> MATCHED_NOTICE
        }
        return VersionInspection(components, notice, System.currentTimeMillis())
    }
}

/** Cheap metadata checks every poll; periodically reread even if timestamps were preserved. */
internal class VersionInspectionWatcher(private val files: File) {
    private var signature: List<String>? = null
    private var lastRead = Long.MIN_VALUE
    fun readIfChanged(monotonicMillis: Long): VersionInspection? {
        val root = File(files, "runtime-data/ubuntu")
        val tracked = InstalledVersionInspection.paths.map { File(root, it) } + File(files, "manager/astrbot-startup.json")
        val current = tracked.map { "${it.exists()}:${it.length()}:${it.lastModified()}" }
        if (current == signature && lastRead != Long.MIN_VALUE && monotonicMillis - lastRead < 60_000) return null
        val result = InstalledVersionInspection.read(root)
        signature = current
        lastRead = monotonicMillis
        return result
    }
}
