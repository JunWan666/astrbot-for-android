// SPDX-License-Identifier: MIT
// Copyright (c) 2026 JunWan666
package io.astrbot.android.runtime

import android.content.Context
import android.system.Os
import android.util.Base64
import io.astrbot.android.BuildConfig
import io.astrbot.android.core.ArchivePaths
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile

/** The official archive is authenticated by our signed compatibility catalog, never repackaged. */
internal object OfficialNapCat {
    const val MARKER = ".astrbot-napcat.json"
    private const val MAX_UNPACKED = 512L * 1024 * 1024

    fun validateEntry(entry: JSONObject) {
        val version = entry.getString("version")
        require(version.matches(Regex("[0-9]{1,5}\\.[0-9]{1,5}\\.[0-9]{1,5}")))
        require(entry.getString("url") == "https://github.com/NapNeko/NapCatQQ/releases/download/v$version/NapCat.Shell.zip") {
            "NapCat 更新必须来自上游正式发布的 Shell 包"
        }
        require(entry.getString("qqVersion").matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+-[0-9]+")))
        require(entry.getString("backendSha256").matches(Regex("[a-f0-9]{64}")))
    }

    fun entry(context: Context, proof: JSONObject): JSONObject {
        val envelope = proof.getJSONObject("envelope")
        val raw = Base64.decode(envelope.getString("payload"), Base64.NO_WRAP)
        require(raw.size in 1..128 * 1024)
        UpdateTrust(context).verify(raw, Base64.decode(envelope.getString("signature"), Base64.NO_WRAP))
        val document = JSONObject(raw.toString(Charsets.UTF_8))
        require(document.getInt("schema") == 1 && document.getString("channel") == "stable")
        val items = document.getJSONArray("entries")
        require(items.length() in 1..100)
        val entry = (0 until items.length()).map { items.getJSONObject(it) }
            .single { it.getString("id") == proof.getString("id") }
        require(entry.getString("component") == "napcat")
        validateEntry(entry)
        val installed = InstalledVersions.read(context)
        require(entry.getInt("minAppCode") <= BuildConfig.VERSION_CODE &&
            entry.getString("environmentKey") == installed.environmentKey &&
            entry.getString("abi") == installed.components["架构"] &&
            entry.getString("qqVersion") == installed.components["Linux QQ"]) {
            "此 NapCat 版本需要不同的 QQ 或运行依赖，已保留现有程序；请先升级匹配的运行环境"
        }
        // Expiry was enforced when downloading. A verified downloaded package, like an AstrBot
        // maintenance package, remains installable offline after the catalog has expired.
        return entry
    }

    suspend fun extract(context: Context, archive: File, destination: File, proof: JSONObject): JSONObject {
        val entry = entry(context, proof)
        require(entry.getLong("bytes") in 1..256L * 1024 * 1024 && archive.length() == entry.getLong("bytes")) { "NapCat 更新包大小不符" }
        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer); if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        require(digest.digest().joinToString("") { "%02x".format(it) } == entry.getString("sha256")) { "NapCat 更新包摘要不符，当前程序已保留" }
        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.size in 1..100_000 && entries.sumOf { it.size.coerceAtLeast(0) } <= MAX_UNPACKED) { "NapCat 更新包解压大小异常" }
            val names = HashSet<String>()
            require(context.filesDir.usableSpace >= entries.sumOf { it.size.coerceAtLeast(0) } * 2 + 64L * 1024 * 1024) { "NapCat 更新暂存空间不足" }
            check(destination.mkdirs())
            var total = 0L
            entries.forEach { file ->
                currentCoroutineContext().ensureActive()
                val path = ArchivePaths.entry(destination.toPath(), file.name)
                require(path != destination.toPath() && names.add(path.toString()) &&
                    !file.name.split('/').contains("..") && file.name != MARKER) { "NapCat 更新包含重复或异常路径" }
                // java.util.zip does not expose Unix file types. The publisher also rejects links;
                // extraction always creates regular files and never honors archive link attributes.
                if (file.isDirectory) require(path.toFile().isDirectory || path.toFile().mkdirs()) else {
                    require(file.size in 0..MAX_UNPACKED)
                    path.parent.toFile().mkdirs()
                    var count = 0L
                    FileOutputStream(path.toFile()).use { output ->
                        zip.getInputStream(file).use { input ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                count += n; total += n
                                require(count <= file.size && total <= MAX_UNPACKED) { "NapCat 更新包超出声明大小" }
                                output.write(buffer, 0, n)
                            }
                        }
                        output.fd.sync()
                    }
                    require(count == file.size)
                    Os.chmod(path.toString(), 420)
                }
            }
        }
        val backend = File(destination, "napcat.mjs")
        require(backend.isFile && backend.length() <= 32 * 1024 * 1024 &&
            UpdateTrust.sha256(backend.readBytes()) == entry.getString("backendSha256")) { "NapCat 主程序与签名版本不符" }
        require(File(destination, "static/index.html").isFile && File(destination, "package.json").isFile &&
            File(destination, "node_modules").isDirectory && File(destination, "native").isDirectory) { "NapCat 更新包缺少后台或运行资源" }
        DurableFiles.write(File(destination, MARKER), JSONObject().put("version", entry.getString("version"))
            .put("backendSha256", entry.getString("backendSha256")).put("archiveSha256", entry.getString("sha256")))
        DurableFiles.sync(destination)
        return JSONObject().put("version", entry.getString("version")).put("channel", "稳定更新")
            .put("notes", entry.getString("notes")).put("unpackedBytes", destination.walkTopDown().filter { it.isFile }.sumOf { it.length() })
    }

    /** A few older builds placed user plugins beside the program. Keep them across replacements. */
    suspend fun preservePlugins(active: File, staged: File) {
        val source = File(active, "plugins")
        if (!Files.exists(source.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        val target = File(staged, "plugins")
        require(!target.exists()) { "新版包含同名插件目录，请先将自定义插件移到 NapCat 工作目录后更新" }
        DurableFiles.copyProgram(source, target)
    }
}

internal object NapCatVersion {
    fun read(program: File, source: String): String? {
        val marker = runCatching { DurableFiles.read(File(program, OfficialNapCat.MARKER)) }.getOrNull()
        if (marker != null) {
            val version = marker.optString("version")
            if (version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) &&
                UpdateTrust.sha256(NapCatCompatibility.originalBackend(source).toByteArray()) == marker.optString("backendSha256")) return version
            return null
        }
        return Regex("\\bVu\\s*=.{0,100}?[\"'](\\d+\\.\\d+\\.\\d+[^\"']*)[\"']").find(source)?.groupValues?.get(1)
    }
}
