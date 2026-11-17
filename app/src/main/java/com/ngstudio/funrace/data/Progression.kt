package com.ngstudio.funrace.data

import com.ngstudio.funrace.game.RaceResult
import com.ngstudio.funrace.game.sim.RaceKind
import com.ngstudio.funrace.level.LevelRepository

/** What a race paid out (shown on the result screen). */
class Payout(
    val stars: Int,
    val previousStars: Int,
    val coins: Int,
    val trophies: Int,
    val cleared: Boolean,
    val unlockedNext: Boolean,
    val milestone: List<Reward>?,
    val newItems: List<String>,
    val medal: Int = 0,
)

/** All writes to the save that come from playing go through here. */
object Progression {
    fun today(): Long = TimeUtil.today()

    fun applyRace(repo: SaveRepository, r: RaceResult, timeTrialPar: Float = 0f, dailyChallenge: Boolean = false): Payout {
        var payout: Payout? = null
        repo.update { d0 ->
            var d = rollDaily(d0)
            val stars = GameRules.stars(r)
            val prev = d.progress.starsFor(r.level)
            val cleared = stars > 0
            val coins = GameRules.coinsFor(r)
            val trophies = GameRules.trophiesFor(r)
            var progress = d.progress
            var unlocked = false
            var milestone: List<Reward>? = null
            var medal = 0
            if (timeTrialPar > 0f) {
                medal = if (r.finished) GameRules.medalFor(r.timeMs / 1000f, timeTrialPar) else 0
                val best = progress.bestTimesMs[r.level]
                if (r.finished && (best == null || r.timeMs < best)) progress = progress.copy(bestTimesMs = progress.bestTimesMs + (r.level to r.timeMs))
                if (medal > (progress.medals[r.level] ?: 0)) progress = progress.copy(medals = progress.medals + (r.level to medal))
            } else if (!dailyChallenge) {
                if (cleared) progress = progress.withStars(r.level, stars)
                if (cleared && r.level >= progress.highestUnlocked && r.level < LevelRepository.MAX_LEVEL) {
                    progress = progress.copy(highestUnlocked = r.level + 1)
                    unlocked = true
                }
                progress = progress.copy(lastPlayed = if (cleared) minOf(r.level + 1, LevelRepository.MAX_LEVEL) else r.level, trophies = progress.trophies + trophies)
                // milestone chest every 10 levels, claimed automatically on first clear
                val mIndex = r.level / 10
                if (cleared && r.level % 10 == 0 && mIndex > progress.milestonesClaimed) {
                    milestone = GameRules.milestone(mIndex)
                    progress = progress.copy(milestonesClaimed = mIndex)
                }
            }
            val st = d.stats
            val boss = r.kind == RaceKind.BOSS_TIDE || r.kind == RaceKind.BOSS_DUCK || r.kind == RaceKind.BOSS_STORM
            val stats = st.copy(
                races = st.races + 1,
                wins = st.wins + if (r.finished && r.rank == 1 && r.kind != RaceKind.BONUS) 1 else 0,
                podiums = st.podiums + if (r.finished && r.rank <= 3) 1 else 0,
                perfects = st.perfects + r.perfects,
                splashes = st.splashes + r.splashes,
                bonks = st.bonks + r.bonks,
                meters = st.meters + r.meters,
                coinsEarned = st.coinsEarned + coins,
                playSeconds = st.playSeconds + r.timeMs / 1000,
                bossesBeaten = st.bossesBeaten + if (boss && cleared) 1 else 0,
                turbos = st.turbos + r.turbos,
                goldMedals = st.goldMedals + if (medal == 3 && (d.progress.medals[r.level] ?: 0) < 3) 1 else 0,
                dailyChallenges = st.dailyChallenges + if (dailyChallenge && cleared && !d.daily.challengeDone) 1 else 0,
            )
            // missions
            val missions = d.daily.missions.map { m ->
                if (m.claimed) m else m.copy(progress = (m.progress + GameRules.missionDelta(m.id, r)).coerceAtMost(m.target))
            }
            var daily = d.daily.copy(missions = missions)
            var wallet = d.wallet.copy(coins = d.wallet.coins + coins)
            if (dailyChallenge && cleared && !d.daily.challengeDone) {
                daily = daily.copy(challengeDone = true, challengeBestRank = r.rank)
                wallet = wallet.copy(gems = wallet.gems + GameRules.CHALLENGE_GEMS)
            }
            d = d.copy(progress = progress, stats = stats, daily = daily, wallet = wallet)
            val newItems = ArrayList<String>()
            milestone?.let { rs -> for (rw in rs) { d = grant(d, rw); if (rw is Reward.Item) newItems += rw.id } }
            payout = Payout(stars, prev, coins, trophies, cleared, unlocked, milestone, newItems, medal)
            d
        }
        return payout!!
    }

    /** Grants a reward into the save. Duplicate items convert to gems. */
    fun grant(d: SaveData, r: Reward): SaveData = when (r) {
        is Reward.Coins -> d.copy(wallet = d.wallet.copy(coins = d.wallet.coins + r.n))
        is Reward.Gems -> d.copy(wallet = d.wallet.copy(gems = d.wallet.gems + r.n))
        is Reward.Booster -> d.copy(progress = d.progress.copy(boosters = d.progress.boosters + (r.id to ((d.progress.boosters[r.id] ?: 0) + r.n))))
        is Reward.Item -> if (r.id in d.collection.owned) d.copy(wallet = d.wallet.copy(gems = d.wallet.gems + 10))
        else d.copy(collection = d.collection.copy(owned = d.collection.owned + r.id, unseen = d.collection.unseen + r.id))
    }

