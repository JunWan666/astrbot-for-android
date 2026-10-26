package io.astrbot.android.core

enum class ServiceId(val label: String, val defaultPort: Int) {
    ASTRBOT("AstrBot", 6185), NAPCAT("NapCat", 6099)
}
enum class ProcessState { STOPPED, STARTING, RUNNING, STOPPING, CRASHED }
enum class HealthState { UNKNOWN, WAITING, AVAILABLE, RECHECKING, UNRESPONSIVE, DISABLED }
enum class QqState { UNKNOWN, LOGGED_OUT, ONLINE, OFFLINE, VERIFICATION_REQUIRED }
enum class OneBotState { UNKNOWN, UNCONFIGURED, CONNECTING, CONNECTED, DISCONNECTED }
enum class MaintenanceState { IDLE, INSTALLING, BACKING_UP, UPDATING, RESTORING }

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

    fun summary(): String = when {
        process == ProcessState.STARTING -> "启动中"
        process == ProcessState.STOPPING -> "停止中"
        process == ProcessState.CRASHED -> "异常退出"
        process == ProcessState.STOPPED -> "已停止"
        health == HealthState.DISABLED -> "运行中，WebUI 已关闭"
        health == HealthState.RECHECKING -> "后台复查中"
        health == HealthState.UNRESPONSIVE -> "后台无响应"
        health != HealthState.AVAILABLE -> "等待后台就绪"
        id == ServiceId.ASTRBOT -> "后台可访问"
        qq == QqState.LOGGED_OUT -> "QQ 需要登录"
        qq == QqState.VERIFICATION_REQUIRED -> "QQ 需要验证"
        qq == QqState.OFFLINE -> "QQ 已离线"
        qq == QqState.UNKNOWN -> "QQ 状态待确认"
        oneBot == OneBotState.UNCONFIGURED -> "等待配置 OneBot"
        oneBot == OneBotState.CONNECTED -> "QQ 在线，OneBot 已连接"
        oneBot == OneBotState.DISCONNECTED -> "OneBot 已断开"
        oneBot == OneBotState.CONNECTING -> "OneBot 连接中"
        else -> "OneBot 状态待确认"
    }

    /** Reopening the app must never turn persisted business observations into fresh evidence. */
    fun awaitingReconciliation() = copy(
        health = HealthState.UNKNOWN, qq = QqState.UNKNOWN,
        oneBot = OneBotState.UNKNOWN, checkedAtMillis = null,
    )
}
