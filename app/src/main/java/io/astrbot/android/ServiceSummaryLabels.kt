package io.astrbot.android

import io.astrbot.android.core.ServiceStatus
import io.astrbot.android.core.ServiceSummary

internal fun ServiceStatus.localizedSummary(): String = AppText.get(when (summaryKind()) {
    ServiceSummary.STARTING -> R.string.status_starting
    ServiceSummary.STOPPING -> R.string.status_stopping
    ServiceSummary.CRASHED -> R.string.status_crashed
    ServiceSummary.STOPPED -> R.string.status_stopped
    ServiceSummary.WEB_UI_DISABLED -> R.string.status_web_ui_disabled
    ServiceSummary.RECHECKING -> R.string.status_rechecking
    ServiceSummary.UNRESPONSIVE -> R.string.status_unresponsive
    ServiceSummary.WAITING -> R.string.status_waiting
    ServiceSummary.AVAILABLE -> R.string.status_available
    ServiceSummary.QQ_LOGIN -> R.string.status_qq_login
    ServiceSummary.QQ_VERIFY -> R.string.status_qq_verify
    ServiceSummary.QQ_OFFLINE -> R.string.status_qq_offline
    ServiceSummary.QQ_UNKNOWN -> R.string.status_qq_unknown
    ServiceSummary.ONEBOT_CONFIG -> R.string.status_onebot_config
    ServiceSummary.CONNECTED -> R.string.status_connected
    ServiceSummary.ONEBOT_OFFLINE -> R.string.status_onebot_offline
    ServiceSummary.ONEBOT_CONNECTING -> R.string.status_onebot_connecting
    ServiceSummary.ONEBOT_UNKNOWN -> R.string.status_onebot_unknown
})
