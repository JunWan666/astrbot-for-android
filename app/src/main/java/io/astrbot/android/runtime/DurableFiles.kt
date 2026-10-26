package io.astrbot.android.runtime

import io.astrbot.android.checkLocalized
import io.astrbot.android.requireLocalized

import io.astrbot.android.AppText
import io.astrbot.android.R

import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

internal object DurableFiles {
    fun write(file: File, value: JSONObject) {
        file.parentFile!!.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
        sync(file.parentFile!!)
    }
    fun read(file: File): JSONObject? = if (exists(file))
        AtomicFile(file).openRead().bufferedReader().use { JSONObject(it.readText()) } else null
    fun exists(file: File) = file.exists() || File(file.path + ".bak").exists()
    fun removeRecord(file: File) { AtomicFile(file).delete(); sync(file.parentFile!!) }
    fun sync(directory: File) {
        val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }
    fun move(source: File, destination: File) {
        destination.parentFile!!.mkdirs()
        checkLocalized(!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS) && source.renameTo(destination)) { AppText.get(R.string.ui_cannot_switch_program_directories_recovery_records_are_preserved) }
        sync(source.parentFile!!)
        if (source.parentFile != destination.parentFile) sync(destination.parentFile!!)
    }
    fun delete(directory: File, writableGuestDirectories: Boolean = false) {
        if (!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (writableGuestDirectories) Os.chmod(dir.toString(), Os.lstat(dir.toString()).st_mode or 448)
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                // PRoot creates mode-000 mount placeholders. Only a stopped, owned guest
                // or a backup approved for deletion may repair directories. Never follow links
                // or change surviving backups.
                val mode = if (writableGuestDirectories) Os.lstat(file.toString()).st_mode else 0
                if (!OsConstants.S_ISDIR(mode) || mode and 448 == 448) throw exc
                Os.chmod(file.toString(), mode or 448)
                Files.walkFileTree(file, this)
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir); return FileVisitResult.CONTINUE
            }
        })
    }
    suspend fun copyProgram(source: File, destination: File) {
        val coroutine = currentCoroutineContext()
        check(source.isDirectory && !Files.isSymbolicLink(source.toPath()) && !destination.exists())
        val base = source.toPath()
        Files.walkFileTree(base, object : SimpleFileVisitor<Path>() {
            fun target(path: Path) = destination.toPath().resolve(base.relativize(path))
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                coroutine.ensureActive(); Files.createDirectory(target(dir)); return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                coroutine.ensureActive()
                requireLocalized(attrs.isRegularFile && !attrs.isSymbolicLink) { AppText.get(R.string.ui_the_program_contains_unsupported_files_the_old_version_is) }
                FileOutputStream(target(file).toFile()).use { output -> file.toFile().inputStream().use { it.copyTo(output) }; output.fd.sync() }
                Os.chmod(target(file).toString(), 420)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) throw exc
                sync(target(dir).toFile()); return FileVisitResult.CONTINUE
            }
        })
    }
}
