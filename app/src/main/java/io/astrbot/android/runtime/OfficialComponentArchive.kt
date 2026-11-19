package io.astrbot.android.runtime

import android.content.Context
import android.system.Os
import io.astrbot.android.AppText
import io.astrbot.android.R
import io.astrbot.android.core.ArchivePaths
import io.astrbot.android.requireLocalized
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/** Stages upstream files without executing their installers or touching the installed programs. */
internal object OfficialComponentArchive {
    internal suspend fun extractDashboard(archive: File, entry: JSONObject, destination: File) {
        verify(archive, entry)
        check(!destination.exists())
        ZipFile(archive).use { unpack(it, destination) }
    }

    suspend fun extract(context: Context, release: JSONObject, archive: File, dashboard: File, destination: File): JSONObject {
        val component = release.getString("component")
        OfficialReleaseRepository.validate(release, component)
        val files = release.getJSONArray("files")
        verify(archive, files.getJSONObject(0))
        val version = release.getString("version")
        verify(dashboard, files.getJSONObject(1))
        check(destination.mkdirs())
        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().take(100_001).toList()
            require(entries.size in 1..100_000)
            val names = entries.map { it.name.substringBefore('/') }.toSet()
            require(names.size == 1 && names.single().isNotBlank())
            unpack(zip, destination, names.single() + "/")
        }
        val dist = File(destination, "astrbot/dashboard")
        DurableFiles.delete(File(dist, "dist"))
        ZipFile(dashboard).use { unpack(it, dist) }
        val source = JSONObject().put("version", version).put("commit", release.getString("commit"))
            .put("source", files.getJSONObject(0)).put("dashboard", files.getJSONObject(1))
        return finish(destination, source, OfficialReleaseRepository.channel(release), release.getString("notes"))
    }

    suspend fun extractLocal(release: JSONObject, archive: File, dashboard: File?, destination: File): JSONObject {
        OfficialImportedArchive.validate(release)
        val files = release.getJSONArray("files")
        require((dashboard != null) == (files.length() == 2))
        verify(archive, files.getJSONObject(0), R.string.upstream_import_changed)
        dashboard?.let { verify(it, files.getJSONObject(1), R.string.upstream_import_changed) }
        val selected = OfficialImportedArchive.select(listOfNotNull(archive, dashboard))
        requireLocalized(selected.identity.version == release.getString("version")) { AppText.get(R.string.upstream_import_pair) }
        check(destination.mkdirs())
        ZipFile(archive).use { unpack(it, destination, selected.identity.sourcePrefix) }
        if (dashboard != null) {
            val dist = File(destination, "astrbot/dashboard")
            DurableFiles.delete(File(dist, "dist"))
            ZipFile(dashboard).use { unpack(it, dist) }
        }
        val source = JSONObject().put("version", selected.identity.version).put("origin", "local-import")
            .put("source", files.getJSONObject(0)).put("dashboard", files.optJSONObject(1) ?: JSONObject().put("embedded", true))
        return finish(destination, source, release.getString("channel"), release.getString("notes"))
    }

    private fun finish(destination: File, source: JSONObject, channel: String, notes: String): JSONObject {
        DurableFiles.write(File(destination, ".astrbot-source.json"), source)
        return JSONObject().put("version", source.getString("version")).put("channel", channel)
            .put("notes", notes).put("unpackedBytes", destination.walkTopDown().filter { it.isFile }.sumOf { it.length() })
    }

    internal suspend fun verify(file: File, entry: JSONObject, failureMessage: Int = R.string.upstream_download_failed) {
        requireLocalized(file.isFile && file.length() == entry.getLong("bytes") && entry.getLong("bytes") in 1..OfficialReleaseRepository.MAX_PACKAGE) { AppText.get(failureMessage) }
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer); if (n < 0) break
                hash.update(buffer, 0, n)
            }
        }
        requireLocalized(hash.digest().joinToString("") { "%02x".format(it) } == entry.getString("sha256")) { AppText.get(failureMessage) }
    }

    private suspend fun unpack(zip: ZipFile, destination: File, prefix: String = "") {
        destination.mkdirs()
        val names = HashSet<String>()
        var total = 0L
        val entries = zip.entries().asSequence().take(100_001).toList()
        require(entries.size in 1..100_000)
        for (entry in entries) {
            currentCoroutineContext().ensureActive()
            require(entry.name.startsWith(prefix))
            val name = entry.name.removePrefix(prefix)
            if (name.isEmpty() && entry.isDirectory) continue
            val path = ArchivePaths.entry(destination.toPath(), name)
            require(path != destination.toPath() && names.add(path.toString()) && !name.split('/').contains(".."))
            if (entry.isDirectory) check(path.toFile().isDirectory || path.toFile().mkdirs()) else {
                require(entry.size in 0..512L * 1024 * 1024)
                require(destination.usableSpace >= entry.size + 64L * 1024 * 1024)
                path.parent.toFile().mkdirs()
                var count = 0L
                FileOutputStream(path.toFile()).use { output ->
                    zip.getInputStream(entry).use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer); if (n < 0) break
                            count += n; total += n
                            require(count <= entry.size && total <= 512L * 1024 * 1024)
                            output.write(buffer, 0, n)
                        }
                    }
                    output.fd.sync()
                }
                require(count == entry.size)
                Os.chmod(path.toString(), 420)
            }
        }
    }
}
