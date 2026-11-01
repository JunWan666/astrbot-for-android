package io.astrbot.android

import android.content.Intent
import android.os.SystemClock
import androidx.core.net.toUri

/** Only wallet links are handed to apps. Never trust actions, components or grants in an intent URI. */
internal object WebsiteAppLinks {
    class Target(val name: Int, val intent: Intent) {
        override fun toString() = "WebsiteAppLinks.Target([redacted])"
    }

    fun parse(value: String): Target? = runCatching {
        require(value.length in 1..32768 && value.none { it.isISOControl() })
        val parsedData = if (value.startsWith("intent:", ignoreCase = true))
            Intent.parseUri(value, Intent.URI_INTENT_SCHEME).data else value.toUri()
        val data = requireNotNull(parsedData).normalizeScheme()
        val (name, app) = when (data.scheme?.lowercase()) {
            "weixin" -> R.string.websites_wechat to "com.tencent.mm"
            "alipays", "alipay" -> R.string.websites_alipay to "com.eg.android.AlipayGphone"
            else -> return null
        }
        Target(name, Intent(Intent.ACTION_VIEW, data).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(app))
    }.getOrNull()
}

/** A wallet redirect may follow a form submission or network response after the actual tap. */
internal class WebsiteGestureWindow {
    private var lastGesture: Long? = null
    fun record() { lastGesture = SystemClock.elapsedRealtime() }
    fun recent(): Boolean = lastGesture?.let { SystemClock.elapsedRealtime() - it in 0L..10_000L } == true
    fun consume() { lastGesture = null }
}
