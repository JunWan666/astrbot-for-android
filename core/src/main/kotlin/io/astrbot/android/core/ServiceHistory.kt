package io.astrbot.android.core

data class ServiceEvent(val atMillis: Long, val summary: String)

/** Historical evidence only: never use these records to infer current readiness. */
data class ServiceHistory(val lastAnomaly: ServiceEvent? = null, val lastRecovery: ServiceEvent? = null)

/** Tracks independently observed failures, so a working WebUI cannot imply QQ has recovered. */
class ServiceHistoryTracker(initial: ServiceHistory = ServiceHistory()) {
    var history: ServiceHistory = initial
        private set
    private enum class Problem { PROCESS, WEB_UI, QQ, ONEBOT }
    private val pending = mutableSetOf<Problem>()
    private var knownQq = QqState.UNKNOWN
    private var knownOneBot = OneBotState.UNKNOWN

    fun newProcess() { knownQq = QqState.UNKNOWN; knownOneBot = OneBotState.UNKNOWN }
    fun stopped() { pending.clear(); newProcess() }

    fun processFailed(summary: String, atMillis: Long) {
        pending.add(Problem.PROCESS)
        anomaly(summary, atMillis)
    }

    fun observe(health: HealthState, qq: QqState, oneBot: OneBotState, atMillis: Long) {
        val failures = mutableListOf<String>()
        val recoveries = mutableListOf<String>()
        if (health == HealthState.UNRESPONSIVE && pending.add(Problem.WEB_UI)) failures += "后台无响应"
        if (health == HealthState.AVAILABLE || health == HealthState.DISABLED) {
            if (pending.remove(Problem.PROCESS)) recoveries += if (health == HealthState.AVAILABLE) "进程与后台已恢复" else "进程已恢复（WebUI 已关闭）"
        }
        if (health == HealthState.AVAILABLE && pending.remove(Problem.WEB_UI)) recoveries += "后台访问已恢复"
        if (qq != QqState.UNKNOWN) {
            if (knownQq == QqState.ONLINE && qq != QqState.ONLINE && pending.add(Problem.QQ)) failures += when (qq) {
                QqState.LOGGED_OUT -> "QQ 已退出登录"
                QqState.VERIFICATION_REQUIRED -> "QQ 需要验证"
                else -> "QQ 已离线"
            }
            if (qq == QqState.ONLINE && pending.remove(Problem.QQ)) recoveries += "QQ 已重新上线"
            knownQq = qq
        }
        if (oneBot != OneBotState.UNKNOWN) {
            if (knownOneBot == OneBotState.CONNECTED && oneBot != OneBotState.CONNECTED && pending.add(Problem.ONEBOT)) failures += "OneBot 连接中断"
            if (oneBot == OneBotState.CONNECTED && pending.remove(Problem.ONEBOT)) recoveries += "OneBot 已重新连接"
            knownOneBot = oneBot
        }
        if (failures.isNotEmpty()) anomaly(failures.joinToString("；"), atMillis)
        if (recoveries.isNotEmpty()) history = history.copy(lastRecovery = ServiceEvent(atMillis, recoveries.joinToString("；")))
    }

    private fun anomaly(summary: String, atMillis: Long) {
        history = history.copy(lastAnomaly = ServiceEvent(atMillis,
            SecretRedactor.redact(summary).filter { !it.isISOControl() }.take(384)))
    }
}
