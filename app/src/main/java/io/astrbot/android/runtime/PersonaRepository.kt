package io.astrbot.android.runtime

import io.astrbot.android.checkLocalized

import io.astrbot.android.AppText
import io.astrbot.android.R

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.astrbot.android.core.SecretRedactor
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Only called with AstrBot stopped and the manager operation lock held for mutations. */
internal class PersonaRepository(private val context: Context) {
    private val data = File(context.filesDir, "persistent/astrbot/data")
    private val config = File(data, "cmd_config.json")
    private val database = File(data, "data_v4.db")
    private val journal = File(context.filesDir, "operations/persona.json")
    private val undo = File(context.filesDir, "persona-library/previous-default.json")
    val hasPendingChange get() = DurableFiles.exists(journal)

    fun inspect(): PersonaInstallation = runCatching {
        checkLocalized(!hasPendingChange) { AppText.get(R.string.ui_recovering_an_interrupted_persona_operation_please_wait) }
        val configuration = readConfig()
        open(readOnly = true).use { db ->
            checkSchema(db)
            val ids = mutableSetOf<String>()
            db.rawQuery("SELECT persona_id FROM personas WHERE persona_id LIKE ?", arrayOf("安卓预设·%" )).use { cursor ->
                while (cursor.moveToNext()) ids += cursor.getString(0)
            }
            val current = personaObject(configuration).optString("persona_id", "default")
            val previous = DurableFiles.read(undo)
            PersonaInstallation(ids, current, null, previous?.optString("applied") == current)
        }
    }.getOrElse { PersonaInstallation(unavailable = it.message?.takeIf { message -> message.any { c -> c in '\u4e00'..'\u9fff' } }
        ?.let(SecretRedactor::redact) ?: AppText.get(R.string.ui_this_persona_database_version_is_incompatible_copy_the_prompt)) }

    fun begin(preset: PersonaPreset?, applyDefault: Boolean, restorePrevious: Boolean = false) {
        checkLocalized(!hasPendingChange) { AppText.get(R.string.ui_an_unfinished_persona_operation_must_be_recovered_first) }
        val configuration = readConfig()
        val selected = personaObject(configuration)
        val prior = selected.optString("persona_id", "default")
        val undoBefore = DurableFiles.read(undo)
        val target = if (restorePrevious) {
            checkLocalized(undoBefore != null && undoBefore.getString("applied") == prior) { AppText.get(R.string.ui_the_default_persona_was_changed_in_the_dashboard_select) }
            undoBefore.getString("previous")
        } else requireNotNull(preset).databaseId
        open().use { db ->
            checkSchema(db)
            val exists = exists(db, target)
            if (restorePrevious) checkLocalized(target == "default" || exists) { AppText.get(R.string.ui_the_original_persona_was_deleted_choose_another_in_the) }
            val record = JSONObject().put("schema", 1).put("target", target).put("previous", prior)
                .put("created", !exists && !restorePrevious).put("changeDefault", applyDefault || restorePrevious)
                .put("previousPresent", selected.has("persona_id"))
                .put("undoBefore", undoBefore ?: JSONObject.NULL)
            DurableFiles.write(journal, record)
            db.beginTransaction()
            try {
                if (!exists && !restorePrevious) {
                    val now = LocalDateTime.now(ZoneOffset.UTC).toString().replace('T', ' ')
                    val values = ContentValues().apply {
                        put("persona_id", target); put("system_prompt", requireNotNull(preset).prompt)
                        put("created_at", now); put("updated_at", now); put("sort_order", 0)
                        put("begin_dialogs", "[]"); putNull("tools"); putNull("skills"); putNull("custom_error_message"); putNull("folder_id")
                    }
                    check(db.insertOrThrow("personas", null, values) > 0)
                }
                if (applyDefault || restorePrevious) {
                    selected.put("persona_id", target)
                    DurableFiles.write(config, configuration)
                    if (restorePrevious) {
                        if (DurableFiles.exists(undo)) DurableFiles.removeRecord(undo)
                    } else if (prior != target) DurableFiles.write(undo, JSONObject().put("schema", 1).put("previous", prior).put("applied", target))
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }

    /** Commit only after the originally running AstrBot has passed its readiness check. */
    fun commit() { if (hasPendingChange) DurableFiles.removeRecord(journal) }

    fun recover(): Boolean {
        val record = DurableFiles.read(journal) ?: return false
        checkLocalized(record.getInt("schema") == 1) { AppText.get(R.string.ui_incompatible_persona_recovery_record_data_is_preserved) }
        val configuration = readConfig()
        open().use { db ->
            checkSchema(db)
            db.beginTransaction()
            try {
                if (record.getBoolean("changeDefault")) {
                    val selected = personaObject(configuration)
                    if (record.getBoolean("previousPresent")) selected.put("persona_id", record.getString("previous"))
                    else selected.remove("persona_id")
                    DurableFiles.write(config, configuration)
                }
                if (record.getBoolean("created")) db.delete("personas", "persona_id = ?", arrayOf(record.getString("target")))
                record.optJSONObject("undoBefore")?.let { DurableFiles.write(undo, it) }
                    ?: if (DurableFiles.exists(undo)) DurableFiles.removeRecord(undo) else Unit
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        DurableFiles.removeRecord(journal)
        return true
    }

    private fun readConfig(): JSONObject {
        checkLocalized(config.isFile && database.isFile) { AppText.get(R.string.ui_start_astrbot_once_and_wait_for_the_dashboard_to) }
        val versionFile = File(context.filesDir, "runtime-data/ubuntu/opt/astrbot/astrbot/__init__.py")
        // Database and config contracts are explicitly checked below; no schema migration is performed here.
        checkLocalized(versionFile.isFile) { AppText.get(R.string.ui_initialize_the_astrbot_runtime_first) }
        return requireNotNull(DurableFiles.read(config)).also { personaObject(it) }
    }

    private fun personaObject(configuration: JSONObject): JSONObject {
        val runner = configuration.optJSONObject("agent_runner")
        checkLocalized(runner?.optString("runner_type") == "local") { AppText.get(R.string.ui_a_remote_agent_is_active_configure_personas_in_astrbot) }
        val result = runner.optJSONObject("config")?.optJSONObject("persona")
        checkLocalized(result != null) { AppText.get(R.string.ui_incompatible_astrbot_persona_configuration_format_copy_the_prompt_into) }
        return result
    }

    private fun open(readOnly: Boolean = false): SQLiteDatabase = SQLiteDatabase.openDatabase(database.path, null,
        (if (readOnly) SQLiteDatabase.OPEN_READONLY else SQLiteDatabase.OPEN_READWRITE) or SQLiteDatabase.NO_LOCALIZED_COLLATORS)

    private fun exists(db: SQLiteDatabase, id: String): Boolean = db.rawQuery("SELECT 1 FROM personas WHERE persona_id = ?", arrayOf(id)).use { it.moveToFirst() }

    private fun checkSchema(db: SQLiteDatabase) {
        val columns = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info(personas)", null).use { cursor -> while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name")) }
        checkLocalized(columns.containsAll(listOf("persona_id", "system_prompt", "begin_dialogs", "tools", "skills", "custom_error_message", "folder_id", "sort_order", "created_at", "updated_at"))) {
            AppText.get(R.string.ui_this_astrbot_database_is_incompatible_copy_the_prompt_into)
        }
    }
}
