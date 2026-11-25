package com.ngstudio.funrace.level

import com.ngstudio.funrace.core.Rng
import com.ngstudio.funrace.core.clamp
import com.ngstudio.funrace.core.hash2
import com.ngstudio.funrace.core.lerp
import com.ngstudio.funrace.game.sim.HazType
import com.ngstudio.funrace.game.sim.RaceKind
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Difficulty score D(n) in 0..1 and the metrics derived from it (see docs/GAME_BUILD_PLAN.md §4.3). */
object DifficultyCurve {
    fun trend(n: Int): Float = 0.15f + 0.80f * (1f - exp(-(n - 10).coerceAtLeast(0) / 420f))

    fun saw(n: Int): Float = when ((n - 1) % 10) {
        0 -> -0.06f        // x1 relief
        1 -> -0.03f
        2 -> -0.01f
        3 -> 0.0f
        4 -> -0.04f        // x5 bonus (no hazards anyway)
        5 -> 0.01f
        6 -> 0.025f
        7 -> 0.035f
        8 -> 0.05f
        else -> 0.08f      // x0 boss
    }

    fun of(n: Int): Float {
        if (n <= 10) return 0.05f + n * 0.01f
        val j = ((hash2(n, 99) and 0xFFFF) / 65535f - 0.5f) * 0.04f
        return clamp(trend(n) + saw(n) + j, 0.08f, 1f)
    }

    class Metrics(d: Float) {
        val length = lerp(160f, 330f, d)
        val hazards = lerp(6f, 17f, d).roundToInt().coerceAtMost(20)
        val minWindow = max(0.45f, lerp(1.3f, 0.5f, d))
        val speedMult = min(1.45f, lerp(0.75f, 1.35f, d))
        val pad = max(1.4f, lerp(3.0f, 1.5f, d))
        val comboChance = clamp((d - 0.3f) * 0.55f, 0f, 0.35f)
        val width = max(3.2f, (lerp(4.6f, 3.4f, d) * 5f).roundToInt() / 5f)
        val railFrac = lerp(0.7f, 0.15f, d)
        val checkpointEvery = if (d < 0.3f) 1 else if (d < 0.7f) 2 else 3
        val rivals = if (d < 0.3f) 3 else if (d < 0.65f) 4 else 5
        val rivalSkill = lerp(0.64f, 0.9f, d)
    }
}

/**
 * Deterministic generator for levels 11..5000. The same (level, salt) always produces the same
 * course. Each hazard group is tuned on the spot so its safe window meets the curve's minimum.
 */
object LevelGenerator {
    /** First level each hazard can appear in (introduced alone, slow, behind a safe pad). */
    val unlock: Map<HazType, Int> = mapOf(
        HazType.PUSHER to 1, HazType.PENDULUM to 2, HazType.SWEEPER to 3, HazType.GLOVES to 8, HazType.TURNTABLE to 8,
        HazType.PRESS to 12, HazType.TRAPDOOR to 15, HazType.PADDLE to 18, HazType.CANNON to 22, HazType.TILT to 26,
        HazType.FAN to 30, HazType.BOLLARDS to 34, HazType.GATES to 38, HazType.WAVE to 45, HazType.CONVEYOR to 60,
        HazType.BARRELS to 65,
        // introduced in the handcrafted levels (2, 4 and 6), so they join the pool from level 11
        HazType.HAMMER to 2, HazType.SPINNER to 4, HazType.ROLLERS to 6,
    )
    private const val COMBOS_FROM = 40
    private const val TRIPLES_FROM = 150

    private fun basePeriod(t: HazType): Float = when (t) {
        HazType.PUSHER -> 3.0f; HazType.PENDULUM -> 3.2f; HazType.GLOVES -> 2.8f; HazType.PRESS -> 3.0f
        HazType.TRAPDOOR -> 3.2f; HazType.CANNON -> 2.8f; HazType.TILT -> 4.0f; HazType.FAN -> 3.2f
        HazType.BOLLARDS -> 3.0f; HazType.GATES -> 3.4f; HazType.WAVE -> 3.4f; HazType.BARRELS -> 3.2f
        HazType.HAMMER -> 3.6f; HazType.ROLLERS -> 3.0f
        else -> 3f
    }

