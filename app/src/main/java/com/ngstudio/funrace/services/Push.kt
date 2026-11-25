package com.ngstudio.funrace.services

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.ngstudio.funrace.BuildConfig
import com.ngstudio.funrace.app.container

/**
 * Firebase Cloud Messaging. Every install joins the "all" topic (plus "debug" for debug builds), so
 * a message can go to everyone from the Firebase console's Messaging page or to test phones only.
 * Turning notifications off in Settings drops the token and the topics, so nothing arrives at all.
 */
object Push {
    const val TOPIC_ALL = "all"
    const val TOPIC_DEBUG = "debug"
    private const val LOG = "Push"

    fun setEnabled(on: Boolean) {
        try {
            val fm = FirebaseMessaging.getInstance()
            if (on) {
                fm.isAutoInitEnabled = true
                fm.subscribeToTopic(TOPIC_ALL)
                if (BuildConfig.DEBUG) {
                    fm.subscribeToTopic(TOPIC_DEBUG)
                    fm.token.addOnSuccessListener { Log.i(LOG, "FCM token: $it") }
                }
            } else {
                fm.isAutoInitEnabled = false
                fm.unsubscribeFromTopic(TOPIC_ALL)
                fm.unsubscribeFromTopic(TOPIC_DEBUG)
                fm.deleteToken()
            }
        } catch (t: Throwable) {
            Log.w(LOG, "push setup failed", t)
        }
    }
}

class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        if (BuildConfig.DEBUG) Log.i("Push", "new FCM token: $token")
        if (applicationContext.container.save.data.settings.notifications) Push.setEnabled(true)
    }

    /**
     * Called for data messages, and for notification messages while the game is open (in the
     * background the system shows those itself, on the default channel from the manifest).
     */
    override fun onMessageReceived(message: RemoteMessage) {
        val ctx: Context = applicationContext
        if (!ctx.container.save.data.settings.notifications) return
        val n = message.notification
        val title = n?.title ?: message.data["title"] ?: return
        val body = n?.body ?: message.data["body"] ?: ""
        // only the game's own channels exist on the phone; anything else would never show
        val channel = (n?.channelId ?: message.data["channel"]).takeIf { it == Notifications.CH_NEWS || it == Notifications.CH_REMINDERS } ?: Notifications.CH_NEWS
        val id = 200 + ((message.messageId ?: title).hashCode() and 0xFFFF)
        Notifications.post(ctx, id, channel, title, body, "push")
    }
}
