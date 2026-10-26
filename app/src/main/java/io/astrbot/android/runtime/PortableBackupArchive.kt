package io.astrbot.android.runtime

import android.system.Os
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class PortableBackupInfo(val id: String, val createdAt: Long, val bytes: Long, val count: Int, val versions: Map<String, String>)

/** Only the declared portable scopes enter this format. QQ's HOME and runtime never enter it. */
internal class PortableBackupArchive {
    private data class Entry(val name: String, val kind: String, val bytes: Long = 0, val sha256: String = "",
                             val executable: Boolean = false, val target: String = "") {
        fun json() = JSONObject().put("name", name).put("kind", kind).put("bytes", bytes).put("sha256", sha256)
            .put("executable", executable).put("target", target)
    }

    suspend fun write(source: File, destination: File, versions: RuntimeVersions): PortableBackupInfo {
        val entries = scan(source)
        val manifest = JSONObject().put("schema", 1).put("format", FORMAT).put("id", UUID.randomUUID().toString())
            .put("createdAt", System.currentTimeMillis()).put("versions", versions.json())
            .put("scopes", JSONArray(SCOPES)).put("qqSessionsIncluded", false)
            .put("entries", JSONArray(entries.map { it.json() }))
        val bytes = manifest.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_MANIFEST) { "便携备份清单过大" }
        require(destination.parentFile!!.usableSpace >= entries.sumOf { it.bytes }.coerceAtMost(MAX_ARCHIVE) + RESERVE) { "便携备份暂存空间不足" }
        val coroutine = currentCoroutineContext()
        var written = 0L
        var spaceCheck = 0L
        try {
            FileOutputStream(destination).use { raw ->
                val bounded = object : FilterOutputStream(raw) {
                    override fun write(value: Int) = write(byteArrayOf(value.toByte()))
                    override fun write(buffer: ByteArray, offset: Int, length: Int) {
                        coroutine.ensureActive()
                        written += length
                        require(written <= MAX_ARCHIVE) { "便携备份文件超过 2 GiB 上限" }
                        if (written - spaceCheck >= 1024 * 1024) {
                            require(destination.parentFile!!.usableSpace >= RESERVE) { "便携备份暂存空间不足" }
                            spaceCheck = written
                        }
                        out.write(buffer, offset, length)
                    }
                }
                ZipOutputStream(BufferedOutputStream(bounded)).use { zip ->
                    zip.putNextEntry(ZipEntry("manifest.json")); zip.write(bytes); zip.closeEntry()
                    entries.filter { it.kind == "file" }.forEach { entry ->
                        coroutine.ensureActive()
                        zip.putNextEntry(ZipEntry("data/${entry.name}"))
                        File(source, entry.name).inputStream().use { input ->
                            check(copy(input, zip, entry.bytes) == entry.sha256) { "便携备份期间数据发生变化，未生成备份" }
                        }
                        zip.closeEntry()
                    }
                    val complete = JSONObject().put("manifestSha256", digest(bytes)).put("entries", entries.size).toString().toByteArray()
                    zip.putNextEntry(ZipEntry("complete.json")); zip.write(complete); zip.closeEntry()
                    zip.finish(); zip.flush(); raw.fd.sync()
                }
            }
            check(scan(source) == entries) { "便携备份期间数据发生变化，未生成备份" }
            return info(manifest)
        } catch (failure: Throwable) { destination.delete(); throw failure }
    }

    suspend fun read(archive: File, destination: File, versions: RuntimeVersions): PortableBackupInfo {
        require(archive.length() in 1..MAX_ARCHIVE) { "便携备份大小无效或超过 2 GiB" }
        check(!destination.exists()) { "便携备份暂存目录已存在" }
        try {
            ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
                require(zip.nextEntry?.name == "manifest.json") { "不是本应用支持的便携备份" }
                val bytes = limited(zip, MAX_MANIFEST)
                val manifest = JSONObject(bytes.toString(Charsets.UTF_8))
                val entries = parse(manifest, versions)
                validateContainer(archive, entries.count { it.kind == "file" } + 2)
                val required = entries.sumOf { it.bytes } + RESERVE
                require(destination.parentFile!!.usableSpace >= required) { "便携备份解包空间不足" }
                check(destination.mkdirs())
                entries.filter { it.kind == "directory" }.sortedBy { it.name.count { c -> c == '/' } }.forEach {
                    check(File(destination, it.name).mkdir() || File(destination, it.name).isDirectory)
                    Os.chmod(File(destination, it.name).path, 448)
                }
                entries.filter { it.kind == "file" }.forEach { entry ->
                    currentCoroutineContext().ensureActive()
                    require(zip.nextEntry?.name == "data/${entry.name}") { "便携备份文件项缺失、重复或顺序异常" }
                    val file = File(destination, entry.name)
                    FileOutputStream(file).use { output ->
                        require(copy(zip, output, entry.bytes) == entry.sha256) { "便携备份文件摘要不符，未修改现有数据" }
                        output.fd.sync()
                    }
                    Os.chmod(file.path, if (entry.executable) 448 else 384)
                }
                require(zip.nextEntry?.name == "complete.json") { "便携备份不完整" }
                val complete = JSONObject(limited(zip, 1024).toString(Charsets.UTF_8))
                require(complete.getString("manifestSha256") == digest(bytes) && complete.getInt("entries") == entries.size) { "便携备份完成记录不符" }
                require(zip.nextEntry == null) { "便携备份包含未声明文件" }
                entries.filter { it.kind == "symlink" }.forEach { entry ->
                    Os.symlink(entry.target, File(destination, entry.name).path)
                }
                verify(destination, manifest, versions, exclusive = true)
                DurableFiles.write(File(destination.parentFile, "verified.json"), manifest)
                return info(manifest)
            }
        } catch (failure: Throwable) { DurableFiles.delete(destination); throw failure }
    }

    suspend fun verify(tree: File, manifest: JSONObject, versions: RuntimeVersions, exclusive: Boolean = false): PortableBackupInfo {
        val expected = parse(manifest, versions)
        if (exclusive) require(tree.list()?.toSet() == setOf("astrbot", "napcat") && File(tree, "napcat").list()?.toSet() == setOf("config")) {
            "便携备份暂存包含范围之外的数据，请重新导入"
        }
        require(scan(tree) == expected) { "便携备份暂存文件发生变化，请重新导入" }
        return info(manifest)
    }

    private fun parse(manifest: JSONObject, versions: RuntimeVersions): List<Entry> {
        require(manifest.getInt("schema") == 1 && manifest.getString("format") == FORMAT) { "便携备份格式不支持" }
        require(UUID.fromString(manifest.getString("id")).toString() == manifest.getString("id") && manifest.getLong("createdAt") >= 0) { "便携备份标识无效" }
        require(!manifest.getBoolean("qqSessionsIncluded") && manifest.getJSONArray("scopes").let {
            it.length() == SCOPES.size && (0 until it.length()).map(it::getString) == SCOPES
        }) { "便携备份的数据范围不支持" }
        require(manifest.getJSONObject("versions").getString("compatibilityKey") == versions.compatibilityKey) {
            "便携备份与当前运行版本组合不同，请先安装匹配的运行环境"
        }
        val array = manifest.getJSONArray("entries")
        require(array.length() in 3..MAX_ENTRIES) { "便携备份文件数量超出范围" }
        val entries = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            Entry(item.getString("name"), item.getString("kind"), item.getLong("bytes"), item.getString("sha256"),
                item.getBoolean("executable"), item.getString("target"))
        }
        validate(entries)
        return entries
    }

    private fun validate(entries: List<Entry>) {
        require(entries.size <= MAX_ENTRIES && entries == entries.sortedBy { it.name } && entries.map { it.name }.toSet().size == entries.size) { "便携备份文件路径重复或顺序异常" }
        val byName = entries.associateBy { it.name }
        require(listOf("astrbot", "napcat", "napcat/config").all { byName[it]?.kind == "directory" }) { "便携备份数据目录缺失" }
        var bytes = 0L
        entries.forEach { entry ->
            val parts = entry.name.split('/')
            require(entry.name.length in 1..1024 && parts.all { it.isNotEmpty() && it !in setOf(".", "..") } &&
                entry.name.none { it == '\\' || it.code < 32 } && allowed(entry.name)) { "便携备份包含越界或不支持的路径" }
            require(entry.name != "napcat" || entry.kind == "directory") { "NapCat 配置父目录异常" }
            for (end in 1 until parts.size) require(byName[parts.take(end).joinToString("/")]?.kind == "directory") { "便携备份包含链接父目录或缺失目录" }
            when (entry.kind) {
                "file" -> {
                    require(entry.bytes in 0..MAX_FILE && entry.sha256.matches(Regex("[0-9a-f]{64}")) && entry.target.isEmpty()) { "便携备份文件大小或摘要异常" }
                    bytes += entry.bytes
                }
                "directory" -> require(entry.bytes == 0L && entry.sha256.isEmpty() && entry.target.isEmpty() && !entry.executable) { "便携备份目录记录异常" }
                "symlink" -> {
                    require(entry.bytes == 0L && entry.sha256.isEmpty() && !entry.executable) { "便携备份链接记录异常" }
                    require(entry.target.isNotEmpty() && entry.target.length <= 1024 && !entry.target.startsWith('/') && entry.target.none { it == '\\' || it.code < 32 }) { "便携备份不支持绝对或异常链接" }
                    val resolved = Paths.get(entry.name).parent.resolve(entry.target).normalize().toString()
                    require(allowed(resolved) && resolved != "napcat") { "便携备份链接指向 QQ 会话或备份范围之外" }
                }
                else -> error("便携备份文件类型不支持")
            }
        }
        require(bytes <= MAX_UNPACKED) { "便携备份解包数据超过 8 GiB 上限" }
    }

    private suspend fun scan(root: File): List<Entry> {
        require(!Files.isSymbolicLink(root.toPath())) { "便携备份根目录不能为链接" }
        val coroutine = currentCoroutineContext()
        val entries = mutableListOf(Entry("napcat", "directory"))
        var totalBytes = 0L
        val base = root.toPath()
        val napcat = File(root, "napcat")
        require(!Files.isSymbolicLink(napcat.toPath()) && (!napcat.exists() || napcat.isDirectory)) { "NapCat 配置父目录异常" }
        SCOPES.forEach { scope ->
            val path = File(root, scope).toPath()
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) entries += Entry(scope, "directory")
            else {
                require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { "便携备份范围根目录必须为实际目录" }
                Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        add(Entry(base.relativize(dir).toString(), "directory")); return FileVisitResult.CONTINUE
                    }
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val name = base.relativize(file).toString()
                        when {
                            attrs.isRegularFile -> {
                                require(attrs.size() <= MAX_FILE) { "便携备份单文件超过 2 GiB 上限" }
                                totalBytes += attrs.size()
                                require(totalBytes <= MAX_UNPACKED) { "便携备份解包数据超过 8 GiB 上限" }
                                val hash = file.toFile().inputStream().use { copyBlocking(it, null, attrs.size(), coroutine) }
                                add(Entry(name, "file", attrs.size(), hash, Os.lstat(file.toString()).st_mode and 64 != 0))
                            }
                            attrs.isSymbolicLink -> add(Entry(name, "symlink", target = Files.readSymbolicLink(file).toString()))
                            else -> error("便携备份中存在不支持的特殊文件")
                        }
                        return FileVisitResult.CONTINUE
                    }
                    private fun add(entry: Entry) {
                        coroutine.ensureActive()
                        require(entries.size < MAX_ENTRIES) { "便携备份最多支持 50000 个文件项" }
                        entries += entry
                    }
                })
            }
        }
        val sorted = entries.sortedBy { it.name }
        validate(sorted)
        // Links are validated lexically above and against the actual tree without reading targets.
        sorted.filter { it.kind == "symlink" }.forEach {
            val resolved = File(root, it.name).canonicalFile.toPath()
            val canonicalRoot = root.canonicalFile.toPath()
            require(resolved.startsWith(canonicalRoot) && allowed(canonicalRoot.relativize(resolved).toString())) { "便携备份链接目标超出数据范围" }
        }
        return sorted
    }

    private suspend fun copy(input: InputStream, output: OutputStream?, expected: Long): String =
        copyBlocking(input, output, expected, currentCoroutineContext())

    private fun copyBlocking(input: InputStream, output: OutputStream?, expected: Long, coroutine: kotlin.coroutines.CoroutineContext): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        var size = 0L
        while (true) {
            coroutine.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            size += count
            require(size <= expected) { "便携备份文件大小不符" }
            output?.write(buffer, 0, count); hash.update(buffer, 0, count)
        }
        require(size == expected) { "便携备份文件缺失或被截断" }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun limited(input: InputStream, max: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= max) { "便携备份清单超出范围" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun info(manifest: JSONObject): PortableBackupInfo {
        val entries = manifest.getJSONArray("entries")
        val versions = manifest.getJSONObject("versions")
        return PortableBackupInfo(manifest.getString("id"), manifest.getLong("createdAt"),
            (0 until entries.length()).sumOf { entries.getJSONObject(it).getLong("bytes") }, entries.length(),
            versions.keys().asSequence().filter { it != "compatibilityKey" }.associateWith(versions::getString))
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun validateContainer(file: File, expectedEntries: Int) {
        // Our limits fit ordinary single-disk ZIP. Check its closing directory before extraction;
        // streaming readers alone otherwise accept a ZIP truncated after the last local entry.
        RandomAccessFile(file, "r").use { input ->
            val size = input.length()
            val start = (size - 65_557).coerceAtLeast(0)
            val tail = ByteArray((size - start).toInt())
            input.seek(start); input.readFully(tail)
            fun short(at: Int) = (tail[at].toInt() and 255) or ((tail[at + 1].toInt() and 255) shl 8)
            fun long(at: Int) = short(at).toLong() or (short(at + 2).toLong() shl 16)
            val end = (tail.size - 22 downTo 0).firstOrNull { at ->
                long(at) == 0x06054b50L && at + 22 + short(at + 20) == tail.size
            }
            require(end != null) { "便携备份 ZIP 目录被截断或不完整" }
            require(short(end + 4) == 0 && short(end + 6) == 0 && short(end + 8) == expectedEntries && short(end + 10) == expectedEntries) {
                "便携备份 ZIP 目录数量或分卷格式异常"
            }
            val bytes = long(end + 12)
            val offset = long(end + 16)
            require(bytes >= expectedEntries * 46L && offset > 0 && offset + bytes == start + end) { "便携备份 ZIP 目录范围异常" }
            input.seek(offset)
            require(input.read() == 0x50 && input.read() == 0x4b && input.read() == 0x01 && input.read() == 0x02) { "便携备份 ZIP 目录损坏" }
        }
    }

    private fun allowed(name: String) = name == "napcat" || SCOPES.any { name == it || name.startsWith("$it/") }
    companion object {
        val SCOPES = listOf("astrbot", "napcat/config")
        const val MAX_ARCHIVE = 2L * 1024 * 1024 * 1024
        private const val MAX_UNPACKED = 8L * 1024 * 1024 * 1024
        private const val MAX_FILE = 2L * 1024 * 1024 * 1024
        private const val MAX_MANIFEST = 16 * 1024 * 1024
        private const val MAX_ENTRIES = 50_000
        private const val RESERVE = 64L * 1024 * 1024
        private const val FORMAT = "astrbot-android-portable"
    }
}
