package io.astrbot.android

import android.app.ActivityManager
import android.content.Context

/** Changes only this app's task cards; its foreground services keep their lifecycle. */
internal object RecentTaskVisibility {
    private const val KEY = "hide-recent-task"
    fun hidden(context: Context): Boolean = BackgroundGuide.preferences(context).getBoolean(KEY, false)

    fun apply(context: Context, hidden: Boolean = this.hidden(context)) {
        context.getSystemService(ActivityManager::class.java).appTasks.forEach { it.setExcludeFromRecents(hidden) }
    }

    fun setHidden(context: Context, hidden: Boolean): Boolean = runCatching {
        apply(context, hidden)
        BackgroundGuide.preferences(context).edit().putBoolean(KEY, hidden).apply()
    }.isSuccess
}
