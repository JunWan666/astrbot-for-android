package io.astrbot.android.runtime

import android.content.Context
import android.net.Uri
import android.system.Os
import io.astrbot.android.BuildConfig
import io.astrbot.android.core.ArchivePaths
import io.astrbot.android.core.ServiceId
import kotlinx.coroutines.*
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

data class UpdateInfo(val version: String, val channel: String, val notes: String, val bytes: Long)
data class RollbackInfo(val version: String, val snapshotId: String)

/** Transactions replace one interpreted program and retain a matching data recovery point. */
class UpdateRepository(private val context: Context, private val component: String = "astrbot") {
    init { require(component in setOf("astrbot", "napcat")) }
    private val label = if (component == "napcat") "NapCat" else "AstrBot"
    private val files = context.filesDir
    private val active = File(files, if (component == "napcat") "runtime-data/ubuntu/opt/qq/resources/app/app_launcher/napcat" else "runtime-data/ubuntu/opt/astrbot")
    private val work = File(files, "staging/$component-update")
    private val staged = File(work, "program")
    private val packageFile = File(work, "package.zip")
    private val prepared = File(work, "verified.json")
    private val journal = File(files, if (component == "astrbot") "operations/update.json" else "operations/napcat-update.json")
    private val rollback = File(files, if (component == "astrbot") "manifests/rollback.json" else "manifests/napcat-rollback.json")
    private val officialProof = File(work, "official-proof.json")
    private val backups = BackupRepository(files)
    val hasPendingUpdate get() = DurableFiles.exists(journal)
    val preparedInfo: UpdateInfo? get() = DurableFiles.read(prepared)?.let(::info)
    val rollbackInfo: RollbackInfo? get() = DurableFiles.read(rollback)?.let {
        RollbackInfo(it.getJSONObject("oldVersions").getString(label), it.getString("snapshotId"))
    }
    val protectedBackupIds: Set<String> get() = listOfNotNull(DurableFiles.read(journal), DurableFiles.read(rollback))
        .map { it.getString("snapshotId") }.toSet()

    suspend fun prepare(uri: Uri, progress: (String) -> Unit): UpdateInfo = prepareExpected(uri, null, progress)

