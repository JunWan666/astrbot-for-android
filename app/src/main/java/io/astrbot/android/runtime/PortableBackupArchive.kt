package io.astrbot.android.runtime

import io.astrbot.android.checkLocalized
import io.astrbot.android.requireLocalized

import io.astrbot.android.AppText
import io.astrbot.android.R

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
        requireLocalized(bytes.size <= MAX_MANIFEST) { AppText.get(R.string.ui_portable_backup_manifest_is_too_large) }
        requireLocalized(destination.parentFile!!.usableSpace >= entries.sumOf { it.bytes }.coerceAtMost(MAX_ARCHIVE) + RESERVE) { AppText.get(R.string.ui_insufficient_portable_backup_staging_space) }
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
                        requireLocalized(written <= MAX_ARCHIVE) { AppText.get(R.string.ui_portable_backup_exceeds_the_2_gib_limit) }
                        if (written - spaceCheck >= 1024 * 1024) {
                            requireLocalized(destination.parentFile!!.usableSpace >= RESERVE) { AppText.get(R.string.ui_insufficient_portable_backup_staging_space) }
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
                            checkLocalized(copy(input, zip, entry.bytes) == entry.sha256) { AppText.get(R.string.ui_data_changed_during_portable_backup_no_backup_was_created) }
                        }
                        zip.closeEntry()
                    }
                    val complete = JSONObject().put("manifestSha256", digest(bytes)).put("entries", entries.size).toString().toByteArray()
                    zip.putNextEntry(ZipEntry("complete.json")); zip.write(complete); zip.closeEntry()
                    zip.finish(); zip.flush(); raw.fd.sync()
                }
            }
            checkLocalized(scan(source) == entries) { AppText.get(R.string.ui_data_changed_during_portable_backup_no_backup_was_created) }
            return info(manifest)
        } catch (failure: Throwable) { destination.delete(); throw failure }
    }

    suspend fun read(archive: File, destination: File, versions: RuntimeVersions): PortableBackupInfo {
        requireLocalized(archive.length() in 1..MAX_ARCHIVE) { AppText.get(R.string.ui_invalid_portable_backup_size_or_size_exceeds_2_gib) }
        checkLocalized(!destination.exists()) { AppText.get(R.string.ui_portable_backup_staging_directory_already_exists) }
        try {
            ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
                requireLocalized(zip.nextEntry?.name == "manifest.json") { AppText.get(R.string.ui_this_portable_backup_is_not_supported_by_this_app) }
                val bytes = limited(zip, MAX_MANIFEST)
                val manifest = JSONObject(bytes.toString(Charsets.UTF_8))
                val entries = parse(manifest, versions)
                validateContainer(archive, entries.count { it.kind == "file" } + 2)
                val required = entries.sumOf { it.bytes } + RESERVE
                requireLocalized(destination.parentFile!!.usableSpace >= required) { AppText.get(R.string.ui_insufficient_space_to_extract_the_portable_backup) }
                check(destination.mkdirs())
                entries.filter { it.kind == "directory" }.sortedBy { it.name.count { c -> c == '/' } }.forEach {
                    check(File(destination, it.name).mkdir() || File(destination, it.name).isDirectory)
                    Os.chmod(File(destination, it.name).path, 448)
                }
                entries.filter { it.kind == "file" }.forEach { entry ->
                    currentCoroutineContext().ensureActive()
                    requireLocalized(zip.nextEntry?.name == "data/${entry.name}") { AppText.get(R.string.ui_portable_backup_entries_are_missing_duplicated_or_out_of) }
                    val file = File(destination, entry.name)
                    FileOutputStream(file).use { output ->
                        requireLocalized(copy(zip, output, entry.bytes) == entry.sha256) { AppText.get(R.string.ui_portable_backup_file_digest_mismatch_existing_data_was_not) }
                        output.fd.sync()
                    }
                    Os.chmod(file.path, if (entry.executable) 448 else 384)
                }
                requireLocalized(zip.nextEntry?.name == "complete.json") { AppText.get(R.string.ui_portable_backup_is_incomplete) }
                val complete = JSONObject(limited(zip, 1024).toString(Charsets.UTF_8))
                requireLocalized(complete.getString("manifestSha256") == digest(bytes) && complete.getInt("entries") == entries.size) { AppText.get(R.string.ui_portable_backup_completion_record_mismatch) }
                requireLocalized(zip.nextEntry == null) { AppText.get(R.string.ui_portable_backup_contains_undeclared_files) }
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
        if (exclusive) requireLocalized(tree.list()?.toSet() == setOf("astrbot", "napcat") && File(tree, "napcat").list()?.toSet() == setOf("config")) {
            AppText.get(R.string.ui_portable_backup_staging_contains_data_outside_its_scope_import)
        }
        requireLocalized(scan(tree) == expected) { AppText.get(R.string.ui_portable_backup_staged_files_have_changed_import_it_again) }
        return info(manifest)
    }

    private fun parse(manifest: JSONObject, versions: RuntimeVersions): List<Entry> {
        requireLocalized(manifest.getInt("schema") == 1 && manifest.getString("format") == FORMAT) { AppText.get(R.string.ui_unsupported_portable_backup_format) }
        requireLocalized(UUID.fromString(manifest.getString("id")).toString() == manifest.getString("id") && manifest.getLong("createdAt") >= 0) { AppText.get(R.string.ui_invalid_portable_backup_id) }
        requireLocalized(!manifest.getBoolean("qqSessionsIncluded") && manifest.getJSONArray("scopes").let {
            it.length() == SCOPES.size && (0 until it.length()).map(it::getString) == SCOPES
        }) { AppText.get(R.string.ui_unsupported_portable_backup_data_scope) }
        requireLocalized(manifest.getJSONObject("versions").getString("compatibilityKey") == versions.compatibilityKey) {
            AppText.get(R.string.ui_portable_backup_does_not_match_installed_program_versions_install)
        }
        val array = manifest.getJSONArray("entries")
        requireLocalized(array.length() in 3..MAX_ENTRIES) { AppText.get(R.string.ui_too_many_portable_backup_files) }
        val entries = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            Entry(item.getString("name"), item.getString("kind"), item.getLong("bytes"), item.getString("sha256"),
                item.getBoolean("executable"), item.getString("target"))
        }
        validate(entries)
        return entries
    }

    private fun validate(entries: List<Entry>) {
        requireLocalized(entries.size <= MAX_ENTRIES && entries == entries.sortedBy { it.name } && entries.map { it.name }.toSet().size == entries.size) { AppText.get(R.string.ui_portable_backup_file_paths_are_duplicated_or_out_of) }
        val byName = entries.associateBy { it.name }
        requireLocalized(listOf("astrbot", "napcat", "napcat/config").all { byName[it]?.kind == "directory" }) { AppText.get(R.string.ui_portable_backup_data_directory_is_missing) }
        var bytes = 0L
        entries.forEach { entry ->
            val parts = entry.name.split('/')
            requireLocalized(entry.name.length in 1..1024 && parts.all { it.isNotEmpty() && it !in setOf(".", "..") } &&
                entry.name.none { it == '\\' || it.code < 32 } && allowed(entry.name)) { AppText.get(R.string.ui_portable_backup_contains_paths_outside_its_scope_or_unsupported) }
            requireLocalized(entry.name != "napcat" || entry.kind == "directory") { AppText.get(R.string.ui_unexpected_napcat_configuration_parent_directory) }
            for (end in 1 until parts.size) requireLocalized(byName[parts.take(end).joinToString("/")]?.kind == "directory") { AppText.get(R.string.ui_portable_backup_contains_linked_parent_directories_or_missing_directorie) }
            when (entry.kind) {
                "file" -> {
                    requireLocalized(entry.bytes in 0..MAX_FILE && entry.sha256.matches(Regex("[0-9a-f]{64}")) && entry.target.isEmpty()) { AppText.get(R.string.ui_invalid_portable_backup_file_size_or_digest) }
                    bytes += entry.bytes
                }
                "directory" -> requireLocalized(entry.bytes == 0L && entry.sha256.isEmpty() && entry.target.isEmpty() && !entry.executable) { AppText.get(R.string.ui_invalid_portable_backup_directory_record) }
                "symlink" -> {
                    requireLocalized(entry.bytes == 0L && entry.sha256.isEmpty() && !entry.executable) { AppText.get(R.string.ui_invalid_portable_backup_link_record) }
                    requireLocalized(entry.target.isNotEmpty() && entry.target.length <= 1024 && !entry.target.startsWith('/') && entry.target.none { it == '\\' || it.code < 32 }) { AppText.get(R.string.ui_portable_backup_contains_absolute_or_invalid_links) }
                    val resolved = Paths.get(entry.name).parent.resolve(entry.target).normalize().toString()
                    requireLocalized(allowed(resolved) && resolved != "napcat") { AppText.get(R.string.ui_portable_backup_link_targets_a_qq_session_or_a) }
                }
                else -> error(AppText.get(R.string.ui_unsupported_portable_backup_file_type))
            }
        }
        requireLocalized(bytes <= MAX_UNPACKED) { AppText.get(R.string.ui_extracted_portable_backup_exceeds_the_8_gib_limit) }
    }

    private suspend fun scan(root: File): List<Entry> {
        requireLocalized(!Files.isSymbolicLink(root.toPath())) { AppText.get(R.string.ui_portable_backup_root_cannot_be_a_link) }
        val coroutine = currentCoroutineContext()
        val entries = mutableListOf(Entry("napcat", "directory"))
        var totalBytes = 0L
        val base = root.toPath()
        val napcat = File(root, "napcat")
        requireLocalized(!Files.isSymbolicLink(napcat.toPath()) && (!napcat.exists() || napcat.isDirectory)) { AppText.get(R.string.ui_unexpected_napcat_configuration_parent_directory) }
        SCOPES.forEach { scope ->
            val path = File(root, scope).toPath()
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) entries += Entry(scope, "directory")
            else {
                requireLocalized(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { AppText.get(R.string.ui_portable_backup_scope_roots_must_be_real_directories) }
                Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        add(Entry(base.relativize(dir).toString(), "directory")); return FileVisitResult.CONTINUE
                    }
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val name = base.relativize(file).toString()
                        when {
                            attrs.isRegularFile -> {
                                requireLocalized(attrs.size() <= MAX_FILE) { AppText.get(R.string.ui_portable_backup_file_exceeds_the_2_gib_limit) }
                                totalBytes += attrs.size()
                                requireLocalized(totalBytes <= MAX_UNPACKED) { AppText.get(R.string.ui_extracted_portable_backup_exceeds_the_8_gib_limit) }
                                val hash = file.toFile().inputStream().use { copyBlocking(it, null, attrs.size(), coroutine) }
                                add(Entry(name, "file", attrs.size(), hash, Os.lstat(file.toString()).st_mode and 64 != 0))
                            }
                            attrs.isSymbolicLink -> add(Entry(name, "symlink", target = Files.readSymbolicLink(file).toString()))
                            else -> error(AppText.get(R.string.ui_portable_backup_contains_unsupported_special_files))
                        }
                        return FileVisitResult.CONTINUE
                    }
                    private fun add(entry: Entry) {
                        coroutine.ensureActive()
                        requireLocalized(entries.size < MAX_ENTRIES) { AppText.get(R.string.ui_portable_backup_supports_up_to_50_000_entries) }
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
            requireLocalized(resolved.startsWith(canonicalRoot) && allowed(canonicalRoot.relativize(resolved).toString())) { AppText.get(R.string.ui_portable_backup_link_target_is_outside_its_data_scope) }
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
            requireLocalized(size <= expected) { AppText.get(R.string.ui_portable_backup_file_size_mismatch) }
            output?.write(buffer, 0, count); hash.update(buffer, 0, count)
        }
        requireLocalized(size == expected) { AppText.get(R.string.ui_portable_backup_file_is_missing_or_truncated) }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun limited(input: InputStream, max: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            requireLocalized(output.size() + count <= max) { AppText.get(R.string.ui_portable_backup_manifest_is_outside_supported_limits) }
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
            requireLocalized(end != null) { AppText.get(R.string.ui_portable_backup_zip_directory_is_truncated_or_incomplete) }
            requireLocalized(short(end + 4) == 0 && short(end + 6) == 0 && short(end + 8) == expectedEntries && short(end + 10) == expectedEntries) {
                AppText.get(R.string.ui_invalid_portable_backup_zip_directory_count_or_split_archive)
            }
            val bytes = long(end + 12)
            val offset = long(end + 16)
            requireLocalized(bytes >= expectedEntries * 46L && offset > 0 && offset + bytes == start + end) { AppText.get(R.string.ui_invalid_portable_backup_zip_directory_bounds) }
            input.seek(offset)
            requireLocalized(input.read() == 0x50 && input.read() == 0x4b && input.read() == 0x01 && input.read() == 0x02) { AppText.get(R.string.ui_portable_backup_zip_directory_is_damaged) }
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
