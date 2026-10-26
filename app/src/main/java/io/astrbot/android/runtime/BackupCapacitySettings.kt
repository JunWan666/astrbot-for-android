package io.astrbot.android.runtime

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
        check(preferences.edit().putString(KEY, raw).commit()) { "无法保存快照上限，请检查存储空间" }
    }

    companion object { const val KEY = "backup_limits_v1" }
}
