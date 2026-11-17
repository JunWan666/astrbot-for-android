package io.astrbot.android.runtime

import io.astrbot.android.checkLocalized

import io.astrbot.android.AppText
import io.astrbot.android.R

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File

/** Recover unfinished changes created by older versions; current persona editing belongs to AstrBot. */
internal class PersonaRepository(private val context: Context) {
    private val data = File(context.filesDir, "persistent/astrbot/data")
    private val config = File(data, "cmd_config.json")
    private val database = File(data, "data_v4.db")
    private val journal = File(context.filesDir, "operations/persona.json")
    private val undo = File(context.filesDir, "persona-library/previous-default.json")
    val hasPendingChange get() = DurableFiles.exists(journal)

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

    private fun checkSchema(db: SQLiteDatabase) {
        val columns = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info(personas)", null).use { cursor -> while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name")) }
        checkLocalized(columns.containsAll(listOf("persona_id", "system_prompt", "begin_dialogs", "tools", "skills", "custom_error_message", "folder_id", "sort_order", "created_at", "updated_at"))) {
            AppText.get(R.string.ui_this_astrbot_database_is_incompatible_copy_the_prompt_into)
        }
    }
}