    internal suspend fun prepareExpected(uri: Uri, expectedVersion: String?, progress: (String) -> Unit): UpdateInfo = withContext(Dispatchers.IO) {
        check(component == "astrbot")
        check(!hasPendingUpdate && !backups.hasPendingRestore) { "请先完成上次维护操作" }
        DurableFiles.delete(work); check(work.mkdirs())
        try {
            progress("正在读取更新包")
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法读取所选更新包" }
                FileOutputStream(packageFile).use { output -> boundedCopy(input, output, MAX_PACKAGE); output.fd.sync() }
            }
            verifyAndExtract(progress, expectedVersion)
        } catch (failure: Throwable) { DurableFiles.delete(work); throw failure }
    }

    internal suspend fun prepareNapCat(archive: File, envelope: JSONObject, id: String, progress: (String) -> Unit): UpdateInfo = withContext(Dispatchers.IO) {
        check(component == "napcat" && !hasPendingUpdate && !backups.hasPendingRestore) { "请先完成上次维护操作" }
        DurableFiles.delete(work); check(work.mkdirs())
        try {
            FileOutputStream(packageFile).use { output -> archive.inputStream().use { boundedCopy(it, output, MAX_PACKAGE) }; output.fd.sync() }
            DurableFiles.write(officialProof, JSONObject().put("envelope", envelope).put("id", id))
            verifyAndExtract(progress)
        } catch (failure: Throwable) { DurableFiles.delete(work); throw failure }
    }

    suspend fun verifyPrepared(progress: (String) -> Unit): UpdateInfo = withContext(Dispatchers.IO) {
        check(!hasPendingUpdate) { "有未完成的更新记录" }
        verifyAndExtract(progress)
    }

    private suspend fun verifyAndExtract(progress: (String) -> Unit, expectedVersion: String? = null): UpdateInfo {
        progress("正在校验更新签名及兼容性")
        DurableFiles.delete(staged)
        if (DurableFiles.exists(prepared)) DurableFiles.removeRecord(prepared)
        require(packageFile.isFile && packageFile.length() <= MAX_PACKAGE) { "更新包缺失或过大" }
        if (component == "napcat") {
            val value = OfficialNapCat.extract(context, packageFile, staged, requireNotNull(DurableFiles.read(officialProof)))
            DurableFiles.write(prepared, value)
            return info(value)
        }
        val manifest = ZipFile(packageFile).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.size == 3 && entries.map { it.name }.toSet() == setOf("manifest.json", "manifest.sig", "program.tar.bin")) { "更新包结构无效" }
            fun read(name: String, limit: Int): ByteArray {
                val entry = zip.getEntry(name)
                require(entry.size in 1..limit.toLong()) { "更新包字段大小异常" }
                return zip.getInputStream(entry).use { it.readBytesLimited(limit) }
            }
            val bytes = read("manifest.json", 64 * 1024)
            val signature = read("manifest.sig", 256)
            UpdateTrust(context).verify(bytes, signature)
            val value = JSONObject(bytes.toString(Charsets.UTF_8))
            require(value.getInt("schema") == 1 && value.getString("component") == "astrbot") { "更新格式或组件不支持" }
            require(value.getString("version").matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[a-zA-Z0-9.-]+)?"))) { "更新版本无效" }
            require(expectedVersion == null || value.getString("version") == expectedVersion) { "更新包版本与签名目录不一致" }
            require(value.getInt("minAppCode") <= BuildConfig.VERSION_CODE) { "此更新需要新版管理器 APK" }
            val installed = InstalledVersions.read(context)
            require(value.getString("abi") == installed.components["架构"] && value.getString("environmentKey") == installed.environmentKey) {
                "此更新与当前运行环境或依赖不匹配，请使用对应运行包或 APK"
            }
            val count = value.getInt("entries")
            val size = value.getLong("unpackedBytes")
            val payloadSize = value.getLong("payloadBytes")
            require(count in 1..100_000 && size in 1..MAX_PROGRAM && payloadSize in 1..MAX_PACKAGE)
            require(files.usableSpace >= size * 2 + 64L * 1024 * 1024) { "更新暂存空间不足" }
            val payload = File(work, "program.tar.bin")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val entry = zip.getEntry("program.tar.bin")
                require(entry.size == payloadSize) { "更新资源大小不符" }
                FileOutputStream(payload).use { output ->
                    zip.getInputStream(entry).use { boundedCopy(it, output, payloadSize, digest) }; output.fd.sync()
                }
                require(payload.length() == payloadSize && digest.digest().hex() == value.getString("payloadSha256")) { "更新资源摘要不符，已保留当前版本" }
                progress("正在解包并校验 AstrBot ${value.getString("version")}")
                extract(payload, count, size)
                validateProgram(staged, value.getString("version"))
                val source = JSONObject(File(staged, ".astrbot-source.json").readText())
                require(canonicalSource(source) == canonicalSource(value.getJSONObject("source"))) { "项目来源记录与签名清单不一致" }
            } finally { payload.delete() }
            value
        }
        DurableFiles.write(prepared, manifest)
        return info(manifest)
    }

    private suspend fun extract(payload: File, expectedCount: Int, expectedSize: Long) {
        check(staged.mkdirs())
        val names = HashSet<String>()
        var size = 0L
        var count = 0
        TarArchiveInputStream(GZIPInputStream(payload.inputStream())).use { archive ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = archive.nextEntry ?: break
                val path = ArchivePaths.entry(staged.toPath(), entry.name)
                require(path != staged.toPath() && names.add(path.toString()) && ++count <= expectedCount) { "更新归档含重复或异常路径" }
                require(!entry.name.split('/').contains("..") && !entry.isSymbolicLink && !entry.isLink) { "更新包不支持链接或父目录路径" }
                when {
                    entry.isDirectory -> require(path.toFile().isDirectory || path.toFile().mkdirs())
                    entry.isFile -> {
                        require(entry.size in 0..MAX_PROGRAM)
                        size += entry.size; require(size <= expectedSize) { "更新解包大小超出签名清单" }
                        path.parent.toFile().mkdirs()
                        FileOutputStream(path.toFile()).use { output ->
                            boundedCopy(archive, output, entry.size); output.fd.sync()
                        }
                        Os.chmod(path.toString(), 420)
                    }
                    else -> error("更新归档含特殊文件")
                }
            }
        }
        require(count == expectedCount && size == expectedSize) { "更新解包数量或大小不符" }
        DurableFiles.sync(staged)
    }

    suspend fun prepareRollback(progress: (String) -> Unit): String = withContext(Dispatchers.IO) {
        check(!hasPendingUpdate && !backups.hasPendingRestore)
        val record = DurableFiles.read(rollback) ?: error("没有可回退的程序与数据快照")
        val current = InstalledVersions.read(context)
        val counterpart = if (component == "napcat") "AstrBot" else "NapCat"
        require(listOf(counterpart, "Linux QQ", "Python", "运行环境", "架构").all {
            record.getJSONObject("oldVersions").optString(it) == current.components[it]
        }) { "其他组件已更新，无法单独恢复此旧组合；请使用匹配的完整环境与备份" }
        val previous = previous(record)
        require(previous.isDirectory && backups.list().any { it.id == record.getString("snapshotId") }) { "回退程序或数据快照缺失" }
        val needed = previous.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        require(files.usableSpace >= needed + 64L * 1024 * 1024) { "回退准备空间不足" }
        progress("正在准备旧程序与对应数据")
        DurableFiles.delete(work); check(work.mkdirs())
        DurableFiles.copyProgram(previous, staged)
        validateProgram(staged, record.getJSONObject("oldVersions").getString(label))
        record.getString("snapshotId")
    }

    internal suspend fun preserveUserPlugins() {
        if (component == "napcat") OfficialNapCat.preservePlugins(active, staged)
    }

    fun begin(snapshot: BackupInfo, versions: RuntimeVersions, wanted: List<ServiceId>) {
        check(!hasPendingUpdate && staged.isDirectory)
        val record = JSONObject().put("schema", 1).put("id", UUID.randomUUID().toString()).put("phase", "prepared")
            .put("snapshotId", snapshot.id).put("oldVersions", versions.json())
            .put("wanted", JSONArray(wanted.map { it.name }))
        DurableFiles.read(rollback)?.let { record.put("previousRollback", it) }
        DurableFiles.write(journal, record)
        DurableFiles.move(active, previous(record))
        DurableFiles.move(staged, active)
        record.put("phase", "switched"); DurableFiles.write(journal, record)
    }

    fun commit() {
        val record = requireNotNull(DurableFiles.read(journal))
        check(record.getString("phase") == "switched")
        record.put("phase", "committed"); DurableFiles.write(journal, record)
        finishCommitted(record)
    }

    /** Idempotent program + corresponding data recovery before any service launch. */
    suspend fun recover(): String? = withContext(Dispatchers.IO) {
        val record = DurableFiles.read(journal) ?: return@withContext null
        require(record.getInt("schema") == 1 && record.getString("phase") in setOf("prepared", "switched", "rolling-back", "committed")) { "更新记录无法识别，已保留全部文件" }
        if (record.getString("phase") == "committed") {
            finishCommitted(record); return@withContext "已完成上次更新的提交与清理"
        }
        val phase = record.getString("phase")
        record.put("phase", "rolling-back"); DurableFiles.write(journal, record)
        backups.recover()
        val old = previous(record)
        if (old.exists()) {
            DurableFiles.delete(active)
            DurableFiles.move(old, active)
        } else {
            require(phase == "prepared" || phase == "rolling-back") { "恢复所需旧程序缺失，已保留数据" }
        }
        val versions = InstalledVersions.read(context)
        require(versions.compatibilityKey == record.getJSONObject("oldVersions").getString("compatibilityKey")) { "旧程序版本校验失败，已保留更新快照" }
        backups.beginRestore(record.getString("snapshotId"), versions)
        checkDatabase()
        backups.commitRestore()
        DurableFiles.delete(work)
        DurableFiles.removeRecord(journal)
        "更新未完成，已恢复旧程序及操作前的数据快照"
    }

    suspend fun restoreRollbackData(id: String) {
        backups.beginRestore(id, InstalledVersions.read(context))
        checkDatabase()
        // Data stays uncommitted until service checks pass; recovery first unwinds this restore.
    }
    fun commitRollbackData() { if (backups.hasPendingRestore) backups.commitRestore() }

    internal fun discardForRuntimeReplacement() {
        check(!hasPendingUpdate)
        DurableFiles.read(rollback)?.let { DurableFiles.delete(previous(it)) }
        if (DurableFiles.exists(rollback)) DurableFiles.removeRecord(rollback)
        DurableFiles.delete(work)
    }

    suspend fun checkDatabase() {
        val result = BootstrapRuntime(context).execute("/opt/astrbot-venv/bin/python", "-c", """
            import sqlite3
            from pathlib import Path
            p = Path('/persistent/astrbot/data/data_v4.db')
            if p.is_file():
                with sqlite3.connect(f'file:{p}?mode=ro', uri=True) as c:
                    assert c.execute('PRAGMA quick_check').fetchall() == [('ok',)], 'database check failed'
        """.trimIndent(), timeoutSeconds = 60)
        check(result.exitCode == 0) { "数据快照的 SQLite 检查失败，已保留恢复记录" }
    }

    private fun finishCommitted(record: JSONObject) {
        require(active.isDirectory && previous(record).isDirectory)
        DurableFiles.write(rollback, JSONObject(record.toString()).apply { remove("previousRollback") })
        record.optJSONObject("previousRollback")?.let { old ->
            if (old.getString("id") != record.getString("id")) DurableFiles.delete(previous(old))
        }
        DurableFiles.delete(work)
        DurableFiles.removeRecord(journal)
    }
    private fun previous(record: JSONObject): File {
        val id = record.getString("id")
        require(UUID.fromString(id).toString() == id)
        return File(files, "releases/$component-$id")
    }
    private fun info(value: JSONObject) = UpdateInfo(value.getString("version"), value.getString("channel"),
        value.getString("notes").take(8000), value.getLong("unpackedBytes"))
    private fun canonicalSource(source: JSONObject): List<String> = listOf(source.getString("version")) +
        listOf("source", "dashboard").flatMap { name ->
            val record = source.getJSONObject(name)
            listOf(record.getString("url"), record.getString("sha256"))
        }
    private fun validateProgram(program: File, version: String) {
        if (component == "napcat") {
            val source = File(program, "napcat.mjs")
            require(source.isFile && source.length() <= 32 * 1024 * 1024 &&
                NapCatVersion.read(program, source.readText()) == version && File(program, "static/index.html").isFile) { "NapCat 回退程序版本或界面不匹配" }
            return
        }
        fun text(path: String): String {
            val file = File(program, path)
            require(file.isFile && file.length() <= 4 * 1024 * 1024) { "更新缺少必要程序或界面文件" }
            return file.readText()
        }
        text("main.py")
        require(Regex("__version__\\s*=\\s*[\"']([^\"']+)").find(text("astrbot/__init__.py"))?.groupValues?.get(1) == version) { "程序版本不匹配" }
        require(text("astrbot/dashboard/dist/assets/version").trim().removePrefix("v").removePrefix("V") == version) { "Dashboard 与程序版本不匹配" }
        val html = text("astrbot/dashboard/dist/index.html")
        val references = Regex("(?:src|href)=[\"']/?(assets/[^\"']+\\.(?:js|css))[\"']").findAll(html).map { it.groupValues[1] }.toList()
        require(references.isNotEmpty() && references.all { File(program, "astrbot/dashboard/dist/$it").isFile }) { "Dashboard 资源缺失" }
    }
    private suspend fun boundedCopy(input: InputStream, output: java.io.OutputStream, max: Long, digest: MessageDigest? = null) {
        val buffer = ByteArray(128 * 1024)
        var size = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            size += count; require(size <= max) { "更新资源超出允许大小" }
            require(files.usableSpace >= 16L * 1024 * 1024) { "读取更新资源时空间不足" }
            output.write(buffer, 0, count); digest?.update(buffer, 0, count)
        }
    }
    private fun InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    companion object {
        private const val MAX_PACKAGE = 256L * 1024 * 1024
        private const val MAX_PROGRAM = 512L * 1024 * 1024
    }
}