    /** Half-length of each hazard's danger area along the course (for deck sizing). */
    fun extent(t: HazType, hw: Float): Float = when (t) {
        HazType.PUSHER -> 0.95f; HazType.PENDULUM -> 1.05f; HazType.SWEEPER -> hw + 0.85f; HazType.TURNTABLE -> hw + 1.0f
        HazType.GLOVES -> 0.8f; HazType.PRESS -> 1.15f; HazType.TRAPDOOR -> 1.8f; HazType.PADDLE -> 1.9f
        HazType.CANNON -> 1.0f; HazType.TILT -> 2.5f; HazType.FAN -> 1.5f; HazType.BOLLARDS -> 1.55f
        HazType.GATES -> 0.5f; HazType.WAVE -> 1.9f
        HazType.SPINNER -> 2f * hw + 1.5f; HazType.HAMMER -> 1.2f; HazType.ROLLERS -> 1.75f
        else -> 1.2f
    }

    fun kindFor(n: Int): RaceKind = when {
        n % 10 == 0 -> when ((n / 10) % 3) { 1 -> RaceKind.BOSS_TIDE; 2 -> RaceKind.BOSS_DUCK; else -> RaceKind.BOSS_STORM }
        n % 10 == 5 -> RaceKind.BONUS
        else -> RaceKind.NORMAL
    }

