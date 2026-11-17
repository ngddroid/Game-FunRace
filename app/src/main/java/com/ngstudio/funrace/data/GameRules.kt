package com.ngstudio.funrace.data

import com.ngstudio.funrace.core.hash2
import com.ngstudio.funrace.game.RaceResult
import com.ngstudio.funrace.game.sim.RaceKind

/** A reward that can be granted (calendar, wheel, chests, missions, trophy road...). */
sealed class Reward {
    data class Coins(val n: Int) : Reward()
    data class Gems(val n: Int) : Reward()
    data class Booster(val id: String, val n: Int) : Reward()
    data class Item(val id: String) : Reward()

    val label: String get() = when (this) {
        is Coins -> "$n Coins"
        is Gems -> "$n Gems"
        is Booster -> "${GameRules.booster(id)?.name ?: id} x$n"
        is Item -> Catalog.itemName(id)
    }
}

data class BoosterDef(val id: String, val name: String, val desc: String, val price: Int, val icon: String)

data class MissionDef(val id: String, val text: String, val targets: IntArray, val reward: Int)

data class AchievementDef(val id: String, val title: String, val stat: (SaveData) -> Long, val tiers: LongArray, val unit: String)

enum class Modifier(val title: String, val desc: String) {
    MOON("Moon Gravity", "Floaty jumps and slow-motion tumbles."),
    BIG_HEADS("Big Heads", "Everyone's head is huge. Purely for giggles."),
    SLIPPERY("Slippery Soap", "Stopping takes a little longer. Plan ahead!"),
    TINY("Tiny Mode", "You're pocket-sized and a bit faster."),
    MIRROR("Mirror Course", "Everything is flipped left to right."),
}

/** All tunable rules of the meta game in one place. */
object GameRules {
    val boosters = listOf(
        BoosterDef("sneakers", "Jet Sneakers", "+20% speed for the first 8 seconds", 80, "ui_sneaker"),
        BoosterDef("shield", "Bubble Shield", "Pops instead of you once", 100, "ui_bubble"),
        BoosterDef("magnet", "Coin Magnet", "Pulls in nearby coins", 60, "ui_magnet"),
    )
    fun booster(id: String) = boosters.firstOrNull { it.id == id }

    // ------------------------------------------------------------------ race payouts
    fun stars(r: RaceResult): Int = when {
        r.kind == RaceKind.BONUS -> if (!r.finished) 0 else {
            val f = if (r.totalCoins == 0) 1f else r.coins.toFloat() / r.totalCoins
            if (f >= 0.85f) 3 else if (f >= 0.55f) 2 else 1
        }
        !r.finished -> 0
        r.rank == 1 -> 3
        r.rank == 2 -> 2
        r.rank == 3 -> 1
        else -> 0
    }

    fun cleared(r: RaceResult): Boolean = stars(r) > 0

    fun coinsFor(r: RaceResult): Int {
        val base = when {
            r.kind == RaceKind.BONUS -> 40
            !r.finished || r.rank > 3 -> 15
            r.rank == 1 -> 100
            r.rank == 2 -> 60
            else -> 40
        }
        val boss = if (r.kind == RaceKind.BOSS_TIDE || r.kind == RaceKind.BOSS_DUCK || r.kind == RaceKind.BOSS_STORM) 1.5f else 1f
        val pickups = r.coins * (if (r.kind == RaceKind.BONUS) 2 else 1)
        return (base * boss).toInt() + pickups + r.perfects * 5
    }

    fun trophiesFor(r: RaceResult): Int = if (!r.finished) 0 else when (r.rank) { 1 -> 10; 2 -> 6; 3 -> 4; else -> 1 }

    /** Milestone chest every 10 levels (index = level / 10). */
    fun milestone(index: Int): List<Reward> = when (index) {
        1 -> listOf(Reward.Coins(300), Reward.Gems(10), Reward.Item("goldie"))
        else -> listOf(Reward.Coins(250 + index * 25), Reward.Gems(5 + (index % 5)), Reward.Booster(boosters[index % 3].id, 1))
    }

    // ------------------------------------------------------------------ login calendar
    val calendar: List<Reward> = listOf(
        Reward.Coins(100), Reward.Coins(150), Reward.Gems(5), Reward.Booster("shield", 2),
        Reward.Coins(300), Reward.Gems(10), Reward.Item("shark"),
    )

    fun calendarAvailable(d: DailyState, today: Long): Boolean = d.loginLastDay != today

    // ------------------------------------------------------------------ lucky wheel
    class WheelSlot(val reward: Reward, val weight: Int, val color: Long)
    val wheel = listOf(
        WheelSlot(Reward.Coins(50), 22, 0xFF19C6C2), WheelSlot(Reward.Coins(100), 18, 0xFFFFD23F),
        WheelSlot(Reward.Gems(3), 12, 0xFFFF5FA8), WheelSlot(Reward.Coins(200), 12, 0xFF7BE36B),
        WheelSlot(Reward.Booster("sneakers", 1), 12, 0xFF2E9BFF), WheelSlot(Reward.Gems(8), 6, 0xFF9B6BFF),
        WheelSlot(Reward.Booster("shield", 1), 12, 0xFFFF6B57), WheelSlot(Reward.Coins(500), 6, 0xFFFFB13D),
    )

    fun spinWheel(seed: Long): Int {
        val total = wheel.sumOf { it.weight }
        var x = ((hash2(seed.toInt(), (seed ushr 32).toInt()) and 0x7FFFFFFF) % total)
        for ((i, s) in wheel.withIndex()) { if (x < s.weight) return i; x -= s.weight }
        return 0
    }

