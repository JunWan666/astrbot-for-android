package io.astrbot.android.runtime

import io.astrbot.android.AppText
import io.astrbot.android.R
import io.astrbot.android.core.ServiceId
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Uses AstrBot's authenticated local API. Never writes its configuration behind its back. */
internal class PlatformDashboardClient(
    private val origin: String,
    private val persisted: (suspend (JSONObject) -> Boolean)? = null,
    private val isCurrent: () -> Boolean,
) : AutoCloseable {
    enum class SaveResult { SAVED, STORED_AFTER_ERROR }
    private val http = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS).callTimeout(50, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    @Volatile private var token: String? = null
    val authenticated get() = token != null && isCurrent()

    init {
        val uri = java.net.URI(origin)
        require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port in 1..65535 &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.isNullOrEmpty())
    }

    suspend fun login(username: String, password: String, code: String): Boolean {
        token = null
        val data = request("/api/auth/login", JSONObject().put("username", username).put("password", password)
            .apply { if (code.isNotBlank()) put("code", code) }, login = true)
        // Keep upstream's mandatory first-login/password-upgrade flow intact.
        check(!data.optBoolean("change_pwd_hint") && !data.optBoolean("password_upgrade_required")) {
            AppText.get(R.string.platform_finish_setup)
        }
        val value = data.optString("token")
        check(value.isNotBlank() && value.length <= 16384) { AppText.get(R.string.platform_login_failed) }
        token = value
        return true
    }

    suspend fun connectLocal(secret: String) {
        require(secret.matches(Regex("[0-9a-f]{64}")))
        token = null
        val data = request("/api/v1/auth/desktop-session", JSONObject(), login = true, sessionSecret = secret)
        val value = data.optString("token")
        check(value.isNotBlank() && value.length <= 16384) { AppText.get(R.string.platform_request_failed) }
        token = value
    }

    suspend fun list(platform: MessagePlatform): List<JSONObject> = all().filter { it.optString("type") in platform.types }

    private suspend fun all(): List<JSONObject> {
        val array = request("/api/config/platform/list").optJSONArray("platforms")
            ?: throw IllegalStateException(AppText.get(R.string.platform_api_unsupported))
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    suspend fun save(platform: MessagePlatform, original: JSONObject?, changed: JSONObject): SaveResult {
        require(changed.optString("type") in platform.types)
        val id = changed.optString("id").trim()
        require(id.isNotEmpty() && id.length <= 128 && id.none { it.isISOControl() }) { AppText.get(R.string.platform_invalid_id) }
        val latest = all().filter { it.optString("id") == id }
        // A timed-out write may already be stored. Retrying must not duplicate the bot.
        val matches = latest.size == 1 && containsConfig(latest.single(), changed)
        if (matches && (persisted?.invoke(changed) != false)) return SaveResult.SAVED
        if (!matches && original == null) {
            check(latest.isEmpty()) { AppText.get(R.string.platform_duplicate_id) }
        } else if (!matches) {
            requireNotNull(original)
            check(original.optString("id") == id && latest.size == 1 && sameJson(original, latest.single())) {
                AppText.get(R.string.platform_config_changed)
            }
            // All unknown/advanced fields are retained from the original configuration.
        }
        try {
            if (original == null && !matches) request("/api/config/platform/new", changed)
            else {
                // Upstream can append to its in-memory list before a failed disk save.
                // Retrying that exact request must update it while retaining server-added fields.
                val payload = if (matches) JSONObject(latest.single().toString()).apply {
                    changed.keys().forEach { put(it, changed.get(it)) }
                } else changed
                request("/api/config/platform/update", JSONObject().put("id", id).put("config", payload))
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val saved = try { all().filter { it.optString("id") == id }.singleOrNull()?.let { containsConfig(it, changed) } == true }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            if (saved && persisted?.invoke(changed) != false) return SaveResult.STORED_AFTER_ERROR
            throw failure
        }
        return SaveResult.SAVED
    }

    suspend fun registration(config: JSONObject, previous: JSONObject? = null): JSONObject {
        val type = config.optString("type")
        require(type in MessagePlatform.entries.flatMap { it.types })
        val payload = JSONObject().put("action", if (previous == null) "start" else "poll").put("platform_config", config)
        previous?.let {
            payload.put("registration_code", it.getString("registration_code"))
            if (type in MessagePlatform.QQ.types) {
                payload.put("task_id", it.getString("task_id")).put("bind_key", it.getString("bind_key"))
            }
        }
        return request("/api/platform/registration/$type", payload)
    }

    suspend fun delete(platform: MessagePlatform, original: JSONObject) {
        require(original.optString("type") in platform.types)
        val id = original.getString("id")
        val latest = all().filter { it.optString("id") == id }
        check(latest.size == 1 && sameJson(original, latest.single())) {
            AppText.get(R.string.platform_config_changed)
        }
        // Let AstrBot stop this adapter and persist the removal through its official API.
        request("/api/config/platform/delete", JSONObject().put("id", id))
    }

    private suspend fun request(path: String, payload: JSONObject? = null, login: Boolean = false, sessionSecret: String? = null): JSONObject {
        check(isCurrent()) { AppText.get(R.string.platform_service_required) }
        val registration = path.startsWith("/api/platform/registration/")
        val saving = path in setOf("/api/config/platform/new", "/api/config/platform/update")
        val builder = Request.Builder().url(origin + path)
        sessionSecret?.let { builder.header("X-AstrBot-Desktop-Session", it) }
        if (!login) builder.header("Authorization", "Bearer ${token ?: error(AppText.get(R.string.platform_login_required))}")
        if (payload != null) builder.post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        val call = http.newCall(builder.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(AppText.get(
                        if (registration) R.string.platform_registration_failed
                        else if (saving) R.string.platform_save_failed else R.string.platform_request_failed)))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use {
                        check(isCurrent()) { AppText.get(R.string.platform_service_required) }
                        val body = it.body ?: error(AppText.get(R.string.platform_request_failed))
                        val source = body.source()
                        source.request(2L * 1024 * 1024 + 1)
                        check(source.buffer.size <= 2L * 1024 * 1024) { AppText.get(R.string.platform_request_failed) }
                        val json = runCatching { JSONObject(source.readUtf8()) }.getOrNull()
                        if (!it.isSuccessful || json?.optString("status") != "ok") {
                            // Record response metadata only; upstream error bodies can contain credentials.
                            android.util.Log.w("PlatformDashboard", "request failed path=$path http=${it.code} json=${json != null}")
                        }
                        if (it.code == 401) {
                            token = null
                            error(AppText.get(if (sessionSecret != null) R.string.platform_session_restart
                                else if (json?.optJSONObject("data")?.optBoolean("totp_required") == true)
                                R.string.platform_totp_required else if (login) R.string.platform_login_failed else R.string.platform_login_expired))
                        }
                        check(it.code !in setOf(404, 405)) { AppText.get(R.string.platform_api_unsupported) }
                        check(it.isSuccessful && json?.optString("status") == "ok") {
                            AppText.get(if (registration) R.string.platform_registration_failed
                                else if (sessionSecret != null) R.string.platform_session_restart
                                else if (login) R.string.platform_login_failed
                                else if (saving) R.string.platform_save_failed
                                else R.string.platform_request_failed)
                        }
                        json!!.optJSONObject("data") ?: JSONObject()
                    } }
                    if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
                }
            })
        }
    }

    override fun close() { token = null; http.dispatcher.cancelAll(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }

    companion object {
        internal fun savedInFile(file: java.io.File, requested: JSONObject): Boolean = runCatching {
            require(file.isFile && file.length() <= 8L * 1024 * 1024)
            val configs = JSONObject(file.readText()).getJSONArray("platform")
            (0 until configs.length()).map { configs.getJSONObject(it) }
                .filter { it.optString("id") == requested.optString("id") }
                .singleOrNull()?.let { containsConfig(it, requested) } == true
        }.getOrDefault(false)
        private fun containsConfig(saved: JSONObject, requested: JSONObject) = requested.keys().asSequence().all {
            saved.has(it) && sameJson(saved.opt(it), requested.opt(it))
        }
        internal fun sameJson(a: Any?, b: Any?): Boolean = when {
            a is JSONObject && b is JSONObject -> a.keys().asSequence().toSet() == b.keys().asSequence().toSet() &&
                a.keys().asSequence().all { sameJson(a.opt(it), b.opt(it)) }
            a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { sameJson(a.opt(it), b.opt(it)) }
            else -> a == b
        }
        fun defaultConfig(platform: MessagePlatform) = if (platform == MessagePlatform.QQ) JSONObject()
            .put("id", "qq_official").put("type", "qq_official").put("enable", true)
            .put("appid", "").put("secret", "").put("enable_group_c2c", true)
            .put("enable_guild_direct_message", true).put("use_markdown", true)
        else JSONObject().put("id", "weixin_clawbot").put("type", "weixin_oc").put("enable", true)
            .put("weixin_oc_base_url", "https://ilinkai.weixin.qq.com").put("weixin_oc_bot_type", "3")
            .put("weixin_oc_qr_poll_interval", 1).put("weixin_oc_long_poll_timeout_ms", 35000).put("weixin_oc_api_timeout_ms", 120000)
    }
}
