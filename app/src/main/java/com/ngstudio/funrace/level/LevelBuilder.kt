package com.ngstudio.funrace.level

import com.ngstudio.funrace.core.Rng
import com.ngstudio.funrace.game.sim.Barrels
import com.ngstudio.funrace.game.sim.Cannon
import com.ngstudio.funrace.game.sim.Chaser
import com.ngstudio.funrace.game.sim.CoinSpot
import com.ngstudio.funrace.game.sim.Coconuts
import com.ngstudio.funrace.game.sim.Conveyor
import com.ngstudio.funrace.game.sim.Course
import com.ngstudio.funrace.game.sim.CourseBuilder
import com.ngstudio.funrace.game.sim.Fan
import com.ngstudio.funrace.game.sim.Gates
import com.ngstudio.funrace.game.sim.Gloves
import com.ngstudio.funrace.game.sim.HazType
import com.ngstudio.funrace.game.sim.Hazard
import com.ngstudio.funrace.game.sim.PaddleWheel
import com.ngstudio.funrace.game.sim.Pendulum
import com.ngstudio.funrace.game.sim.Press
import com.ngstudio.funrace.game.sim.Pusher
import com.ngstudio.funrace.game.sim.RaceKind
import com.ngstudio.funrace.game.sim.RaceRules
import com.ngstudio.funrace.game.sim.RaceSim
import com.ngstudio.funrace.game.sim.Racer
import com.ngstudio.funrace.game.sim.Raft
import com.ngstudio.funrace.game.sim.SegKind
import com.ngstudio.funrace.game.sim.Storm
import com.ngstudio.funrace.game.sim.Sweeper
import com.ngstudio.funrace.game.sim.Tilt
import com.ngstudio.funrace.game.sim.Trapdoor
import com.ngstudio.funrace.game.sim.Turntable
import com.ngstudio.funrace.game.sim.Wave
import com.ngstudio.funrace.game.sim.Bollards
import kotlin.math.max

/** Everything needed to run (and draw) one race, built from a [LevelSpec]. */
class RaceSetup(
    val spec: LevelSpec,
    val course: Course,
    val hazards: List<Hazard>,
    val checkpoints: FloatArray,
    val coins: List<CoinSpot>,
    val chaser: Chaser?,
    val storm: Storm?,
)

object LevelBuilder {
    fun makeHazard(hs: HazSpec, base: Float, pieceHw: Float): Hazard? {
            val s0 = base + hs.at
            return when (hs.type) {
                HazType.PUSHER -> Pusher(s0, pieceHw, hs.period, hs.phase, hs.side, hs.p.takeIf { it > 0f } ?: 0.7f)
                HazType.PENDULUM -> Pendulum(s0, pieceHw, hs.period, hs.phase, hs.p.takeIf { it > 0f } ?: 0.9f, hs.q.toInt())
                HazType.SWEEPER -> Sweeper(s0, pieceHw, if (hs.p != 0f) hs.p else 1.9f, hs.phase, max(1, hs.q.toInt()), pieceHw + 0.35f)
                HazType.TURNTABLE -> Turntable(s0, pieceHw, if (hs.p != 0f) hs.p else 1.5f, hs.phase)
                HazType.GLOVES -> Gloves(s0, pieceHw, hs.period, hs.phase, hs.side, hs.p.takeIf { it > 0f } ?: 0.75f)
                HazType.PRESS -> Press(s0, pieceHw, hs.period, hs.phase)
                HazType.TRAPDOOR -> Trapdoor(s0, pieceHw, hs.period, hs.phase, hs.q > 0f)
                HazType.PADDLE -> PaddleWheel(s0, pieceHw, if (hs.p != 0f) hs.p else 1.4f, hs.phase)
                HazType.CANNON -> Cannon(s0, pieceHw, hs.period, hs.phase, hs.side, hs.p.takeIf { it > 0f } ?: 6.5f)
                HazType.TILT -> Tilt(s0, pieceHw, hs.period, hs.phase)
                HazType.FAN -> Fan(s0, pieceHw, hs.period, hs.phase, hs.side, 1.5f, hs.p.takeIf { it > 0f } ?: 3.8f)
                HazType.BOLLARDS -> Bollards(s0, pieceHw, hs.period, hs.phase)
                HazType.GATES -> Gates(s0, pieceHw, hs.period, hs.phase)
                HazType.WAVE -> Wave(s0, pieceHw, hs.period, hs.phase, hs.side)
                HazType.CONVEYOR -> Conveyor(s0, pieceHw, hs.q.takeIf { it > 0f } ?: 3f, hs.p.takeIf { it > 0f } ?: 2f)
                HazType.BARRELS, HazType.COCONUTS, HazType.RAFT -> null
            }
        }


