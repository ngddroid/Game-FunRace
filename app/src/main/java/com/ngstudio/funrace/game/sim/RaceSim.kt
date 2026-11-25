package com.ngstudio.funrace.game.sim

import com.ngstudio.funrace.core.Rng
import com.ngstudio.funrace.core.clamp
import com.ngstudio.funrace.core.moveTowards
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sign
import kotlin.math.sqrt

enum class Mode { RUN, STUN, AIR, TUMBLE, FALL, SPLASHED, CLIMB, MONKEY, ZIP, SWING, RAFT, SLIDE, FINISHED }

enum class RaceKind { NORMAL, TUTORIAL, BOSS_TIDE, BOSS_DUCK, BOSS_STORM, BONUS, DAILY, TIME_TRIAL }

/** Rule tweaks (daily-challenge modifiers, boosters, difficulty assists). */
class RaceRules(
    var gravity: Float = 22f,
    var stopDecel: Float = 48f,
    var runSpeed: Float = 5.2f,
    /** The player's top speed over [runSpeed]: a small edge over the bots so a clean run wins. */
    var playerEdge: Float = PLAYER_EDGE,
    var playerScale: Float = 1f,
    var mirror: Boolean = false,
    var bigHeads: Boolean = false,
) {
    companion object {
        /**
         * 8% over base speed: the best bots run at ~1.01x, so a clean run wins most races while a
         * hurdle hit still costs the lead (measured: a mid-skill player wins ~60%, podiums ~87%).
         */
        const val PLAYER_EDGE = 1.08f
    }
}

class Racer(
    val index: Int,
    val isPlayer: Boolean,
    var laneD: Float,
    val name: String,
    var skill: Float,
    val lookIndex: Int,
) {
    var s = 0f; var d = 0f; var y = 0f
    var vs = 0f; var vd = 0f; var vy = 0f
    var speed = 0f
    var mode = Mode.RUN
    var modeT = 0f
    var hold = false
    var checkpoint = 0f
    var checkpointIdx = -1
    var invuln = 0f
    var stun = 0f
    var squash = 0f
    var flash = 0f
    var finishTime = 0f
    var rank = 0
    var turbo = 0f
    var turboTime = 0f
    var boostTime = 0f
    var shield = 0
    var magnetTime = 0f
    var coins = 0
    var meters = 0f
    var splashes = 0
    var bonks = 0
    var perfects = 0
    var animPhase = 0f
    var climbBaseS = 0f
    var segIndex = 0
    var lastHitHazard = -1
    /** Hit by a hurdle: after the knock (and a short dizzy beat) the racer goes back to its checkpoint. */
    var respawnPending = false
    /** Seconds before this racer can be shoved over again. */
    var shoveCooldown = 0f
    // near-miss tracking (player)
    var zoneHaz = -1
    var zoneClear = 99f
    var zoneHit = false
    // AI memory
    var aiDecision = true
    var aiNextThink = 0f
    var aiErr = 0f
    var aiErrHaz = -1
    var aiPatience = 0f
    /** Furthest point reached, and seconds since it last moved forward (deadlock breaker). */
    var aiBestS = 0f
    var aiStall = 0f
    var wasHold = false
    /** Player input: desired lateral position (NaN = not steering) and a queued jump. */
    var steer = Float.NaN
    var jumpQueued = false
    val airborne get() = y > 0.001f && (mode == Mode.RUN)

    val finished get() = mode == Mode.FINISHED
    val laneTarget get() = laneD
}

/** Something that happened this tick; consumed by the renderer/controller for FX, sound and UI. */
const val JUMP_V = 6.6f
const val JUMP_G = 24f
const val STEER_SPEED = 6.5f
/** Seconds a hit racer stays dizzy where it lands before it is sent back to its checkpoint. */
const val RESPAWN_DIZZY = 0.55f
/** How long a rival you ran into lies on the ground before getting up. */
const val SHOVE_STUN = 0.9f

class SimEvent(val type: Type, val racer: Int, val x: Float = 0f, val v: Int = 0) {
    enum class Type { STEP, KNOCK, BLOCKED, SPLASH, RESPAWN, CHECKPOINT, COIN, FINISH, NICE, PERFECT, TURBO, SHIELD_POP, JUMP, LAND, GRAB, ZIP, SWING, SLIDE, HAZARD, CAUGHT, GUST, POOF, SHOVE }
}

/**
 * Deterministic race simulation in course space at a fixed 60 Hz.
 * The player's input is [Racer.hold]; rivals are driven by [AiBrain].
 */
