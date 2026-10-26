package io.astrbot.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.astrbot.android.runtime.AppVisibility

class ManagerApplication : Application(), Application.ActivityLifecycleCallbacks {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) = AppVisibility.started(activity)
    override fun onActivityStopped(activity: Activity) = AppVisibility.stopped(activity)
    override fun onActivityDestroyed(activity: Activity) = AppVisibility.stopped(activity)
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