    /** Resets daily state when the day changes (missions, wheel, challenge). */
    fun rollDaily(d: SaveData): SaveData {
        val today = today()
        var daily = d.daily
        if (daily.missionsDay != today) daily = daily.copy(missionsDay = today, missions = GameRules.rollMissions(today))
        if (daily.wheelDay != today) daily = daily.copy(wheelDay = today, wheelFreeUsed = false, wheelAdSpins = 0)
        if (daily.challengeDay != today) daily = daily.copy(challengeDay = today, challengeDone = false, challengeBestRank = 0)
        return if (daily == d.daily) d else d.copy(daily = daily)
    }

    fun claimCalendar(repo: SaveRepository): Reward? {
        var got: Reward? = null
        repo.update { d ->
            val today = today()
            if (!GameRules.calendarAvailable(d.daily, today)) return@update d
            // missing a day restarts the streak
            val idx = if (d.daily.loginLastDay >= 0 && today - d.daily.loginLastDay > 1) 0 else d.daily.loginIndex % 7
            val r = GameRules.calendar[idx]
            got = r
            grant(d, r).let { it.copy(daily = it.daily.copy(loginIndex = (idx + 1) % 7, loginLastDay = today)) }
        }
        return got
    }

    fun claimMission(repo: SaveRepository, id: String): Int {
        var coins = 0
        repo.update { d ->
            val m = d.daily.missions.firstOrNull { it.id == id && it.progress >= it.target && !it.claimed } ?: return@update d
            coins = GameRules.missionReward(m)
            d.copy(
                wallet = d.wallet.copy(coins = d.wallet.coins + coins),
                daily = d.daily.copy(missions = d.daily.missions.map { if (it.id == id) it.copy(claimed = true) else it }),
            )
        }
        return coins
    }

    fun claimAchievement(repo: SaveRepository, id: String): Int {
        var gems = 0
        repo.update { d ->
            val def = GameRules.achievements.first { it.id == id }
            val claimed = d.achievements[id] ?: 0
            if (claimed >= def.tiers.size || def.stat(d) < def.tiers[claimed]) return@update d
            gems = GameRules.achievementReward(claimed)
            d.copy(wallet = d.wallet.copy(gems = d.wallet.gems + gems), achievements = d.achievements + (id to claimed + 1))
        }
        return gems
    }

    fun claimRoad(repo: SaveRepository): Reward? {
        var got: Reward? = null
        repo.update { d ->
            val i = d.progress.trophyRoadClaimed
            val step = GameRules.trophyRoad.getOrNull(i) ?: return@update d
            if (d.progress.trophies < step.trophies) return@update d
            got = step.reward
            grant(d, step.reward).let { it.copy(progress = it.progress.copy(trophyRoadClaimed = i + 1)) }
        }
        return got
    }

    fun spin(repo: SaveRepository, viaAd: Boolean): Int {
        var slot = -1
        repo.update { d0 ->
            val d = rollDaily(d0)
            if (!viaAd && d.daily.wheelFreeUsed) return@update d
            slot = GameRules.spinWheel(System.nanoTime() xor d.stats.races.toLong())
            val nd = grant(d, GameRules.wheel[slot].reward)
            nd.copy(daily = nd.daily.copy(wheelFreeUsed = true, wheelAdSpins = nd.daily.wheelAdSpins + if (viaAd) 1 else 0))
        }
        return slot
    }

    /** Buys an item with coins or gems. Returns false if unaffordable or already owned. */
    fun buy(repo: SaveRepository, key: String, price: Int, gems: Boolean): Boolean {
        var ok = false
        repo.update { d ->
            if (key in d.collection.owned) return@update d
            val have = if (gems) d.wallet.gems else d.wallet.coins
            if (have < price) return@update d
            ok = true
            d.copy(
                wallet = if (gems) d.wallet.copy(gems = d.wallet.gems - price) else d.wallet.copy(coins = d.wallet.coins - price),
                collection = d.collection.copy(owned = d.collection.owned + key),
            )
        }
        return ok
    }

    fun buyBooster(repo: SaveRepository, id: String): Boolean {
        val def = GameRules.booster(id) ?: return false
        var ok = false
        repo.update { d ->
            if (d.wallet.coins < def.price) return@update d
            ok = true
            d.copy(wallet = d.wallet.copy(coins = d.wallet.coins - def.price), progress = d.progress.copy(boosters = d.progress.boosters + (id to ((d.progress.boosters[id] ?: 0) + 1))))
        }
        return ok
    }

    fun consumeBoosters(repo: SaveRepository, ids: Set<String>) {
        if (ids.isEmpty()) return
        repo.update { d ->
            val b = d.progress.boosters.toMutableMap()
            for (id in ids) { val n = b[id] ?: 0; if (n > 0) b[id] = n - 1 }
            d.copy(progress = d.progress.copy(boosters = b))
        }
    }

    fun equip(repo: SaveRepository, key: String) {
        repo.update { d ->
            if (key !in d.collection.owned) return@update d
            val p = d.profile
            val np = when {
                key.startsWith("hat:") -> p.copy(hat = key.removePrefix("hat:"))
                key.startsWith("trail:") -> p.copy(trail = key.removePrefix("trail:"))
                key.startsWith("dance:") -> p.copy(dance = key.removePrefix("dance:"))
                else -> p.copy(character = key)
            }
            d.copy(profile = np, collection = d.collection.copy(unseen = d.collection.unseen - key))
        }
    }

    /** Debug/ad unlock of a single level out of order. */
    fun unlockLevel(repo: SaveRepository, level: Int) {
        repo.update { d -> d.copy(progress = d.progress.copy(extraUnlocked = d.progress.extraUnlocked + level)) }
    }
}
