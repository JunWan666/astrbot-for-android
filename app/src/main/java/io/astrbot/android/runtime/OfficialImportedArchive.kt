package io.astrbot.android.runtime

import io.astrbot.android.AppText
import io.astrbot.android.R
import io.astrbot.android.requireLocalized
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Offline imports use the selected bytes; no network metadata or upstream comparison is required. */
internal object OfficialImportedArchive {
    data class Identity(val kind: String, val version: String, val sourcePrefix: String = "", val embeddedDashboard: Boolean = false)
    data class Selection(val source: File, val dashboard: File?, val identity: Identity)

    fun inspect(file: File): Identity = ZipFile(file).use { zip ->
        val entries = checkedEntries(zip)
        val dashboard = entries.singleOrNull { it.name == "dist/assets/version" && !it.isDirectory }
        val source = entries.singleOrNull { it.name == "astrbot/__init__.py" ||
            it.name.split('/').size == 3 && it.name.endsWith("/astrbot/__init__.py") }
        requireLocalized((dashboard == null) != (source == null)) { AppText.get(R.string.upstream_import_format) }
        val entry = requireNotNull(dashboard ?: source)
        val text = readText(zip, entry)
        val version = if (dashboard != null) text.trim().removePrefix("v").removePrefix("V")
            else Regex("(?m)^__version__\\s*=\\s*[\"']([^\"']+)[\"']").find(text)?.groupValues?.get(1).orEmpty()
        requireLocalized(ComponentVersion.supported(version)) { AppText.get(R.string.upstream_import_format) }
        val prefix = source?.name?.removeSuffix("astrbot/__init__.py").orEmpty()
        val embedded = if (source != null) entries.singleOrNull { it.name == "${prefix}astrbot/dashboard/dist/assets/version" } else null
        requireLocalized(if (source != null) entries.any { it.name == "${prefix}main.py" && !it.isDirectory }
            else entries.any { it.name == "dist/index.html" && !it.isDirectory }) { AppText.get(R.string.upstream_import_format) }
        val embeddedMatches = embedded != null && entries.any { it.name == "${prefix}astrbot/dashboard/dist/index.html" && !it.isDirectory } &&
            readText(zip, embedded).trim().removePrefix("v").removePrefix("V") == version
        Identity(if (dashboard != null) "dashboard" else "source", version, prefix, embeddedMatches)
    }

    private fun readText(zip: ZipFile, entry: ZipEntry): String {
        requireLocalized(entry.size in 1..65536) { AppText.get(R.string.upstream_import_format) }
        val bytes = zip.getInputStream(entry).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val n = input.read(buffer); if (n < 0) break
                requireLocalized(output.size() + n <= 65536) { AppText.get(R.string.upstream_import_format) }
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
        requireLocalized(bytes.size.toLong() == entry.size) { AppText.get(R.string.upstream_import_format) }
        return bytes.toString(Charsets.UTF_8)
    }

    fun select(imports: List<File>): Selection {
        requireLocalized(imports.size in 1..2) { AppText.get(R.string.upstream_import_pair) }
        val selected = imports.map { it to inspect(it) }
        requireLocalized(selected.map { it.second.kind }.distinct().size == selected.size) { AppText.get(R.string.upstream_import_duplicate) }
        requireLocalized(selected.map { it.second.version }.distinct().size == 1) {
            AppText.get(R.string.upstream_import_version_conflict, selected.first().second.version, selected.last().second.version)
        }
        val source = selected.singleOrNull { it.second.kind == "source" }
        requireLocalized(source != null) { AppText.get(R.string.upstream_import_source_missing) }
        val dashboard = selected.singleOrNull { it.second.kind == "dashboard" }?.first
        requireLocalized(dashboard != null || source!!.second.embeddedDashboard) { AppText.get(R.string.upstream_import_dashboard_missing) }
        return Selection(source!!.first, dashboard, source.second)
    }

    /** Hashes only bind the staged local files to the later installation; they do not claim official provenance. */
    suspend fun describe(selection: Selection): JSONObject {
        suspend fun entry(name: String, file: File): JSONObject {
            requireLocalized(file.length() in 1..OfficialReleaseRepository.MAX_PACKAGE) { AppText.get(R.string.upstream_import_format) }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val size = input.read(buffer); if (size < 0) break
                    digest.update(buffer, 0, size)
                }
            }
            return JSONObject().put("name", name).put("bytes", file.length())
                .put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
        }
        val files = JSONArray().put(entry("source.zip", selection.source))
        selection.dashboard?.let { files.put(entry("dashboard.zip", it)) }
        return JSONObject().put("schema", 1).put("origin", "local-import").put("component", "astrbot")
            .put("version", selection.identity.version).put("files", files)
            .put("notes", AppText.get(R.string.upstream_import_notes)).put("channel", AppText.get(R.string.upstream_import_channel))
    }

    fun validate(value: JSONObject) {
        requireLocalized(value.getInt("schema") == 1 && value.getString("origin") == "local-import" &&
            value.getString("component") == "astrbot" && ComponentVersion.supported(value.getString("version"))) {
            AppText.get(R.string.upstream_import_format)
        }
        require(value.getString("notes").length <= 8000 && value.getString("channel").length <= 128)
        val files = value.getJSONArray("files")
        require(files.length() in 1..2)
        for (index in 0 until files.length()) {
            val entry = files.getJSONObject(index)
            require(entry.getString("name") == if (index == 0) "source.zip" else "dashboard.zip")
            require(entry.getLong("bytes") in 1..OfficialReleaseRepository.MAX_PACKAGE && entry.getString("sha256").matches(Regex("[a-f0-9]{64}")))
        }
    }

    private const val MAX_UNPACKED = 512L * 1024 * 1024
    private fun checkedEntries(zip: ZipFile): List<ZipEntry> {
        val entries = zip.entries().asSequence().take(100_001).toList()
        requireLocalized(entries.size in 1..100_000) { AppText.get(R.string.upstream_import_format) }
        val names = hashSetOf<String>()
        var total = 0L
        entries.forEach { entry ->
            val name = entry.name.removeSuffix("/")
            requireLocalized(name.isNotEmpty() && !name.startsWith('/') && !name.contains('\\') &&
                !name.contains('\u0000') && name.split('/').none { it in setOf("", ".", "..") } &&
                names.add(name) && entry.size in 0..MAX_UNPACKED) { AppText.get(R.string.upstream_import_format) }
            total += entry.size
            requireLocalized(total <= MAX_UNPACKED) { AppText.get(R.string.upstream_import_format) }
        }
        return entries
    }
}
