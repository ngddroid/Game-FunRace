package com.ngstudio.funrace.services

import com.ngstudio.funrace.data.DailyState
import com.ngstudio.funrace.data.Progress
import com.ngstudio.funrace.data.SaveData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class RemindersTest {
    private val tz = TimeZone.getTimeZone("Asia/Kolkata")

    private fun at(hour: Int, minute: Int): Long = Calendar.getInstance(tz).apply {
        set(2026, Calendar.NOVEMBER, 25, hour, minute, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun localTime(ms: Long): Pair<Int, Int> = Calendar.getInstance(tz).apply { timeInMillis = ms }.let {
        it.get(Calendar.HOUR_OF_DAY) to it.get(Calendar.MINUTE)
    }

    @Test
    fun remindersComeAtTheSameTimeOfDayTheyPlayed() {
        val now = at(17, 40)
        for (day in Reminders.DAYS) {
            val fire = now + Reminders.delayFor(day, now, tz)
            assertEquals(17 to 40, localTime(fire))
            assertEquals(day * 24L * 3600_000L, fire - now)
        }
    }

    @Test
    fun lateNightAndEarlyMorningPlayIsMovedIntoFriendlyHours() {
        val night = at(23, 50)
        assertEquals(20 to 30, localTime(night + Reminders.delayFor(1, night, tz)))
        val early = at(6, 15)
        assertEquals(10 to 0, localTime(early + Reminders.delayFor(1, early, tz)))
        // never sooner than about a day: a late-night player hears from us the next evening
        assertTrue(Reminders.delayFor(1, night, tz) > 20 * 3600_000L)
    }

    @Test
    fun messagesFollowWhatIsWaiting() {
        val today = 20_000L
        val giftReady = SaveData(daily = DailyState(loginLastDay = today - 1), progress = Progress(highestUnlocked = 42))
        assertTrue(Reminders.message(1, giftReady, today).first.contains("daily gift"))
        val claimed = giftReady.copy(daily = DailyState(loginLastDay = today))
        assertTrue(Reminders.message(1, claimed, today).first.contains("Free spin"))
        assertTrue(Reminders.message(3, giftReady, today).first.contains("Level 42"))
        assertTrue(Reminders.message(7, giftReady, today).first.contains("miss you"))
    }
}
