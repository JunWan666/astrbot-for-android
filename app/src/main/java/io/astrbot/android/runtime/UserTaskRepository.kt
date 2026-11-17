package io.astrbot.android.runtime

import android.content.Context
import io.astrbot.android.AppText
import io.astrbot.android.R
import io.astrbot.android.checkLocalized
import io.astrbot.android.core.RotatingLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class UserTaskDefinition(val id: String, val name: String, val directory: String, val command: String)

enum class UserTaskPhase { STOPPED, STARTING, RUNNING, STOPPING, EXITED, FAILED, INTERRUPTED;
    val active get() = this == STARTING || this == RUNNING || this == STOPPING
}

data class UserTaskState(val definition: UserTaskDefinition, val phase: UserTaskPhase = UserTaskPhase.STOPPED,
    val startedAt: Long? = null, val finishedAt: Long? = null, val exitCode: Int? = null, val detail: String? = null)

/** Definitions and bounded output are private app data, separate from terminal transcripts and diagnostics. */
class UserTaskRepository(context: Context) {
    private val directory = File(context.filesDir, "user-tasks")
    private val definitions = File(directory, "definitions.json")

    fun read(): List<UserTaskState> {
        if (!DurableFiles.exists(definitions)) return emptyList()
        try {
            check(definitions.length() <= 8 * 1024 * 1024)
            val document = requireNotNull(DurableFiles.read(definitions))
            check(document.getInt("schemaVersion") == 1)
            val rows = document.getJSONArray("tasks")
            check(rows.length() <= MAX_TASKS)
            val result = (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                val definition = UserTaskDefinition(row.getString("id"), row.getString("name"), row.getString("directory"), row.getString("command"))
                validate(definition)
                val history = File(directory, "${definition.id}.json")
                val last = runCatching { DurableFiles.read(history) }.getOrNull()
                fun time(key: String) = last?.optLong(key, 0)?.takeIf { it > 0 }
                val phase = last?.optString("phase")?.let { saved -> UserTaskPhase.entries.firstOrNull { it.name == saved } }
                    ?: UserTaskPhase.STOPPED
                UserTaskState(definition, if (phase.active) UserTaskPhase.INTERRUPTED else phase,
                    time("startedAt"), time("finishedAt"), last?.takeIf { it.has("exitCode") }?.getInt("exitCode"))
            }
            check(result.map { it.definition.id }.distinct().size == result.size)
            return result
        } catch (failure: Exception) {
            throw io.astrbot.android.LocalizedStateException(AppText.get(R.string.tasks_read_failed))
        }
    }

    fun save(definitions: List<UserTaskDefinition>) {
        checkLocalized(definitions.size <= MAX_TASKS) { AppText.get(R.string.tasks_limit, MAX_TASKS) }
        definitions.forEach(::validate)
        check(definitions.map { it.id }.distinct().size == definitions.size)
        val rows = JSONArray()
        definitions.forEach { rows.put(JSONObject().put("id", it.id).put("name", it.name).put("directory", it.directory).put("command", it.command)) }
        DurableFiles.write(this.definitions, JSONObject().put("schemaVersion", 1).put("tasks", rows))
    }

    fun history(task: UserTaskState) {
        validateId(task.definition.id)
        DurableFiles.write(File(directory, "${task.definition.id}.json"), JSONObject().put("phase", task.phase.name)
            .put("startedAt", task.startedAt).put("finishedAt", task.finishedAt).put("exitCode", task.exitCode))
    }

    fun log(id: String): RotatingLog {
        validateId(id)
        return RotatingLog(File(directory, "logs"), id, maxBytes = 512 * 1024, archives = 1)
    }

    fun tail(id: String): String {
        validateId(id)
        return if (File(directory, "logs/$id.log").isFile) log(id).tail(500) else ""
    }

    fun removeHistory(id: String) {
        validateId(id)
        log(id).clear()
        val file = File(directory, "$id.json")
        DurableFiles.removeRecord(file)
    }

    companion object {
        const val MAX_TASKS = 32
        const val MAX_RUNNING = 8
        fun newId(): String = UUID.randomUUID().toString()
        private fun validateId(id: String) = require(id.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")))
        fun validate(task: UserTaskDefinition) {
            validateId(task.id)
            checkLocalized(task.name.length in 1..60 && task.name.none { it.isISOControl() } && task.name.isNotBlank()) {
                AppText.get(R.string.tasks_invalid_name)
            }
            checkLocalized(task.directory.startsWith("/") && task.directory.length <= 4096 && task.directory.none { it.isISOControl() }) {
                AppText.get(R.string.tasks_invalid_directory)
            }
            checkLocalized(task.command.isNotBlank() && task.command.length <= 32_768 && '\u0000' !in task.command) {
                AppText.get(R.string.tasks_invalid_command)
            }
        }
    }
}
