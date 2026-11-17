package io.astrbot.android.runtime

import java.io.File

/** Resolve the same explicit/bundled and persistent locations used by our AstrBot launcher. */
internal object AstrBotDashboard {
    data class Assets(val directory: File, val source: String, val version: String?, val readable: Boolean)

    fun dataDirectory(root: File): File? = root.takeIf { it.name == "ubuntu" && it.parentFile?.name == "runtime-data" }
        ?.parentFile?.parentFile?.let { File(it, "persistent/astrbot/data") }

    fun candidates(root: File, data: File? = dataDirectory(root)): List<Pair<String, File>> = listOfNotNull(
        "bundled" to File(root, "opt/astrbot/astrbot/dashboard/dist"),
        data?.let { "data" to File(it, "dist") })

    fun inspect(root: File, data: File? = dataDirectory(root)): Assets? {
        val candidates = candidates(root, data)
        // The launcher explicitly supplies the bundled directory. Upstream only falls
        // back to data/dist when that directory is absent. Do not hide a broken explicit dist.
        val candidate = candidates.firstOrNull { it.second.exists() } ?: return null
        return inspectDirectory(candidate.second, candidate.first)
    }

    fun inspectDirectory(directory: File, source: String): Assets {
        val version = runCatching {
            File(directory, "assets/version").takeIf { it.isFile && it.length() <= 1024 }?.readText()?.trim()
                ?.removePrefix("v")?.removePrefix("V")?.takeIf { VERSION.matches(it) }
        }.getOrNull()
        val index = File(directory, "index.html")
        val readable = runCatching {
            index.isFile && index.canRead() && index.length() in 1..MAX_INDEX &&
                RESOURCE.findAll(index.readText()).all { match ->
                    val address = match.groupValues[1].substringBefore('?').substringBefore('#')
                    if (address.isEmpty() || address.startsWith("//") || Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(address)) true
                    else {
                        val file = File(directory, address.removePrefix("/"))
                        file.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath()) && file.isFile && file.canRead()
                    }
                }
        }.getOrDefault(false)
        return Assets(directory, source, version, readable)
    }

    private const val MAX_INDEX = 4L * 1024 * 1024
    private val VERSION = Regex("[0-9]+\\.[0-9]+\\.[0-9]+[a-zA-Z0-9.+_-]{0,48}")
    private val RESOURCE = Regex("<(?:script|link)\\b[^>]*?\\b(?:src|href)=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
}
