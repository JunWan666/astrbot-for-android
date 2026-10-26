package io.astrbot.android.runtime

import io.astrbot.android.core.LocalEndpoint
import io.astrbot.android.core.QqState
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest

/** API adapter for the pinned 4.18.41 release. An unsupported response means UNKNOWN. */
data class QqObservation(val state: QqState, val account: String? = null)

class NapCatHealth(private val client: OkHttpClient, private val endpoint: LocalEndpoint) {
    private var credential: String? = null
    private var credentialUntil = 0L
    private var lastToken: String? = null

    fun queryQq(token: String): QqState = query(token).state

    fun query(token: String): QqObservation = runCatching {
        if (token != lastToken || System.currentTimeMillis() >= credentialUntil) {
            credential = null
            lastToken = token
            val hash = MessageDigest.getInstance("SHA-256").digest((token + ".napcat").toByteArray())
                .joinToString("") { "%02x".format(it) }
            val auth = post("/api/auth/login", JSONObject().put("hash", hash), null).getJSONObject("data")
            // Enabling 2FA intentionally makes unattended health authentication unavailable.
            if (auth.optBoolean("require2FA")) return QqObservation(QqState.UNKNOWN)
            credential = auth.getString("Credential")
            credentialUntil = System.currentTimeMillis() + 50 * 60 * 1000L
        }
        val result = post("/api/QQLogin/CheckLoginStatus", JSONObject(), credential).getJSONObject("data")
        val state = when {
            result.optBoolean("isLogin") && result.optBoolean("coreReady") -> QqState.ONLINE
            result.optBoolean("isOffline") -> QqState.OFFLINE
            result.optString("loginPhase") in setOf("waiting_qrcode", "generating_qrcode") -> QqState.LOGGED_OUT
            result.optString("loginPhase") in setOf("qrcode_scanned", "initializing") -> QqState.UNKNOWN
            result.optString("loginPhase") in setOf("offline", "reconnecting") -> QqState.OFFLINE
            result.optString("loginPhase") in setOf("captcha", "verification", "new_device") -> QqState.VERIFICATION_REQUIRED
            else -> QqState.UNKNOWN
        }
        // The account stays in memory only. A login form or another OneBot account cannot
        // establish that this NapCat instance is connected to AstrBot.
        val account = if (state == QqState.ONLINE) runCatching {
            val info = post("/api/QQLogin/GetQQLoginInfo", JSONObject(), credential).getJSONObject("data")
            info.optString("uin").takeIf { info.optBoolean("online") && it.matches(Regex("[1-9][0-9]{4,19}")) }
        }.getOrNull() else null
        QqObservation(state, account)
    }.getOrElse { credentialUntil = 0; QqObservation(QqState.UNKNOWN) }

    private fun post(path: String, body: JSONObject, auth: String?): JSONObject {
        val request = Request.Builder().url(endpoint.origin + path)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (auth != null) request.header("Authorization", "Bearer $auth")
        return client.newCall(request.build()).execute().use {
            check(it.isSuccessful)
            val result = JSONObject(it.peekBody(64 * 1024).string())
            check(result.optInt("code", -1) == 0)
            result
        }
    }
}
