package com.aiguruapp.student

import android.app.Application
import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle

/**
 * Custom Application class.
 * Locks every activity to portrait orientation — prevents accidental landscape
 * rotation especially during BB lessons where the board layout is portrait-only.
 */
class AiGuruApp : Application() {

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
