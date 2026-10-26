package io.astrbot.android

import android.app.LocaleManager
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

enum class AppLanguage(val tag: String, val displayName: String) {
    SYSTEM("", "System default / 跟随系统"), CHINESE("zh-CN", "简体中文"), ENGLISH("en", "English");

    companion object {
        fun selected(context: Context): AppLanguage {
            val tag = if (Build.VERSION.SDK_INT >= 33)
                context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
            else context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("language", "").orEmpty()
            return entries.firstOrNull { it.tag == tag } ?: when {
                tag.startsWith("zh", ignoreCase = true) -> CHINESE
                tag.startsWith("en", ignoreCase = true) -> ENGLISH
                else -> SYSTEM
            }
        }

        fun set(context: Context, language: AppLanguage) {
            context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit()
                .putString("language", language.tag).apply()
            if (Build.VERSION.SDK_INT >= 33) {
                context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language.tag)
            }
            AppText.invalidate()
        }

        fun context(context: Context): Context {
            val selected = selected(context)
            val locales = if (selected == SYSTEM) {
                if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).systemLocales
                else android.content.res.Resources.getSystem().configuration.locales
            } else LocaleList.forLanguageTags(selected.tag)
            val configuration = Configuration(context.resources.configuration).apply { setLocales(locales) }
            return context.createConfigurationContext(configuration)
        }
    }
}

/** Explicit resource lookups, also available to background work and notification builders. */
@SuppressLint("StaticFieldLeak") // Both cached contexts derive only from applicationContext.
object AppText {
    private lateinit var application: Context
    @Volatile private var localized: Context? = null
    val languageChanged = MutableStateFlow(0)

    fun initialize(context: Context) { application = context.applicationContext }
    fun invalidate() { localized = null; languageChanged.value++ }
    private fun resources() = (localized ?: synchronized(this) {
        localized ?: AppLanguage.context(application).also { localized = it }
    }).resources
    val locale: Locale get() = resources().configuration.locales[0]
    fun get(@StringRes resource: Int, vararg values: Any?): String {
        val resources = resources()
        return if (values.isEmpty()) resources.getString(resource) else resources.getString(resource, *values)
    }
}

/** API 26–32 use a localized activity context; API 33+ also support system per-app language settings. */
abstract class LocalizedActivity : ComponentActivity() {
    private var languageAtCreation: AppLanguage? = null
    override fun attachBaseContext(base: Context) { super.attachBaseContext(AppLanguage.context(base)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        languageAtCreation = AppLanguage.selected(this)
        super.onCreate(savedInstanceState)
    }
    override fun onResume() {
        super.onResume()
        if (languageAtCreation != AppLanguage.selected(this)) recreate()
    }
}
