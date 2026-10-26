package io.astrbot.android.runtime

import io.astrbot.android.checkLocalized

import io.astrbot.android.AppText
import io.astrbot.android.R

import android.content.Context
import android.content.SharedPreferences
import io.astrbot.android.core.BackupLimits
import org.json.JSONObject

class BackupCapacitySettings(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("manager", Context.MODE_PRIVATE))

    fun read(): BackupLimits {
        val raw = preferences.getString(KEY, null) ?: return BackupLimits()
        val json = JSONObject(raw)
        return BackupLimits(json.getInt("count"), json.getLong("bytes"))
    }

    fun save(limits: BackupLimits) {
        val raw = JSONObject().put("count", limits.automaticCount).put("bytes", limits.automaticBytes).toString()
        checkLocalized(preferences.edit().putString(KEY, raw).commit()) { AppText.get(R.string.ui_could_not_save_snapshot_limits_check_free_storage) }
    }

    companion object { const val KEY = "backup_limits_v1" }
}
