package io.astrbot.android.runtime

import android.content.Context
import android.system.Os
import android.util.AtomicFile
import io.astrbot.android.AppText
import io.astrbot.android.LocalizedStateException
import io.astrbot.android.R
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.nio.file.Files

/** Avoid native crashes during startup or after login without changing QQ data or upstream code. */
internal class NapCatStartupCompatibility(filesDirectory: File) {
    constructor(context: Context) : this(context.filesDir)

    private val files = filesDirectory.canonicalFile
    private val root = File(files, "runtime-data/ubuntu")
    private val config = File(files, "persistent/napcat/config/napcat.json")
    private val backup = File(files, "persistent/manager/napcat-startup-compat/napcat.before-compat.json")

    data class Inspection(val affectedRuntime: Boolean, val optionsToDisable: List<String> = emptyList())

    /** Read-only: environment checks must not recover AtomicFile journals or edit configuration. */
    fun inspect(): Inspection {
        if (!affectedRuntime()) return Inspection(false)
        val selected = if (File(config.path + ".bak").exists()) File(config.path + ".bak") else config
        val candidate = read(selected) ?: return Inspection(true)
        return Inspection(true, disabledOptions(candidate.document))
    }

    /** Called only after the previous service has stopped and its WebUI port is free. */
    fun prepare(): List<String> {
        try {
            if (!affectedRuntime()) return emptyList()
            safe(config)
            safe(File(config.path + ".bak"))
            if (!DurableFiles.exists(config)) return emptyList()
            AtomicFile(config).openRead().close()
            val candidate = read(config) ?: return emptyList()
            val options = disabledOptions(candidate.document)
            if (options.isEmpty()) return emptyList()
            val bypass = candidate.document.getJSONObject("bypass")
            options.forEach { bypass.put(it, false) }

            // Keep exactly one previous original, byte for byte, before committing a correction.
            safe(backup)
            check(backup.parentFile!!.isDirectory || backup.parentFile!!.mkdirs())
            atomicWrite(backup, candidate.bytes, 384) // 0600: configuration may contain secrets.
            atomicWrite(config, candidate.document.toString(2).toByteArray(Charsets.UTF_8), Os.stat(config.path).st_mode and 511)
            return options
        } catch (_: Exception) {
            // Parse and path failures can contain a line with credentials. Never expose them.
            throw LocalizedStateException(AppText.get(R.string.napcat_startup_compat_failed))
        }
    }

    private fun affectedRuntime(): Boolean {
        // Scope this workaround to the combination reproduced on Android ARM64 PRoot.
        // Future upstream releases retain their own options until checked separately.
        val manifest = read(File(root, ".astrbot-bootstrap.json"))?.document ?: return false
        if (manifest.optString("abi") != "arm64-v8a") return false
        val qq = read(File(root, "opt/qq/resources/app/package.json"))?.document ?: return false
        if (qq.optString("version") != "3.2.34-53644") return false
        val program = File(root, "opt/qq/resources/app/app_launcher/napcat")
        val backend = File(program, "napcat.mjs")
        safe(backend)
        if (!backend.isFile || backend.length() > 20L * 1024 * 1024) return false
        return NapCatVersion.read(program, backend.readText(), recoverPendingWrite = false) == "4.18.41"
    }

    private data class Candidate(val bytes: ByteArray, val document: JSONObject)

    private fun read(file: File): Candidate? {
        safe(file)
        if (!file.exists()) return null
        require(file.isFile && file.length() <= 8L * 1024 * 1024)
        val bytes = file.readBytes()
        val parser = JSONTokener(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF"))
        val document = parser.nextValue() as? JSONObject ?: error("Expected a configuration object")
        require(parser.nextClean() == '\u0000')
        return Candidate(bytes, document)
    }

    private fun disabledOptions(document: JSONObject): List<String> {
        if (!document.has("bypass")) return emptyList()
        val bypass = document.getJSONObject("bypass")
        // Module/Container crash at startup; the remaining four together also crashed after
        // login (SIGSEGV, Worker 256). Upstream recommends not enabling these bypasses:
        // https://github.com/NapNeko/NapCatQQ/issues/1813#issuecomment-4519412623
        // O3 is a separate packet hook mode and must retain the user's value.
        return listOf("hook", "window", "module", "process", "container", "js").filter { option ->
            // NapCat fills missing fields in an existing bypass object with true.
            if (!bypass.has(option)) true else {
                val value = bypass.get(option)
                require(value is Boolean)
                value
            }
        }
    }

    private fun safe(file: File) {
        require(file.canonicalFile.toPath().startsWith(files.toPath()) && !Files.isSymbolicLink(file.toPath()))
    }

    private fun atomicWrite(file: File, bytes: ByteArray, mode: Int) {
        safe(file)
        safe(File(file.path + ".bak"))
        safe(File(file.path + ".new"))
        require(!file.exists() || file.isFile)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            Os.fchmod(output.fd, mode)
            atomic.finishWrite(output)
        } catch (failure: Throwable) { atomic.failWrite(output); throw failure }
        // Some Android versions log a failed atomic rename without throwing it.
        check(file.isFile && file.length() == bytes.size.toLong() && file.readBytes().contentEquals(bytes))
        DurableFiles.sync(file.parentFile!!)
    }
}
