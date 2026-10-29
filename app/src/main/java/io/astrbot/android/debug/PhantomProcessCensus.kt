package io.astrbot.android.debug

import org.json.JSONObject

/** Counts only ActivityManager's active child-process table, never all Linux or Android processes. */
internal object PhantomProcessCensus {
    fun parse(dump: String?, appUid: Int): JSONObject {
        val result = JSONObject().put("appUid", appUid).put("readable", false)
            .put("app", JSONObject.NULL).put("system", JSONObject.NULL)
            .put("source", "dumpsys activity processes").put("checkedAtMillis", System.currentTimeMillis())
        val lines = dump?.lines() ?: return result
        val start = lines.indexOfFirst { it.trim() == "All Active App Child Processes:" }
        if (start < 0) return result
        val indentation = lines[start].takeWhile(Char::isWhitespace).length
        val records = mutableMapOf<Int, Int>()
        var pendingPid: Int? = null
        var malformed = false
        for (line in lines.drop(start + 1)) {
            if (line.isBlank()) continue
            if (line.takeWhile(Char::isWhitespace).length <= indentation) break
            if (line.contains("PhantomProcessRecord")) {
                if (pendingPid != null) malformed = true
                pendingPid = record.find(line)?.groupValues?.get(1)?.toIntOrNull()
                if (pendingPid == null) malformed = true
            } else if (line.trimStart().startsWith("user #")) {
                val fields = identity.find(line)
                val uid = fields?.groupValues?.get(1)?.toIntOrNull()
                val pid = fields?.groupValues?.get(2)?.toIntOrNull()
                if (pid == null || uid == null || pid <= 0 || uid < 0 || pendingPid == null || pid != pendingPid) {
                    malformed = true
                } else if (records.put(pid, uid)?.let { it != uid } == true) malformed = true
                pendingPid = null
            } else if (!line.trimStart().startsWith("lastCpuTime=") && line.trim() != "(none)") malformed = true
        }
        if (malformed || pendingPid != null) return result
        return result.put("readable", true).put("app", records.values.count { it == appUid }).put("system", records.size)
    }

    private val record = Regex("PhantomProcessRecord\\s*\\{\\S+\\s+(\\d+):")
    private val identity = Regex("\\buid=(\\d+)\\s+pid=(\\d+)\\b")
}
