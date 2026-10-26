package io.astrbot.android.core

enum class ServiceId(val label: String, val defaultPort: Int) {
    ASTRBOT("AstrBot", 6185), NAPCAT("NapCat", 6099)
}
enum class ProcessState { STOPPED, STARTING, RUNNING, STOPPING, CRASHED }
enum class HealthState { UNKNOWN, WAITING, AVAILABLE, RECHECKING, UNRESPONSIVE, DISABLED }
enum class QqState { UNKNOWN, LOGGED_OUT, ONLINE, OFFLINE, VERIFICATION_REQUIRED }
enum class OneBotState { UNKNOWN, UNCONFIGURED, CONNECTING, CONNECTED, DISCONNECTED }
enum class MaintenanceState { IDLE, INSTALLING, BACKING_UP, UPDATING, RESTORING }

/** Stable status identity; UI layers supply translations. */
enum class ServiceSummary(val defaultText: String) {
    STARTING("启动中"),
    STOPPING("停止中"),
    CRASHED("异常退出"),
    STOPPED("已停止"),
    WEB_UI_DISABLED("运行中，WebUI 已关闭"),
    RECHECKING("后台复查中"),
    UNRESPONSIVE("后台无响应"),
    WAITING("等待后台就绪"),
    AVAILABLE("后台可访问"),
    QQ_LOGIN("QQ 需要登录"),
    QQ_VERIFY("QQ 需要验证"),
    QQ_OFFLINE("QQ 已离线"),
    QQ_UNKNOWN("QQ 状态待确认"),
    ONEBOT_CONFIG("等待配置 OneBot"),
    CONNECTED("QQ 在线，OneBot 已连接"),
    ONEBOT_OFFLINE("OneBot 已断开"),
    ONEBOT_CONNECTING("OneBot 连接中"),
    ONEBOT_UNKNOWN("OneBot 状态待确认");
}

data class ServiceStatus(
    val id: ServiceId,
    val desiredRunning: Boolean = false,
    val process: ProcessState = ProcessState.STOPPED,
    val health: HealthState = HealthState.UNKNOWN,
    val qq: QqState = QqState.UNKNOWN,
    val oneBot: OneBotState = OneBotState.UNKNOWN,
    val checkedAtMillis: Long? = null,
    val detail: String = "",
    val processGeneration: Long = 0,
    val history: ServiceHistory = ServiceHistory(),
) {
    // A recent failed probe should not unload a working page. RECHECKING is shown
    // explicitly, while readiness/maintenance still requires a fresh AVAILABLE probe.
    val canOpenWebUi: Boolean get() = process == ProcessState.RUNNING && (health == HealthState.AVAILABLE || health == HealthState.RECHECKING)

    fun summaryKind(): ServiceSummary = when {
        process == ProcessState.STARTING -> ServiceSummary.STARTING
        process == ProcessState.STOPPING -> ServiceSummary.STOPPING
        process == ProcessState.CRASHED -> ServiceSummary.CRASHED
        process == ProcessState.STOPPED -> ServiceSummary.STOPPED
        health == HealthState.DISABLED -> ServiceSummary.WEB_UI_DISABLED
        health == HealthState.RECHECKING -> ServiceSummary.RECHECKING
        health == HealthState.UNRESPONSIVE -> ServiceSummary.UNRESPONSIVE
        health != HealthState.AVAILABLE -> ServiceSummary.WAITING
        id == ServiceId.ASTRBOT -> ServiceSummary.AVAILABLE
        qq == QqState.LOGGED_OUT -> ServiceSummary.QQ_LOGIN
        qq == QqState.VERIFICATION_REQUIRED -> ServiceSummary.QQ_VERIFY
        qq == QqState.OFFLINE -> ServiceSummary.QQ_OFFLINE
        qq == QqState.UNKNOWN -> ServiceSummary.QQ_UNKNOWN
        oneBot == OneBotState.UNCONFIGURED -> ServiceSummary.ONEBOT_CONFIG
        oneBot == OneBotState.CONNECTED -> ServiceSummary.CONNECTED
        oneBot == OneBotState.DISCONNECTED -> ServiceSummary.ONEBOT_OFFLINE
        oneBot == OneBotState.CONNECTING -> ServiceSummary.ONEBOT_CONNECTING
        else -> ServiceSummary.ONEBOT_UNKNOWN
    }

    fun summary(): String = summaryKind().defaultText

    /** Reopening the app must never turn persisted business observations into fresh evidence. */
    fun awaitingReconciliation() = copy(
        health = HealthState.UNKNOWN, qq = QqState.UNKNOWN,
        oneBot = OneBotState.UNKNOWN, checkedAtMillis = null,
    )
}
