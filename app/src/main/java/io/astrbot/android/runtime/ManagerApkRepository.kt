package io.astrbot.android.runtime

import io.astrbot.android.requireLocalized
import io.astrbot.android.requireNotNullLocalized

import io.astrbot.android.AppText
import io.astrbot.android.R

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import io.astrbot.android.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

internal data class ManagerApkInfo(val id: String, val versionCode: Long, val versionName: String,
    val notes: String, val bytes: Long, val sha256: String)
internal data class ManagerApkStatus(val candidate: ManagerApkInfo?, val prepared: Boolean, val downloadedBytes: Long,
    val message: String)

/** Independent of guest installation and service gates. APKs remain inert until a user opens the system installer. */
internal class ManagerApkRepository(private val context: Context,
    private val source: String = BuildConfig.MANAGER_CATALOG_URL,
    private val providedClient: OkHttpClient? = null,
    private val now: () -> Long = System::currentTimeMillis) {
    private val clientDelegate = lazy { providedClient ?: OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(30, TimeUnit.MINUTES)
        .followRedirects(false).followSslRedirects(false).build() }
    private val client by clientDelegate
    private val directory = File(context.filesDir, "staging/manager-apk")
    private val catalog = File(directory, "catalog.json")
    private val receipt = File(directory, "download.json")
    private val prepared = File(directory, "prepared.json")
    private val partial = File(directory, "package.part")
    internal val apkFile = File(directory, "share/update.apk")
    val configured get() = source.isNotBlank()
    private val scope by lazy { UpdateTrust.sha256(("manager\u0000" + source + "\u0000" + UpdateTrust(context).identity).toByteArray()) }
    private val sequenceFile get() = File(context.filesDir, "manifests/manager-sequences/$scope.json")
    private data class Entry(val info: ManagerApkInfo, val url: HttpUrl, val minSdk: Int,
        val abis: Set<String>, val signers: Set<String>, val packageName: String, val runtimeKind: String)

    suspend fun cached(): ManagerApkStatus = gate.withLock { withContext(Dispatchers.IO) {
        if (!configured) return@withContext ManagerApkStatus(null, false, 0, AppText.get(R.string.ui_this_apk_has_no_manager_update_channel_configured))
        runCatching { savedCandidate()?.let(::status) }.getOrNull()
            ?: ManagerApkStatus(null, false, 0, AppText.get(R.string.ui_check_for_signed_manager_version_information))
    } }

    suspend fun check(progress: (String) -> Unit): ManagerApkStatus = gate.withLock { withContext(Dispatchers.IO) {
        requireLocalized(configured) { AppText.get(R.string.ui_this_apk_has_no_manager_update_channel_configured) }
        progress(AppText.get(R.string.ui_checking_the_signed_manager_catalog))
        val envelope = response(https(source)) { result ->
            requireLocalized(result.isSuccessful) { AppText.get(R.string.ui_manager_update_catalog_is_temporarily_unavailable) }
            val body = requireNotNull(result.body)
            requireLocalized(body.contentLength() <= MAX_CATALOG) { AppText.get(R.string.ui_manager_update_catalog_is_too_large) }
            val bytes = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    requireLocalized(bytes.size() + count <= MAX_CATALOG) { AppText.get(R.string.ui_manager_update_catalog_is_too_large) }
                    bytes.write(buffer, 0, count)
                }
            }
            JSONObject(bytes.toString("UTF-8"))
        }
        val entry = verify(envelope, commitSequence = true)
        DurableFiles.write(catalog, JSONObject().put("source", source).put("envelope", envelope))
        entry?.let(::status) ?: ManagerApkStatus(null, false, 0, AppText.get(R.string.manager_update_latest))
    } }

    suspend fun download(id: String, progress: (String) -> Unit): ManagerApkStatus = gate.withLock { withContext(Dispatchers.IO) {
        val entry = requireNotNullLocalized(savedCandidate()) { AppText.get(R.string.ui_check_for_manager_updates_again_first) }
        requireLocalized(entry.info.id == id) { AppText.get(R.string.ui_version_information_has_changed_review_it_again) }
        directory.mkdirs()
        val previous = runCatching { DurableFiles.read(receipt) }.getOrNull()
        if (previous?.optString("sha256") != entry.info.sha256 || partial.length() > entry.info.bytes) {
            if (partial.exists()) check(partial.delete())
        }
        DurableFiles.write(receipt, JSONObject().put("sha256", entry.info.sha256))
        val offset = partial.length()
        requireLocalized(context.filesDir.usableSpace >= entry.info.bytes - offset + entry.info.bytes + 64L * 1024 * 1024) {
            AppText.get(R.string.ui_insufficient_download_and_system_installation_staging_space_free_storage)
        }
        if (offset != entry.info.bytes) response(entry.url, offset) { result ->
            requireLocalized(result.code == 200 || result.code == 206) { AppText.get(R.string.ui_download_failed_progress_is_preserved_you_can_retry) }
            val append = offset > 0 && result.code == 206
            val start = if (append) offset else 0L
            if (result.code == 206) requireLocalized(result.header("Content-Range") == "bytes $start-${entry.info.bytes - 1}/${entry.info.bytes}") {
                AppText.get(R.string.ui_server_download_range_mismatch)
            }
            requireLocalized(result.header("Content-Encoding").let { it == null || it == "identity" }) { AppText.get(R.string.ui_the_server_returned_an_unsupported_transfer_encoding) }
            val body = requireNotNull(result.body)
            requireLocalized(body.contentLength() == -1L || body.contentLength() == entry.info.bytes - start) { AppText.get(R.string.ui_download_size_differs_from_the_signed_catalog) }
            var received = start
            var lastProgress = -1L
            FileOutputStream(partial, append).use { output ->
                try { body.byteStream().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        requireLocalized(received + count <= entry.info.bytes) { AppText.get(R.string.ui_download_exceeds_the_signed_size) }
                        requireLocalized(context.filesDir.usableSpace >= 16L * 1024 * 1024) { AppText.get(R.string.ui_storage_ran_out_during_download) }
                        output.write(buffer, 0, count); received += count
                        if (received / (1024 * 1024) != lastProgress) {
                            lastProgress = received / (1024 * 1024)
                            progress(AppText.get(R.string.ui_downloading_mib, entry.info.versionName, received / 1024 / 1024, entry.info.bytes / 1024 / 1024))
                        }
                    }
                } } finally { output.fd.sync() }
            }
            requireLocalized(received == entry.info.bytes) { AppText.get(R.string.ui_download_interrupted_progress_is_preserved_you_can_resume) }
        }
        progress(AppText.get(R.string.ui_verifying_apk_digest_version_and_signing_certificate))
        verifyFile(partial, entry)
        requireLocalized(savedCandidate()?.info == entry.info) { AppText.get(R.string.ui_catalog_expired_or_changed_check_again) }
        apkFile.parentFile!!.mkdirs()
        // Only the narrowly scoped share directory is exposed to the Android installer.
        if (apkFile.exists()) check(apkFile.delete())
        DurableFiles.move(partial, apkFile)
        DurableFiles.write(prepared, JSONObject().put("sha256", entry.info.sha256).put("scope", scope))
        DurableFiles.removeRecord(receipt)
        status(entry).copy(message = AppText.get(R.string.ui_apk_verified_confirm_to_open_system_installation_running_services))
    } }

    /** Always rehash and reparse immediately before granting an installer access. */
    suspend fun forInstaller(id: String): File = gate.withLock { withContext(Dispatchers.IO) {
        val entry = requireNotNullLocalized(savedCandidate()) { AppText.get(R.string.ui_version_information_has_expired_check_again) }
        requireLocalized(entry.info.id == id && isPrepared(entry)) { AppText.get(R.string.ui_download_and_verify_the_apk_first) }
        verifyFile(apkFile, entry)
        requireLocalized(context.filesDir.usableSpace >= entry.info.bytes + 64L * 1024 * 1024) { AppText.get(R.string.ui_insufficient_system_installation_staging_space_free_storage_first) }
        apkFile
    } }

    suspend fun clearDownload(): Unit = gate.withLock { withContext(Dispatchers.IO) {
        if (partial.exists()) check(partial.delete())
        if (apkFile.exists()) check(apkFile.delete())
        if (DurableFiles.exists(receipt)) DurableFiles.removeRecord(receipt)
        if (DurableFiles.exists(prepared)) DurableFiles.removeRecord(prepared)
    } }

    private fun savedCandidate(): Entry? {
        if (!configured) return null
        val saved = DurableFiles.read(catalog) ?: return null
        requireLocalized(saved.getString("source") == source) { AppText.get(R.string.ui_update_source_has_changed) }
        return verify(saved.getJSONObject("envelope"), commitSequence = false)
    }
    private fun isPrepared(entry: Entry): Boolean = runCatching {
        val record = DurableFiles.read(prepared)
        record?.optString("sha256") == entry.info.sha256 && record.optString("scope") == scope &&
            apkFile.isFile && apkFile.length() == entry.info.bytes
    }.getOrDefault(false)
    private fun status(entry: Entry): ManagerApkStatus {
        val ready = isPrepared(entry)
        val offset = if (runCatching { DurableFiles.read(receipt)?.optString("sha256") == entry.info.sha256 }.getOrDefault(false))
            partial.length().coerceIn(0, entry.info.bytes) else 0
        return ManagerApkStatus(entry.info, ready, if (ready) entry.info.bytes else offset,
            if (ready) AppText.get(R.string.ui_apk_ready_it_will_be_verified_again_before_installation) else AppText.get(R.string.ui_manager_is_available_download_and_installation_require_your_confirmation, entry.info.versionName))
    }

    private fun verify(envelope: JSONObject, commitSequence: Boolean): Entry? {
        requireLocalized(envelope.toString().toByteArray().size <= MAX_CATALOG) { AppText.get(R.string.ui_manager_update_catalog_is_too_large) }
        val raw = Base64.decode(envelope.getString("payload"), Base64.NO_WRAP)
        requireLocalized(raw.size in 1..128 * 1024) { AppText.get(R.string.ui_unsupported_manager_update_catalog_format) }
        UpdateTrust(context).verify(raw, Base64.decode(envelope.getString("signature"), Base64.NO_WRAP))
        val document = JSONObject(raw.toString(Charsets.UTF_8))
        requireLocalized(document.getInt("schema") == 1 && document.getString("component") == "manager" && document.getString("channel") == "stable") {
            AppText.get(R.string.ui_this_is_not_the_stable_manager_update_catalog)
        }
        val sequence = document.getLong("sequence")
        require(sequence in 1..9_007_199_254_740_991L)
        val from = document.getLong("issuedAtMs"); val until = document.getLong("expiresAtMs")
        val window = 31L * 24 * 60 * 60 * 1000
        requireLocalized(from in 1..Long.MAX_VALUE - window && until in from + 1..from + window && now() in from..until) {
            AppText.get(R.string.ui_update_catalog_expired_or_device_time_is_incorrect)
        }
        val own = installed()
        val entries = document.getJSONArray("entries")
        require(entries.length() <= 100)
        val parsed = (0 until entries.length()).map { i ->
            val value = entries.getJSONObject(i)
            val code = value.getLong("versionCode"); require(code in 1..Int.MAX_VALUE.toLong())
            val name = value.getString("versionName"); require(name.matches(Regex("[a-zA-Z0-9._+-]{1,100}")))
            val id = value.getString("id"); require(id.matches(Regex("[a-zA-Z0-9._-]{1,100}")))
            val notes = value.getString("notes"); require(notes.length <= 8000)
            val bytes = value.getLong("bytes"); require(bytes in 1 until 2L * 1024 * 1024 * 1024)
            val sha = value.getString("sha256"); require(sha.matches(Regex("[a-f0-9]{64}")))
            val packageName = value.getString("packageName"); require(packageName.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")))
            val min = value.getInt("minSdk"); require(min in 26..1000)
            fun stringSet(key: String): Set<String> {
                val values = value.getJSONArray(key)
                require(values.length() in 1..8)
                val list = (0 until values.length()).map { values.getString(it) }
                require(list.toSet().size == list.size)
                return list.toSet()
            }
            val abis = stringSet("abis"); require(abis.all { it in setOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86") })
            val signers = stringSet("signerSha256"); require(signers.all { it.matches(Regex("[a-f0-9]{64}")) })
            val kind = value.getString("runtimeKind"); require(kind in setOf("bundled", "bootstrap"))
            Entry(ManagerApkInfo(id, code, name, notes, bytes, sha), https(value.getString("url")), min, abis, signers, packageName, kind)
        }
        requireLocalized(parsed.map { it.info.id }.toSet().size == parsed.size &&
            parsed.map { it.packageName to it.info.versionCode }.toSet().size == parsed.size) { AppText.get(R.string.ui_update_catalog_contains_duplicate_versions) }
        val fingerprint = UpdateTrust.sha256(raw)
        val previous = DurableFiles.read(sequenceFile)
        if (previous != null) requireLocalized(sequence > previous.getLong("sequence") ||
            sequence == previous.getLong("sequence") && fingerprint == previous.getString("fingerprint")) { AppText.get(R.string.ui_update_catalog_is_outdated_or_its_sequence_number_conflicts) }
        if (commitSequence) DurableFiles.write(sequenceFile, JSONObject().put("sequence", sequence).put("fingerprint", fingerprint))
        val kind = if (BuildConfig.BUNDLED_RUNTIME) "bundled" else "bootstrap"
        return parsed.filter { it.packageName == context.packageName && it.info.versionCode > code(own) &&
            it.minSdk <= Build.VERSION.SDK_INT && it.abis.any { abi -> abi in Build.SUPPORTED_ABIS } &&
            it.signers == signers(own) && it.runtimeKind == kind }.maxByOrNull { it.info.versionCode }
    }

    private suspend fun verifyFile(file: File, entry: Entry) {
        requireLocalized(file.isFile && file.length() == entry.info.bytes) { AppText.get(R.string.ui_apk_size_differs_from_the_signed_catalog) }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != entry.info.sha256) {
            check(file.delete()); error(AppText.get(R.string.ui_apk_digest_verification_failed_download_it_again))
        }
        val info = requireNotNullLocalized(context.packageManager.getPackageArchiveInfo(file.path, signingFlags())) { AppText.get(R.string.ui_cannot_read_apk_installation_information) }
        requireLocalized(info.packageName == context.packageName && info.packageName == entry.packageName &&
            code(info) == entry.info.versionCode && info.versionName == entry.info.versionName && code(info) > code(installed())) {
            AppText.get(R.string.ui_apk_package_name_or_version_does_not_match_this)
        }
        requireLocalized(signers(info).isNotEmpty() && signers(info) == signers(installed()) && signers(info) == entry.signers) { AppText.get(R.string.ui_apk_signing_certificate_differs_from_the_installed_app) }
        requireLocalized(info.applicationInfo?.minSdkVersion == entry.minSdk && entry.minSdk <= Build.VERSION.SDK_INT) { AppText.get(R.string.ui_apk_android_version_requirements_are_incompatible) }
        ZipFile(file).use { archive ->
            val entries = archive.entries().asSequence().map { it.name }.toList()
            val abis = entries.filter { it.startsWith("lib/") && it.endsWith(".so") }.map { it.split('/')[1] }.toSet()
            requireLocalized(abis == entry.abis) { AppText.get(R.string.ui_apk_architecture_differs_from_the_signed_catalog) }
            if (entry.runtimeKind == "bundled") {
                val manifest = requireNotNull(archive.getEntry("assets/bootstrap/arm64-v8a/manifest.json"))
                require(manifest.size in 1..64 * 1024 && archive.getEntry("assets/bootstrap/arm64-v8a/ubuntu.tar.bin") != null)
                val json = archive.getInputStream(manifest).use { JSONObject(it.bufferedReader().readText()) }
                requireLocalized(json.getString("kind") == "complete") { AppText.get(R.string.ui_apk_does_not_contain_the_complete_runtime) }
            }
        }
    }

    private fun installed(): PackageInfo = context.packageManager.getPackageInfo(context.packageName, signingFlags())
    private fun signingFlags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    @Suppress("DEPRECATION") private fun signers(info: PackageInfo): Set<String> =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            ?.map { UpdateTrust.sha256(it.toByteArray()) }?.toSet().orEmpty()
    @Suppress("DEPRECATION") private fun code(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private suspend fun <T> response(url: HttpUrl, offset: Long = 0, block: suspend (Response) -> T): T = coroutineScope {
        var current = https(url.toString())
        repeat(6) {
            val request = Request.Builder().url(current).header("Accept-Encoding", "identity").header("Cache-Control", "no-cache")
            if (offset > 0) request.header("Range", "bytes=$offset-")
            val call = client.newCall(request.build())
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
            try {
                call.execute().use { result ->
                    if (result.code in setOf(301, 302, 303, 307, 308))
                        current = https(requireNotNull(current.resolve(requireNotNull(result.header("Location")))).toString())
                    else return@coroutineScope block(result)
                }
            } finally { cancellation.cancel() }
        }
        error(AppText.get(R.string.ui_too_many_update_server_redirects))
    }
    fun close() {
        if (providedClient == null && clientDelegate.isInitialized()) {
            // Closing a pooled TLS socket may write to the network. Activity teardown runs on main.
            CoroutineScope(Dispatchers.IO).launch {
                try { client.connectionPool.evictAll() }
                finally { client.dispatcher.executorService.shutdown() }
            }
        }
    }
    companion object {
        private val gate = Mutex()
        private const val MAX_CATALOG = 256 * 1024
        private fun https(value: String): HttpUrl = value.toHttpUrl().also {
            requireLocalized(it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null && value.length <= 4096) { AppText.get(R.string.ui_updates_require_https_urls_without_embedded_credentials) }
        }
    }
}
