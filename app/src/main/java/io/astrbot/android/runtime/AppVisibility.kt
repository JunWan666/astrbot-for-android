package io.astrbot.android.runtime

import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections
import java.util.IdentityHashMap

/** Main-thread lifecycle callbacks count all visible app activities, including overlapping ones. */
object AppVisibility {
    private val activities = Collections.newSetFromMap(IdentityHashMap<Activity, Boolean>())
    private val mutableVisible = MutableStateFlow(false)
    val visible = mutableVisible.asStateFlow()

    internal fun started(activity: Activity) {
        activities.add(activity)
        mutableVisible.value = activities.isNotEmpty()
    }

    internal fun stopped(activity: Activity) {
        activities.remove(activity)
        mutableVisible.value = activities.isNotEmpty()
    }
}