    fun generate(n: Int, salt: Int = 0): LevelSpec {
        val seed = (hash2(n, 77_777 + salt * 131).toLong() shl 20) xor n.toLong() xor (salt.toLong() shl 48)
        val rng = Rng(seed)
        val d = DifficultyCurve.of(n)
        val m = DifficultyCurve.Metrics(d)
        val kind = kindFor(n)
        val world = World.forLevel(n)
        val time = World.timeForLevel(n)
        val width = m.width
        val hw = width / 2f
        val rivals = if (kind == RaceKind.BONUS) 0 else m.rivals
        val lanes = LevelBuilder.lanes(rivals + 1, hw)
        val playerD = lanes[if (rivals + 1 >= 4) 1 else if (rivals == 0) 0 else (rivals + 1) / 2]
        val pieces = ArrayList<Piece>()
        val pool = unlock.filter { it.value <= n }.keys.toList()
        var yaw = 0f
        var height = 3.2f + rng.range(0f, 1.2f)
        val startY = height
        var placed = 0
        var lengthSoFar = 0f
        var sinceCheckpoint = 0
        var lastSetPiece = -1
        var newThing = ""
        unlock.entries.firstOrNull { it.value == n }?.let { newThing = it.key.label }

        fun turn(): Piece.Turn {
            var deg = rng.range(18f, 38f) * rng.sign()
            if (abs(yaw + deg) > 70f) deg = -deg
            yaw += deg
            return Piece.Turn(deg, rng.range(11f, 16f), coins = rng.nextInt(4, 7))
        }

        fun rails() = rng.chance(m.railFrac)

        fun hazSpec(t: HazType, at: Float, speedMult: Float): HazSpec {
            val period = basePeriod(t) / speedMult * rng.range(0.92f, 1.1f)
            val phase = rng.range(0f, period)
            val side = rng.sign()
            return when (t) {
                HazType.PUSHER -> HazSpec(t, at, period, phase, side, lerp(0.6f, 0.9f, d))
                HazType.GLOVES -> HazSpec(t, at, period, phase, side, lerp(0.65f, 0.9f, d))
                HazType.PENDULUM -> HazSpec(t, at, period, phase, side, rng.range(0.78f, 0.95f), rng.nextInt(0, 6).toFloat())
                HazType.SWEEPER -> HazSpec(t, at, period, phase, side, 1.5f * speedMult * rng.sign() * rng.range(0.92f, 1.08f), if (d > 0.6f && rng.chance(0.25f)) 2f else 1f)
                HazType.TURNTABLE -> HazSpec(t, at, period, phase, side, 1.3f * speedMult * rng.sign())
                HazType.PADDLE -> HazSpec(t, at, period, phase, side, 0.95f * speedMult * rng.sign())
                HazType.CANNON -> HazSpec(t, at, period, phase, side, lerp(5.5f, 7.5f, d))
                HazType.FAN -> HazSpec(t, at, period, phase, side, lerp(3.4f, 4.4f, d))
                HazType.TRAPDOOR -> HazSpec(t, at, period, phase, side, 0f, if (rng.chance(0.5f)) 1f else 0f)
                HazType.SPINNER -> HazSpec(t, at, period, phase, side, 2.3f * speedMult * side * rng.range(0.92f, 1.08f))
                HazType.HAMMER -> HazSpec(t, at, period, phase, side, rng.range(0.88f, 0.98f))
                else -> HazSpec(t, at, period, phase, side)
            }
        }

        /** A deck carrying one hazard group, tuned until its safe window is wide enough (null if impossible). */
        fun hazardDeck(types: List<HazType>, extra: HazSpec? = null): Piece.Deck? {
            val pad = m.pad + rng.range(0f, 0.6f)
            var speed = m.speedMult
            var at = pad
            val positions = ArrayList<Float>()
            for ((i, t) in types.withIndex()) {
                val e = extent(t, hw)
                at += e
                positions += at
                at += e + if (i < types.size - 1) rng.range(0.2f, 0.8f) else 1.2f
            }
            val len = at
            fun withExtra(l: List<HazSpec>) = if (extra != null) l + extra.copy(at = len / 2f, q = len / 2f - 0.5f) else l
            var specs = withExtra(types.mapIndexed { i, t -> hazSpec(t, positions[i], speed) })
            var tries = 0
            while (LevelValidator.groupWindow(specs, hw, playerD) < m.minWindow * 1.03f) {
                tries++
                if (tries > 7) return null
                speed *= 0.9f
                specs = withExtra(types.mapIndexed { i, t -> hazSpec(t, positions[i], speed) })
            }
            // bars and rollers sweep in over the deck edge, so those decks never get rails
            val edgeSweep = types.any { it == HazType.SPINNER || it == HazType.ROLLERS }
            return Piece.Deck(len, specs, rails = !edgeSweep && rails(), coins = if (rng.chance(0.3f)) 3 else 0)
        }

        fun setPiece(): List<Piece> {
            val opts = ArrayList<Int>()
            opts += 0 // jump
            if (n >= 5) opts += 1 // slide
            if (n >= 6) opts += 2 // climb
            if (n >= 7) opts += 3 // raft
            if (n >= 9) { opts += 4; opts += 5 } // zip, swing
            if (n >= 11) opts += 6 // trampoline
            if (n >= 50) opts += 7 // monkey
            if (n >= 55) opts += 8 // beam
            var pick = opts[rng.nextInt(opts.size)]
            if (pick == lastSetPiece && opts.size > 1) pick = opts[(opts.indexOf(pick) + 1) % opts.size]
            lastSetPiece = pick
            val out = ArrayList<Piece>()
            when (pick) {
                0 -> { out += Piece.Jump(rng.range(6.5f, 9.5f), rng.range(2.2f, 3.2f)); out += Piece.Deck(4f, rails = true) }
                1 -> {
                    if (height < 5.5f) { out += Piece.Ramp(12f, 3f); height += 3f }
                    val drop = min(height - 2.2f, rng.range(3.5f, 5.5f))
                    out += Piece.Deck(3f, rails = true)
                    out += Piece.Slide(rng.range(22f, 34f), drop, rng.range(18f, 32f), 10)
                    height -= drop
                    out += Piece.Deck(4f, rails = true)
                }
                2 -> {
                    out += Piece.Deck(3f, rails = true)
                    val h = rng.range(3.2f, 4.5f)
                    val per = max(1.9f, 3.2f / m.speedMult)
                    out += Piece.Climb(h, if (d > 0.15f) per else 0f, rng.range(0f, per))
                    height += h
                    out += Piece.Deck(5f, rails = true)
                    if (height > 8.5f) { out += Piece.Ramp(12f, -3f); height -= 3f }
                }
                3 -> {
                    val drop = height - 0.3f
                    out += Piece.Ramp(max(10f, drop * 4f), -drop)
                    out += Piece.Deck(4f, rails = true)
                    out += Piece.Checkpoint
                    out += Piece.Raft(rng.range(7f, 10f), rng.range(5.5f, 7f) / min(1.2f, m.speedMult), rng.range(0f, 3f))
                    out += Piece.Deck(4f, rails = true)
                    val up = rng.range(2.6f, 4f)
                    out += Piece.Ramp(max(10f, up * 4f), up)
                    height = 0.3f + up
                }
                4 -> { out += Piece.Deck(3f, rails = true); out += Piece.Zip(rng.range(14f, 20f), 1.6f); height -= 1.6f; out += Piece.Deck(5f, rails = true); if (height < 2f) { out += Piece.Ramp(10f, 2.5f); height += 2.5f } }
                5 -> { out += Piece.Deck(3f, rails = true); out += Piece.Swing(rng.range(9f, 12f), rng.range(6f, 7f)); out += Piece.Deck(5f, rails = true) }
                6 -> { out += Piece.Jump(rng.range(10f, 14f), rng.range(2.2f, 2.8f), hops = 2 + rng.nextInt(2)); out += Piece.Deck(4f, rails = true) }
                7 -> {
                    out += Piece.Deck(3f, rails = true)
                    val len = rng.range(8f, 12f)
                    val hz = if (d > 0.3f) listOf(hazSpec(HazType.PENDULUM, len / 2f, m.speedMult * 0.85f)) else emptyList()
                    out += Piece.Monkey(len, hz)
                    out += Piece.Deck(4f, rails = true)
                }
                8 -> {
                    out += Piece.Deck(3f, rails = true)
                    val len = rng.range(8f, 12f)
                    val hz = if (d > 0.3f) listOf(hazSpec(if (rng.chance(0.5f) && n >= 30) HazType.FAN else HazType.PENDULUM, len / 2f, m.speedMult * 0.85f)) else emptyList()
                    out += Piece.Beam(len, hz)
                    out += Piece.Deck(4f, rails = true)
                }
            }
            return out
        }

        pieces += Piece.Deck(8f, rails = true, coins = 5)
        if (kind == RaceKind.BONUS) {
            // joyful coin rush: slides, bounces and ziplines, no hazards, no rivals
            var len = 0f
            while (len < m.length * 0.75f) {
                val pick = rng.nextInt(4)
                when (pick) {
                    0 -> { pieces += Piece.Ramp(12f, 3f); pieces += Piece.Deck(3f, rails = true); pieces += Piece.Slide(30f, 4.5f, 30f, 16); pieces += Piece.Deck(4f, rails = true, coins = 4); len += 49f }
                    1 -> { pieces += Piece.Jump(9f, 3f); pieces += Piece.Deck(6f, rails = true, coins = 5); len += 15f }
                    2 -> { pieces += Piece.Jump(12f, 2.6f, hops = 3); pieces += Piece.Deck(5f, rails = true, coins = 4); len += 17f }
                    else -> { pieces += turn().copy(coins = 9); pieces += Piece.Deck(8f, rails = true, coins = 6); len += 22f }
                }
            }
        } else {
            val target = m.hazards
            while (placed < target || lengthSoFar < m.length * 0.6f) {
                if (placed >= target + 4) break
                if (sinceCheckpoint >= m.checkpointEvery) { pieces += Piece.Checkpoint; sinceCheckpoint = 0 }
                val r = rng.nextFloat()
                when {
                    r < 0.14f && placed > 0 -> { val sp = setPiece(); pieces += sp; lengthSoFar += 20f; pieces += Piece.Checkpoint; sinceCheckpoint = 0 }
                    r < 0.26f -> { val t = turn(); pieces += t; lengthSoFar += t.len }
                    r < 0.32f && pool.contains(HazType.BARRELS) -> {
                        val rise = rng.range(1.5f, 2.5f)
                        val per = max(5.2f, 7.2f / kotlin.math.sqrt(m.speedMult))
                        pieces += Piece.Ramp(10f, rise, HazSpec(HazType.BARRELS, 0f, per, rng.range(0f, per), 1f, 3.5f))
                        height += rise; placed++; sinceCheckpoint++; lengthSoFar += 10f
                    }
                    else -> {
                        val combo = n >= COMBOS_FROM && rng.chance(m.comboChance)
                        val count = if (combo) (if (n >= TRIPLES_FROM && rng.chance(0.3f)) 3 else 2) else 1
                        val types = (0 until count).map {
                            // newly unlocked hazard appears more often right after its intro
                            val fresh = pool.filter { (unlock[it] ?: 0) > n - 8 }
                            if (fresh.isNotEmpty() && rng.chance(0.45f)) fresh[rng.nextInt(fresh.size)] else pool[rng.nextInt(pool.size)]
                        }.map { if (it == HazType.BARRELS) HazType.PUSHER else it }
                        val decks = ArrayList<Piece.Deck>()
                        if (types.size == 1 && types[0] == HazType.CONVEYOR) {
                            // lazy river under a short hazard
                            val shortOnes = pool.filter { it in listOf(HazType.PUSHER, HazType.PENDULUM, HazType.GLOVES, HazType.CANNON, HazType.PRESS, HazType.GATES) }
                            val other = shortOnes[rng.nextInt(shortOnes.size)]
                            val belt = HazSpec(HazType.CONVEYOR, 0f, 1f, 0f, 1f, lerp(1.2f, 2.2f, d), 1f)
                            (hazardDeck(listOf(other), belt) ?: hazardDeck(listOf(other)))?.let { decks += it }
                        } else {
                            val dk = hazardDeck(types)
                            if (dk != null) decks += dk
                            else for (t in types) hazardDeck(listOf(if (t == HazType.CONVEYOR) HazType.PUSHER else t))?.let { decks += it; decks += Piece.Deck(m.pad, rails = rng.chance(0.5f)) }
                        }
                        for (deck in decks) {
                            pieces += deck
                            placed += deck.hazards.count { it.type != HazType.CONVEYOR }
                            lengthSoFar += deck.len
                        }
                        if (decks.isEmpty()) placed++ // impossible pick: skip it
                        sinceCheckpoint++
                        if (rng.chance(0.35f)) { pieces += Piece.Deck(rng.range(3f, 6f), rails = true, coins = rng.nextInt(0, 4)); lengthSoFar += 4f }
                    }
                }
                // keep the deck height in a nice band
                if (height > 8f) { pieces += Piece.Ramp(12f, -2.5f); height -= 2.5f }
            }
        }
        if (rng.chance(0.5f)) pieces += turn().copy(coins = 5)
        val chaserSpeed = when (kind) { RaceKind.BOSS_TIDE -> lerp(2.3f, 3.0f, d); RaceKind.BOSS_DUCK -> lerp(2.4f, 3.1f, d); else -> 0f }
        val intro = when (kind) {
            RaceKind.BOSS_TIDE -> "Boss! The tide is rising behind you. Keep moving!"
            RaceKind.BOSS_DUCK -> "Boss! A giant duck is bouncing after the pack!"
            RaceKind.BOSS_STORM -> "Boss! Storm gusts push everyone back. Push through!"
            RaceKind.BONUS -> "Bonus! Grab as many coins as you can. You can't lose!"
            else -> ""
        }
        return LevelSpec(
            level = n, seed = seed, name = if (kind == RaceKind.BONUS) "Coin Rush" else Names.level(n, world), world = world, time = time,
            kind = kind, width = width, startY = startY, pieces = pieces, rivals = rivals, rivalSkill = m.rivalSkill,
            difficulty = d, chaserSpeed = chaserSpeed, chaserDelay = 6f,
            stormStrength = if (kind == RaceKind.BOSS_STORM) lerp(1.4f, 2.2f, d) else 0f,
            intro = intro, newThing = newThing,
        )
    }
}
