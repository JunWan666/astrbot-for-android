package io.astrbot.android.runtime

import io.astrbot.android.checkLocalized
import io.astrbot.android.requireLocalized
import io.astrbot.android.LocalizedStateException

import io.astrbot.android.AppText
import io.astrbot.android.R

import android.content.Context
import android.os.Build
import android.system.Os
import io.astrbot.android.core.ArchivePaths
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

data class CommandResult(val exitCode: Int, val output: String)

/** The launch executables are installed from APK jniLibs, never chmod'd from writable assets. */
class BootstrapRuntime(private val context: Context) {
    val rootfs: File = File(context.filesDir, "runtime-data/ubuntu")
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    private val lock get() = installationLock
    val abi: String get() = if (nativeDir.path.contains("x86_64")) "x86_64" else Build.SUPPORTED_ABIS.first {
        it == "arm64-v8a" || it == "x86_64"
    }

    suspend fun install(progress: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        lock.withLock {
            val manifest = bundledManifest()
            val marker = File(rootfs, ".astrbot-bootstrap.json")
            if (marker.isFile) {
                val installed = JSONObject(marker.readText())
                // An APK replacement does not authorize replacing a user's working guest.
                requireLocalized(installed.getString("abi") == abi) { AppText.get(R.string.ui_the_installed_runtime_architecture_differs_from_this_apk_files) }
                progress(AppText.get(R.string.ui_ubuntu_runtime_is_ready))
                return@withLock
            }
            val staging = File(rootfs.parentFile, "ubuntu.installing")
            prepareAt(staging, manifest, progress)
            // Only an interrupted, uncommitted bootstrap can exist here.
            checkLocalized(!rootfs.exists()) { AppText.get(R.string.ui_unrecognized_existing_runtime_initialization_stopped_data_is_preserved) }
            DurableFiles.move(staging, rootfs)
            progress(AppText.get(R.string.ui_ubuntu_runtime_is_ready))
        }
    }

    internal fun bundledManifest(): JSONObject = context.assets.open("bootstrap/$abi/manifest.json").bufferedReader().use {
        JSONObject(it.readText())
    }

    internal suspend fun prepareBundled(staging: File, progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        lock.withLock { prepareAt(staging, bundledManifest(), progress) }
    }

    private suspend fun prepareAt(staging: File, manifest: JSONObject, progress: (String) -> Unit) {
            val assetBase = "bootstrap/$abi"
            requireLocalized(manifest.getString("abi") == abi) { AppText.get(R.string.ui_bundled_runtime_architecture_mismatch) }
            progress(AppText.get(R.string.ui_verifying_bundled_ubuntu_resources))
            val digest = MessageDigest.getInstance("SHA-256")
            var bytesRead = 0L
            context.assets.open("$assetBase/ubuntu.tar.bin").use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    bytesRead += count
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            val expected = manifest.getString("sha256")
            requireLocalized(actual == expected) {
                // Keep checksum details in exported runtime logs, with a short recovery hint in the UI.
                runCatching {
                    RuntimeStore.log(context, LogCategory.RUNTIME).append(
                        "${java.time.Instant.now()} Ubuntu asset integrity mismatch: " +
                            "asset=$assetBase/ubuntu.tar.bin abi=$abi bytesRead=$bytesRead " +
                            "expectedSha256=$expected actualSha256=$actual"
                    )
                }
                AppText.get(R.string.ui_ubuntu_resource_integrity_check_failed)
            }
            requireLocalized(manifest.getLong("unpackedBytes") in 1..(12L * 1024 * 1024 * 1024)) { AppText.get(R.string.ui_resource_size_exceeds_the_supported_limit) }
            RuntimeStorageAdvice.fromManifest(manifest, context.filesDir.usableSpace)?.takeIf { it.shouldWarn }?.let {
                progress(it.message)
            }
            DurableFiles.delete(staging, writableGuestDirectories = true)
            checkLocalized(staging.mkdirs()) { AppText.get(R.string.ui_cannot_create_runtime_staging_directory) }
            try {
                progress(AppText.get(R.string.ui_extracting_ubuntu_runtime))
                extract(assetBase, staging, manifest.getLong("unpackedBytes"), progress)
                listOf("root", "tmp", "proc", "sys", "dev", "run", "var/tmp", "var/log", "var/cache", "persistent").forEach { File(staging, it).mkdirs() }
                Os.chmod(File(staging, "tmp").path, 1023)
                Os.chmod(File(staging, "var/tmp").path, 1023)
                File(staging, "etc/resolv.conf").writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
                progress(AppText.get(R.string.ui_verifying_extracted_runtime))
                val verification = if (manifest.optString("kind") == "complete") {
                    "test -x /opt/qq/qq && test -f /opt/astrbot/main.py && " +
                        "/opt/astrbot-venv/bin/python -c 'import quart, fastapi, faiss; print(\"runtime-ok\")'"
                } else "test -f /etc/os-release && /bin/echo bootstrap-ok"
                val check = runCommand(processBuilderAt(staging, "/bin/sh", "-lc", verification), 180)
                requireLocalized(check.exitCode == 0) { AppText.get(R.string.ui_runtime_verification_failed, check.output.takeLast(3000)) }
                DurableFiles.write(File(staging, ".astrbot-bootstrap.json"), manifest)
            } catch (failure: Throwable) {
                DurableFiles.delete(staging, writableGuestDirectories = true)
                throw failure
            }
    }

