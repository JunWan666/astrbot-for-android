package io.astrbot.android.device

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import io.astrbot.android.AppText
import io.astrbot.android.DeviceAccessActivity
import io.astrbot.android.R
import kotlinx.coroutines.*

class DeviceBridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("device-access", AppText.get(R.string.device_access_title), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 30, Intent(this, DeviceAccessActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 31, Intent(this, DeviceBridgeService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(1030, Notification.Builder(this, "device-access").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(AppText.get(R.string.device_access_active)).setContentText(AppText.get(R.string.device_access_private))
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, AppText.get(R.string.ui_stop), stop).build()).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "start") runCatching { DeviceBridge.start(this, scope) }.onFailure { stopSelf() }
        else { DeviceBridge.stop(this); stopSelf() }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { DeviceBridge.stop(this); scope.cancel(); super.onDestroy() }
    companion object {
        fun setEnabled(context: Context, enabled: Boolean) {
            val intent = Intent(context, DeviceBridgeService::class.java).setAction(if (enabled) "start" else "stop")
            if (enabled) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
