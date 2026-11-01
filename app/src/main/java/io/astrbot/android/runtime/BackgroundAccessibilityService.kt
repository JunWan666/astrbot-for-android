package io.astrbot.android.runtime

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.ref.WeakReference

/** An optional system-bound background helper. No screen, gesture, key or event access. */
class BackgroundAccessibilityService : AccessibilityService() {
    private var overlay: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Ignore events even if a vendor populates defaults for omitted XML attributes.
        serviceInfo = serviceInfo.apply { eventTypes = 0 }
        instance = WeakReference(this)
        attachOverlay()
        connected.value = true
        BackgroundRecovery.request(this, "accessibility-connected")
    }

    private fun attachOverlay() {
        if (overlay != null) return
        val marker = View(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val parameters = WindowManager.LayoutParams(1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 0f
            title = "AstrBot background assistance"
        }
        overlayAttached.value = runCatching {
            getSystemService(WindowManager::class.java).addView(marker, parameters)
            overlay = marker
        }.isSuccess
    }

    private fun disconnect() {
        overlay?.let { marker -> runCatching { getSystemService(WindowManager::class.java).removeViewImmediate(marker) } }
        overlay = null
        if (instance.get() === this) {
            instance.clear()
            connected.value = false
            overlayAttached.value = false
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onUnbind(intent: Intent?): Boolean { disconnect(); return super.onUnbind(intent) }
    override fun onDestroy() { disconnect(); super.onDestroy() }

    companion object {
        @Volatile private var instance = WeakReference<BackgroundAccessibilityService>(null)
        internal val connected = MutableStateFlow(false)
        internal val overlayAttached = MutableStateFlow(false)

        internal fun enabled(context: Context): Boolean = runCatching {
            val component = ComponentName(context, BackgroundAccessibilityService::class.java)
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
        }.getOrDefault(false)

        internal fun disable(): Boolean = instance.get()?.let { service ->
            service.disableSelf()
            service.disconnect()
            true
        } ?: false

        internal fun settings(context: Context): List<Intent> = listOf(
            Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(context, BackgroundAccessibilityService::class.java)),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        )
    }
}