    fun build(spec: LevelSpec): RaceSetup {
        val cb = CourseBuilder(spec.startY)
        val w = spec.width
        val hw = w / 2f
        val hazards = ArrayList<Hazard>()
        val cps = ArrayList<Float>()
        val coins = ArrayList<CoinSpot>()
        val rng = Rng(spec.seed * 31 + 7)

        fun coinLine(s0: Float, len: Float, n: Int, d: Float = 0f, h: Float = 0.6f) {
            if (n <= 0) return
            for (k in 0 until n) coins += CoinSpot(s0 + len * (k + 0.5f) / n, d, h)
        }

        // start platform
        cb.segment(SegKind.START, 9f, w + 1.2f, railL = true, railR = true)
        for (p in spec.pieces) {
            val base = cb.s
            when (p) {
                is Piece.Deck -> {
                    cb.segment(SegKind.DECK, p.len, w, p.rails, p.rails)
                    for (h in p.hazards) makeHazard(h, base, hw)?.let { hazards += it }
                    if (p.coins > 0) coinLine(base + 1f, p.len - 2f, p.coins, if (rng.chance(0.5f)) -0.4f else 0.4f)
                }
                is Piece.Turn -> {
                    cb.segment(SegKind.TURN, p.len, w, p.rails, p.rails, turnDeg = p.deg)
                    if (p.coins > 0) coinLine(base, p.len, p.coins, if (p.deg > 0) -0.6f else 0.6f)
                }
                is Piece.Ramp -> {
                    cb.segment(SegKind.RAMP, p.len, w, p.rails, p.rails, rise = p.rise)
                    p.barrels?.let { b -> hazards += Barrels(base + p.len / 2f, hw, b.period, b.phase, p.len / 2f - 0.6f, b.p.takeIf { it > 0f } ?: 3.5f) }
                }
                is Piece.Jump -> {
                    val seg = cb.segment(if (p.hops > 1) SegKind.TRAMPOLINE else SegKind.JUMP, p.len, w, p0 = p.apex, p1 = p.hops.toFloat())
                    coinLine(seg.s0, p.len, 5, 0f, 0f).also {
                        // coins follow the arc
                        val n = 5
                        for (k in 0 until n) {
                            val c = coins[coins.size - n + k]
                            val pp = (c.s - seg.s0) / p.len
                            coins[coins.size - n + k] = CoinSpot(c.s, 0f, 0.6f + com.ngstudio.funrace.game.sim.SetPieceMath.jumpHeight(pp, p.apex, p.hops))
                        }
                    }
                }
                is Piece.Zip -> cb.segment(SegKind.ZIP, p.len, w, rise = -p.drop, p0 = p.drop)
                is Piece.Swing -> cb.segment(SegKind.SWING, p.len, w, p0 = p.pivotH)
                is Piece.Raft -> {
                    val seg = cb.segment(SegKind.RAFT, p.len, w, p0 = p.period)
                    hazards += Raft(seg, hw, p.period, p.phase)
                }
                is Piece.Monkey -> {
                    cb.segment(SegKind.MONKEY, p.len, w)
                    for (h in p.hazards) makeHazard(h, base, hw)?.let { hazards += it }
                }
                is Piece.Beam -> {
                    cb.segment(SegKind.BEAM, p.len, 1.0f)
                    for (h in p.hazards) makeHazard(h, base, 0.5f)?.let { hazards += it }
                }
                is Piece.Climb -> {
                    val seg = cb.segment(SegKind.CLIMB, p.height, w)
                    if (p.coconutPeriod > 0f) hazards += Coconuts(seg.s0, hw, p.coconutPeriod, p.phase, p.height)
                }
                is Piece.Slide -> {
                    val seg = cb.slide(p.len, p.drop, w * 0.75f, p.bend)
                    if (p.coins > 0) {
                        for (k in 0 until p.coins) coins += CoinSpot(seg.s0 + 2f + (p.len - 4f) * k / max(1, p.coins - 1), 0f, 0.5f)
                    }
                }
                Piece.Checkpoint -> cps += base
            }
        }
        cb.segment(SegKind.DECK, 6f, w, true, true)
        cb.segment(SegKind.FINISH, 16f, w + 2.5f, railL = true, railR = true)
        val course = cb.build()
        val chaser = when (spec.kind) {
            RaceKind.BOSS_TIDE -> Chaser(false, course.startS - 10f, spec.chaserDelay, spec.chaserSpeed, course.finishS - 2f)
            RaceKind.BOSS_DUCK -> Chaser(true, course.startS - 10f, spec.chaserDelay, spec.chaserSpeed, course.finishS - 2f)
            else -> null
        }
        val storm = if (spec.kind == RaceKind.BOSS_STORM) Storm(7.5f, rng.range(0f, 7f), spec.stormStrength) else null
        coins.sortBy { it.s }
        return RaceSetup(spec, course, hazards, cps.toFloatArray(), coins, chaser, storm)
    }

