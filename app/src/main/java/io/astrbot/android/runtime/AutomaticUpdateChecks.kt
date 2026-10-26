package io.astrbot.android.runtime

import io.astrbot.android.checkLocalized

import io.astrbot.android.AppText
import io.astrbot.android.R

import android.content.Context
import android.content.SharedPreferences
import io.astrbot.android.BuildConfig
import io.astrbot.android.core.AutomaticUpdatePolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

internal class AutomaticUpdateSettings(context: Context) {
    private val preferences = context.getSharedPreferences("automatic_updates", Context.MODE_PRIVATE)
    val enabled get() = preferences.getBoolean("enabled", false)
    fun setEnabled(value: Boolean) { checkLocalized(preferences.edit().putBoolean("enabled", value).commit()) { AppText.get(R.string.ui_could_not_save_update_preferences_check_free_storage) } }
    fun observeEnabled() = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "enabled") trySend(enabled) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(enabled)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().distinctUntilChanged()
    fun isDue(scope: String, now: Long) = AutomaticUpdatePolicy.isDue(now,
        preferences.getLong("success_$scope", 0), preferences.getLong("attempt_$scope", 0))
    fun attempted(scope: String, now: Long) { check(preferences.edit().putLong("attempt_$scope", now).commit()) }
    fun succeeded(scope: String, now: Long) {
        check(preferences.edit().putLong("attempt_$scope", now).putLong("success_$scope", now).commit())
    }
}

/** Runs only while the manager is visible. Downloads catalog metadata; never packages or installs.
 * A single in-process flight also covers Activity recreation. The service gate is released for IO.
 */
internal class AutomaticUpdateChecks(
    private val context: Context,
    private val source: String = BuildConfig.UPDATE_CATALOG_URL,
    private val providedClient: OkHttpClient? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val gate: Mutex = RuntimeStore.operationLock,
    private val publish: (OnlineUpdateCheck) -> Unit = { result ->
        RuntimeStore.state.update { it.copy(availableUpdate = result.candidate, latestUpdate = result.latest,
            notice = if (result.candidate != null) result.message else it.notice) }
    },
) {
    val settings = AutomaticUpdateSettings(context)
    private val sourceScope by lazy { if (source.isEmpty()) null else runCatching { OnlineUpdateRepository.scopeFor(context, source) }.getOrNull() }
    val configured get() = sourceScope != null

    suspend fun checkIfDue(eligible: () -> Boolean): Unit = withContext(Dispatchers.IO) {
        if (!flight.tryLock()) return@withContext
        var repository: OnlineUpdateRepository? = null
        try {
            val scope = sourceScope ?: return@withContext
            val reserved = gate.withLock {
                if (!settings.enabled || !eligible() || !settings.isDue(scope, now())) false
                else { settings.attempted(scope, now()); true }
            }
            if (!reserved) return@withContext
            repository = OnlineUpdateRepository(context, source, providedClient, now)
            val envelope = withTimeout(30_000) { repository.fetchCatalog() }
            gate.withLock {
                currentCoroutineContext().ensureActive()
                // User may disable the option, leave the app or begin maintenance during IO.
                if (settings.enabled && eligible()) {
                    val result = repository.acceptCatalog(envelope)
                    settings.succeeded(scope, now())
                    publish(result)
                }
            }
        } catch (_: TimeoutCancellationException) {
            // Attempt was saved before IO; subsequent foreground entries respect the cooldown.
        } catch (failure: CancellationException) { throw failure }
        catch (_: Exception) {
            // A background metadata check must not replace service errors or disable controls.
        } finally {
            repository?.close()
            flight.unlock()
        }
    }

    companion object { private val flight = Mutex() }
}