    // ------------------------------------------------------------------ missions
    val missionPool = listOf(
        MissionDef("win", "Win %d races", intArrayOf(1, 2, 3), 120),
        MissionDef("podium", "Finish on the podium %d times", intArrayOf(2, 3, 5), 100),
        MissionDef("coins", "Collect %d coins on the track", intArrayOf(30, 60, 100), 100),
        MissionDef("perfect", "Land %d PERFECT dodges", intArrayOf(3, 6, 10), 130),
        MissionDef("races", "Play %d races", intArrayOf(3, 5, 8), 90),
        MissionDef("clean", "Finish %d races without a splash", intArrayOf(1, 2, 3), 150),
        MissionDef("turbo", "Trigger Turbo %d times", intArrayOf(1, 2, 4), 120),
        MissionDef("checkpoint", "Reach %d checkpoints", intArrayOf(8, 15, 25), 90),
    )

    fun rollMissions(day: Long): List<MissionState> {
        val picks = ArrayList<MissionState>()
        val used = HashSet<Int>()
        var k = 0
        while (picks.size < 3 && k < 20) {
            val i = (hash2(day.toInt(), 991 + k) and 0x7FFFFFFF) % missionPool.size
            k++
            if (!used.add(i)) continue
            val def = missionPool[i]
            val tier = (hash2(day.toInt() + i, 17) and 0x7FFFFFFF) % def.targets.size
            picks += MissionState(def.id, def.targets[tier])
        }
        return picks
    }

    fun missionDef(id: String) = missionPool.first { it.id == id }
    fun missionReward(m: MissionState): Int = missionDef(m.id).reward + (m.target - missionDef(m.id).targets[0]) * 4

    /** Progress delta a race adds to a mission. */
    fun missionDelta(id: String, r: RaceResult): Int = when (id) {
        "win" -> if (r.finished && r.rank == 1 && r.kind != RaceKind.BONUS) 1 else 0
        "podium" -> if (r.finished && r.rank <= 3 && r.kind != RaceKind.BONUS) 1 else 0
        "coins" -> r.coins
        "perfect" -> r.perfects
        "races" -> 1
        "clean" -> if (r.finished && r.splashes == 0) 1 else 0
        "turbo" -> r.turbos
        "checkpoint" -> 0
        else -> 0
    }

    // ------------------------------------------------------------------ achievements
    val achievements = listOf(
        AchievementDef("races", "Race Regular", { it.stats.races.toLong() }, longArrayOf(10, 50, 200, 1000), "races"),
        AchievementDef("wins", "Lagoon Champ", { it.stats.wins.toLong() }, longArrayOf(5, 25, 100, 500), "wins"),
        AchievementDef("levels", "Course Crusher", { (it.progress.highestUnlocked - 1).toLong() }, longArrayOf(10, 50, 250, 1000, 5000), "levels"),
        AchievementDef("perfects", "Timing Master", { it.stats.perfects.toLong() }, longArrayOf(10, 100, 500, 2000), "PERFECTs"),
        AchievementDef("coins", "Treasure Hunter", { it.stats.coinsEarned }, longArrayOf(1000, 10000, 100000), "coins"),
        AchievementDef("splashes", "Making Waves", { it.stats.splashes.toLong() }, longArrayOf(10, 100, 500), "splashes"),
        AchievementDef("bosses", "Boss Buster", { it.stats.bossesBeaten.toLong() }, longArrayOf(1, 10, 50, 200), "bosses"),
        AchievementDef("stars", "Star Collector", { it.progress.totalStars().toLong() }, longArrayOf(30, 150, 600, 3000), "stars"),
        AchievementDef("collection", "Wardrobe", { it.collection.owned.size.toLong() }, longArrayOf(8, 20, 40, 70), "items"),
        AchievementDef("daily", "Daily Dasher", { it.stats.dailyChallenges.toLong() }, longArrayOf(1, 7, 30), "challenges"),
        AchievementDef("gold", "Gold Rush", { it.stats.goldMedals.toLong() }, longArrayOf(1, 10, 50), "gold medals"),
        AchievementDef("meters", "Marathon", { it.stats.meters / 1000 }, longArrayOf(1, 10, 100), "km"),
    )

    fun achievementReward(tier: Int) = 5 + tier * 5

    // ------------------------------------------------------------------ trophy road
    class RoadStep(val trophies: Int, val reward: Reward)
    val trophyRoad: List<RoadStep> = buildList {
        val items = listOf("bunny", "kitty", "hat:visor", "trail:hearts", "dance:spin", "froggy", "hat:propeller", "trail:stars", "dino", "dance:robot", "hat:crown", "rainbow")
        var t = 0
        var it = 0
        for (k in 1..36) {
            t += 40 + k * 4
            add(RoadStep(t, when {
                k % 3 == 0 && it < items.size -> Reward.Item(items[it++])
                k % 3 == 1 -> Reward.Coins(150 + k * 20)
                else -> Reward.Gems(5 + k / 4)
            }))
        }
    }

    // ------------------------------------------------------------------ daily challenge
    fun challengeLevel(day: Long): Int = 30 + (hash2(day.toInt(), 5150) and 0x7FFFFFFF) % 260

    fun challengeModifier(day: Long): Modifier = Modifier.entries[(day % Modifier.entries.size).toInt()]

    const val CHALLENGE_GEMS = 15

    // ------------------------------------------------------------------ time trial
    /** Medal thresholds in seconds: gold, silver, bronze. */
    fun medalTimes(par: Float) = floatArrayOf(par * 1.0f, par * 1.22f, par * 1.55f)
    fun medalFor(timeS: Float, par: Float): Int { val t = medalTimes(par); return if (timeS <= t[0]) 3 else if (timeS <= t[1]) 2 else if (timeS <= t[2]) 1 else 0 }
}
