package io.astrbot.android

import io.astrbot.android.runtime.SnowLumaInitialCredentials
import org.json.JSONObject
import java.net.URI

/** Only the dedicated local extension browser receives the one-time bootstrap password. */
internal object SnowLumaLogin {
    fun accepts(url: String?): Boolean = runCatching {
        val uri = URI(url ?: return false)
        uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == 5099 && uri.rawUserInfo == null
    }.getOrDefault(false)

    fun script(template: String, credentials: SnowLumaInitialCredentials): String =
        template.replace("__INITIAL_PASSWORD__", JSONObject.quote(credentials.password))
}
