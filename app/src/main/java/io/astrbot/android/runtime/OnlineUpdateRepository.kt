package io.astrbot.android.runtime

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
        require(configured) { "此安装包尚未配置在线更新地址，可导入可信更新包或安装新版 APK" }
        progress("正在检查签名更新目录")
        acceptCatalog(fetchCatalog())
    }

    /** Network-only phase. Automatic checks must not hold the service/maintenance gate here. */
    internal suspend fun fetchCatalog(): JSONObject = withContext(Dispatchers.IO) {
        require(configured) { "当前安装包尚未配置在线更新地址" }
        requireHttps(source)
        val bytes = withResponse(source.toHttpUrl()) { response ->
            require(response.isSuccessful) { "更新目录暂不可用，请稍后重试" }
            val body = requireNotNull(response.body)
            require(body.contentLength() <= MAX_CATALOG)
            val output = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_CATALOG) { "更新目录过大" }
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
            chosen != null -> "发现 $label ${chosen.info.version}，可下载并校验"
            latest != null -> "$label $installed 已是最新兼容版本，可重新下载并校验"
            catalog.entries.any { it.environment == installedVersions.environmentKey && it.abi == installedVersions.components["架构"] && it.minAppCode <= BuildConfig.VERSION_CODE } ->
                "$label $installed 高于通道已发布版本，已保留当前程序；需要回退时使用对应快照"
            catalog.entries.isNotEmpty() -> "上游版本需要不同的运行环境或新版管理器，当前 $label $installed 已保留"
            else -> "$label 当前通道尚无已发布的兼容程序包"
        }
        return OnlineUpdateCheck(chosen?.info, message, latest?.info)
    }

    suspend fun download(id: String, updates: UpdateRepository, progress: (String) -> Unit): UpdateInfo = withContext(Dispatchers.IO) {
        require(configured) { "当前安装包尚未配置在线更新地址" }
        val saved = DurableFiles.read(cache) ?: error("请先检查更新")
        require(saved.getString("source") == source) { "更新来源已变化，请重新检查" }
        val catalog = verify(saved.getJSONObject("envelope"), commitSequence = false)
        val chosen = candidate(catalog, onlyNewer = false) ?: error("没有适配当前环境的程序包，请重新检查更新")
        require(chosen.info.id == id) { "更新目录已变化，请重新查看版本说明" }
        downloadDirectory.mkdirs()
        val previous = runCatching { DurableFiles.read(receipt) }.getOrNull()
        if (previous?.optString("sha256") != chosen.sha256 || previous.optLong("bytes") != chosen.info.bytes ||
            partial.length() > chosen.info.bytes) {
            if (partial.exists()) check(partial.delete())
        }
        DurableFiles.write(receipt, JSONObject().put("sha256", chosen.sha256).put("bytes", chosen.info.bytes))
        val offset = partial.length()
        require(context.filesDir.usableSpace >= chosen.info.bytes - offset + 64L * 1024 * 1024) { "下载更新所需空间不足" }
        if (offset != chosen.info.bytes) {
            progress("正在下载 $label ${chosen.info.version}（${offset / 1024 / 1024} / ${chosen.info.bytes / 1024 / 1024} MB）")
            withResponse(chosen.url, offset) { response ->
                require(response.code == 200 || response.code == 206) { "更新下载失败，已保留进度，可重试" }
                val append = offset > 0 && response.code == 206
                val start = if (append) offset else 0L
                if (response.code == 206) require(response.header("Content-Range") == "bytes $start-${chosen.info.bytes - 1}/${chosen.info.bytes}") {
                    "下载范围与签名资源不一致，可重新检查更新"
                }
                require(response.header("Content-Encoding").let { it == null || it == "identity" }) { "更新服务器返回了不支持的传输编码" }
                val body = requireNotNull(response.body)
                require(body.contentLength() == -1L || body.contentLength() == chosen.info.bytes - start) { "下载大小与签名目录不一致" }
                var received = start
                var lastProgress = 0L
                FileOutputStream(partial, append).use { output ->
                    try { body.byteStream().use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(received + count <= chosen.info.bytes) { "下载资源超出签名大小" }
                            require(context.filesDir.usableSpace >= 16L * 1024 * 1024) { "下载更新时存储空间不足" }
                            output.write(buffer, 0, count); received += count
                            if (received - lastProgress >= 1024 * 1024) {
                                progress("正在下载 $label ${chosen.info.version}（${received / 1024 / 1024} / ${chosen.info.bytes / 1024 / 1024} MB）")
                                lastProgress = received
                            }
                        }
                    } } finally { output.fd.sync() }
                }
                require(received == chosen.info.bytes) { "下载中断，已保留进度，可再次下载继续" }
            }
        }
        progress("正在核对下载文件摘要")
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
            error("下载文件校验失败，未改变当前程序；可重新下载")
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
        require(document.getInt("schema") == 1 && document.getString("channel") == "stable") { "更新目录格式或通道不支持" }
        val sequence = document.getLong("sequence")
        require(sequence in 1..9_007_199_254_740_991L)
        val from = document.getLong("issuedAtMs")
        val until = document.getLong("expiresAtMs")
        val window = 31L * 24 * 60 * 60 * 1000
        require(from in 1..(Long.MAX_VALUE - window) && until in (from + 1)..(from + window) && now() in from..until) {
            "更新目录已过期或设备时间不正确，请重新检查更新"
        }
        val items = document.getJSONArray("entries")
        require(items.length() <= 100)
        val entries = (0 until items.length()).map { index ->
            val value = items.getJSONObject(index)
            require(value.getString("component") == component) { "更新目录的组件与当前页面不一致" }
            if (component == "napcat") OfficialNapCat.validateEntry(value)
            val id = value.getString("id"); require(id.matches(Regex("[a-zA-Z0-9._-]{1,100}")))
            val version = value.getString("version"); versionParts(version)
            val notes = value.getString("notes"); require(notes.length <= 8000)
            val size = value.getLong("bytes"); require(size in 1..MAX_PACKAGE)
            val sha = value.getString("sha256"); require(sha.matches(Regex("[a-f0-9]{64}")))
            val environment = value.getString("environmentKey"); require(environment.matches(Regex("[a-f0-9]{64}")))
            val abi = value.getString("abi"); require(abi in setOf("arm64-v8a", "x86_64"))
            val min = value.getInt("minAppCode"); require(min > 0)
            Entry(OnlineUpdateInfo(id, version, "稳定更新", notes, size), requireHttps(value.getString("url")), sha, environment, abi, min)
        }
        require(entries.map { it.info.id }.toSet().size == entries.size)
        require(entries.map { Triple(it.info.version, it.environment, it.abi) }.toSet().size == entries.size) { "更新目录存在重复版本" }
        val fingerprint = UpdateTrust.sha256(bytes)
        val sequenceFile = File(context.filesDir, "manifests/update-sequences/$sourceScope.json")
        val previous = DurableFiles.read(sequenceFile)
        if (previous != null) require(sequence > previous.getLong("sequence") ||
            sequence == previous.getLong("sequence") && fingerprint == previous.getString("fingerprint")) { "更新目录版本已过时或序号冲突，请稍后重试" }
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
        error("更新服务器重定向次数过多")
    }

    fun close() { if (providedClient == null && clientDelegate.isInitialized()) { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() } }

    companion object {
        internal fun scopeFor(context: Context, source: String): String =
            UpdateTrust.sha256((source + "\u0000" + UpdateTrust(context).identity).toByteArray())
        private const val MAX_CATALOG = 256 * 1024
        private const val MAX_PACKAGE = 256L * 1024 * 1024
        private fun requireHttps(value: String): HttpUrl = value.toHttpUrl().also {
            require(it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null && value.length <= 4096) { "在线更新仅支持无凭据的 HTTPS 地址" }
        }
        private fun versionParts(value: String): List<Int> {
            require(value.matches(Regex("[0-9]{1,5}\\.[0-9]{1,5}\\.[0-9]{1,5}"))) { "更新版本不属于受支持的稳定版本" }
            return value.split('.').map(String::toInt)
        }
        private fun compare(a: List<Int>, b: List<Int>): Int = a.zip(b).firstOrNull { it.first != it.second }?.let { it.first.compareTo(it.second) } ?: 0
    }
}
