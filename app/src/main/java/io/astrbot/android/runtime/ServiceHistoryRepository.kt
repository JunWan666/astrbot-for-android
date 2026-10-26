package io.astrbot.android.runtime

import android.content.Context
import io.astrbot.android.core.*
import org.json.JSONObject
import java.io.File

/** Two bounded records per service, outside business backups and live state. */
internal class ServiceHistoryRepository(context: Context) {
    private val directory = File(context.filesDir, "service-history")
    fun read(id: ServiceId): ServiceHistory = runCatching {
        val json = DurableFiles.read(file(id)) ?: return ServiceHistory()
        require(json.getInt("schema") == 1)
        ServiceHistory(event(json, "lastAnomaly"), event(json, "lastRecovery"))
    }.getOrDefault(ServiceHistory())

    fun save(id: ServiceId, history: ServiceHistory) {
        fun encode(event: ServiceEvent?) = event?.let {
            JSONObject().put("atMillis", it.atMillis).put("summary", safe(it.summary))
        } ?: JSONObject.NULL
        DurableFiles.write(file(id), JSONObject().put("schema", 1)
            .put("lastAnomaly", encode(history.lastAnomaly)).put("lastRecovery", encode(history.lastRecovery)))
    }

    private fun file(id: ServiceId) = File(directory, "${id.name.lowercase()}.json")
    private fun event(json: JSONObject, key: String): ServiceEvent? = json.optJSONObject(key)?.let {
        val time = it.getLong("atMillis")
        require(time > 0)
        ServiceEvent(time, safe(it.getString("summary")))
    }
    private fun safe(value: String) = SecretRedactor.redact(value).filter { !it.isISOControl() }.take(384)
}
