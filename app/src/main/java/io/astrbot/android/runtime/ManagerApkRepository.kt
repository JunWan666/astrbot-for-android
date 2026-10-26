package io.astrbot.android.runtime

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
        if (!configured) return@withContext ManagerApkStatus(null, false, 0, "当前安装包尚未配置管理器在线更新通道")
        runCatching { savedCandidate()?.let(::status) }.getOrNull()
            ?: ManagerApkStatus(null, false, 0, "点击检查，获取已签名的管理器版本信息")
    } }

    suspend fun check(progress: (String) -> Unit): ManagerApkStatus = gate.withLock { withContext(Dispatchers.IO) {
        require(configured) { "当前安装包尚未配置管理器在线更新通道" }
        progress("正在检查管理器签名目录")
        val envelope = response(https(source)) { result ->
            require(result.isSuccessful) { "管理器更新目录暂不可用" }
            val body = requireNotNull(result.body)
            require(body.contentLength() <= MAX_CATALOG) { "管理器更新目录过大" }
            val bytes = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(bytes.size() + count <= MAX_CATALOG) { "管理器更新目录过大" }
                    bytes.write(buffer, 0, count)
                }
            }
            JSONObject(bytes.toString("UTF-8"))
        }
        val entry = verify(envelope, commitSequence = true)
        DurableFiles.write(catalog, JSONObject().put("source", source).put("envelope", envelope))
        entry?.let(::status) ?: ManagerApkStatus(null, false, 0, "已检查签名目录，当前应用没有适用的新版本")
    } }

    suspend fun download(id: String, progress: (String) -> Unit): ManagerApkStatus = gate.withLock { withContext(Dispatchers.IO) {
        val entry = requireNotNull(savedCandidate()) { "请先重新检查管理器更新" }
        require(entry.info.id == id) { "版本信息已变化，请重新查看" }
        directory.mkdirs()
        val previous = runCatching { DurableFiles.read(receipt) }.getOrNull()
        if (previous?.optString("sha256") != entry.info.sha256 || partial.length() > entry.info.bytes) {
            if (partial.exists()) check(partial.delete())
        }
        DurableFiles.write(receipt, JSONObject().put("sha256", entry.info.sha256))
        val offset = partial.length()
        require(context.filesDir.usableSpace >= entry.info.bytes - offset + entry.info.bytes + 64L * 1024 * 1024) {
            "下载与系统安装暂存所需空间不足，请先清理手机存储"
        }
        if (offset != entry.info.bytes) response(entry.url, offset) { result ->
            require(result.code == 200 || result.code == 206) { "下载失败，已保留进度，可重试" }
            val append = offset > 0 && result.code == 206
            val start = if (append) offset else 0L
            if (result.code == 206) require(result.header("Content-Range") == "bytes $start-${entry.info.bytes - 1}/${entry.info.bytes}") {
                "服务器返回的下载范围不匹配"
            }
            require(result.header("Content-Encoding").let { it == null || it == "identity" }) { "服务器返回了不支持的传输编码" }
            val body = requireNotNull(result.body)
            require(body.contentLength() == -1L || body.contentLength() == entry.info.bytes - start) { "下载大小与签名目录不一致" }
            var received = start
            var lastProgress = -1L
            FileOutputStream(partial, append).use { output ->
                try { body.byteStream().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(received + count <= entry.info.bytes) { "下载资源超出签名大小" }
                        require(context.filesDir.usableSpace >= 16L * 1024 * 1024) { "下载时存储空间不足" }
                        output.write(buffer, 0, count); received += count
                        if (received / (1024 * 1024) != lastProgress) {
                            lastProgress = received / (1024 * 1024)
                            progress("正在下载 ${entry.info.versionName}（${received / 1024 / 1024} / ${entry.info.bytes / 1024 / 1024} MiB）")
                        }
                    }
                } } finally { output.fd.sync() }
            }
            require(received == entry.info.bytes) { "下载中断，已保留进度，可继续下载" }
        }
        progress("正在校验 APK 摘要、版本和应用签名")
        verifyFile(partial, entry)
        require(savedCandidate()?.info == entry.info) { "目录已过期或变化，请重新检查" }
        apkFile.parentFile!!.mkdirs()
        // Only the narrowly scoped share directory is exposed to the Android installer.
        if (apkFile.exists()) check(apkFile.delete())
        DurableFiles.move(partial, apkFile)
        DurableFiles.write(prepared, JSONObject().put("sha256", entry.info.sha256).put("scope", scope))
        DurableFiles.removeRecord(receipt)
        status(entry).copy(message = "APK 已校验，通过确认后由系统安装；现有服务尚未改变")
    } }

    /** Always rehash and reparse immediately before granting an installer access. */
    suspend fun forInstaller(id: String): File = gate.withLock { withContext(Dispatchers.IO) {
        val entry = requireNotNull(savedCandidate()) { "版本信息已过期，请重新检查" }
        require(entry.info.id == id && isPrepared(entry)) { "请先下载并校验 APK" }
        verifyFile(apkFile, entry)
        require(context.filesDir.usableSpace >= entry.info.bytes + 64L * 1024 * 1024) { "系统安装暂存空间不足，请先清理手机存储" }
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
        require(saved.getString("source") == source) { "更新来源已变化" }
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
            if (ready) "APK 已就绪；安装前会再次校验" else "发现管理器 ${entry.info.versionName}，下载与安装由你确认")
    }

    private fun verify(envelope: JSONObject, commitSequence: Boolean): Entry? {
        require(envelope.toString().toByteArray().size <= MAX_CATALOG) { "管理器更新目录过大" }
        val raw = Base64.decode(envelope.getString("payload"), Base64.NO_WRAP)
        require(raw.size in 1..128 * 1024) { "管理器更新目录格式不支持" }
        UpdateTrust(context).verify(raw, Base64.decode(envelope.getString("signature"), Base64.NO_WRAP))
        val document = JSONObject(raw.toString(Charsets.UTF_8))
        require(document.getInt("schema") == 1 && document.getString("component") == "manager" && document.getString("channel") == "stable") {
            "此目录不是管理器稳定更新目录"
        }
        val sequence = document.getLong("sequence")
        require(sequence in 1..9_007_199_254_740_991L)
        val from = document.getLong("issuedAtMs"); val until = document.getLong("expiresAtMs")
        val window = 31L * 24 * 60 * 60 * 1000
        require(from in 1..Long.MAX_VALUE - window && until in from + 1..from + window && now() in from..until) {
            "更新目录已过期或设备时间不正确"
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
        require(parsed.map { it.info.id }.toSet().size == parsed.size &&
            parsed.map { it.packageName to it.info.versionCode }.toSet().size == parsed.size) { "更新目录存在重复版本" }
        val fingerprint = UpdateTrust.sha256(raw)
        val previous = DurableFiles.read(sequenceFile)
        if (previous != null) require(sequence > previous.getLong("sequence") ||
            sequence == previous.getLong("sequence") && fingerprint == previous.getString("fingerprint")) { "更新目录版本已过时或序号冲突" }
        if (commitSequence) DurableFiles.write(sequenceFile, JSONObject().put("sequence", sequence).put("fingerprint", fingerprint))
        val kind = if (BuildConfig.BUNDLED_RUNTIME) "bundled" else "bootstrap"
        return parsed.filter { it.packageName == context.packageName && it.info.versionCode > code(own) &&
            it.minSdk <= Build.VERSION.SDK_INT && it.abis.any { abi -> abi in Build.SUPPORTED_ABIS } &&
            it.signers == signers(own) && it.runtimeKind == kind }.maxByOrNull { it.info.versionCode }
    }

    private suspend fun verifyFile(file: File, entry: Entry) {
        require(file.isFile && file.length() == entry.info.bytes) { "APK 大小与签名目录不一致" }
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
            check(file.delete()); error("APK 摘要校验失败，请重新下载")
        }
        val info = requireNotNull(context.packageManager.getPackageArchiveInfo(file.path, signingFlags())) { "无法识别 APK 安装信息" }
        require(info.packageName == context.packageName && info.packageName == entry.packageName &&
            code(info) == entry.info.versionCode && info.versionName == entry.info.versionName && code(info) > code(installed())) {
            "APK 包名或版本与当前应用不匹配"
        }
        require(signers(info).isNotEmpty() && signers(info) == signers(installed()) && signers(info) == entry.signers) { "APK 应用签名与当前安装不一致" }
        require(info.applicationInfo?.minSdkVersion == entry.minSdk && entry.minSdk <= Build.VERSION.SDK_INT) { "APK 系统版本要求不匹配" }
        ZipFile(file).use { archive ->
            val entries = archive.entries().asSequence().map { it.name }.toList()
            val abis = entries.filter { it.startsWith("lib/") && it.endsWith(".so") }.map { it.split('/')[1] }.toSet()
            require(abis == entry.abis) { "APK 架构与签名目录不一致" }
            if (entry.runtimeKind == "bundled") {
                val manifest = requireNotNull(archive.getEntry("assets/bootstrap/arm64-v8a/manifest.json"))
                require(manifest.size in 1..64 * 1024 && archive.getEntry("assets/bootstrap/arm64-v8a/ubuntu.tar.bin") != null)
                val json = archive.getInputStream(manifest).use { JSONObject(it.bufferedReader().readText()) }
                require(json.getString("kind") == "complete") { "APK 不包含完整运行资源" }
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
        error("更新服务器重定向次数过多")
    }
    fun close() { if (providedClient == null && clientDelegate.isInitialized()) { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() } }
    companion object {
        private val gate = Mutex()
        private const val MAX_CATALOG = 256 * 1024
        private fun https(value: String): HttpUrl = value.toHttpUrl().also {
            require(it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null && value.length <= 4096) { "更新仅支持无凭据的 HTTPS 地址" }
        }
    }
}
