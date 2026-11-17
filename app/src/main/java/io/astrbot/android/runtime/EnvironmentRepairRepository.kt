package io.astrbot.android.runtime

import android.content.Context
import io.astrbot.android.AppText
import io.astrbot.android.R
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64

/** Repairs only identified resources; independent component updates and persistent data stay in place. */
internal class EnvironmentRepairRepository(private val context: Context,
    private val prepareDonor: suspend (File, (String) -> Unit) -> Unit = { dir, progress -> BootstrapRuntime(context).prepareBundled(dir, progress) },
    private val pythonProbe: suspend (BootstrapRuntime) -> Boolean = EnvironmentChecks::probePython,
    private val downloadDashboard: suspend (String, File, (String) -> Unit) -> File = { version, directory, progress ->
        val source = OfficialReleaseRepository(context, "astrbot")
        try { source.repairDashboard(version, directory, progress) } finally { source.close() }
    }) {
    private val runtime = BootstrapRuntime(context)
    private val root = runtime.rootfs
    private val work = File(context.filesDir, "staging/environment-repair")
    private val transaction = EnvironmentRepairTransaction(context)
    val hasPendingRepair get() = transaction.pending

    fun recover(): Boolean {
        val recovered = transaction.recover()
        if (!hasPendingRepair) DurableFiles.delete(work, writableGuestDirectories = true)
        return recovered
    }

    suspend fun prepare(targets: List<EnvironmentRepairTarget>, progress: (String) -> Unit): Prepared = withContext(Dispatchers.IO) {
        check(!hasPendingRepair && targets.isNotEmpty())
        DurableFiles.delete(work, writableGuestDirectories = true)
        transaction.reset()
        check(work.mkdirs())
        try {
            val manifest = runtime.bundledManifest()
            val installed = JSONObject(File(root, ".astrbot-bootstrap.json").readText())
            check(installed.getString("kind") == "complete" && installed.getString("abi") == runtime.abi)
            val components = InstalledVersionInspection.read(root).components
            val version = components.getValue("AstrBot")
            val dashboardNeeded = EnvironmentRepairTarget.ASTRBOT_DASHBOARD in targets
            val localDashboard = if (dashboardNeeded) AstrBotDashboard.candidates(root).map { AstrBotDashboard.inspectDirectory(it.second, it.first) }
                .firstOrNull { it.readable && it.version == version }?.directory else null
            val needsDonor = targets.any { it != EnvironmentRepairTarget.ASTRBOT_DASHBOARD } ||
                dashboardNeeded && localDashboard == null && version == manifest.getJSONObject("components").getJSONObject("astrbot").getString("version")
            val donor = File(work, "donor")
            if (needsDonor) {
                check(installed.getString("sha256") == manifest.getString("sha256"))
                progress(AppText.get(R.string.repair_prepare_resources))
                prepareDonor(donor, progress)
                check(JSONObject(File(donor, ".astrbot-bootstrap.json").readText()).getString("sha256") == installed.getString("sha256"))
            }
            val repaired = mutableListOf<String>()
            val effectiveTargets = mutableListOf<EnvironmentRepairTarget>()
            if (EnvironmentRepairTarget.RUNTIME_FILES in targets) {
                val before = repaired.size
                for (path in missingRestorableFiles(root, manifest, components)) {
                    if (!matchingSystemPackage(root, donor, path)) continue
                    if (path == "opt/astrbot/main.py") {
                        val sourceRecord = "opt/astrbot/.astrbot-source.json"
                        val installedRecord = File(root, sourceRecord)
                        // Matching version numbers alone cannot identify a customized source tree.
                        if (!installedRecord.isFile || !installedRecord.readBytes().contentEquals(File(donor, sourceRecord).readBytes())) continue
                    }
                    progress(AppText.get(R.string.repair_staging_file, path))
                    transaction.stage(path, File(donor, path)); repaired += path
                }
                if (repaired.size > before) effectiveTargets += EnvironmentRepairTarget.RUNTIME_FILES
            }
            if (EnvironmentRepairTarget.PYTHON_DEPENDENCIES in targets) {
                progress(AppText.get(R.string.repair_python_staging))
                check(pythonMatchesBaseline(root))
                val before = repaired.size
                repairPython(donor) { path, source -> transaction.stage(path, source); repaired += path }
                if (repaired.size > before) effectiveTargets += EnvironmentRepairTarget.PYTHON_DEPENDENCIES
            }
            if (dashboardNeeded) {
                progress(AppText.get(R.string.repair_dashboard_staging, version))
                val source = localDashboard ?: if (donor.exists() &&
                    AstrBotDashboard.inspectDirectory(File(donor, DASHBOARD_PATH), "donor").let { it.readable && it.version == version })
                    File(donor, DASHBOARD_PATH) else downloadDashboard(version, File(work, "download"), progress)
                check(AstrBotDashboard.inspectDirectory(source, "repair").let { it.readable && it.version == version })
                transaction.stage(DASHBOARD_PATH, source); repaired += DASHBOARD_PATH
                effectiveTargets += EnvironmentRepairTarget.ASTRBOT_DASHBOARD
            }
            if (repaired.isEmpty()) throw OperationProblemException(unhandled(AppText.get(R.string.repair_no_matching_files)))
            Prepared(effectiveTargets, repaired, version, transaction, this@EnvironmentRepairRepository)
        } catch (failure: Throwable) {
            if (!hasPendingRepair) DurableFiles.delete(work, writableGuestDirectories = true)
            throw failure
        }
    }

    internal class Prepared(val targets: List<EnvironmentRepairTarget>, val files: List<String>, private val version: String,
        private val transaction: EnvironmentRepairTransaction, private val repository: EnvironmentRepairRepository) {
        fun discard() { if (!transaction.pending) DurableFiles.delete(repository.work, writableGuestDirectories = true) }
        suspend fun applyAndVerify(progress: (String) -> Unit) {
            try {
                progress(AppText.get(R.string.repair_switching))
                transaction.begin(); transaction.apply()
                progress(AppText.get(R.string.repair_verifying))
                if (EnvironmentRepairTarget.PYTHON_DEPENDENCIES in targets && !repository.pythonProbe(repository.runtime))
                    throw OperationProblemException(unhandled(AppText.get(R.string.repair_python_still_failed)))
                if (EnvironmentRepairTarget.ASTRBOT_DASHBOARD in targets) check(AstrBotDashboard.inspect(repository.root).let {
                    it?.readable == true && it.version == version
                })
                for (path in files.filter { it != DASHBOARD_PATH }) check(File(repository.root, path).isFile)
                transaction.commit()
            } catch (failure: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) { transaction.recover() }
                throw failure
            } finally {
                if (!transaction.pending) DurableFiles.delete(repository.work, writableGuestDirectories = true)
            }
        }
    }

    /** Pip RECORD binds files to a distribution. Never replace a differently installed version or extra packages. */
    private suspend fun repairPython(donor: File, stage: suspend (String, File) -> Unit) {
        val site = File(donor, SITE_PACKAGES)
        val activeSite = File(root, SITE_PACKAGES)
        val baselineLock = "var/lib/astrbot-manager/astrbot-python.lock.txt"
        val sameLock = File(root, baselineLock).let { it.isFile && it.readBytes().contentEquals(File(donor, baselineLock).readBytes()) }
        val installed = activeSite.listFiles().orEmpty().filter { it.name.endsWith(".dist-info") }
        for (distribution in site.listFiles().orEmpty().filter { it.name.endsWith(".dist-info") }) {
            currentCoroutineContext().ensureActive()
            val metadata = File(distribution, "METADATA")
            val record = File(distribution, "RECORD")
            if (!metadata.isFile || !record.isFile || record.length() > 8 * 1024 * 1024) continue
            val active = File(activeSite, distribution.name)
            val records = record.readLines().map(::csv).filter { it.size == 3 }
            val identity = distribution.name.substringBeforeLast('-').lowercase().replace('-', '_')
            val conflicting = installed.any { it.name.substringBeforeLast('-').lowercase().replace('-', '_') == identity && it.name != distribution.name }
            val recognized = if (active.isDirectory) File(active, "METADATA").let { it.isFile && it.readBytes().contentEquals(metadata.readBytes()) } &&
                File(active, "RECORD").let { it.isFile && it.readBytes().contentEquals(record.readBytes()) }
                else sameLock && !conflicting && records.none { values ->
                    val relative = values[0]
                    relative.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
                        EnvironmentRepairTransaction.exists(File(activeSite, relative))
                }
            if (!recognized || conflicting) continue
            for (values in records) {
                if (values.size != 3 || !values[1].startsWith("sha256=")) continue
                val relative = values[0]
                if (relative.split('/').any { it.isEmpty() || it == "." || it == ".." } || relative.startsWith('/')) continue
                val path = "$SITE_PACKAGES/$relative"
                val source = File(donor, path)
                val destination = File(root, path)
                check(source.canonicalFile.toPath().startsWith(donor.canonicalFile.toPath()))
                check(destination.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
                if (!source.isFile || Files.isSymbolicLink(source.toPath()) || Files.isSymbolicLink(destination.toPath())) continue
                val expected = values[1].removePrefix("sha256=")
                if (recordDigest(source) != expected) error("Bundled Python RECORD digest mismatch")
                if (!destination.isFile || recordDigest(destination) != expected) {
                    stage(path, source)
                }
            }
            // Pip RECORD has no self digest. Restore it only for a completely missing, nonconflicting distribution.
            if (!active.exists() && sameLock && !conflicting) stage("$SITE_PACKAGES/${distribution.name}/RECORD", record)
        }
    }

    companion object {
        const val DASHBOARD_PATH = "opt/astrbot/astrbot/dashboard/dist"
        const val SITE_PACKAGES = "opt/astrbot-venv/lib/python3.12/site-packages"
        val CORE_PATHS = setOf("usr/bin/env", "usr/bin/dash", "usr/bin/python3.12", "usr/bin/Xvfb", "opt/astrbot/main.py")

        fun missingRestorableFiles(root: File, bundled: JSONObject, components: Map<String, String>): List<String> = CORE_PATHS.filter { path ->
            !EnvironmentRepairTransaction.exists(File(root, path)) && when (path) {
                "opt/astrbot/main.py" -> components["AstrBot"] == bundled.getJSONObject("components").getJSONObject("astrbot").getString("version")
                else -> true
            }
        }

        fun pythonMatchesBaseline(root: File) = runCatching {
            File(root, "opt/astrbot-venv/pyvenv.cfg").readText().lineSequence().any { it.trim() == "version = 3.12.3" }
        }.getOrDefault(false)

        private fun matchingSystemPackage(root: File, donor: File, path: String): Boolean {
            val name = when (path) {
                "usr/bin/env" -> "coreutils"; "usr/bin/dash" -> "dash"; "usr/bin/python3.12" -> "python3.12-minimal"
                "usr/bin/Xvfb" -> "xvfb"; else -> return true // Program versions are checked separately.
            }
            fun version(directory: File): String? = runCatching {
                val file = File(directory, "var/lib/dpkg/status")
                require(file.isFile && file.length() <= 32 * 1024 * 1024)
                file.readText().split(Regex("\\r?\\n\\r?\\n")).firstNotNullOfOrNull { paragraph ->
                    val fields = paragraph.lineSequence().mapNotNull { line ->
                        if (line.startsWith(' ') || !line.contains(": ")) null else line.substringBefore(": ") to line.substringAfter(": ")
                    }.toMap()
                    fields["Version"]?.takeIf { fields["Package"] == name && fields["Status"] == "install ok installed" }
                }
            }.getOrNull()
            val baseline = version(donor) ?: return false
            return version(root) == baseline
        }

        fun unhandled(reason: String) = OperationProblem("REPAIR_NEEDS_DIAGNOSIS", "repair-environment", "targeted-repair",
            AppText.get(R.string.repair_unhandled_title), reason, AppText.get(R.string.repair_unhandled_next))

        private fun recordDigest(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(128 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest())
        }

        private fun csv(line: String): List<String> {
            val result = mutableListOf<String>(); val field = StringBuilder(); var quoted = false; var index = 0
            while (index < line.length) {
                val char = line[index++]
                when {
                    char == '"' && quoted && index < line.length && line[index] == '"' -> { field.append('"'); index++ }
                    char == '"' -> quoted = !quoted
                    char == ',' && !quoted -> { result += field.toString(); field.clear() }
                    else -> field.append(char)
                }
            }
            if (quoted) return emptyList()
            return result + field.toString()
        }
    }
}
