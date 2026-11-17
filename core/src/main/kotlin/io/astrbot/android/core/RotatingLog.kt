package io.astrbot.android.core

import java.io.File
import java.io.Writer
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.ConcurrentHashMap

/** Capacity is enforced at write time; exports can be redacted again by the caller. */
class RotatingLog(private val directory: File, private val name: String,
                  private val maxBytes: Long = 1024 * 1024, private val archives: Int = 2) {
    private val lock = locks.computeIfAbsent(File(directory, name).absolutePath) { Any() }
    init { require(maxBytes >= 256 && archives in 1..10) }
    fun append(line: String): Unit = synchronized(lock) {
        directory.mkdirs()
        val file = File(directory, "$name.log")
        val bytes = (SecretRedactor.redact(line).take(16_000) + "\n").toByteArray(Charsets.UTF_8)
        val bounded = if (bytes.size > maxBytes) "[日志行超出容量，已省略]\n".toByteArray() else bytes
        if (file.length() + bounded.size > maxBytes) {
            File(directory, "$name.$archives.log").delete()
            for (index in archives - 1 downTo 1) {
                val old = File(directory, "$name.$index.log")
                if (old.exists()) check(old.renameTo(File(directory, "$name.${index + 1}.log")))
            }
            if (file.exists()) check(file.renameTo(File(directory, "$name.1.log")))
        }
        file.appendBytes(bounded)
    }
    fun tail(lines: Int = 160): String = synchronized(lock) {
        require(lines in 1..1000)
        val file = File(directory, "$name.log")
        if (!file.exists()) return@synchronized "暂无日志"
        val tail = ArrayDeque<String>()
        file.useLines { source -> source.forEach { line -> tail.addLast(line); if (tail.size > lines) tail.removeFirst() } }
        // Existing files may predate normalization or the current redaction rules.
        SecretRedactor.redact(tail.joinToString("\n"))
    }

    /** Writers open on every append. A shared lock also covers different instances of this log. */
    fun clear(): Unit = synchronized(lock) {
        check(!Files.isSymbolicLink(directory.toPath()))
        (0..10).forEach { index ->
            val file = File(directory, if (index == 0) "$name.log" else "$name.$index.log")
            check(!Files.isSymbolicLink(file.toPath()))
            if (file.exists()) check(file.delete())
        }
    }

    /** Oldest archive first; reapply redaction to files produced by an older application. */
    fun exportTo(output: Writer, maxFileBytes: Int? = null): Unit = synchronized(lock) {
        require(maxFileBytes == null || maxFileBytes in 256..8 * 1024 * 1024)
        if (Files.isSymbolicLink(directory.toPath())) {
            output.write("[日志目录为链接，已省略]\n"); return@synchronized
        }
        ((archives downTo 1).map { "$name.$it.log" } + "$name.log").forEach { filename ->
            val file = File(directory, filename)
            if (Files.isSymbolicLink(file.toPath())) output.write("[日志文件为链接，已省略]\n")
            else if (Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
                    if (maxFileBytes == null) input.bufferedReader().useLines { lines ->
                        lines.forEach { output.write(SecretRedactor.redact(it) + "\n") }
                    } else {
                        val offset = (file.length() - maxFileBytes).coerceAtLeast(0)
                        var remaining = offset
                        while (remaining > 0) {
                            val skipped = input.skip(remaining)
                            check(skipped > 0) { "日志截取期间文件发生变化" }
                            remaining -= skipped
                        }
                        val bytes = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (bytes.size() <= maxFileBytes) {
                            val count = input.read(buffer, 0, minOf(buffer.size, maxFileBytes + 1 - bytes.size()))
                            if (count < 0) break
                            bytes.write(buffer, 0, count)
                        }
                        val truncated = bytes.size() > maxFileBytes
                        val text = bytes.toByteArray().let { it.copyOf(minOf(it.size, maxFileBytes)).toString(Charsets.UTF_8) }
                        // Never export a partial line: its credential label might be outside the cut.
                        var complete = if (truncated) text.substringBeforeLast('\n', "") else text
                        if (offset > 0) {
                            // Discard a line cut at the beginning, including any unlabelled secret tail.
                            complete = complete.substringAfter('\n', "")
                            output.write("[较早日志超出诊断采集上限，已省略]\n")
                        }
                        complete.lineSequence().forEach { line -> output.write(
                            if (line.length > 16_000) "[日志行超出长度，已省略]\n" else SecretRedactor.redact(line) + "\n") }
                        if (truncated) output.write("[日志文件超出诊断采集上限，剩余内容已省略]\n")
                    }
                }
            }
        }
    }
    companion object { private val locks = ConcurrentHashMap<String, Any>() }
}
