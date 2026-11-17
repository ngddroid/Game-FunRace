package com.ngstudio.funrace.game.sim

import com.ngstudio.funrace.core.Rng
import com.ngstudio.funrace.core.clamp
import com.ngstudio.funrace.core.moveTowards
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

enum class Mode { RUN, STUN, AIR, TUMBLE, FALL, SPLASHED, CLIMB, MONKEY, ZIP, SWING, RAFT, SLIDE, FINISHED }

enum class RaceKind { NORMAL, TUTORIAL, BOSS_TIDE, BOSS_DUCK, BOSS_STORM, BONUS, DAILY, TIME_TRIAL }

/** Rule tweaks (daily-challenge modifiers, boosters, difficulty assists). */
class RaceRules(
    var gravity: Float = 22f,
    var stopDecel: Float = 48f,
    var runSpeed: Float = 5.2f,
    var playerScale: Float = 1f,
    var mirror: Boolean = false,
    var bigHeads: Boolean = false,
    /** Levels 1..10: rivals never beat a player who keeps going. */
    var guaranteeWin: Boolean = false,
    var rivalSpeedCap: Float = 1f,
)

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

class SimEvent(val type: Type, val racer: Int, val x: Float = 0f, val v: Int = 0) {
    enum class Type { STEP, KNOCK, BLOCKED, SPLASH, RESPAWN, CHECKPOINT, COIN, FINISH, NICE, PERFECT, TURBO, SHIELD_POP, JUMP, LAND, GRAB, ZIP, SWING, SLIDE, HAZARD, CAUGHT, GUST }
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
                if (r.modeT >= r.stun) { r.mode = Mode.RUN; r.modeT = 0f }
                applyGround(r, seg, dt, true)
            }
            Mode.AIR -> air(r, seg, dt)
            Mode.TUMBLE -> tumble(r, dt)
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
        if (!r.isPlayer) {
            v *= (0.86f + 0.14f * r.skill) * rules.rivalSpeedCap
            if (rules.guaranteeWin && !player.finished) {
                val lead = r.s - player.s
                if (lead > -1.5f) v *= 0.55f
                if (r.s > course.finishS - 5f) v = 0f
            }
        }
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
        var conv = 0f
        if (!air) forHazards(r.s - 0.5f, r.s + 0.5f) { h -> conv += h.conveyor(time, r.s - h.s0, r.d) }
        // storm headwind pushes everyone back a little
        storm?.let { st -> conv -= st.gust(time) * st.strength }
        ns += conv * dt
        // blocks: don't walk into solid things
        var blocked = false
        forHazards(ns - 1f, ns + 1f) { h ->
            if (!blocked && h.danger(time, ns - h.s0, r.d, r.y, hit) == Danger.BLOCK) {
                blocked = true
                if (h.danger(time, prevS - h.s0, r.d, r.y, null) == Danger.BLOCK) {
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
        if (kind == Danger.FALL) {
            r.mode = Mode.FALL; r.modeT = 0f; r.vy = 0.5f; r.vd = hit.vd * 0.3f; r.vs = r.speed * 0.4f
        } else {
            r.mode = Mode.TUMBLE; r.modeT = 0f
            r.vs = hit.vs; r.vd = hit.vd; r.vy = hit.vy; r.stun = hit.stun
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
                if (h.danger(time + dtOff, a, r.d, r.y, null) >= Danger.KNOCK || h.danger(time - dtOff, a, r.d, r.y, null) >= Danger.KNOCK) {
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
        r.s += r.vs * dt; r.d += r.vd * dt; r.y += r.vy * dt
        r.vy -= rules.gravity * dt
        r.vs *= 1f - min(1f, dt * 1.5f)
        course.world(r.s, r.d, r.y, wtmp)
        if (wtmp[1] < Course.WATER_Y + 0.2f) {
            r.mode = Mode.SPLASHED; r.modeT = 0f
            r.splashes++
            events += SimEvent(SimEvent.Type.SPLASH, r.index)
        }
    }

    fun respawn(r: Racer) {
        var cs = r.checkpoint
        chaser?.let { c -> cs = max(cs, min(course.finishS - 8f, c.s(time) + 8f)) }
        // never respawn on a hazard: step back to the nearest calm spot
        r.s = safeSpot(cs)
        r.d = r.laneD; r.y = 0f; r.vs = 0f; r.vd = 0f; r.vy = 0f; r.speed = 0f
        r.mode = Mode.RUN; r.modeT = 0f
        r.invuln = 1.3f
        r.segIndex = course.segmentAt(r.s).index
        r.zoneHaz = -1
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
        r.d = moveTowards(r.d, r.laneD.coerceIn(-seg.halfWidth + 0.4f, seg.halfWidth - 0.4f), 0.8f * dt)
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
                    r.y = h; r.s = seg.s0 - 0.7f; r.vs = -1.5f; r.vd = 0f; r.vy = 0f; r.stun = 0.9f
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
            } else if (r.s > seg.s0 + 0.12f && r.s < seg.s1 - 0.12f) {
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
        r.y = 0f
        // bank toward the outside of bends
        val yawRate = (course.yawAt(r.s + 1f) - course.yawAt(r.s - 1f)) / 2f
        r.d = moveTowards(r.d, clamp(-yawRate * 9f + r.laneD * 0.3f, -seg.halfWidth + 0.4f, seg.halfWidth - 0.4f), 3f * dt)
        r.animPhase += dt
        if (r.s >= seg.s1) { r.mode = Mode.RUN; r.modeT = 0f; r.speed = 5f }
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
