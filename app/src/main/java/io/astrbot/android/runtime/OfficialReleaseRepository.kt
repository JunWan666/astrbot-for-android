package io.astrbot.android.runtime

import android.content.Context
import io.astrbot.android.AppText
import io.astrbot.android.R
import io.astrbot.android.LocalizedArgumentException
import io.astrbot.android.LocalizedFailure
import io.astrbot.android.requireLocalized
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Official HTTPS metadata is the trust source; this is independent of our signed maintenance channel. */
internal class OfficialReleaseRepository(private val context: Context, private val component: String,
                                         private val providedClient: OkHttpClient? = null) {
    private val repo = repository(component)
    private val label = "AstrBot"
    private val cache = File(context.filesDir, "manifests/github-$component.json")
    private val work = File(context.filesDir, "staging/github-$component")
    private val client by lazy { providedClient ?: OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(15, TimeUnit.MINUTES).followRedirects(false).build() }

    fun cached(onlyNewer: Boolean): OnlineUpdateInfo? = runCatching {
        val release = DurableFiles.read(cache) ?: return null
        validate(release, component)
        val installed = InstalledVersions.read(context).components.getValue(label)
        info(release).takeIf { compareVersions(it.version, installed) >= if (onlyNewer) 1 else 0 }
    }.getOrNull()

    suspend fun fetch(): JSONObject = withContext(Dispatchers.IO) {
        try { fetchRelease() }
        catch (failure: CancellationException) { throw failure }
        catch (failure: java.io.IOException) { throw failure }
        catch (failure: Exception) {
            if (failure is LocalizedFailure) throw failure
            throw LocalizedArgumentException(AppText.get(R.string.upstream_invalid))
        }
    }

    private suspend fun fetchRelease(requestedVersion: String? = null): JSONObject {
        require(requestedVersion == null || requestedVersion.matches(VERSION))
        val release = requestedVersion?.let { json("https://api.github.com/repos/$repo/releases/tags/v$it") }
            ?: latestStableRelease()
        requireLocalized(!release.getBoolean("draft") && !release.getBoolean("prerelease")) { AppText.get(R.string.upstream_invalid) }
        val tag = release.getString("tag_name")
        val version = tag.removePrefix("v")
        requireLocalized(version.matches(VERSION)) { AppText.get(R.string.upstream_invalid) }
        require(requestedVersion == null || version == requestedVersion)
        val assets = release.getJSONArray("assets")
        fun asset(name: String): JSONObject {
            val selected = (0 until assets.length()).map(assets::getJSONObject).single { it.getString("name") == name }
            return JSONObject().put("name", name).put("url", selected.getString("browser_download_url"))
                .put("bytes", selected.getLong("size")).put("sha256", selected.getString("digest").removePrefix("sha256:"))
        }
        val files = JSONArray()
        var commit = ""
        if (component == "astrbot") {
            // Resolve the tag to an immutable commit, rather than downloading a moving branch.
            commit = json("https://api.github.com/repos/$repo/commits/$tag").getString("sha")
            files.put(JSONObject().put("name", "source.zip").put("url", "https://codeload.github.com/$repo/zip/$commit")
                .put("bytes", 0).put("sha256", ""))
            files.put(asset("AstrBot-v$version-dashboard.zip"))
        }
        return JSONObject().put("schema", 1).put("origin", "github").put("component", component).put("version", version)
            .put("id", "github-$component-${release.getLong("id")}").put("notes", release.optString("body").take(8000))
            .put("commit", commit).put("files", files).also { validate(it, component) }
    }

    private suspend fun latestStableRelease(): JSONObject {
        val latest = json("https://api.github.com/repos/$repo/releases/latest")
        if (isStableRelease(latest)) return latest
        // Upstream sometimes publishes alpha/beta tags with prerelease=false.
        // Keep the stable channel usable without installing those builds.
        for (page in 1..5) {
            val releases = JSONArray(jsonText("https://api.github.com/repos/$repo/releases?per_page=20&page=$page"))
            newestStableRelease(releases)?.let { return it }
            if (releases.length() < 20) break
        }
        throw LocalizedArgumentException(AppText.get(R.string.upstream_no_stable))
    }

    fun accept(release: JSONObject): OnlineUpdateCheck {
        validate(release, component)
        DurableFiles.write(cache, release)
        val installed = InstalledVersions.read(context).components.getValue(label)
        val latest = info(release)
        val compare = compareVersions(latest.version, installed)
        val message = when {
            compare > 0 -> AppText.get(R.string.upstream_available, label, latest.version)
            compare == 0 -> AppText.get(R.string.upstream_latest, label, installed)
            else -> AppText.get(R.string.upstream_newer, label, installed)
        }
        return OnlineUpdateCheck(latest.takeIf { compare > 0 }, message, latest.takeIf { compare >= 0 })
    }

    /** Fetch only the dashboard for the installed core; never changes the selected update or installed core. */
    internal suspend fun repairDashboard(version: String, destination: File, progress: (String) -> Unit): File {
        check(component == "astrbot")
        val release = fetchRelease(version)
        val entry = release.getJSONArray("files").getJSONObject(1)
        val archive = File(destination.parentFile, "repair-dashboard.zip")
        try {
            downloadFile(entry, archive, progress)
            OfficialComponentArchive.extractDashboard(archive, entry, destination)
            val assets = AstrBotDashboard.inspectDirectory(File(destination, "dist"), "repair")
            require(assets.readable && assets.version == version)
            return assets.directory
        } finally { archive.delete() }
    }

    suspend fun download(id: String, updates: UpdateRepository, progress: (String) -> Unit): UpdateInfo = withContext(Dispatchers.IO) {
        val release = requireNotNull(DurableFiles.read(cache))
        validate(release, component)
        require(release.getString("id") == id)
        DurableFiles.delete(work); check(work.mkdirs())
        try {
            val files = release.getJSONArray("files")
            for (index in 0 until files.length()) {
                val entry = files.getJSONObject(index)
                val target = File(work, if (index == 0) "package.zip" else "dashboard.zip")
                val hash = downloadFile(entry, target, progress)
                // Source zipballs have no GitHub asset digest. Record the HTTPS download's digest
                // for independent verification during preparation/install, pinned to the commit.
                if (entry.getString("sha256").isEmpty()) entry.put("sha256", hash).put("bytes", target.length())
            }
            updates.prepareOfficial(release, File(work, "package.zip"), File(work, "dashboard.zip"), progress)
        } finally { DurableFiles.delete(work) }
    }

    private suspend fun json(url: String): JSONObject = JSONObject(jsonText(url))

    private suspend fun jsonText(url: String): String = response(url) { response ->
        requireLocalized(response.isSuccessful) { AppText.get(R.string.upstream_error) }
        val body = requireNotNull(response.body)
        require(body.contentLength() <= 1024 * 1024)
        val text = body.byteStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer); if (n < 0) break
                require(output.size() + n <= 1024 * 1024)
                output.write(buffer, 0, n)
            }
            output.toString("UTF-8")
        }
        text
    }

    private suspend fun downloadFile(entry: JSONObject, target: File, progress: (String) -> Unit): String = response(entry.getString("url"), "download") { response ->
        requireLocalized(response.isSuccessful) { AppText.get(R.string.upstream_download_failed) }
        val expected = entry.getLong("bytes")
        val body = requireNotNull(response.body)
        val total = if (expected > 0) expected else body.contentLength().coerceAtLeast(0)
        requireLocalized(context.filesDir.usableSpace > total + 128L * 1024 * 1024) { AppText.get(R.string.ui_insufficient_space_to_download_the_update) }
        val hash = MessageDigest.getInstance("SHA-256")
        var count = 0L
        var last = -1L
        FileOutputStream(target).use { output ->
            body.byteStream().use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val size = input.read(buffer); if (size < 0) break
                    count += size
                    requireLocalized(count <= MAX_PACKAGE && (expected == 0L || count <= expected)) { AppText.get(R.string.upstream_download_failed) }
                    require(context.filesDir.usableSpace > 32L * 1024 * 1024)
                    output.write(buffer, 0, size); hash.update(buffer, 0, size)
                    if (count / (1024 * 1024) != last) {
                        last = count / (1024 * 1024)
                        progress(if (total > 0) AppText.get(R.string.upstream_downloading, entry.getString("name"), last, total / 1024 / 1024)
                            else AppText.get(R.string.upstream_downloading_unknown, entry.getString("name"), last))
                    }
                }
            }
            output.fd.sync()
        }
        val digest = hash.digest().joinToString("") { "%02x".format(it) }
        requireLocalized(count > 0 && (expected == 0L || count == expected) &&
            (entry.getString("sha256").isEmpty() || digest == entry.getString("sha256"))) { AppText.get(R.string.upstream_download_failed) }
        digest
    }

    private suspend fun <T> response(url: String, phase: String = "metadata", block: suspend (okhttp3.Response) -> T): T = coroutineScope {
        var current = url.toHttpUrl()
        repeat(6) {
            require(current.isHttps && current.username.isEmpty() && current.password.isEmpty() &&
                current.host in setOf("api.github.com", "github.com", "codeload.github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"))
            val call = client.newCall(Request.Builder().url(current).header("Accept", "application/vnd.github+json")
                .header("User-Agent", "AstrBot-for-Android").header("Accept-Encoding", "identity").build())
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
            try {
                call.execute().use { response ->
                    if (response.code in setOf(301, 302, 303, 307, 308)) current = requireNotNull(current.resolve(requireNotNull(response.header("Location"))))
                    else {
                        if (!response.isSuccessful) throw UpdateRequestFailure(phase, current.host, response.code)
                        return@coroutineScope block(response)
                    }
                }
            } catch (failure: CancellationException) { throw failure }
            catch (failure: java.io.IOException) {
                throw if (failure is UpdateRequestFailure) failure else UpdateRequestFailure(phase, current.host, cause = failure)
            } finally { cancellation.cancel() }
        }
        error(AppText.get(R.string.upstream_error))
    }

    fun close() { if (providedClient == null) { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() } }

    companion object {
        const val MAX_PACKAGE = 256L * 1024 * 1024
        private val VERSION = Regex("[0-9]{1,5}\\.[0-9]{1,5}\\.[0-9]{1,5}")
        internal fun isStableRelease(release: JSONObject): Boolean =
            !release.optBoolean("draft", true) && !release.optBoolean("prerelease", true) &&
                release.optString("tag_name").removePrefix("v").matches(VERSION)

        internal fun newestStableRelease(releases: JSONArray): JSONObject? =
            (0 until releases.length()).mapNotNull { releases.optJSONObject(it) }.filter(::isStableRelease)
                .maxWithOrNull { a, b -> compareVersions(a.getString("tag_name").removePrefix("v"), b.getString("tag_name").removePrefix("v")) }
        fun repository(component: String) = when (component) { "astrbot" -> "AstrBotDevs/AstrBot"; else -> error("Unknown component") }
        fun compareVersions(a: String, b: String): Int {
            require(a.matches(VERSION) && b.matches(VERSION))
            return a.split('.').map(String::toInt).zip(b.split('.').map(String::toInt)).firstOrNull { it.first != it.second }
                ?.let { it.first.compareTo(it.second) } ?: 0
        }
        fun info(value: JSONObject): OnlineUpdateInfo {
            val files = value.getJSONArray("files")
            val sizes = (0 until files.length()).map { files.getJSONObject(it).getLong("bytes") }
            return OnlineUpdateInfo(value.getString("id"), value.getString("version"), AppText.get(R.string.upstream_channel),
                value.getString("notes"), if (0L in sizes) 0 else sizes.sum())
        }
        fun validate(value: JSONObject, component: String) {
            requireLocalized(value.getInt("schema") == 1 && value.getString("origin") == "github" && value.getString("component") == component) { AppText.get(R.string.upstream_invalid) }
            val repo = repository(component)
            val version = value.getString("version"); require(version.matches(VERSION))
            require(value.getString("id").matches(Regex("github-$component-[0-9]+")))
            require(value.getString("notes").length <= 8000)
            val files = value.getJSONArray("files")
            require(files.length() == 2)
            for (index in 0 until files.length()) {
                val entry = files.getJSONObject(index)
                val source = component == "astrbot" && index == 0
                val name = if (source) "source.zip" else "AstrBot-v$version-dashboard.zip"
                val url = if (source) {
                    val commit = value.getString("commit"); require(commit.matches(Regex("[a-f0-9]{40}")))
                    "https://codeload.github.com/$repo/zip/$commit"
                } else "https://github.com/$repo/releases/download/v$version/$name"
                require(entry.getString("name") == name && entry.getString("url") == url)
                require(entry.getLong("bytes") in (if (source) 0 else 1)..MAX_PACKAGE)
                require(entry.getString("sha256").matches(Regex(if (source) "(?:[a-f0-9]{64})?" else "[a-f0-9]{64}")))
            }
        }
    }
}