    private suspend fun extract(assetBase: String, staging: File, expectedBytes: Long, progress: (String) -> Unit) {
        data class Link(val name: String, val target: String, val hard: Boolean)
        val links = mutableListOf<Link>()
        val root = staging.canonicalFile.toPath()
        var written = 0L
        var entries = 0
        context.assets.open("$assetBase/ubuntu.tar.bin").use { input ->
            TarArchiveInputStream(GZIPInputStream(input)).use { archive ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = archive.nextEntry ?: break
                    entries++
                    if (entries % 500 == 0) progress(AppText.get(R.string.ui_extracting_ubuntu_entries, entries))
                    val path = ArchivePaths.entry(root, entry.name, allowUnixEscapes = true)
                    when {
                        entry.isSymbolicLink || entry.isLink -> links += Link(entry.name, entry.linkName, entry.isLink)
                        entry.isDirectory -> check(path.toFile().isDirectory || path.toFile().mkdirs())
                        entry.isFile -> {
                            requireLocalized(entry.size >= 0 && entry.size <= 1024L * 1024 * 1024) { AppText.get(R.string.ui_invalid_resource_file_size) }
                            written += entry.size
                            requireLocalized(written <= expectedBytes + 1024 * 1024) { AppText.get(R.string.ui_invalid_extracted_resource_size) }
                            path.parent.toFile().mkdirs()
                            path.toFile().outputStream().use { archive.copyTo(it) }
                            Os.chmod(path.toString(), entry.mode and 511)
                        }
                        else -> Unit // Device nodes are provided through Android /dev bindings.
                    }
                }
            }
        }
        progress(AppText.get(R.string.ui_preparing_ubuntu_links_entries, links.size))
        links.filter { it.hard }.forEach {
            val path = ArchivePaths.entry(root, it.name, allowUnixEscapes = true)
            val target = ArchivePaths.entry(root, it.target, allowUnixEscapes = true)
            requireLocalized(target.toFile().canonicalFile.toPath().startsWith(root)) { AppText.get(R.string.ui_hard_link_outside_permitted_paths, it.name, it.target) }
            path.parent.toFile().mkdirs()
            // Android app SELinux policy can forbid hard links, even inside the app's data.
            target.toFile().copyTo(path.toFile(), overwrite = false)
            Os.chmod(path.toString(), Os.stat(target.toString()).st_mode and 511)
        }
        links.filterNot { it.hard }.forEach {
            val path = ArchivePaths.entry(root, it.name, allowUnixEscapes = true)
            // A previously created link must not redirect creation outside staging.
            requireLocalized(path.parent.toFile().canonicalFile.toPath().startsWith(root)) { AppText.get(R.string.ui_symbolic_link_parent_outside_permitted_paths, it.name) }
            val target = ArchivePaths.link(root, path, it.target, allowUnixEscapes = true)
            path.parent.toFile().mkdirs()
            try { Os.symlink(target.toString(), path.toString()) }
            catch (failure: android.system.ErrnoException) {
                throw LocalizedStateException(AppText.get(R.string.ui_cannot_prepare_link, it.name, it.target), failure)
            }
        }
    }

    fun processBuilder(vararg command: String): ProcessBuilder = processBuilderAt(rootfs, *command)

    fun terminalProcessBuilder(columns: Int, rows: Int, vararg command: String): ProcessBuilder = processBuilder(*command).apply {
        require(columns in 1..1000 && rows in 1..1000)
        val existing = command()
        command(listOf(existing.first(), File(nativeDir, "libterminal-runner.so").path, columns.toString(), rows.toString()) + existing.drop(1))
    }

    private fun processBuilderAt(guestRoot: File, vararg command: String): ProcessBuilder {
        val tmp = File(context.cacheDir, "proot-tmp").apply { mkdirs() }
        // Android has no /dev/shm; Electron needs a private writable shared-memory directory.
        val sharedMemory = File(context.cacheDir, "guest-shm").apply { mkdirs() }
        val persistent = File(context.filesDir, "persistent").apply { mkdirs() }
        val manager = File(context.filesDir, "manager").apply { mkdirs() }
        io.astrbot.android.device.DeviceBridge.prepareGuest(context)
        val sharedFiles = File(context.getExternalFilesDir(null) ?: context.filesDir, "shared").apply { mkdirs() }
        // procfs reports Android host paths even inside PRoot. Keep our own
        // mapped files readable by those paths as well as their guest paths.
        // Include both spellings: /data/user/0 can resolve to /data/data.
        val hostPathBindings = listOf(guestRoot, persistent, manager)
            .flatMap { listOf(it.absolutePath, it.canonicalPath) }
            .distinct()
            .flatMap { listOf("-b", "$it:$it") }
        return ProcessBuilder(listOf(
            File(nativeDir, "libprocess-runner.so").path,
            File(nativeDir, "libproot.so").path,
            "--kill-on-exit", "--link2symlink", "-0", "-r", guestRoot.path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "${sharedMemory.path}:/dev/shm",
            "-b", "${persistent.path}:/persistent", "-b", "${manager.path}:/manager", "-b", "${sharedFiles.path}:/android-share",
        ) + hostPathBindings + listOf(
            "-w", "/root",
            "/usr/bin/env", "-i", "HOME=/root", "USER=root", "TMPDIR=/tmp",
            "PATH=/manager/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", "ANDROID_SHARED_DIR=/android-share",
            "LANG=C.UTF-8", "TERM=xterm-256color", "SHELL=/bin/bash",
        ) + command).apply {
            directory(context.filesDir)
            redirectErrorStream(true)
            environment().apply {
                remove("LD_PRELOAD")
                put("LD_LIBRARY_PATH", nativeDir.path)
                put("PROOT_LOADER", File(nativeDir, "libproot-loader.so").path)
                put("PROOT_LOADER_32", File(nativeDir, "libproot-loader32.so").path)
                put("PROOT_TMP_DIR", tmp.path)
                put("TMPDIR", "/tmp")
                put("HOME", "/root")
                put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                put("LANG", "C.UTF-8")
                put("TERM", "xterm-256color")
            }
        }
    }

    suspend fun execute(vararg command: String, timeoutSeconds: Long = 30): CommandResult =
        runCommand(processBuilder(*command), timeoutSeconds)

    private suspend fun runCommand(builder: ProcessBuilder, timeoutSeconds: Long): CommandResult = withContext(Dispatchers.IO) {
        val process = builder.start()
        coroutineScope {
                val output = async(Dispatchers.IO) {
                    process.inputStream.bufferedReader().use { reader ->
                        buildString {
                            val buffer = CharArray(4096)
                            while (true) {
                                val n = reader.read(buffer)
                                if (n < 0) break
                                if (length < 1_048_576) append(buffer, 0, n.coerceAtMost(1_048_576 - length))
                            }
                        }
                    }
                }
                try {
                    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                        stopOwnedProcess(process)
                        throw LocalizedStateException(AppText.get(R.string.ui_command_timed_out))
                    }
                    CommandResult(process.exitValue(), output.await())
                } finally {
                    if (process.isAlive) stopOwnedProcess(process)
                }
        }
    }

    private fun stopOwnedProcess(process: Process) {
        process.destroy()
        if (!process.waitFor(8, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(3, TimeUnit.SECONDS)
        }
        process.inputStream.close()
    }

    companion object { private val installationLock = Mutex() }
}
