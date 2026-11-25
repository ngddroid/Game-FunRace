package com.ngstudio.funrace.services

import com.ngstudio.funrace.data.Reward

/**
 * Where game events and crash context go. The app installs the Firebase sink at start-up
 * ([FirebaseTelemetry]); unit tests and the level tools keep the silent default, so the game code
 * that reports events never depends on Firebase.
 */
interface EventSink {
    fun event(name: String, params: Map<String, Any?> = emptyMap())
    fun userProperty(name: String, value: String?) {}
    /** A crash-report key: the value shows on every crash that happens after it is set. */
    fun context(key: String, value: String) {}
    fun nonFatal(t: Throwable) {}
}

object Telemetry {
    @Volatile var sink: EventSink = Silent

    private object Silent : EventSink {
        override fun event(name: String, params: Map<String, Any?>) {}
    }

    fun event(name: String, vararg params: Pair<String, Any?>) = safe { sink.event(name, params.toMap()) }
    fun userProperty(name: String, value: String?) = safe { sink.userProperty(name, value) }
    fun context(key: String, value: String) = safe { sink.context(key, value) }
    fun nonFatal(t: Throwable) = safe { sink.nonFatal(t) }

    // reporting must never take the game down with it
    private inline fun safe(block: () -> Unit) { try { block() } catch (_: Throwable) {} }
}

/**
 * The game's analytics events. Names and parameters follow Google Analytics' recommended game
 * events (level_start, level_end, earn_virtual_currency ...), so the Firebase console's game reports
 * fill in by themselves.
 */
object GameEvents {
    fun screen(name: String) {
        Telemetry.event("screen_view", "screen_name" to name, "screen_class" to name)
        Telemetry.context("screen", name)
    }

    fun raceStart(level: Int, mode: String, kind: String, world: String, boosters: Set<String>) {
        Telemetry.event(
            "level_start", "level_name" to levelName(level, mode), "level" to level, "mode" to mode, "kind" to kind,
            "world" to world, "boosters" to boosters.size,
        )
        Telemetry.context("level", "$level $mode")
    }

    fun raceEnd(level: Int, mode: String, kind: String, finished: Boolean, cleared: Boolean, rank: Int, timeMs: Int, stars: Int, coins: Int, bonks: Int, splashes: Int) {
        Telemetry.event(
            "level_end", "level_name" to levelName(level, mode), "success" to cleared, "level" to level, "mode" to mode, "kind" to kind,
            "finished" to finished, "rank" to rank, "time_s" to timeMs / 1000, "stars" to stars, "bonks" to bonks, "splashes" to splashes,
        )
        if (coins > 0) earn(COINS, coins, "race")
    }

    fun progress(highestLevel: Int, character: String) {
        Telemetry.userProperty("highest_level", highestLevel.toString())
        Telemetry.userProperty("character", character)
    }

    fun tutorialBegin() = Telemetry.event("tutorial_begin")
    fun tutorialComplete() = Telemetry.event("tutorial_complete")

    fun earn(currency: String, amount: Int, source: String) {
        if (amount <= 0) return
        Telemetry.event("earn_virtual_currency", "virtual_currency_name" to currency, "value" to amount, "source" to source)
    }

    fun spend(currency: String, amount: Int, item: String) {
        if (amount <= 0) return
        Telemetry.event("spend_virtual_currency", "virtual_currency_name" to currency, "value" to amount, "item_name" to item)
    }

    /** A reward from a chest, the calendar, the wheel or the trophy road. */
    fun reward(source: String, r: Reward) {
        when (r) {
            is Reward.Coins -> earn(COINS, r.n, source)
            is Reward.Gems -> earn(GEMS, r.n, source)
            is Reward.Booster -> Telemetry.event("booster_earned", "item_name" to r.id, "value" to r.n, "source" to source)
            is Reward.Item -> Telemetry.event("item_earned", "item_name" to r.id, "source" to source)
        }
    }

    fun achievement(id: String, tier: Int) = Telemetry.event("unlock_achievement", "achievement_id" to "$id:$tier")

    fun notificationOpened(source: String) = Telemetry.event("notification_tap", "source" to source)

    const val COINS = "coins"
    const val GEMS = "gems"

    private fun levelName(level: Int, mode: String) = if (mode == "campaign") "level_$level" else "${mode}_$level"
}
