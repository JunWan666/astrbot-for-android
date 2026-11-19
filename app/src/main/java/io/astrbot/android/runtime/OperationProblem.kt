package io.astrbot.android.runtime

import io.astrbot.android.AppText
import io.astrbot.android.LocalizedFailure
import io.astrbot.android.R
import io.astrbot.android.core.SecretRedactor
import org.json.JSONArray
import org.json.JSONObject

enum class ProblemAction { CHECK_ENVIRONMENT, SHARE_DIAGNOSTICS }

data class ProblemDetail(val name: String, val current: String, val expected: String, val reason: String) {
    fun json() = JSONObject().put("name", safe(name)).put("current", safe(current)).put("expected", safe(expected)).put("reason", safe(reason))
}

/** A failure snapshot: retain the failed operation even when the user navigates or runs diagnostics. */
data class OperationProblem(
    val code: String,
    val operation: String,
    val stage: String,
    val title: String,
    val summary: String,
    val nextStep: String,
    val details: List<ProblemDetail> = emptyList(),
    val actions: List<ProblemAction> = listOf(ProblemAction.CHECK_ENVIRONMENT, ProblemAction.SHARE_DIAGNOSTICS),
    val occurredAtMillis: Long = System.currentTimeMillis(),
) {
    fun json() = JSONObject().put("code", code).put("operation", operation).put("stage", stage)
        .put("title", safe(title)).put("summary", safe(summary)).put("nextStep", safe(nextStep)).put("occurredAt", occurredAtMillis)
        .put("details", JSONArray().also { array -> details.forEach { array.put(it.json()) } })

    companion object {
        fun failed(action: String, phase: String, message: String, timeout: Boolean = false) = OperationProblem(
            code = if (timeout) "OPERATION_TIMEOUT" else "OPERATION_FAILED", operation = action, stage = safe(phase).take(256),
            title = AppText.get(R.string.problem_operation_failed, label(action)),
            summary = SecretRedactor.redact(message).take(2000),
            nextStep = AppText.get(when (action) {
                "prepare-update", "clear-update-import", "apply-update", "update-online", "download-update", "rollback" -> R.string.upstream_failure_next
                else -> R.string.problem_general_next
            }),
            actions = if (action in setOf("prepare-update", "clear-update-import")) emptyList() else
                listOf(ProblemAction.CHECK_ENVIRONMENT, ProblemAction.SHARE_DIAGNOSTICS))

        private fun label(action: String): String = AppText.get(when {
            action == "repair-runtime" -> R.string.ui_repair_runtime_resources
            action == "repair-environment" -> R.string.repair_auto_title
            action == "initialize" -> R.string.ui_initialize_runtime
            action in setOf("start", "restart") -> R.string.problem_start_services
            action.contains("diagnostics") -> R.string.problem_export_diagnostics
            action.contains("backup") || action == "restore" -> R.string.ui_backup_and_restore
            action in setOf("prepare-update", "clear-update-import") -> R.string.ui_import_update_package
            action in setOf("apply-update", "update-online") -> R.string.upstream_install_title
            action == "download-update" -> R.string.upstream_download_install
            action == "rollback" -> R.string.ui_roll_back_astrbot
            action.contains("update") || action.contains("runtime") -> R.string.ui_check_for_updates
            action.startsWith("desktop-") -> R.string.desktop_name
            action.startsWith("cpolar-") -> R.string.cpolar_name
            action.startsWith("harness-") -> R.string.harness_name
            action.startsWith("ssh-") -> R.string.problem_ssh
            action == "check-environment" -> R.string.ui_environment_check_and_repair
            else -> R.string.problem_current_operation
        })
    }
}

internal class OperationProblemException(val problem: OperationProblem) : IllegalStateException(problem.summary), LocalizedFailure

/** Only pair a structured snapshot with the error it describes, never with a newer unrelated error. */
fun ManagerState.currentProblem(): OperationProblem? = problem?.takeIf { error == it.summary }

private fun safe(text: String) = SecretRedactor.redact(text).take(2000)
