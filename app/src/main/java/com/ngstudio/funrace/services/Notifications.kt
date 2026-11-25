package com.ngstudio.funrace.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ngstudio.funrace.MainActivity
import com.ngstudio.funrace.R

/** Notification channels and posting, shared by the local reminders and push messages. */
object Notifications {
    /** Come-back reminders scheduled on the phone ([Reminders]). */
    const val CH_REMINDERS = "reminders"
    /** Messages sent from the Firebase console (new levels, events). Also the FCM default channel. */
    const val CH_NEWS = "news"
    /** Intent extra on a notification tap: where it came from ("reminder_1", "push" ...). */
    const val EXTRA_SOURCE = "notif_source"

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_REMINDERS, "Daily rewards", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A heads-up when your daily gift and free spin are ready"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_NEWS, "News & events", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "New levels, events and updates"
            },
        )
    }

    /** Android 13+ asks before an app may post notifications. */
    fun needsPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    /** True when a notification would actually show (permission granted, not blocked in system settings). */
    fun allowed(ctx: Context): Boolean = !needsPermission(ctx) && NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    @SuppressLint("MissingPermission") // checked by allowed()
    fun post(ctx: Context, id: Int, channel: String, title: String, body: String, source: String) {
        if (!allowed(ctx)) return
        val open = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_SOURCE, source)
        val tap = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_splash)
            .setColor(ContextCompat.getColor(ctx, R.color.ocean))
            .setLargeIcon(avatar(ctx))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(if (channel == CH_REMINDERS) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (t: SecurityException) { Telemetry.nonFatal(t) }
    }

    /**
     * Head and shoulders of the launch-screen racer (a 432 px image with the racer in its middle),
     * so they read at notification size, even when Android crops the picture to a circle.
     */
    private fun avatar(ctx: Context): Bitmap? = try {
        BitmapFactory.decodeResource(ctx.resources, R.drawable.splash_icon)?.let { src ->
            val k = src.width / 432f
            val crop = Bitmap.createBitmap(src, (150 * k).toInt(), (98 * k).toInt(), (132 * k).toInt(), (132 * k).toInt())
            Bitmap.createScaledBitmap(crop, 160, 160, true)
        }
    } catch (_: Throwable) { null }
}
