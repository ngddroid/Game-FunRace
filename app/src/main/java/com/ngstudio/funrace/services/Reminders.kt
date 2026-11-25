package com.ngstudio.funrace.services

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ngstudio.funrace.app.container
import com.ngstudio.funrace.data.GameRules
import com.ngstudio.funrace.data.SaveData
import com.ngstudio.funrace.data.TimeUtil
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Local "come back" reminders. When the game goes to the background three are booked, 1, 3 and 7
 * days later at about the time of day the player was playing (kept between 10:00 and 20:30). Opening
 * the game cancels them, and leaving books them again from that moment, so an active player never
 * gets one. WorkManager keeps them across reboots.
 */
object Reminders {
    private const val TAG = "comeback"
    val DAYS = intArrayOf(1, 3, 7)
    private const val EARLIEST_MIN = 10 * 60
    private const val LATEST_MIN = 20 * 60 + 30

    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.cancelAllWorkByTag(TAG)
        val now = System.currentTimeMillis()
        for (day in DAYS) {
            val req = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(delayFor(day, now, TimeZone.getDefault()), TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_DAY to day))
                .addTag(TAG)
                .build()
            wm.enqueueUniqueWork("$TAG$day", ExistingWorkPolicy.REPLACE, req)
        }
    }

    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelAllWorkByTag(TAG)
    }

    /** Milliseconds from [nowMs] to [days] days later, moved into the friendly hours of that day. */
    fun delayFor(days: Int, nowMs: Long, tz: TimeZone): Long {
        val cal = Calendar.getInstance(tz).apply { timeInMillis = nowMs; add(Calendar.DAY_OF_YEAR, days) }
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val target = minute.coerceIn(EARLIEST_MIN, LATEST_MIN)
        if (target != minute) {
            cal.set(Calendar.HOUR_OF_DAY, target / 60)
            cal.set(Calendar.MINUTE, target % 60)
            cal.set(Calendar.SECOND, 0)
        }
        return (cal.timeInMillis - nowMs).coerceAtLeast(60_000L)
    }

    /** Title and text for the reminder [day] days after the last session. */
    fun message(day: Int, d: SaveData, today: Long): Pair<String, String> {
        val level = d.progress.highestUnlocked
        return when {
            day <= 1 && GameRules.calendarAvailable(d.daily, today) ->
                "Your daily gift is ready! 🎁" to "Pop in to open today's reward and keep your streak going."
            day <= 1 -> "Free spin waiting! 🎡" to "Your Lucky Wheel spin is ready. What will you win?"
            day <= 3 -> "Level $level is calling! 🏁" to "The racers are lining up. Can you splash your way to the crown?"
            else -> "We miss you! 💦" to "New obstacles, new splashes, and a free daily gift waiting for you."
        }
    }

    const val KEY_DAY = "day"
    /** Notification ids 100+day, so a newer reminder replaces an older one still showing. */
    fun notificationId(day: Int) = 100 + day.coerceAtMost(9)
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val day = inputData.getInt(Reminders.KEY_DAY, 1)
        val app = applicationContext
        val d = app.container.save.data
        if (!d.settings.notifications) return Result.success()
        val (title, body) = Reminders.message(day, d, TimeUtil.today())
        // one reminder at a time: the newest replaces any still in the tray
        for (other in Reminders.DAYS) if (other != day) androidx.core.app.NotificationManagerCompat.from(app).cancel(Reminders.notificationId(other))
        Notifications.post(app, Reminders.notificationId(day), Notifications.CH_REMINDERS, title, body, "reminder_$day")
        Telemetry.event("reminder_shown", "day" to day)
        return Result.success()
    }
}
