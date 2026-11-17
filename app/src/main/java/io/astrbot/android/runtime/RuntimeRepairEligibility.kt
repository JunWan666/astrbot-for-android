package io.astrbot.android.runtime

import io.astrbot.android.AppText
import io.astrbot.android.R
import io.astrbot.android.core.SecretRedactor
import org.json.JSONObject
import java.io.File

/** Cheap preflight; the staged image's exact compatibility key is still verified before switching. */
internal object RuntimeRepairEligibility {
    fun matchesBundled(root: File, bundled: JSONObject, versions: RuntimeVersions? = null): Boolean =
        inspect(root, bundled, versions) == null

    fun inspect(root: File, bundled: JSONObject, versions: RuntimeVersions? = null): OperationProblem? {
        val inspection = if (versions == null) runCatching { InstalledVersions.read(root) } else Result.success(versions)
        val installed = inspection.getOrNull()
        val components = installed?.components ?: InstalledVersionInspection.read(root).components
        val marker = readJson(File(root, ".astrbot-bootstrap.json"))
        val expected = bundled.optJSONObject("components")
        val details = mutableListOf<ProblemDetail>()
        var identityUnrecognized = installed == null
        fun compare(name: String, current: String?, target: String?) {
            val known = current?.takeUnless { it in setOf(AppText.get(R.string.ui_unknown), AppText.get(R.string.ui_unknown_unsupported_by_this_build)) }
            if (known.isNullOrBlank() || target.isNullOrBlank()) identityUnrecognized = true
            if (known.isNullOrBlank() || target.isNullOrBlank() || known != target) details += ProblemDetail(name,
                display(known), display(target), AppText.get(if (known.isNullOrBlank() || target.isNullOrBlank())
                    R.string.problem_identity_unknown else R.string.problem_version_different))
        }
        compare(AppText.get(R.string.ui_runtime), marker?.optString("sha256"), bundled.optString("sha256"))
        compare(AppText.get(R.string.problem_runtime_type), marker?.optString("kind"), "complete")
        compare(AppText.get(R.string.problem_architecture), components["架构"], bundled.optString("abi"))
        for ((name, id) in listOf("AstrBot" to "astrbot")) {
            compare(name, components[name], expected?.optJSONObject(id)?.optString("version"))
        }
        val source = readJson(File(root, "opt/astrbot/.astrbot-source.json"))
        compare(AppText.get(R.string.problem_astrbot_record), source?.optString("version"), components["AstrBot"])
        if (installed == null) {
            val failure = inspection.exceptionOrNull() as? VersionRecordFailure
            details += ProblemDetail(failure?.recordPath ?: AppText.get(R.string.ui_version_and_backup_identity),
                AppText.get(when (failure?.issue) { "missing" -> R.string.problem_record_missing; "too-large" -> R.string.problem_record_too_large
                    "unreadable" -> R.string.problem_record_unreadable; else -> R.string.problem_unrecognized }),
                AppText.get(R.string.problem_readable_record), AppText.get(R.string.problem_identity_unknown))
        }
        if (bundled.optString("kind") != "complete") details += ProblemDetail(AppText.get(R.string.problem_bundled_type),
            display(bundled.optString("kind")), "complete", AppText.get(R.string.problem_identity_unknown))
        if (details.isEmpty()) return null
        return mismatch(if (identityUnrecognized) "REPAIR_IDENTITY_UNAVAILABLE" else "REPAIR_COMBINATION_MISMATCH", "preflight", details,
            AppText.get(if (identityUnrecognized) R.string.problem_repair_unknown_summary else R.string.problem_repair_versions_summary))
    }

    /** Compare the same records that produce compatibilityKey, including same-version metadata changes. */
    fun exactProblem(current: RuntimeVersions, expected: RuntimeVersions, stage: String): OperationProblem? {
        if (current.compatibilityKey == expected.compatibilityKey) return null
        val details = (current.identityDigests.keys + expected.identityDigests.keys).mapNotNull { name ->
            val a = current.identityDigests[name]
            val b = expected.identityDigests[name]
            if (a == b) null else ProblemDetail(name, display(a), display(b), AppText.get(R.string.problem_record_different))
        }.ifEmpty { listOf(ProblemDetail(AppText.get(R.string.ui_version_and_backup_identity), display(current.compatibilityKey),
            display(expected.compatibilityKey), AppText.get(R.string.problem_record_different))) }
        return mismatch("REPAIR_CONTENT_MISMATCH", stage, details, AppText.get(R.string.problem_repair_content_summary))
    }

    fun blocked(reason: String) = OperationProblem("REPAIR_NOT_AVAILABLE", "repair-runtime", "environment-check",
        AppText.get(R.string.problem_repair_title), reason, AppText.get(R.string.problem_repair_next))

    private fun mismatch(code: String, stage: String, details: List<ProblemDetail>, summary: String) =
        OperationProblem(code, "repair-runtime", stage, AppText.get(R.string.problem_repair_title), summary,
            AppText.get(R.string.problem_repair_next), details)

    private fun display(value: String?): String = value?.takeIf { it.isNotBlank() }?.let {
        if (it.matches(Regex("[a-f0-9]{64}"))) it.take(12) + "…" else SecretRedactor.redact(it).take(160)
    } ?: AppText.get(R.string.problem_unrecognized)

    private fun readJson(file: File): JSONObject? = runCatching {
        require(file.isFile && file.length() <= 64 * 1024)
        JSONObject(file.readText())
    }.getOrNull()
}
