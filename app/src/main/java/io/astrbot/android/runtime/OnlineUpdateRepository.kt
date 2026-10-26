package io.astrbot.android.runtime

import io.astrbot.android.requireLocalized

import io.astrbot.android.AppText
import io.astrbot.android.R

import android.content.Context
import android.net.Uri
import android.util.Base64
import io.astrbot.android.BuildConfig
import kotlinx.coroutines.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class OnlineUpdateInfo(val id: String, val version: String, val channel: String, val notes: String, val bytes: Long)
data class OnlineUpdateCheck(val candidate: OnlineUpdateInfo?, val message: String, val latest: OnlineUpdateInfo? = candidate)

/** Downloads are inert until both the signed catalog and existing package verifier accept them. */
internal class OnlineUpdateRepository(
    private val context: Context,
    private val source: String = BuildConfig.UPDATE_CATALOG_URL,
    private val providedClient: OkHttpClient? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val component: String = "astrbot",
) {
    init { require(component in setOf("astrbot", "napcat")) }
    private val label = if (component == "napcat") "NapCat" else "AstrBot"
    private val clientDelegate = lazy { providedClient ?: OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(20, TimeUnit.MINUTES)
        .followRedirects(false).followSslRedirects(false).build() }
    private val client by clientDelegate
    private val cache = File(context.filesDir, if (component == "astrbot") "manifests/online-catalog.json" else "manifests/napcat-catalog.json")
    private val downloadDirectory = File(context.filesDir, if (component == "astrbot") "staging/online-download" else "staging/napcat-download")
    private val partial = File(downloadDirectory, "package.part")
    private val receipt = File(downloadDirectory, "download.json")
    val configured get() = source.isNotEmpty()
    internal val sourceScope by lazy { scopeFor(context, source) }

    private data class Entry(val info: OnlineUpdateInfo, val url: HttpUrl, val sha256: String,
                             val environment: String, val abi: String, val minAppCode: Int)
    private data class Catalog(val entries: List<Entry>)

    fun cachedCandidate(): OnlineUpdateInfo? = cached(onlyNewer = true)
    fun cachedLatest(): OnlineUpdateInfo? = cached(onlyNewer = false)
    private fun cached(onlyNewer: Boolean): OnlineUpdateInfo? = runCatching {
        if (!configured) return null
        val saved = DurableFiles.read(cache) ?: return null
        require(saved.getString("source") == source)
        candidate(verify(saved.getJSONObject("envelope"), commitSequence = false), onlyNewer)?.info
    }.getOrNull()

    suspend fun check(progress: (String) -> Unit): OnlineUpdateCheck = withContext(Dispatchers.IO) {
        requireLocalized(configured) { AppText.get(R.string.ui_this_apk_has_no_online_update_url_configured_import) }
        progress(AppText.get(R.string.ui_checking_the_signed_update_catalog))
        acceptCatalog(fetchCatalog())
    }

    /** Network-only phase. Automatic checks must not hold the service/maintenance gate here. */
    internal suspend fun fetchCatalog(): JSONObject = withContext(Dispatchers.IO) {
        requireLocalized(configured) { AppText.get(R.string.ui_this_apk_has_no_online_update_url_configured) }
        requireHttps(source)
        val bytes = withResponse(source.toHttpUrl()) { response ->
            requireLocalized(response.isSuccessful) { AppText.get(R.string.ui_update_catalog_is_temporarily_unavailable_try_again_later) }
            val body = requireNotNull(response.body)
            require(body.contentLength() <= MAX_CATALOG)
            val output = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    requireLocalized(output.size() + count <= MAX_CATALOG) { AppText.get(R.string.ui_update_catalog_is_too_large) }
                    output.write(buffer, 0, count)
                }
            }
            output.toByteArray()
        }
        JSONObject(bytes.toString(Charsets.UTF_8))
    }

    /** Caller holds RuntimeStore.operationLock. Re-evaluate compatibility at commit time. */
    internal fun acceptCatalog(envelope: JSONObject): OnlineUpdateCheck {
        val catalog = verify(envelope, commitSequence = true)
        val chosen = candidate(catalog)
        val latest = candidate(catalog, onlyNewer = false)
        DurableFiles.write(cache, JSONObject().put("source", source).put("envelope", envelope))
        val installedVersions = InstalledVersions.read(context)
        val installed = installedVersions.components[label]
        val message = when {
            chosen != null -> AppText.get(R.string.ui_is_available_to_download_and_verify, label, chosen.info.version)
            latest != null -> AppText.get(R.string.ui_is_the_latest_compatible_version_you_can_download_and, label, installed)
            catalog.entries.any { it.environment == installedVersions.environmentKey && it.abi == installedVersions.components["架构"] && it.minAppCode <= BuildConfig.VERSION_CODE } ->
                AppText.get(R.string.ui_installed_is_newer_than_the_channel_release_and_has, label, installed)
            catalog.entries.isNotEmpty() -> AppText.get(R.string.ui_the_upstream_version_requires_a_different_runtime_or_newer, label, installed)
            else -> AppText.get(R.string.ui_no_compatible_package_is_published_on_this_channel, label)
        }
        return OnlineUpdateCheck(chosen?.info, message, latest?.info)
    }

    suspend fun download(id: String, updates: UpdateRepository, progress: (String) -> Unit): UpdateInfo = withContext(Dispatchers.IO) {
        requireLocalized(configured) { AppText.get(R.string.ui_this_apk_has_no_online_update_url_configured) }
        val saved = DurableFiles.read(cache) ?: error("请先检查更新")
        requireLocalized(saved.getString("source") == source) { AppText.get(R.string.ui_update_source_has_changed_check_again) }
        val catalog = verify(saved.getJSONObject("envelope"), commitSequence = false)
        val chosen = candidate(catalog, onlyNewer = false) ?: error(AppText.get(R.string.ui_no_package_matches_the_current_runtime_check_for_updates))
        requireLocalized(chosen.info.id == id) { AppText.get(R.string.ui_update_catalog_has_changed_review_the_release_information_again) }
        downloadDirectory.mkdirs()
        val previous = runCatching { DurableFiles.read(receipt) }.getOrNull()
        if (previous?.optString("sha256") != chosen.sha256 || previous.optLong("bytes") != chosen.info.bytes ||
            partial.length() > chosen.info.bytes) {
            if (partial.exists()) check(partial.delete())
        }
        DurableFiles.write(receipt, JSONObject().put("sha256", chosen.sha256).put("bytes", chosen.info.bytes))
        val offset = partial.length()
        requireLocalized(context.filesDir.usableSpace >= chosen.info.bytes - offset + 64L * 1024 * 1024) { AppText.get(R.string.ui_insufficient_space_to_download_the_update) }
        if (offset != chosen.info.bytes) {
            progress(AppText.get(R.string.ui_downloading_mb, label, chosen.info.version, offset / 1024 / 1024, chosen.info.bytes / 1024 / 1024))
            withResponse(chosen.url, offset) { response ->
                requireLocalized(response.code == 200 || response.code == 206) { AppText.get(R.string.ui_update_download_failed_progress_is_preserved_you_can_retry) }
                val append = offset > 0 && response.code == 206
                val start = if (append) offset else 0L
                if (response.code == 206) requireLocalized(response.header("Content-Range") == "bytes $start-${chosen.info.bytes - 1}/${chosen.info.bytes}") {
                    AppText.get(R.string.ui_download_range_differs_from_signed_resources_check_for_updates)
                }
                requireLocalized(response.header("Content-Encoding").let { it == null || it == "identity" }) { AppText.get(R.string.ui_update_server_returned_an_unsupported_transfer_encoding) }
                val body = requireNotNull(response.body)
                requireLocalized(body.contentLength() == -1L || body.contentLength() == chosen.info.bytes - start) { AppText.get(R.string.ui_download_size_differs_from_the_signed_catalog) }
                var received = start
                var lastProgress = 0L
                FileOutputStream(partial, append).use { output ->
                    try { body.byteStream().use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            requireLocalized(received + count <= chosen.info.bytes) { AppText.get(R.string.ui_download_exceeds_the_signed_size) }
                            requireLocalized(context.filesDir.usableSpace >= 16L * 1024 * 1024) { AppText.get(R.string.ui_storage_ran_out_while_downloading_the_update) }
                            output.write(buffer, 0, count); received += count
                            if (received - lastProgress >= 1024 * 1024) {
                                progress(AppText.get(R.string.ui_downloading_mb, label, chosen.info.version, received / 1024 / 1024, chosen.info.bytes / 1024 / 1024))
                                lastProgress = received
                            }
                        }
                    } } finally { output.fd.sync() }
                }
                requireLocalized(received == chosen.info.bytes) { AppText.get(R.string.ui_download_interrupted_progress_is_preserved_download_again_to_resume) }
            }
        }
        progress(AppText.get(R.string.ui_checking_the_downloaded_file_digest))
        val digest = MessageDigest.getInstance("SHA-256")
        partial.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (hash != chosen.sha256 || partial.length() != chosen.info.bytes) {
            partial.delete()
            error(AppText.get(R.string.ui_downloaded_file_verification_failed_current_program_is_unchanged_downloa))
        }
        // Revalidate expiration after a potentially long download, then independently verify the ZIP signature.
        verify(saved.getJSONObject("envelope"), commitSequence = false)
        val prepared = if (component == "napcat") updates.prepareNapCat(partial, saved.getJSONObject("envelope"), chosen.info.id, progress)
            else updates.prepareExpected(Uri.fromFile(partial), chosen.info.version, progress)
        partial.delete(); DurableFiles.removeRecord(receipt)
        prepared
    }

    private fun verify(envelope: JSONObject, commitSequence: Boolean): Catalog {
        require(envelope.toString().length <= MAX_CATALOG)
        val bytes = Base64.decode(envelope.getString("payload"), Base64.NO_WRAP)
        require(bytes.size in 1..128 * 1024)
        val trust = UpdateTrust(context)
        trust.verify(bytes, Base64.decode(envelope.getString("signature"), Base64.NO_WRAP))
        val document = JSONObject(bytes.toString(Charsets.UTF_8))
        requireLocalized(document.getInt("schema") == 1 && document.getString("channel") == "stable") { AppText.get(R.string.ui_unsupported_update_catalog_format_or_channel) }
        val sequence = document.getLong("sequence")
        require(sequence in 1..9_007_199_254_740_991L)
        val from = document.getLong("issuedAtMs")
        val until = document.getLong("expiresAtMs")
        val window = 31L * 24 * 60 * 60 * 1000
        requireLocalized(from in 1..(Long.MAX_VALUE - window) && until in (from + 1)..(from + window) && now() in from..until) {
            AppText.get(R.string.ui_update_catalog_expired_or_device_time_is_incorrect_check)
        }
        val items = document.getJSONArray("entries")
        require(items.length() <= 100)
        val entries = (0 until items.length()).map { index ->
            val value = items.getJSONObject(index)
            requireLocalized(value.getString("component") == component) { AppText.get(R.string.ui_update_catalog_component_does_not_match_this_page) }
            if (component == "napcat") OfficialNapCat.validateEntry(value)
            val id = value.getString("id"); require(id.matches(Regex("[a-zA-Z0-9._-]{1,100}")))
            val version = value.getString("version"); versionParts(version)
            val notes = value.getString("notes"); require(notes.length <= 8000)
            val size = value.getLong("bytes"); require(size in 1..MAX_PACKAGE)
            val sha = value.getString("sha256"); require(sha.matches(Regex("[a-f0-9]{64}")))
            val environment = value.getString("environmentKey"); require(environment.matches(Regex("[a-f0-9]{64}")))
            val abi = value.getString("abi"); require(abi in setOf("arm64-v8a", "x86_64"))
            val min = value.getInt("minAppCode"); require(min > 0)
            Entry(OnlineUpdateInfo(id, version, AppText.get(R.string.ui_stable_update), notes, size), requireHttps(value.getString("url")), sha, environment, abi, min)
        }
        require(entries.map { it.info.id }.toSet().size == entries.size)
        requireLocalized(entries.map { Triple(it.info.version, it.environment, it.abi) }.toSet().size == entries.size) { AppText.get(R.string.ui_update_catalog_contains_duplicate_versions) }
        val fingerprint = UpdateTrust.sha256(bytes)
        val sequenceFile = File(context.filesDir, "manifests/update-sequences/$sourceScope.json")
        val previous = DurableFiles.read(sequenceFile)
        if (previous != null) requireLocalized(sequence > previous.getLong("sequence") ||
            sequence == previous.getLong("sequence") && fingerprint == previous.getString("fingerprint")) { AppText.get(R.string.ui_update_catalog_is_outdated_or_its_sequence_number_conflicts_233b9) }
        if (commitSequence) DurableFiles.write(sequenceFile, JSONObject().put("sequence", sequence).put("fingerprint", fingerprint))
        return Catalog(entries)
    }

    private fun candidate(catalog: Catalog, onlyNewer: Boolean = true): Entry? {
        val installed = InstalledVersions.read(context)
        val current = versionParts(installed.components.getValue(label))
        return catalog.entries.filter { it.minAppCode <= BuildConfig.VERSION_CODE && it.environment == installed.environmentKey &&
            it.abi == installed.components["架构"] && compare(versionParts(it.info.version), current) >= (if (onlyNewer) 1 else 0) }
            .maxWithOrNull { a, b -> compare(versionParts(a.info.version), versionParts(b.info.version)) }
    }

    private suspend fun <T> withResponse(url: HttpUrl, offset: Long = 0, block: suspend (Response) -> T): T = coroutineScope {
        var current = requireHttps(url.toString())
        repeat(6) {
            val request = Request.Builder().url(current).header("Accept-Encoding", "identity").header("Cache-Control", "no-cache")
            if (offset > 0) request.header("Range", "bytes=$offset-")
            val call = client.newCall(request.build())
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
            try {
                call.execute().use { response ->
                    if (response.code in setOf(301, 302, 303, 307, 308)) {
                        current = requireHttps(requireNotNull(current.resolve(requireNotNull(response.header("Location")))).toString())
                    } else return@coroutineScope block(response)
                }
            } finally { cancellation.cancel() }
        }
        error(AppText.get(R.string.ui_too_many_update_server_redirects))
    }

    fun close() { if (providedClient == null && clientDelegate.isInitialized()) { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() } }

    companion object {
        internal fun scopeFor(context: Context, source: String): String =
            UpdateTrust.sha256((source + "\u0000" + UpdateTrust(context).identity).toByteArray())
        private const val MAX_CATALOG = 256 * 1024
        private const val MAX_PACKAGE = 256L * 1024 * 1024
        private fun requireHttps(value: String): HttpUrl = value.toHttpUrl().also {
            requireLocalized(it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null && value.length <= 4096) { AppText.get(R.string.ui_online_updates_require_https_urls_without_embedded_credentials) }
        }
        private fun versionParts(value: String): List<Int> {
            requireLocalized(value.matches(Regex("[0-9]{1,5}\\.[0-9]{1,5}\\.[0-9]{1,5}"))) { AppText.get(R.string.ui_this_is_not_a_supported_stable_update_version) }
            return value.split('.').map(String::toInt)
        }
        private fun compare(a: List<Int>, b: List<Int>): Int = a.zip(b).firstOrNull { it.first != it.second }?.let { it.first.compareTo(it.second) } ?: 0
    }
}