class RaceSim(
    val course: Course,
    val hazards: List<Hazard>,
    val checkpoints: FloatArray,
    val coins: List<CoinSpot>,
    val racers: List<Racer>,
    val kind: RaceKind,
    val rules: RaceRules,
    val chaser: Chaser?,
    val storm: Storm?,
    seed: Long,
) {
    var time = 0f
        private set
    var started = false
    val events = ArrayList<SimEvent>(32)
    val player: Racer = racers.first { it.isPlayer }
    var finishedCount = 0
        private set
    var over = false
        private set
    val rng = Rng(seed)
    val ai = AiBrain(this)
    private val hit = Hit()
    private val sortedHaz = hazards.sortedBy { it.zoneStart }
    val totalCoins = coins.sumOf { it.value }
    var playerTookHit = false
    /** Debug/validation: the player is driven by the AI too. */
    var autopilot = false

    init {
        hazards.forEachIndexed { i, h -> h.id = i }
        for (r in racers) {
            r.s = course.startS - 1.5f + (r.index % 2) * 0.2f
            r.d = r.laneD
            r.checkpoint = r.s
        }
    }

    /** Hazards whose zone overlaps [s0, s1]. */
    inline fun forHazards(s0: Float, s1: Float, fn: (Hazard) -> Unit) {
        for (h in hazards) if (h.zoneEnd >= s0 && h.zoneStart <= s1) fn(h)
    }

    fun step(dt: Float) {
        events.clear()
        if (!started) return
        val tPrev = time
        time += dt
        for (h in hazards) if (h.event(tPrev, time)) events += SimEvent(SimEvent.Type.HAZARD, -1, h.s0, h.id)
        storm?.let { if (it.warn(tPrev) == 0f && it.warn(time) > 0f) events += SimEvent(SimEvent.Type.GUST, -1) }
        for (r in racers) {
            if (!r.isPlayer || autopilot) r.hold = ai.decide(r, dt)
            update(r, dt)
            r.wasHold = r.hold
        }
        playerShoves()
        checkOver()
    }

    private fun checkOver() {
        if (over) return
        if (player.finished) { over = true; return }
        val needed = if (racers.size >= 4) 3 else racers.size - 1
        val rivalsDone = racers.count { !it.isPlayer && it.finished }
        if (kind != RaceKind.BONUS && kind != RaceKind.TIME_TRIAL && rivalsDone >= needed && racers.size > 1) over = true
    }

    // ----------------------------------------------------------------------------------- racer
    private fun update(r: Racer, dt: Float) {
        r.modeT += dt
        if (r.invuln > 0f) r.invuln -= dt
        if (r.shoveCooldown > 0f) r.shoveCooldown -= dt
        if (r.flash > 0f) r.flash = max(0f, r.flash - dt * 3f)
        if (r.squash > 0f) r.squash = max(0f, r.squash - dt * 2.2f)
        if (r.boostTime > 0f) r.boostTime -= dt
        if (r.turboTime > 0f) r.turboTime -= dt
        if (r.magnetTime > 0f) r.magnetTime -= dt
        val seg = course.segmentAt(r.s)
        if (seg.index != r.segIndex) onEnterSegment(r, seg)
        when (r.mode) {
            Mode.RUN -> run(r, seg, dt)
            Mode.STUN -> {
                r.speed = 0f
                if (r.modeT >= r.stun) {
                    if (r.respawnPending) {
                        // vanish where we landed, reappear at the last checkpoint (or the start)
                        events += SimEvent(SimEvent.Type.POOF, r.index, r.s, (r.d * 100f).toInt())
                        respawn(r)
                        return
                    }
                    r.mode = Mode.RUN; r.modeT = 0f
                }
                if (r.mode == Mode.STUN) applyGround(r, seg, dt, true)
            }
            Mode.AIR -> air(r, seg, dt)
            Mode.TUMBLE -> {
                tumble(r, dt)
                // safety net: never stay airborne-tumbling for long after a hit
                if (r.mode == Mode.TUMBLE && r.respawnPending && r.modeT > 1.8f) {
                    events += SimEvent(SimEvent.Type.POOF, r.index, r.s, (r.d * 100f).toInt())
                    respawn(r)
                }
            }
            Mode.FALL -> fall(r, dt)
            Mode.SPLASHED -> if (r.modeT > 1.05f) respawn(r)
            Mode.CLIMB -> climb(r, seg, dt)
            Mode.MONKEY -> monkey(r, seg, dt)
            Mode.ZIP -> zip(r, seg, dt)
            Mode.SWING -> swing(r, seg, dt)
            Mode.RAFT -> raft(r, seg, dt)
            Mode.SLIDE -> slide(r, seg, dt)
            Mode.FINISHED -> {
                r.speed = max(0f, r.speed - 10f * dt)
                if (r.s < course.finishS + 4f) r.s += r.speed * dt
            }
        }
        if (!r.finished && r.mode != Mode.SPLASHED && r.mode != Mode.FALL) {
            if (r.s >= course.finishS && seg.kind == SegKind.FINISH) finish(r)
        }
        // boss chaser
        chaser?.let { c ->
            if (!r.finished && r.mode != Mode.SPLASHED && r.mode != Mode.FALL && r.invuln <= 0f && r.s < c.s(time) + 0.4f) {
                events += SimEvent(SimEvent.Type.CAUGHT, r.index)
                r.mode = Mode.FALL; r.modeT = 0f; r.vy = 6f; r.vd = (if (r.d >= 0f) 1f else -1f) * 3f
                if (r.isPlayer) playerTookHit = true
            }
        }
        if (r.isPlayer) collectCoins(r)
    }

    private fun onEnterSegment(r: Racer, seg: Segment) {
        r.segIndex = seg.index
        if (r.mode != Mode.RUN && r.mode != Mode.STUN && r.mode != Mode.CLIMB) return
        when (seg.kind) {
            SegKind.JUMP, SegKind.TRAMPOLINE -> { r.mode = Mode.AIR; r.modeT = 0f; r.y = 0f; events += SimEvent(SimEvent.Type.JUMP, r.index, 0f, seg.kind.ordinal) }
            SegKind.ZIP -> { r.mode = Mode.ZIP; r.modeT = 0f; r.speed = 3f; events += SimEvent(SimEvent.Type.ZIP, r.index) }
            SegKind.SWING -> { r.mode = Mode.SWING; r.modeT = 0f; events += SimEvent(SimEvent.Type.SWING, r.index) }
            SegKind.MONKEY -> { r.mode = Mode.MONKEY; r.modeT = 0f; r.speed = 0f; events += SimEvent(SimEvent.Type.GRAB, r.index) }
            SegKind.CLIMB -> { r.mode = Mode.CLIMB; r.modeT = 0f; r.climbBaseS = seg.s0; r.speed = 0f; events += SimEvent(SimEvent.Type.GRAB, r.index) }
            SegKind.SLIDE -> { r.mode = Mode.SLIDE; r.modeT = 0f; r.speed = max(r.speed, 4f); events += SimEvent(SimEvent.Type.SLIDE, r.index) }
            SegKind.RAFT -> { r.mode = Mode.RAFT; r.modeT = 0f }
            else -> Unit
        }
    }

    fun maxSpeed(r: Racer, seg: Segment): Float {
        var v = rules.runSpeed
        // rivals race for real (0.94x .. 1.01x base by skill) and time hurdles well; the player runs
        // a little faster (PLAYER_EDGE) so a clean run wins, while every hurdle hit still costs time
        if (r.isPlayer) v *= rules.playerEdge else v *= 0.94f + 0.08f * r.skill
        if (seg.kind == SegKind.RAMP) v *= if (course.slopeAt(r.s) > 0f) 0.82f else 1.1f
        if (seg.kind == SegKind.BEAM) v *= 0.85f
        if (r.turboTime > 0f) v *= 1.38f
        if (r.boostTime > 0f) v *= 1.2f
        return v
    }

    private fun run(r: Racer, seg: Segment, dt: Float) {
        // jumping: a quick hop that clears low hazards (noodles, barrels, bollards)
        if (r.jumpQueued) {
            r.jumpQueued = false
            if (r.y <= 0.001f) { r.vy = JUMP_V; r.y = 0.002f; events += SimEvent(SimEvent.Type.JUMP, r.index, 0f, -1) }
        }
        val air = r.y > 0.001f
        if (air) {
            r.y += r.vy * dt
            r.vy -= JUMP_G * dt
            if (r.y <= 0f) {
                r.y = 0f; r.vy = 0f; r.squash = 0.55f
                events += SimEvent(SimEvent.Type.LAND, r.index)
            }
        }
        val target = if (r.hold) maxSpeed(r, seg) else 0f
        val accel = if (target > r.speed) 24f else rules.stopDecel
        // momentum carries through the air
        if (!air) r.speed = moveTowards(r.speed, target, accel * dt)
        if (r.isPlayer && r.hold && r.turbo >= 1f && r.turboTime <= 0f) {
            r.turbo = 0f; r.turboTime = 3f
            events += SimEvent(SimEvent.Type.TURBO, r.index)
        }
        val prevS = r.s
        var ns = r.s + r.speed * dt
        // hazard conveyors (lazy river, raft carry)
        var conv = if (air) 0f else beltSpeed(belts, time, r.s, r.d)
        // storm headwind pushes everyone back a little
        storm?.let { st -> conv -= st.gust(time) * st.strength }
        ns += conv * dt
        // blocks: don't walk into solid things
        var blocked = false
        forHazards(ns - 1f, ns + 1f) { h ->
            if (!blocked && Danger.solid(h.danger(time, ns - h.s0, r.d, r.y, hit))) {
                blocked = true
                if (Danger.solid(h.danger(time, prevS - h.s0, r.d, r.y, null))) {
                    // already inside (door closed on us): get pushed out
                    ns = prevS + hit.vs * dt
                    r.d += hit.vd * dt
                } else ns = prevS
            }
        }
        if (blocked) { if (r.speed > 2f) events += SimEvent(SimEvent.Type.BLOCKED, r.index); r.speed = 0f }
        r.s = ns
        r.meters += abs(ns - prevS)
        val stepPrev = r.animPhase
        r.animPhase += (ns - prevS) * 2.1f
        if ((stepPrev / Math.PI).toInt() != (r.animPhase / Math.PI).toInt()) events += SimEvent(SimEvent.Type.STEP, r.index)
        applyGround(r, seg, dt, false)
        if (r.mode == Mode.RUN) checkHazards(r)
        passCheckpoints(r)
    }

    /** Lateral drift, lane return, edges and rails while standing on a deck. */
    private fun applyGround(r: Racer, seg: Segment, dt: Float, stunned: Boolean) {
        val air = r.y > 0.001f
        var drift = 0f
        if (!air) forHazards(r.s - 0.2f, r.s + 0.2f) { h -> drift += h.drift(time, r.s - h.s0, r.d) }
        // funnel into single file ahead of a balance beam
        val beamAhead = seg.kind == SegKind.BEAM || course.segmentAt(r.s + 3.5f).kind == SegKind.BEAM
        val lane = if (beamAhead) r.laneD * 0.12f else r.laneD
        val hw = seg.halfWidth
        if (r.isPlayer && !r.steer.isNaN() && !stunned) {
            // steering never walks you off the edge by itself; only hazards can knock you off
            val lim = (hw - 0.36f).coerceAtLeast(0.05f)
            r.d += drift * dt
            r.d = moveTowards(r.d, r.steer.coerceIn(-lim, lim), (if (abs(drift) > 0.01f) 3.2f else STEER_SPEED) * dt)
            r.laneD = if (beamAhead) r.d / 0.12f else r.d
        } else if (abs(drift) > 0.01f) r.d += drift * dt
        else if (!stunned) r.d = moveTowards(r.d, lane, (if (beamAhead) 2.4f else 0.9f) * dt)
        if (r.y < 0f) r.y = 0f
        if (abs(r.d) > hw - 0.32f) {
            val side = sign(r.d)
            val rail = if (side < 0f) seg.railL else seg.railR
            if (rail) r.d = side * (hw - 0.32f)
            else if (abs(r.d) > hw + 0.06f || !seg.kind.hasDeck) {
                r.mode = Mode.FALL; r.modeT = 0f; r.vd = side * 1.6f; r.vy = 1.5f; r.vs = r.speed * 0.5f
                if (r.isPlayer) playerTookHit = true
            }
        }
        if (!seg.kind.hasDeck && r.mode == Mode.RUN && !air) {
            r.mode = Mode.FALL; r.modeT = 0f; r.vs = r.speed * 0.6f; r.vy = 0f
        }
    }

    private fun checkHazards(r: Racer) {
        var inZone = -1
        forHazards(r.s, r.s) { h ->
            val a = r.s - h.s0
            val k = h.danger(time, a, r.d, r.y, hit)
            if (k == Danger.KNOCK || k == Danger.FALL) {
                if (r.invuln > 0f) return@forHazards
                applyHit(r, k, h.id)
                return
            }
            if (h.lethal && h.type != HazType.CONVEYOR && h.type != HazType.RAFT) inZone = h.id
        }
        if (r.isPlayer) trackNearMiss(r, inZone)
    }

    /**
     * Running into a rival knocks them over: they tumble away from you, lie down for a moment and
     * get back up (no respawn, unless the shove sends them off the deck into the water).
     */
    private fun playerShoves() {
        val p = player
        if (p.mode != Mode.RUN || p.y > 0.3f || p.speed < 1.2f) return
        val reach = 2f * RR * 0.9f
        for (r in racers) {
            if (r === p || r.finished || r.shoveCooldown > 0f) continue
            if ((r.mode != Mode.RUN && r.mode != Mode.STUN) || r.y > 0.3f) continue
            val ds = r.s - p.s
            val dd = r.d - p.d
            val dist2 = ds * ds + dd * dd
            if (dist2 > reach * reach) continue
            val len = sqrt(dist2).coerceAtLeast(0.05f)
            val us = if (dist2 < 1e-4f) 1f else ds / len
            val ud = if (dist2 < 1e-4f) (if (r.index % 2 == 0) 1f else -1f) else dd / len
            r.mode = Mode.TUMBLE; r.modeT = 0f
            r.vs = r.speed * 0.3f + us * 3f + 1f
            r.vd = ud * 4.5f
            r.vy = 4f
            r.speed = 0f
            r.stun = SHOVE_STUN
            r.squash = 0.6f
            r.flash = 1f
            r.shoveCooldown = 1.6f
            p.speed *= 0.85f
            events += SimEvent(SimEvent.Type.SHOVE, r.index, 0f, p.index)
        }
    }

    fun applyHit(r: Racer, kind: Int, hazId: Int) {
        r.lastHitHazard = hazId
        if (r.isPlayer) { r.zoneHit = true; playerTookHit = true }
        if (kind == Danger.KNOCK && r.shield > 0) {
            r.shield--
            r.invuln = 0.9f
            r.d += hit.vd * 0.03f
            events += SimEvent(SimEvent.Type.SHIELD_POP, r.index)
            return
        }
        r.bonks++
        r.flash = 1f
        r.respawnPending = true
        if (kind == Danger.FALL) {
            r.mode = Mode.FALL; r.modeT = 0f; r.vy = 0.5f; r.vd = hit.vd * 0.3f; r.vs = r.speed * 0.4f
            // start the fall from the moving floor's surface (where the racer is drawn), not y = 0
            tiltUnder(r.s, r.d)?.let { t -> r.y = max(r.y, SetPieceGeometry.tiltSurface(t, time, r.d.coerceIn(-t.hw, t.hw))) }
            for ((h, hole) in holes) if (h is Trapdoor && r.s > hole[0] && r.s < hole[1] && r.y <= 0.01f) {
                r.y = min(r.y, SetPieceGeometry.tileSurface(h, time, r.s - h.s0, r.d)); r.vy = 0f
            }
        } else {
            r.mode = Mode.TUMBLE; r.modeT = 0f
            r.vs = hit.vs; r.vd = hit.vd; r.vy = hit.vy
            r.stun = RESPAWN_DIZZY
            if (hit.squash) r.squash = 1f
        }
        events += SimEvent(SimEvent.Type.KNOCK, r.index, 0f, hazId)
    }

    private fun trackNearMiss(r: Racer, zone: Int) {
        if (zone >= 0) {
            if (r.zoneHaz != zone) { r.zoneHaz = zone; r.zoneClear = 99f; r.zoneHit = false }
            val h = hazards[zone]
            val a = r.s - h.s0
            var dtOff = 0.05f
            while (dtOff <= 0.6f && dtOff < r.zoneClear) {
                if (Danger.hurts(h.danger(time + dtOff, a, r.d, r.y, null)) || Danger.hurts(h.danger(time - dtOff, a, r.d, r.y, null))) {
                    r.zoneClear = dtOff; break
                }
                dtOff += 0.05f
            }
        } else if (r.zoneHaz >= 0) {
            val h = hazards[r.zoneHaz]
            if (!r.zoneHit && r.s > h.zoneEnd - 0.05f && r.mode == Mode.RUN) {
                if (r.zoneClear <= 0.25f) {
                    r.perfects++; r.turbo = min(1f, r.turbo + 0.34f)
                    events += SimEvent(SimEvent.Type.PERFECT, r.index)
                } else if (r.zoneClear <= 0.55f) {
                    r.turbo = min(1f, r.turbo + 0.17f)
                    events += SimEvent(SimEvent.Type.NICE, r.index)
                }
            }
            r.zoneHaz = -1
        }
    }

    private fun tumble(r: Racer, dt: Float) {
        r.s += r.vs * dt; r.d += r.vd * dt; r.y += r.vy * dt
        r.vy -= rules.gravity * dt
        r.vs *= 1f - min(1f, dt * 0.8f)
        val seg = course.segmentAt(r.s)
        val hw = seg.halfWidth
        if (abs(r.d) > hw - 0.3f) {
            val side = sign(r.d)
            if (if (side < 0f) seg.railL else seg.railR) { r.d = side * (hw - 0.3f); r.vd = -r.vd * 0.35f }
        }
        if (r.y <= 0f && r.vy < 0f) {
            if (seg.kind.hasDeck && abs(r.d) > hw + 0.05f && abs(r.d) < hw + SetPieceGeometry.BODY_R) {
                // came down on the deck's edge: slip off it, never sink into its side
                r.d = sign(r.d) * (hw + SetPieceGeometry.BODY_R)
            }
            if (seg.kind.hasDeck && abs(r.d) <= hw + 0.05f) {
                r.y = 0f; r.mode = Mode.STUN; r.modeT = 0f; r.speed = 0f
                if (r.stun <= 0f) r.stun = 0.5f
                r.squash = max(r.squash, 0.6f)
                events += SimEvent(SimEvent.Type.LAND, r.index)
            } else if (r.y < -0.6f) {
                r.mode = Mode.FALL; r.modeT = 0f
            }
        }
    }

    private val wtmp = FloatArray(3)

    private fun fall(r: Racer, dt: Float) {
        val ps = r.s; val pd = r.d
        r.s += r.vs * dt; r.d += r.vd * dt; r.y += r.vy * dt
        r.vy -= rules.gravity * dt
        r.vs *= 1f - min(1f, dt * 1.5f)
        // a tipping deck carries whoever is on it: ride its surface and slide off the low edge
        val tilt = tiltUnder(r.s, r.d)
        if (tilt != null) {
            val roll = tilt.roll(time)
            val surf = SetPieceGeometry.tiltSurface(tilt, time, r.d.coerceIn(-tilt.hw, tilt.hw))
            if (r.y <= surf) {
                r.y = surf
                if (r.vy < 0f) r.vy = 0f
                r.vd += sin(roll) * rules.gravity * dt
                r.vs *= 1f - min(1f, dt * 4f)
                if (abs(r.d) > tilt.hw - 0.05f) {
                    // resting on the very edge: slip off it rather than stand half over the water
                    r.vd = sign(r.d) * max(abs(r.vd), 1.5f)
                } else if (abs(roll) < 0.1f && r.modeT > 0.3f) {
                    // the deck levelled out under us: we're lying on it, so get up (and respawn)
                    r.y = 0f; r.vs = 0f; r.vd = 0f; r.vy = 0f
                    r.mode = Mode.STUN; r.modeT = 0f
                    if (r.stun <= 0f) r.stun = 0.4f
                    return
                }
            }
        }
        // never fall into solid floor (the deck past a hole, a closed flip tile)
        if (inFloor(r.s, r.d, r.y)) {
            when {
                !inFloor(ps, r.d, r.y) -> { r.s = ps; r.vs = 0f }
                !inFloor(r.s, pd, r.y) -> { r.d = pd; r.vd = 0f }
                !inFloor(ps, pd, r.y) -> { r.s = ps; r.d = pd; r.vs = 0f; r.vd = 0f }
                else -> {
                    // the floor closed around us (a flip tile shutting): vanish in a puff and respawn
                    events += SimEvent(SimEvent.Type.POOF, r.index, r.s, (r.d * 100f).toInt())
                    respawn(r)
                    return
                }
            }
        }
        course.world(r.s, r.d, r.y, wtmp)
        if (wtmp[1] < Course.WATER_Y + 0.2f) {
            r.mode = Mode.SPLASHED; r.modeT = 0f
            r.splashes++
            events += SimEvent(SimEvent.Type.SPLASH, r.index)
        }
    }

    /** Lazy-river belts (a racer rides the strongest one under them). */
    val belts: List<Hazard> = hazards.filter { it.type == HazType.CONVEYOR }

    private val holes: List<Pair<Hazard, FloatArray>> = hazards.mapNotNull { h -> SetPieceGeometry.deckHole(h)?.let { h to it } }

    /** The tilting deck under (s, d), if any. */
    private fun tiltUnder(s: Float, d: Float): Tilt? {
        for ((h, hole) in holes) if (h is Tilt && s > hole[0] && s < hole[1] && abs(d) < h.hw + SetPieceGeometry.BODY_R) return h
        return null
    }

    /**
     * Whether a falling body at (s, d, y) overlaps solid floor: a deck slab, or a flip tile that is
     * (nearly) shut. Tilting decks are handled by [fall] itself.
     */
    private fun inFloor(s: Float, d: Float, y: Float): Boolean {
        if (y >= -0.05f) return false
        val seg = course.segmentAt(s)
        val hw = seg.halfWidth
        if (abs(d) >= hw + SetPieceGeometry.BODY_R) return false
        for ((h, hole) in holes) if (s > hole[0] && s < hole[1]) {
            if (h is Trapdoor) {
                val a = s - h.s0
                return h.open(time, if (a < 0f) 0 else 1, if (d < 0f) 0 else 1) < 0.35f && y + SetPieceGeometry.BODY_H > -0.3f
            }
            return false
        }
        if (!seg.kind.hasDeck || seg.kind == SegKind.SLIDE) return false
        return y + SetPieceGeometry.BODY_H > -SetPieceGeometry.DECK_DEPTH
    }

    fun respawn(r: Racer) {
        var cs = r.checkpoint
        chaser?.let { c -> cs = max(cs, min(course.finishS - 8f, c.s(time) + 8f)) }
        // never respawn on a hazard: step back to the nearest calm spot
        r.s = safeSpot(cs)
        r.d = r.laneD; r.y = 0f; r.vs = 0f; r.vd = 0f; r.vy = 0f; r.speed = 0f
        r.mode = Mode.RUN; r.modeT = 0f
        r.respawnPending = false
        r.stun = 0f
        // just long enough not to be hit again on the spot; never long enough to run through the
        // next hurdle untouched
        r.invuln = 0.5f
        r.segIndex = course.segmentAt(r.s).index
        r.zoneHaz = -1
        // a fresh attempt: re-roll the AI's timing error and recklessness for this hurdle (otherwise
        // a bot that decided to rush it would rush it, and fail, forever)
        r.aiErrHaz = -1
        r.aiPatience = 0f
        r.aiBestS = r.s; r.aiStall = 0f
        events += SimEvent(SimEvent.Type.RESPAWN, r.index)
    }

    private fun safeSpot(s: Float): Float {
        var c = s
        repeat(20) {
            var inside = false
            for (h in hazards) if (c >= h.zoneStart - 0.3f && c <= h.zoneEnd + 0.3f && h.type != HazType.CONVEYOR) { inside = true; c = h.zoneStart - 0.5f }
            val seg = course.segmentAt(c)
            if (!seg.kind.hasDeck || seg.kind == SegKind.SLIDE) { c = seg.s0 - 1f; inside = true }
            if (!inside) return c
        }
        return c
    }

    private fun passCheckpoints(r: Racer) {
        for (i in checkpoints.indices) {
            if (i <= r.checkpointIdx) continue
            if (r.s >= checkpoints[i]) {
                r.checkpointIdx = i
                r.checkpoint = checkpoints[i] + 0.5f
                if (r.isPlayer) events += SimEvent(SimEvent.Type.CHECKPOINT, r.index, checkpoints[i], i)
            } else break
        }
    }

    private fun air(r: Racer, seg: Segment, dt: Float) {
        val len = seg.length
        val hops = if (seg.kind == SegKind.TRAMPOLINE) max(1, seg.p1.toInt()) else 1
        val v = if (seg.kind == SegKind.TRAMPOLINE) 6.5f else 8.5f
        r.s += v * dt
        val p = ((r.s - seg.s0) / len).coerceIn(0f, 1f)
        val prevY = r.y
        r.y = SetPieceMath.jumpHeight(p, seg.p0, hops)
        if (hops > 1 && r.y < 0.05f && prevY > r.y && p in 0.05f..0.95f) events += SimEvent(SimEvent.Type.JUMP, r.index, 0f, SegKind.TRAMPOLINE.ordinal)
        r.animPhase += dt * 3f
        if (r.s >= seg.s1) land(r)
    }

    private fun land(r: Racer) {
        r.mode = Mode.RUN; r.modeT = 0f; r.y = 0f; r.squash = 0.8f
        r.speed = min(r.speed, 3f)
        events += SimEvent(SimEvent.Type.LAND, r.index)
    }

    private fun climb(r: Racer, seg: Segment, dt: Float) {
        val h = r.s - seg.s0
        val speed = if (r.hold) 1.8f else 0f
        r.speed = speed
        r.s += speed * dt
        r.animPhase += speed * dt * 3.2f
        val lim = seg.halfWidth - 0.4f
        if (r.isPlayer && !r.steer.isNaN()) {
            // slide along the wall to dodge falling coconuts
            r.d = moveTowards(r.d, r.steer.coerceIn(-lim, lim), STEER_SPEED * 0.6f * dt)
            r.laneD = r.d
        } else r.d = moveTowards(r.d, r.laneD.coerceIn(-lim, lim), 0.8f * dt)
        if (speed > 0f && ((r.animPhase - speed * dt * 3.2f) / Math.PI).toInt() != (r.animPhase / Math.PI).toInt()) events += SimEvent(SimEvent.Type.GRAB, r.index)
        // coconuts
        if (r.invuln <= 0f) {
            for (hz in hazards) {
                if (hz.type != HazType.COCONUTS || abs(hz.s0 - seg.s0) > 0.5f) continue
                if (hz.danger(time, h, r.d, 0f, hit) == Danger.KNOCK) {
                    r.lastHitHazard = hz.id
                    if (r.shield > 0) { r.shield--; r.invuln = 0.9f; events += SimEvent(SimEvent.Type.SHIELD_POP, r.index); break }
                    // drop back to the wall base
                    r.bonks++; r.flash = 1f
                    if (r.isPlayer) { playerTookHit = true; r.zoneHit = true }
                    r.y = h; r.s = seg.s0 - 0.7f; r.vs = -1.5f; r.vd = 0f; r.vy = 0f; r.stun = RESPAWN_DIZZY
                    r.respawnPending = true
                    r.mode = Mode.TUMBLE; r.modeT = 0f
                    events += SimEvent(SimEvent.Type.KNOCK, r.index, 0f, hz.id)
                    return
                }
            }
        }
        if (r.s >= seg.s1) { r.mode = Mode.RUN; r.modeT = 0f; r.speed = 2f; r.squash = 0.4f }
    }

    private fun monkey(r: Racer, seg: Segment, dt: Float) {
        val speed = if (r.hold) 2.6f else 0f
        r.speed = speed
        r.s += speed * dt
        r.animPhase += speed * dt * 2.4f
        r.y = 0.55f
        if (r.invuln <= 0f) {
            forHazards(r.s, r.s) { h ->
                val k = h.danger(time, r.s - h.s0, r.d, r.y, hit)
                if (k == Danger.KNOCK) { applyHit(r, k, h.id); r.mode = Mode.FALL; r.vy = 2f; return }
            }
        }
        if (r.s >= seg.s1) land(r)
    }

    private fun zip(r: Racer, seg: Segment, dt: Float) {
        r.speed = min(10.5f, r.speed + 9f * dt)
        r.s += r.speed * dt
        r.y = 0.6f
        // grab the nearest of the three cables
        r.d = moveTowards(r.d, SetPieceGeometry.nearestCable(seg.halfWidth, r.d), 5f * dt)
        r.animPhase += dt
        if (r.s >= seg.s1) land(r)
    }

    private fun swing(r: Racer, seg: Segment, dt: Float) {
        val dur = 1.9f
        val p = (r.modeT / dur).coerceIn(0f, 1f)
        val half = seg.length / 2f
        val pivotH = seg.p0
        val rope = sqrt(half * half + (pivotH - 1f) * (pivotH - 1f))
        val theta0 = kotlin.math.asin((half / rope).coerceIn(-1f, 1f))
        val th = SetPieceMath.swingAngle(p, theta0)
        r.s = seg.s0 + half + rope * kotlin.math.sin(th)
        r.y = pivotH - rope * cos(th) - 1f
        if (p >= 1f) { r.s = seg.s1 + 0.05f; land(r) }
    }

    private fun raft(r: Racer, seg: Segment, dt: Float) {
        val raftH = hazards.firstOrNull { it is Raft && it.seg.index == seg.index } as? Raft
        val target = if (r.hold) maxSpeed(r, seg) * 0.8f else 0f
        r.speed = moveTowards(r.speed, target, (if (target > r.speed) 20f else rules.stopDecel) * dt)
        var ns = r.s + r.speed * dt
        r.y = 0f
        if (raftH != null) {
            val onNow = raftH.onRaft(time, r.s)
            if (onNow) {
                ns += raftH.velocity(time) * dt
                val c = raftH.centre(time)
                val front = c + raftH.halfLen - 0.2f
                val back = c - raftH.halfLen + 0.2f
                // bumpers keep riders aboard unless docked at that end
                if (ns > front && !raftH.docked(time, false)) { ns = front; r.speed = 0f }
                if (ns < back && !raftH.docked(time, true)) ns = back
            } else if (max(r.s, ns) > seg.s0 + 0.12f && r.s < seg.s1 - 0.12f) {
                // stepped off the dock with no raft there: into the water at once (never walk on it)
                r.s = max(r.s, min(ns, seg.s0 + 0.2f))
                r.mode = Mode.FALL; r.modeT = 0f; r.vs = r.speed * 0.5f; r.vy = 0.5f
                if (r.isPlayer) playerTookHit = true
                return
            }
        }
        r.animPhase += (ns - r.s) * 2.1f
        r.s = ns
        if (r.s >= seg.s1) { r.mode = Mode.RUN; r.modeT = 0f }
    }

    private fun slide(r: Racer, seg: Segment, dt: Float) {
        val slope = course.slopeAt(r.s)
        r.speed = clamp(r.speed + (-slope * 14f - 0.6f) * dt, 5.5f, 11.5f)
        r.s += r.speed * dt
        // bank toward the outside of bends
        val yawRate = (course.yawAt(r.s + 1f) - course.yawAt(r.s - 1f)) / 2f
        r.d = moveTowards(r.d, clamp(-yawRate * 9f + r.laneD * 0.3f, -seg.halfWidth + 0.4f, seg.halfWidth - 0.4f), 3f * dt)
        // feet ride on the curved channel surface (higher up the side in bends)
        r.y = SetPieceGeometry.slideSurface(seg.halfWidth, r.d)
        r.animPhase += dt
        if (r.s >= seg.s1) { r.mode = Mode.RUN; r.modeT = 0f; r.speed = 5f; r.y = 0f }
    }

    private fun finish(r: Racer) {
        finishedCount++
        r.rank = finishedCount
        r.finishTime = time
        r.mode = Mode.FINISHED; r.modeT = 0f
        events += SimEvent(SimEvent.Type.FINISH, r.index, 0f, r.rank)
    }

    private fun collectCoins(r: Racer) {
        val magnet = r.magnetTime > 0f || r.boostTime > 0f && false
        val rs = if (magnet) 2.4f else 0.75f
        for (c in coins) {
            if (c.taken) continue
            if (abs(c.s - r.s) > rs + 0.3f) { if (c.s > r.s + 3f) break else continue }
            if (abs(c.d - r.d) < rs && abs(c.h - (r.y + 0.6f)) < 1.4f) {
                c.taken = true
                r.coins += c.value
                events += SimEvent(SimEvent.Type.COIN, r.index, c.s, c.value)
            }
        }
    }

    /** Current standings (finished first by rank, then by distance). */
    fun standings(): List<Racer> = racers.sortedWith(compareBy<Racer> { if (it.finished) it.rank else 1000 }.thenByDescending { it.s })

    fun progressOf(r: Racer): Float = ((r.s - course.startS) / (course.finishS - course.startS)).coerceIn(0f, 1f)
}
