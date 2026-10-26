package io.astrbot.android.runtime

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID

/** Only a small opaque token goes into saved Activity state, never a multi-megabyte payload. */
internal class WebExportCache(
    private val directory: File,
    private val maxPayload: Long = 8L * 1024 * 1024,
    private val maxTotal: Long = 32L * 1024 * 1024,
    private val reserveBytes: Long = 4L * 1024 * 1024,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun stage(bytes: ByteArray): String = synchronized(lock) {
        check(bytes.size.toLong() <= maxPayload) { "Export payload is too large" }
        check(!Files.isSymbolicLink(directory.toPath())) { "Invalid export cache" }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create export cache" }
        pruneLocked(null)
        val used = ownedFiles().sumOf { it.length() }
        check(used <= maxTotal && bytes.size.toLong() <= maxTotal - used) { "Export cache is full" }
        val available = directory.usableSpace
        check(available >= reserveBytes && bytes.size.toLong() <= available - reserveBytes) { "Insufficient export storage" }
        val token = UUID.randomUUID().toString()
        val partial = File(directory, "$token.part")
        val complete = File(directory, "$token.bin")
        check(partial.createNewFile()) { "Cannot stage export" }
        try {
            FileOutputStream(partial).use { output -> output.write(bytes); output.fd.sync() }
            check(partial.renameTo(complete)) { "Cannot commit export" }
            token
        } finally { partial.delete() }
    }

    /** Validate before opening the destination, so eviction cannot look like a successful empty export. */
    fun writeTo(token: String, destination: () -> OutputStream) {
        val sourceStream = synchronized(lock) {
            val source = checkNotNull(payload(token)) { "Export content is no longer available" }
            check(source.length() <= maxPayload) { "Invalid export length" }
            source.inputStream()
        }
        // A slow document provider must not hold the cache lock while writing.
        sourceStream.use { input ->
            destination().use { output ->
                val buffer = ByteArray(64 * 1024)
                var count = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    count += n
                    check(count <= maxPayload) { "Invalid export length" }
                    output.write(buffer, 0, n)
                }
            }
        }
    }

    fun discard(token: String?) = synchronized(lock) { payload(token)?.delete(); Unit }

    /** Prune only our regular files; a picker restored by this Activity retains its payload. */
    fun prune(retainToken: String? = null) = synchronized(lock) { pruneLocked(retainToken) }

    private fun pruneLocked(retainToken: String?) {
        val cutoff = now() - 24L * 60 * 60 * 1000
        ownedFiles().filter { it.name != "$retainToken.bin" && it.lastModified() < cutoff }.forEach { it.delete() }
    }

    private fun ownedFiles(): List<File> {
        if (Files.isSymbolicLink(directory.toPath())) return emptyList()
        return directory.listFiles()?.filter { ownedName.matches(it.name) && Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) }.orEmpty()
    }

    private fun payload(token: String?): File? {
        if (token == null || !tokenPattern.matches(token) || Files.isSymbolicLink(directory.toPath())) return null
        return File(directory, "$token.bin").takeIf { Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) }
    }

    companion object {
        private val lock = Any()
        private val tokenPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val ownedName = Regex("${tokenPattern.pattern}\\.(bin|part)")
    }
}