    /** Lane offsets for [n] racers on a deck of half-width [hw] (player is lane 1). */
    fun lanes(n: Int, hw: Float): FloatArray {
        val spread = ((hw - 0.55f) / 1.5f).coerceIn(0.5f, 1f)
        val base = when (n) {
            1 -> floatArrayOf(0f)
            2 -> floatArrayOf(-0.5f, 0.5f)
            3 -> floatArrayOf(-0.9f, 0f, 0.9f)
            4 -> floatArrayOf(-1.45f, -0.75f, 0.75f, 1.45f)
            5 -> floatArrayOf(-1.6f, -0.85f, 0f, 0.85f, 1.6f)
            else -> floatArrayOf(-1.7f, -1.05f, -0.4f, 0.4f, 1.05f, 1.7f)
        }
        return FloatArray(base.size) { base[it] * spread }
    }

    /** Creates the simulation with racers. [playerSkill] < 0 means human control. */
    fun createSim(setup: RaceSetup, rules: RaceRules = RaceRules(), rivalLooks: IntArray? = null, names: List<String>? = null): RaceSim {
        val spec = setup.spec
        val n = if (spec.kind == RaceKind.BONUS || spec.kind == RaceKind.TIME_TRIAL) 1 else spec.rivals + 1
        val lanes = lanes(n, spec.width / 2f)
        val playerLane = if (n >= 4) 1 else if (n == 1) 0 else n / 2
        val rng = Rng(spec.seed xor 0x5DEECE66DL)
        val racers = ArrayList<Racer>()
        var ri = 0
        for (i in 0 until n) {
            val isP = i == playerLane
            val skill = if (isP) 1f else (spec.rivalSkill + rng.soft() * 0.06f).coerceIn(0.2f, 0.97f)
            val name = if (isP) "You" else names?.getOrNull(ri) ?: Names.rival(spec.seed, ri)
            racers += Racer(i, isP, lanes[i], name, skill, if (isP) -1 else (rivalLooks?.getOrNull(ri) ?: ri))
            if (!isP) ri++
        }
        rules.guaranteeWin = spec.guaranteeWin
        if (spec.guaranteeWin) rules.rivalSpeedCap = 0.88f
        return RaceSim(setup.course, setup.hazards, setup.checkpoints, setup.coins, racers, spec.kind, rules, setup.chaser, setup.storm, spec.seed)
    }
}
