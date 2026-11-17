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
import java.util.zip.ZipOutputStream

data class PortableBackupInfo(val id: String, val createdAt: Long, val bytes: Long, val count: Int, val versions: Map<String, String>,
    val scopes: List<String> = PortableBackupArchive.SCOPES, val qqSessionsIncluded: Boolean = false)

/** Portable user data, compatible across devices. Version metadata is informational. */
internal class PortableBackupArchive {
    private data class Entry(val name: String, val kind: String, val bytes: Long = 0, val sha256: String = "",
                             val executable: Boolean = false, val target: String = "") {
        fun json() = JSONObject().put("name", name).put("kind", kind).put("bytes", bytes).put("sha256", sha256)
            .put("executable", executable).put("target", target)
    }

    suspend fun write(source: File, destination: File, versions: RuntimeVersions, selection: BackupSelection = BackupSelection(),
                      externalRoots: Map<String, File> = emptyMap()): PortableBackupInfo {
        // An absent optional QQ directory is not an instruction to erase another phone's session.
        val scopes = selection.scopes.filter { it !in BackupSelection.QQ_SCOPES || Files.exists(resolve(source, it, externalRoots).toPath(), LinkOption.NOFOLLOW_LINKS) }
        requireLocalized(scopes.isNotEmpty()) { AppText.get(R.string.backup_no_selected_data) }
        require(externalRoots.keys.all { it.startsWith(DesktopQqData.PREFIX) && it in BackupSelection.QQ_SCOPES })
        val entries = scan(source, scopes, externalRoots)
        val manifest = JSONObject().put("schema", if (scopes == SCOPES) 1 else 2).put("format", FORMAT).put("id", UUID.randomUUID().toString())
            .put("createdAt", System.currentTimeMillis()).put("versions", versions.json())
            .put("scopes", JSONArray(scopes)).put("qqSessionsIncluded", scopes.any { it in BackupSelection.QQ_SCOPES })
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
                        resolve(source, entry.name, externalRoots).inputStream().use { input ->
                            checkLocalized(copy(input, zip, entry.bytes) == entry.sha256) { AppText.get(R.string.ui_data_changed_during_portable_backup_no_backup_was_created) }
                        }
                        zip.closeEntry()
                    }
                    val complete = JSONObject().put("manifestSha256", digest(bytes)).put("entries", entries.size).toString().toByteArray()
                    zip.putNextEntry(ZipEntry("complete.json")); zip.write(complete); zip.closeEntry()
                    zip.finish(); zip.flush(); raw.fd.sync()
                }
            }
            checkLocalized(scan(source, scopes, externalRoots) == entries) { AppText.get(R.string.ui_data_changed_during_portable_backup_no_backup_was_created) }
            return info(manifest)
        } catch (failure: Throwable) { destination.delete(); throw failure }
    }

    suspend fun read(archive: File, destination: File, versions: RuntimeVersions): PortableBackupInfo {
        requireLocalized(archive.length() in 1..MAX_ARCHIVE) { AppText.get(R.string.ui_invalid_portable_backup_size_or_size_exceeds_2_gib) }
        checkLocalized(!destination.exists()) { AppText.get(R.string.ui_portable_backup_staging_directory_already_exists) }
        try {
            java.util.zip.ZipFile(archive).use { zip ->
                val container = zip.entries().asSequence().take(MAX_ENTRIES * 2 + 4).toList()
                require(container.size <= MAX_ENTRIES * 2 + 3)
                requireLocalized(container.map { it.name }.toSet().size == container.size) { AppText.get(R.string.ui_portable_backup_file_paths_are_duplicated_or_out_of) }
                val metadata = zip.getEntry("manifest.json")
                requireLocalized(metadata != null) { AppText.get(R.string.ui_this_portable_backup_is_not_supported_by_this_app) }
                val bytes = zip.getInputStream(metadata).use { limited(it, MAX_MANIFEST) }
                val manifest = JSONObject(bytes.toString(Charsets.UTF_8))
                val scopes = scopes(manifest)
                val entries = parse(manifest, versions)
                val expectedFiles = entries.filter { it.kind == "file" }.map { "data/${it.name}" }.toSet() + setOf("manifest.json", "complete.json")
                val optionalDirectories = entries.filter { it.kind == "directory" }.map { "data/${it.name}/" }.toSet() + "data/"
                requireLocalized(container.filterNot { it.isDirectory && it.name in optionalDirectories }.map { it.name }.toSet() == expectedFiles) {
                    AppText.get(R.string.ui_portable_backup_contains_undeclared_files)
                }
                val required = entries.sumOf { it.bytes } + RESERVE
                requireLocalized(destination.parentFile!!.usableSpace >= required) { AppText.get(R.string.ui_insufficient_space_to_extract_the_portable_backup) }
                check(destination.mkdirs())
                entries.filter { it.kind == "directory" }.sortedBy { it.name.count { c -> c == '/' } }.forEach {
                    check(File(destination, it.name).mkdir() || File(destination, it.name).isDirectory)
                    Os.chmod(File(destination, it.name).path, 448)
                }
                entries.filter { it.kind == "file" }.forEach { entry ->
                    currentCoroutineContext().ensureActive()
                    val file = File(destination, entry.name)
                    FileOutputStream(file).use { output ->
                        zip.getInputStream(zip.getEntry("data/${entry.name}")).use { input ->
                            requireLocalized(copy(input, output, entry.bytes) == entry.sha256) { AppText.get(R.string.ui_portable_backup_file_digest_mismatch_existing_data_was_not) }
                        }
                        output.fd.sync()
                    }
                    Os.chmod(file.path, if (entry.executable) 448 else 384)
                }
                val complete = zip.getInputStream(zip.getEntry("complete.json")).use { JSONObject(limited(it, 1024).toString(Charsets.UTF_8)) }
                requireLocalized(complete.getString("manifestSha256") == digest(bytes) && complete.getInt("entries") == entries.size) { AppText.get(R.string.ui_portable_backup_completion_record_mismatch) }
                entries.filter { it.kind == "symlink" }.forEach { entry -> Os.symlink(entry.target, File(destination, entry.name).path) }
                verify(destination, manifest, versions, exclusive = true)
                DurableFiles.write(File(destination.parentFile, "verified.json"), manifest)
                return info(manifest)
            }
        } catch (failure: Throwable) { DurableFiles.delete(destination); throw failure }
    }

    suspend fun verify(tree: File, manifest: JSONObject, versions: RuntimeVersions, exclusive: Boolean = false,
                       externalRoots: Map<String, File> = emptyMap()): PortableBackupInfo {
        val expected = parse(manifest, versions)
        val scopes = scopes(manifest)
        if (exclusive) {
            val declared = expected.map { it.name }.toSet()
            Files.walkFileTree(tree.toPath(), object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    require(dir == tree.toPath() || tree.toPath().relativize(dir).toString() in declared)
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    require(tree.toPath().relativize(file).toString() in declared)
                    return FileVisitResult.CONTINUE
                }
            })
        }
        requireLocalized(scan(tree, scopes, externalRoots) == expected) { AppText.get(R.string.ui_portable_backup_staged_files_have_changed_import_it_again) }
        return info(manifest)
    }

    private fun parse(manifest: JSONObject, versions: RuntimeVersions): List<Entry> {
        requireLocalized(manifest.getInt("schema") in 1..2 && manifest.getString("format") == FORMAT) { AppText.get(R.string.ui_unsupported_portable_backup_format) }
        requireLocalized(UUID.fromString(manifest.getString("id")).toString() == manifest.getString("id") && manifest.getLong("createdAt") >= 0) { AppText.get(R.string.ui_invalid_portable_backup_id) }
        val scopes = scopes(manifest)
        // Different App/component versions and ABIs must not block data migration.
        // Startup and SQLite checks remain inside the rollback-capable restore transaction.
        val array = manifest.getJSONArray("entries")
        requireLocalized(array.length() in 1..MAX_ENTRIES) { AppText.get(R.string.ui_too_many_portable_backup_files) }
        val entries = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            Entry(item.getString("name"), item.getString("kind"), item.getLong("bytes"), item.getString("sha256"),
                item.getBoolean("executable"), item.getString("target"))
        }
        validate(entries, scopes)
        return entries.sortedBy { it.name }
    }

    private fun validate(entries: List<Entry>, scopes: List<String>) {
        requireLocalized(entries.size <= MAX_ENTRIES && entries.map { it.name }.toSet().size == entries.size) { AppText.get(R.string.ui_portable_backup_file_paths_are_duplicated_or_out_of) }
        val byName = entries.associateBy { it.name }
        requireLocalized((scopes + parents(scopes)).all { byName[it]?.kind == "directory" }) { AppText.get(R.string.ui_portable_backup_data_directory_is_missing) }
        var bytes = 0L
        entries.forEach { entry ->
            val parts = entry.name.split('/')
            requireLocalized(entry.name.length in 1..1024 && parts.all { it.isNotEmpty() && it !in setOf(".", "..") } &&
                entry.name.none { it == '\\' || it.code < 32 } && allowed(entry.name, scopes)) { AppText.get(R.string.ui_portable_backup_contains_paths_outside_its_scope_or_unsupported) }
            requireLocalized(entry.name !in parents(scopes) || entry.kind == "directory") { AppText.get(R.string.backup_invalid_parent) }
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
                    requireLocalized(scopes.any { resolved == it || resolved.startsWith("$it/") }) { AppText.get(R.string.ui_portable_backup_link_targets_a_qq_session_or_a) }
                }
                else -> error(AppText.get(R.string.ui_unsupported_portable_backup_file_type))
            }
        }
        requireLocalized(bytes <= MAX_UNPACKED) { AppText.get(R.string.ui_extracted_portable_backup_exceeds_the_8_gib_limit) }
    }

    private fun resolve(root: File, name: String, externalRoots: Map<String, File>): File {
        val scope = externalRoots.keys.firstOrNull { name == it || name.startsWith("$it/") }
        return if (scope == null) File(root, name) else if (name == scope) externalRoots.getValue(scope)
            else File(externalRoots.getValue(scope), name.removePrefix("$scope/"))
    }

    private suspend fun scan(root: File, scopes: List<String>, externalRoots: Map<String, File> = emptyMap()): List<Entry> {
        requireLocalized(!Files.isSymbolicLink(root.toPath())) { AppText.get(R.string.ui_portable_backup_root_cannot_be_a_link) }
        val coroutine = currentCoroutineContext()
        val entries = parents(scopes).map { Entry(it, "directory") }.toMutableList()
        var totalBytes = 0L
        parents(scopes).filter { parent -> externalRoots.keys.none { it.startsWith("$parent/") } }
            .forEach { parent ->
                val file = File(root, parent)
                require(!Files.isSymbolicLink(file.toPath()) && (!file.exists() || file.isDirectory))
            }
        scopes.forEach { scope ->
            val path = resolve(root, scope, externalRoots).toPath()
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) entries += Entry(scope, "directory")
            else {
                requireLocalized(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { AppText.get(R.string.ui_portable_backup_scope_roots_must_be_real_directories) }
                Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        add(Entry(if (dir == path) scope else "$scope/${path.relativize(dir)}", "directory")); return FileVisitResult.CONTINUE
                    }
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val name = "$scope/${path.relativize(file)}"
                        if (scopes.any { scope -> scope in BackupSelection.QQ_SCOPES && name.startsWith("$scope/") } &&
                            (DesktopQqData.transient(file.fileName.toString()) || !attrs.isRegularFile && !attrs.isSymbolicLink)) return FileVisitResult.CONTINUE
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
        validate(sorted, scopes)
        // Links are validated lexically above and against the actual tree without reading targets.
        sorted.filter { it.kind == "symlink" }.forEach {
            val resolved = resolve(root, it.name, externalRoots).canonicalFile.toPath()
            requireLocalized(scopes.any { scope -> resolved.startsWith(resolve(root, scope, externalRoots).canonicalFile.toPath()) }) {
                AppText.get(R.string.ui_portable_backup_link_target_is_outside_its_data_scope)
            }
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
            versions.keys().asSequence().filter { it != "compatibilityKey" }.associateWith(versions::getString),
            scopes(manifest), manifest.optBoolean("qqSessionsIncluded"))
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    internal fun scopes(manifest: JSONObject): List<String> {
        val array = manifest.getJSONArray("scopes")
        val scopes = (0 until array.length()).map(array::getString)
        requireLocalized(scopes.isNotEmpty() && scopes.distinct().size == scopes.size && scopes.all { it in BackupSelection.ALL_SCOPES }) {
            AppText.get(R.string.ui_unsupported_portable_backup_data_scope)
        }
        requireLocalized(manifest.getBoolean("qqSessionsIncluded") == scopes.any { it in BackupSelection.QQ_SCOPES }) {
            AppText.get(R.string.ui_unsupported_portable_backup_data_scope)
        }
        return scopes
    }
    private fun parents(scopes: List<String>): List<String> = scopes.flatMap { scope ->
        val parts = scope.split('/')
        (1 until parts.size).map { parts.take(it).joinToString("/") }
    }.distinct().sorted()
    private fun allowed(name: String, scopes: List<String>) = name in parents(scopes) || scopes.any { name == it || name.startsWith("$it/") }
    companion object {
        val SCOPES = listOf("astrbot")
        const val MAX_ARCHIVE = 2L * 1024 * 1024 * 1024
        private const val MAX_UNPACKED = 8L * 1024 * 1024 * 1024
        private const val MAX_FILE = 2L * 1024 * 1024 * 1024
        private const val MAX_MANIFEST = 16 * 1024 * 1024
        private const val MAX_ENTRIES = 50_000
        private const val RESERVE = 64L * 1024 * 1024
        private const val FORMAT = "astrbot-android-portable"
    }
}
