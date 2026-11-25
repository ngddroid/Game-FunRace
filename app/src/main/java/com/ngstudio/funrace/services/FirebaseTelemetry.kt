package com.ngstudio.funrace.services

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics

/** Sends [GameEvents] to Google Analytics for Firebase and keeps Crashlytics' crash context current. */
class FirebaseTelemetry(context: Context) : EventSink {
    private val analytics = FirebaseAnalytics.getInstance(context)
    private val crashlytics = FirebaseCrashlytics.getInstance()

    /** The "Send usage & crash reports" setting. Both SDKs remember it across launches. */
    fun setCollectionEnabled(on: Boolean) {
        analytics.setAnalyticsCollectionEnabled(on)
        crashlytics.setCrashlyticsCollectionEnabled(on)
    }

    override fun event(name: String, params: Map<String, Any?>) {
        val b = Bundle()
        for ((k, v) in params) when (v) {
            null -> Unit
            is String -> b.putString(k, v.take(100))
            is Boolean -> b.putLong(k, if (v) 1L else 0L)
            is Int -> b.putLong(k, v.toLong())
            is Long -> b.putLong(k, v)
            is Float -> b.putDouble(k, v.toDouble())
            is Double -> b.putDouble(k, v)
            else -> b.putString(k, v.toString().take(100))
        }
        analytics.logEvent(name, b)
        // the last events before a crash show up in its Crashlytics log
        crashlytics.log(if (params.isEmpty()) name else "$name $params")
    }

    override fun userProperty(name: String, value: String?) = analytics.setUserProperty(name, value?.take(36))

    override fun context(key: String, value: String) = crashlytics.setCustomKey(key, value)

    override fun nonFatal(t: Throwable) = crashlytics.recordException(t)
}
