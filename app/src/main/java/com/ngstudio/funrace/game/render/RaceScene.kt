package com.ngstudio.funrace.game.render

import android.content.Context
import android.opengl.GLES30
import com.ngstudio.funrace.core.PI_F
import com.ngstudio.funrace.core.Rng
import com.ngstudio.funrace.core.TAU
import com.ngstudio.funrace.core.damp
import com.ngstudio.funrace.core.dampAngle
import com.ngstudio.funrace.core.lerp
import com.ngstudio.funrace.core.smoothstep
import com.ngstudio.funrace.data.Bones
import com.ngstudio.funrace.data.Catalog
import com.ngstudio.funrace.game.RaceController
import com.ngstudio.funrace.game.RacePhase
import com.ngstudio.funrace.game.sim.Course
import com.ngstudio.funrace.game.sim.Danger
import com.ngstudio.funrace.game.sim.HazType
import com.ngstudio.funrace.game.sim.Mode
import com.ngstudio.funrace.game.sim.RaceKind
import com.ngstudio.funrace.game.sim.Racer
import com.ngstudio.funrace.game.sim.SegKind
import com.ngstudio.funrace.game.sim.SetPieceGeometry
import com.ngstudio.funrace.game.sim.Tilt
import com.ngstudio.funrace.game.sim.Trapdoor
import com.ngstudio.funrace.game.sim.SetPieceMath
import com.ngstudio.funrace.game.sim.SimEvent
import com.ngstudio.funrace.level.TimeOfDay
import com.ngstudio.funrace.level.World
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Per-racer animation state on the render side. */
private class RacerVis(val racer: Racer, val look: Look) {
    val pose = Pose()
    val target = Pose()
    var state = AnimState.IDLE
    var blend = 1f
    var expr = Expression.HAPPY
    var blink = 0f
    var blinkT = 2f
    var spinYaw = 0f
    var spinPitch = 0f
    var spinRoll = 0f
    var yaw = 0f
    var wx = 0f; var wy = 0f; var wz = 0f
    var visible = true
    var trailT = 0f
    var stepPuff = 0f
    val dance = FloatArray(3)
    var danceId = "hop"
    var stunStars = 0f
}

class RaceScene(
    private val context: Context,
    private val res: RenderResources,
    val controller: RaceController,
) : Scene {
    private val setup = controller.setup
    private val course = setup.course
    private val world = setup.spec.world
    private val rig = LightRig().also { it.configure(world, if (setup.spec.kind == RaceKind.BOSS_STORM) TimeOfDay.SUNSET else setup.spec.time) }
    private val cam = Camera()
    private val chunks: List<Chunk>
    private val scenery: List<Chunk>
    private val shoreTex: Int
    private val shoreRect: FloatArray
    private val decals: Mesh?
    private val zoneVals = FloatArray(48)
    private val hazVis = HazardVisuals(res, course, world)
    private val drawer = CharacterDrawer(res)
    private val vis: List<RacerVis>
    private val particles = ParticleSystem(900)
    private val names: TextAtlas
    private val clouds = ArrayList<FloatArray>()
    private val rng = Rng(setup.spec.seed)
    private var time = 0f
    private val p = FloatArray(3)
    private val hazHit = HashMap<Int, Float>()
    private var shake = 0f
    private val camPos = FloatArray(3)
    private val camTgt = FloatArray(3)
    private var camYaw = 0f
    private var camInit = false
    private var flash = 0f
    private val storm = setup.spec.kind == RaceKind.BOSS_STORM
    private val waterY = Course.WATER_Y

    init {
        val tb = TrackBuilder(setup, world)
        val (track, scen) = tb.build()
        chunks = track
        scenery = scen
        val (buf, rect) = TrackBuilder.shoreMask(course, tb.footprints)
        shoreTex = TextureUtil.r8(256, 256, buf)
        shoreRect = rect
        decals = buildDecals()
        vis = controller.sim.racers.map { r -> RacerVis(r, if (r.isPlayer) controller.playerLook else controller.rivalLooks.getOrElse(r.lookIndex) { controller.playerLook }) }
        for (v in vis) {
            v.danceId = if (v.racer.isPlayer) controller.playerDance else Catalog.dances[(v.racer.index * 3 + 1) % Catalog.dances.size].id
            v.blinkT = 1f + v.racer.index * 0.7f
        }
        val labels = controller.sim.racers.map { (if (it.isPlayer) "YOU" else it.name) to (if (it.isPlayer) 0xFFFFD23F.toInt() else 0xFFFFFFFF.toInt()) }
        names = TextAtlas(context, labels)
        for (i in 0 until 20) {
            val a = rng.range(0f, TAU)
            val r = rng.range(170f, 280f)
            val b = course.bounds()
            val cx = (b[0] + b[2]) / 2f; val cz = (b[1] + b[3]) / 2f
            clouds += floatArrayOf(cx + cos(a) * r, rng.range(30f, 75f), cz + sin(a) * r, rng.range(40f, 80f), rng.range(0.6f, 1f))
        }
    }

    /**
     * Danger-zone decals: a soft solid fill with a crisp border. Each zone is meshed as rows across
     * the track; every vertex stores how far inside the zone it is (0 on the edge, 1 at 0.2 m+) so
     * the shader can draw the border ring. Disc zones (sweepers, spin bars) are clipped per row.
     */
    private fun buildDecals(): Mesh? {
        val md = MeshData(1024, 2048)
        val edgeW = 0.2f
        val cols = FloatArray(6)
        for (h in setup.hazards) {
            if (h.id >= 48) continue
            val seg = course.segmentAt(h.s0)
            if (!seg.kind.hasDeckVisual()) continue
            val trackHw = seg.halfWidth
            for (z in h.zones()) {
                val zd0 = max(z.d0, -trackHw); val zd1 = min(z.d1, trackHw)
                if (zd1 - zd0 < 0.05f) continue
                fun inside(a: Float, d: Float): Float {
                    var e = min(min(a - z.a0, z.a1 - a), min(d - zd0, zd1 - d))
                    if (z.r > 0f) e = min(e, z.r - sqrt(a * a + (d - z.cd) * (d - z.cd)))
                    return (e / edgeW).coerceIn(0f, 1f)
                }
                fun row(a: Float, out: FloatArray): Int {
                    var lo = zd0; var hi = zd1
                    if (z.r > 0f) {
                        val k = z.r * z.r - a * a
                        if (k <= 0f) return 0
                        val c = sqrt(k)
                        lo = max(lo, z.cd - c); hi = min(hi, z.cd + c)
                    }
                    if (hi - lo < 0.02f) return 0
                    val e = min(edgeW, (hi - lo) / 2f)
                    out[0] = lo; out[1] = lo + e; out[2] = (lo + hi) / 2f; out[3] = hi - e; out[4] = hi
                    return 5
                }
                // rows: dense near the ends so the border stays crisp
                val rows = ArrayList<Float>()
                var a = z.a0
                rows += a
                while (a < z.a1) {
                    val step = if (a - z.a0 < edgeW || z.a1 - a < edgeW * 1.5f || z.r > 0f) 0.1f else 0.4f
                    a = min(z.a1, a + step)
                    rows += a
                }
                var prevBase = -1
                for (ra in rows) {
                    val n = row(ra, cols)
                    if (n == 0) { prevBase = -1; continue }
                    val base = md.vCount
                    for (k in 0 until n) {
                        course.world(h.s0 + ra, cols[k], 0.07f, p)
                        val edge = if (ra == z.a0 || ra == z.a1) 0f else inside(ra, cols[k])
                        md.v(p[0], p[1], p[2], 0f, 1f, 0f, world.danger, 1f, h.id or ((edge * 255f).toInt() shl 8))
                    }
                    if (prevBase >= 0) for (k in 0 until n - 1) {
                        md.tri(prevBase + k, base + k + 1, base + k); md.tri(prevBase + k, prevBase + k + 1, base + k + 1)
                    }
                    prevBase = base
                }
            }
        }
        return if (md.isEmpty) null else Mesh(md)
    }

    private fun SegKind.hasDeckVisual() = this == SegKind.DECK || this == SegKind.TURN || this == SegKind.RAMP || this == SegKind.BEAM || this == SegKind.START || this == SegKind.FINISH

    // ------------------------------------------------------------------------------------ update
    override fun update(dt: Float) {
        time += dt
        res.time = time
        controller.update(dt)
        handleFx()
        val sim = controller.sim
        for (v in vis) animate(v, dt, sim.time)
        updateCamera(dt)
        particles.update(dt) { _, _ -> waterY }
        if (shake > 0f) shake = max(0f, shake - dt * 2.2f)
        if (flash > 0f) flash = max(0f, flash - dt * 1.8f)
        val it = hazHit.entries.iterator()
        while (it.hasNext()) { val e = it.next(); e.setValue(e.value - dt * 1.4f); if (e.value <= 0f) it.remove() }
        ambientFx(dt)
    }

    private fun handleFx() {
        val sim = controller.sim
        for (e in controller.fxEvents) {
            val r = if (e.racer >= 0) sim.racers[e.racer] else null
            val v = if (r != null) vis[r.index] else null
            when (e.type) {
                SimEvent.Type.KNOCK -> if (v != null) {
                    burst(v.wx, v.wy + 0.9f, v.wz, 0xFFFFE36B.toInt(), ParticleAtlas.STAR, 8, 4f, 0.5f)
                    burst(v.wx, v.wy + 0.9f, v.wz, 0xFFFFFFFF.toInt(), ParticleAtlas.SPLAT, 1, 0f, 1.6f, life = 0.25f)
                    hazHit[e.v] = 1f
                    if (r?.isPlayer == true && !controller.reducedMotion) shake = 0.6f
                }
                SimEvent.Type.SHOVE -> if (v != null) {
                    burst(v.wx, v.wy + 0.9f, v.wz, 0xFFFFE36B.toInt(), ParticleAtlas.STAR, 8, 4f, 0.5f)
                    burst(v.wx, v.wy + 0.6f, v.wz, 0xDDFFFFFF.toInt(), ParticleAtlas.PUFF, 4, 2f, 0.6f)
                }
                SimEvent.Type.SPLASH -> if (r != null) {
                    course.world(r.s, r.d, 0f, p)
                    val x = p[0]; val z = p[2]
                    splash(x, z, if (r.isPlayer) 1.3f else 0.9f)
                }
                SimEvent.Type.COIN -> if (r != null) {
                    course.world(e.x, 0f, 0.8f, p)
                    burst(vis[r.index].wx, vis[r.index].wy + 1.0f, vis[r.index].wz, 0xFFFFD23F.toInt(), ParticleAtlas.SPARKLE, 4, 2.5f, 0.35f, additive = true)
                }
                SimEvent.Type.CHECKPOINT -> {
                    course.world(e.x, -course.segmentAt(e.x).halfWidth + 0.2f, 2.4f, p)
                    confetti(p[0], p[1], p[2], 22)
                }
                SimEvent.Type.FINISH -> if (v != null) confetti(v.wx, v.wy + 1.5f, v.wz, 40)
                SimEvent.Type.JUMP -> if (v != null) burst(v.wx, v.wy + 0.1f, v.wz, 0xFFFFFFFF.toInt(), ParticleAtlas.PUFF, 6, 2f, 0.7f)
                SimEvent.Type.LAND -> if (v != null) burst(v.wx, v.wy + 0.05f, v.wz, 0xCCFFFFFF.toInt(), ParticleAtlas.PUFF, 5, 1.6f, 0.5f)
                SimEvent.Type.PERFECT -> if (v != null) burst(v.wx, v.wy + 1.2f, v.wz, 0xFFFF5FA8.toInt(), ParticleAtlas.SPARKLE, 10, 3.5f, 0.4f, additive = true)
                SimEvent.Type.TURBO -> if (v != null) burst(v.wx, v.wy + 0.8f, v.wz, 0xFFFFD23F.toInt(), ParticleAtlas.GLOW, 8, 3f, 0.8f, additive = true)
                SimEvent.Type.SHIELD_POP -> if (v != null) burst(v.wx, v.wy + 0.8f, v.wz, 0xFFBFF4FF.toInt(), ParticleAtlas.BUBBLE, 14, 3f, 0.4f)
                SimEvent.Type.HAZARD -> {
                    val h = sim.hazards[e.v]
                    if (abs(h.s0 - sim.player.s) < 40f) when (h.type) {
                        HazType.PRESS -> { course.world(h.s0, 0f, 0.1f, p); for (d in floatArrayOf(-1.5f, 0f, 1.5f)) { course.world(h.s0, d, 0.1f, p); burst(p[0], p[1], p[2], 0xDDFFFFFF.toInt(), ParticleAtlas.PUFF, 3, 2.5f, 0.8f) } }
                        HazType.CANNON -> { val c = h as com.ngstudio.funrace.game.sim.Cannon; course.world(h.s0, c.side * (h.hw + 0.2f), 1.0f, p); burst(p[0], p[1], p[2], 0xEEFFFFFF.toInt(), ParticleAtlas.PUFF, 8, 3f, 1f) }
                        HazType.WAVE -> { course.world(h.s0, 0f, 0.8f, p); burst(p[0], p[1], p[2], 0xEEDDF8FF.toInt(), ParticleAtlas.DROP, 10, 4f, 0.25f) }
                        else -> Unit
                    }
                }
                SimEvent.Type.CAUGHT -> if (r != null) { course.world(r.s, r.d, 0f, p); splash(p[0], p[2], 1.4f) }
                SimEvent.Type.POOF -> if (r != null) {
                    // vanish puff where the bonked racer landed
                    course.world(e.x, e.v / 100f, 0.5f, p)
                    burst(p[0], p[1], p[2], 0xEEFFFFFF.toInt(), ParticleAtlas.PUFF, 10, 2.6f, 0.9f)
                    burst(p[0], p[1] + 0.4f, p[2], 0xFFFFE36B.toInt(), ParticleAtlas.STAR, 5, 3f, 0.4f)
                }
                SimEvent.Type.RESPAWN -> if (r != null) {
                    // reappear at the checkpoint; the camera cuts there instead of flying back
                    course.world(r.s, r.d, 0.4f, p)
                    burst(p[0], p[1], p[2], 0xEEFFFFFF.toInt(), ParticleAtlas.PUFF, 8, 2.2f, 0.8f)
                    burst(p[0], p[1] + 0.6f, p[2], 0xFFFFD23F.toInt(), ParticleAtlas.SPARKLE, 6, 2.5f, 0.35f, additive = true)
                    if (r.isPlayer) camInit = false
                }
                SimEvent.Type.GUST -> flash = 1f
                SimEvent.Type.STEP -> if (v != null && r!!.isPlayer && r.speed > 3f) burst(v.wx, v.wy + 0.05f, v.wz, 0x99FFFFFF.toInt(), ParticleAtlas.PUFF, 1, 0.6f, 0.35f, life = 0.35f)
                else -> Unit
            }
        }
    }

    private fun burst(x: Float, y: Float, z: Float, color: Int, sprite: Int, n: Int, speed: Float, size: Float, additive: Boolean = false, life: Float = 0.7f) {
        val r = particles.rng
        for (i in 0 until n) {
            val a = r.range(0f, TAU); val e = r.range(-0.2f, 1f)
            val sp = speed * r.range(0.5f, 1f)
            particles.emit(x, y, z, cos(a) * sp * (1f - e * 0.5f), e * sp + speed * 0.3f, sin(a) * sp * (1f - e * 0.5f),
                life * r.range(0.7f, 1.2f), size * r.range(0.7f, 1.1f), size * 0.4f, color, sprite, gravity = if (sprite == ParticleAtlas.STAR || sprite == ParticleAtlas.DROP) 9f else 0f,
                dragK = 2f, additive = additive, rotation = r.range(0f, TAU), rotSpeed = r.range(-6f, 6f))
        }
    }

    private fun splash(x: Float, z: Float, k: Float) {
        val r = particles.rng
        for (i in 0 until (26 * k).toInt()) {
            val a = r.range(0f, TAU); val sp = r.range(1.5f, 5f) * k
            particles.emit(x, waterY + 0.2f, z, cos(a) * sp * 0.4f, r.range(4f, 9f) * k, sin(a) * sp * 0.4f, r.range(0.6f, 1.1f), r.range(0.25f, 0.5f) * k, 0.15f,
                if (i % 3 == 0) 0xFFFFFFFF.toInt() else 0xEEBFF4FF.toInt(), ParticleAtlas.DROP, gravity = 18f, dragK = 0.5f)
        }
        particles.emit(x, waterY + 0.05f, z, 0f, 0f, 0f, 1.1f, 0.6f * k, 3.5f * k, 0xEEFFFFFF.toInt(), ParticleAtlas.RING, ground = true)
        particles.emit(x, waterY + 0.04f, z, 0f, 0f, 0f, 1.6f, 1.2f * k, 4.5f * k, 0xAAFFFFFF.toInt(), ParticleAtlas.FOAM, ground = true)
        for (i in 0 until 6) particles.emit(x + r.range(-0.5f, 0.5f), waterY + 0.3f, z + r.range(-0.5f, 0.5f), 0f, r.range(1f, 2.5f), 0f, 0.8f, 1.0f * k, 1.8f * k, 0xCCFFFFFF.toInt(), ParticleAtlas.PUFF, dragK = 2f)
    }

    private fun confetti(x: Float, y: Float, z: Float, n: Int) {
        val r = particles.rng
        val cols = intArrayOf(0xFFFF6B57.toInt(), 0xFFFFD23F.toInt(), 0xFF7BE36B.toInt(), 0xFF2E9BFF.toInt(), 0xFFFF5FA8.toInt(), 0xFF9B6BFF.toInt())
        for (i in 0 until n) {
            val a = r.range(0f, TAU); val sp = r.range(2f, 6f)
            particles.emit(x, y, z, cos(a) * sp, r.range(3f, 8f), sin(a) * sp, r.range(1.4f, 2.4f), 0.22f, 0.2f, cols[i % cols.size], ParticleAtlas.SQUARE,
                gravity = 6f, dragK = 1.6f, rotation = r.range(0f, TAU), rotSpeed = r.range(-12f, 12f))
        }
    }

    private var ambientT = 0f
    private fun ambientFx(dt: Float) {
        val sim = controller.sim
        val pl = sim.player
        ambientT += dt
        // fan gust streaks
        for (h in sim.hazards) {
            if (abs(h.s0 - pl.s) > 30f) continue
            if (h.type == HazType.FAN) {
                val f = h as com.ngstudio.funrace.game.sim.Fan
                if (f.gust(sim.time) > 0f && particles.rng.chance(dt * 30f)) {
                    val a = particles.rng.range(-f.halfLen, f.halfLen)
                    course.world(h.s0 + a, f.side * h.hw, particles.rng.range(0.3f, 1.6f), p)
                    val y = course.yawAt(h.s0)
                    val dx = -cos(y) * -f.side * 9f; val dz = sin(y) * -f.side * 9f
                    particles.emit(p[0], p[1], p[2], dx, 0f, dz, 0.5f, 0.6f, 0.9f, 0x99FFFFFF.toInt(), ParticleAtlas.STREAK, rotation = 0f)
                }
            }
            if (h.type == HazType.CONVEYOR && particles.rng.chance(dt * 8f)) Unit
        }
        // player trail cosmetic
        val pv = vis[pl.index]
        val trail = Catalog.trails.getOrNull(controller.playerTrail)
        if (trail != null && trail.colors.isNotEmpty() && pl.speed > 1f && pv.visible) {
            pv.trailT += dt
            if (pv.trailT > 0.05f) {
                pv.trailT = 0f
                val c = trail.colors[particles.rng.nextInt(trail.colors.size)]
                particles.emit(pv.wx + particles.rng.range(-0.15f, 0.15f), pv.wy + 0.5f + particles.rng.range(0f, 0.4f), pv.wz + particles.rng.range(-0.15f, 0.15f),
                    0f, 0.4f, 0f, 0.7f, 0.28f, 0.08f, c, trail.sprite, gravity = if (trail.sprite == ParticleAtlas.COIN) 3f else -0.5f, additive = trail.additive,
                    rotation = particles.rng.range(0f, TAU), rotSpeed = 2f)
            }
        }
        // turbo flames
        if (pl.turboTime > 0f && pv.visible) {
            particles.emit(pv.wx, pv.wy + 0.5f, pv.wz, particles.rng.range(-0.3f, 0.3f), 1f, particles.rng.range(-0.3f, 0.3f), 0.35f, 0.45f, 0.1f, 0xFFFFB13D.toInt(), ParticleAtlas.FLAME, additive = true)
        }
        // dizzy stars
        for (v in vis) {
            if (v.racer.mode == Mode.STUN && v.visible && particles.rng.chance(dt * 10f)) {
                val a = time * 6f + v.racer.index
                particles.emit(v.wx + cos(a) * 0.35f, v.wy + 1.45f, v.wz + sin(a) * 0.35f, 0f, 0.2f, 0f, 0.35f, 0.18f, 0.12f, 0xFFFFE36B.toInt(), ParticleAtlas.STAR, rotation = a)
            }
        }
        // slide spray
        if (pl.mode == Mode.SLIDE && particles.rng.chance(dt * 25f)) particles.emit(pv.wx, pv.wy + 0.15f, pv.wz, particles.rng.range(-1.5f, 1.5f), particles.rng.range(1.5f, 3f), particles.rng.range(-1.5f, 1.5f), 0.5f, 0.2f, 0.1f, 0xDDBFF4FF.toInt(), ParticleAtlas.DROP, gravity = 12f)
        // finish confetti cannons once racing ends
        if (controller.phase == RacePhase.FINISHED && particles.rng.chance(dt * 6f)) {
            course.world(course.finishS + 9f, particles.rng.range(-2f, 2f), 3.5f, p)
            confetti(p[0], p[1], p[2], 6)
        }
        // storm rain
        if (storm) {
            for (k in 0 until 4) {
                particles.emit(cam.x + particles.rng.range(-12f, 12f), cam.y + particles.rng.range(2f, 8f), cam.z + particles.rng.range(-12f, 12f), -1.5f, -22f, 0f, 0.5f, 0.6f, 0.6f, 0x66CFE6FF, ParticleAtlas.STREAK, rotation = PI_F / 2f)
            }
        }
    }

    // ------------------------------------------------------------------------------------ animation
    /** Tilting decks and flip tiles: racers standing on them are drawn on their moving top. */
    private val movingFloors = controller.sim.hazards.filter { it is Tilt || it is Trapdoor }

    private fun animate(v: RacerVis, dt: Float, simT: Float) {
        val r = v.racer
        val sim = controller.sim
        val podium = controller.phase == RacePhase.FINISHED && controller.phaseT > 0.6f
        v.visible = r.mode != Mode.SPLASHED
        // world position and facing
        val mode = r.mode
        var h = r.y
        if (mode == Mode.MONKEY) h = 0.55f
        // feet follow a tilting deck while it wobbles before tipping
        if (mode == Mode.RUN || mode == Mode.STUN) for (fl in movingFloors) {
            if (r.s <= fl.s0 - fl.reach || r.s >= fl.s0 + fl.reach || abs(r.d) > fl.hw) continue
            if (fl is Tilt && abs(r.s - fl.s0) < fl.halfLen) { h += SetPieceGeometry.tiltSurface(fl, simT, r.d); break }
            if (fl is Trapdoor && abs(r.s - fl.s0) < fl.tileLen) { h += SetPieceGeometry.tileSurface(fl, simT, r.s - fl.s0, r.d); break }
        }
        course.world(r.s, r.d, h, p)
        var yaw = course.yawAt(r.s)
        if (mode == Mode.CLIMB) {
            // hug the wall face
            val y0 = course.yawAt(r.climbBaseS - 0.3f)
            yaw = y0
            p[0] -= sin(y0) * 0.38f; p[2] -= cos(y0) * 0.38f
        }
        v.wx = p[0]; v.wy = p[1]; v.wz = p[2]
        if (podium) placeOnPodium(v)
        v.yaw = dampAngle(v.yaw, yaw + if (podium) PI_F else 0f, 12f, dt)
        // blink
        v.blinkT -= dt
        if (v.blinkT <= 0f) { v.blink = 1f; v.blinkT = 2.2f + (r.index * 0.37f + time) % 1.8f }
        v.blink = max(0f, v.blink - dt * 7f)
        // expression from danger ahead
        var expr = Expression.HAPPY
        // choose target pose
        val st: AnimState
        when {
            podium -> {
                val rankOk = r.finished && r.rank <= controller.podiumCutoff()
                if (rankOk || (sim.kind == RaceKind.BONUS && r.isPlayer)) { st = AnimState.DANCE; expr = Expression.JOY } else { st = AnimState.SAD; expr = Expression.SAD }
            }
            mode == Mode.FINISHED -> { st = AnimState.CHEER; expr = Expression.JOY }
            mode == Mode.RUN && r.y > 0.05f -> { st = AnimState.AIR; expr = Expression.JOY }
            mode == Mode.RUN -> {
                if (r.speed > 0.4f) st = AnimState.RUN else st = AnimState.IDLE
                if (dangerNear(r)) expr = if (r.speed > 0.4f) Expression.SHOCK else Expression.FOCUS
                if (course.segmentAt(r.s).kind == SegKind.BEAM) Unit
                if (controller.phase == RacePhase.COUNTDOWN || controller.phase == RacePhase.INTRO) expr = Expression.FOCUS
            }
            mode == Mode.STUN -> { st = AnimState.STUN; expr = Expression.DIZZY }
            mode == Mode.AIR -> { st = AnimState.AIR; expr = Expression.JOY }
            mode == Mode.TUMBLE -> { st = AnimState.TUMBLE; expr = Expression.SHOCK }
            mode == Mode.FALL -> { st = AnimState.FALL; expr = Expression.SHOCK }
            mode == Mode.CLIMB -> { st = AnimState.CLIMB; expr = Expression.FOCUS }
            mode == Mode.MONKEY -> { st = AnimState.HANG; expr = Expression.FOCUS }
            mode == Mode.ZIP -> { st = AnimState.ZIP; expr = Expression.JOY }
            mode == Mode.SWING -> { st = AnimState.SWING; expr = Expression.JOY }
            mode == Mode.SLIDE -> { st = AnimState.SLIDE; expr = Expression.JOY }
            mode == Mode.RAFT -> st = if (r.speed > 0.4f) AnimState.RUN else AnimState.IDLE
            else -> st = AnimState.IDLE
        }
        if (st != v.state) { v.state = st; v.blend = 0f }
        v.expr = expr
        val tp = v.target
        val k = (r.speed / 5.2f).coerceIn(0f, 1.3f)
        v.dance[0] = 0f; v.dance[1] = 0f; v.dance[2] = 0f
        when (st) {
            AnimState.RUN -> Anim.run(tp, r.animPhase, k.coerceIn(0.35f, 1.25f), if (r.turboTime > 0f) 0.15f else 0f)
            AnimState.IDLE -> Anim.idle(tp, time, r.index.toFloat())
            AnimState.STUN -> Anim.stun(tp, time)
            AnimState.AIR -> Anim.air(tp, time, 1f)
            AnimState.TUMBLE -> Anim.tumble(tp, time)
            AnimState.FALL -> Anim.fall(tp, time)
            AnimState.CLIMB -> Anim.climb(tp, r.animPhase, if (r.speed > 0.1f) 1f else 0.15f)
            AnimState.HANG -> Anim.monkey(tp, r.animPhase, if (r.speed > 0.1f) 1f else 0.2f)
            AnimState.ZIP -> Anim.zip(tp, time)
            AnimState.SWING -> Anim.swing(tp, time, sin(time * 2f) * 0.4f)
            AnimState.SLIDE -> Anim.slide(tp, time)
            AnimState.DANCE -> Anim.dance(tp, v.danceId, time + r.index * 0.3f, v.dance)
            AnimState.SAD -> Anim.sad(tp, time)
            AnimState.CHEER -> Anim.dance(tp, "cheer", time, v.dance)
            else -> Anim.idle(tp, time, 0f)
        }
        v.blend = min(1f, v.blend + dt * 7f)
        v.pose.blendTo(tp, if (v.blend >= 1f) 1f else 0.3f + v.blend * 0.5f)
        if (mode == Mode.ZIP || mode == Mode.MONKEY || mode == Mode.SWING) gripFit(v)
        // whole-body spin while tumbling/falling
        if (mode == Mode.TUMBLE || mode == Mode.FALL) {
            v.spinPitch += dt * (if (r.vs < 0f) -9f else 7f)
            v.spinRoll += dt * r.vd * 1.6f
        } else {
            v.spinPitch = damp(v.spinPitch % TAU, 0f, 10f, dt)
            v.spinRoll = damp(v.spinRoll % TAU, 0f, 10f, dt)
        }
    }

    private val t3 = FloatArray(3)

    /**
     * Hanging racers: move the body up or down so its hands sit exactly on the zip handle bar, the
     * monkey bar or the end of the swing rope, whatever the body's proportions or pose.
     */
    private fun gripFit(v: RacerVis) {
        val r = v.racer
        val grip = when (r.mode) {
            Mode.ZIP -> { course.world(r.s, r.d, SetPieceGeometry.ZIP_BAR_H, p); p[1] }
            Mode.MONKEY -> { course.world(r.s, r.d, SetPieceGeometry.MONKEY_BAR_H, p); p[1] }
            Mode.SWING -> {
                val seg = course.segmentAt(r.s.coerceAtMost(course.length))
                if (seg.kind != SegKind.SWING) return
                val mid = (seg.s0 + seg.s1) / 2f
                val half = seg.length / 2f
                val rope = sqrt(half * half + (seg.p0 - 1f) * (seg.p0 - 1f))
                val ang = asin(((r.s - mid) / rope).coerceIn(-1f, 1f))
                course.world(mid, r.d, seg.p0, p)
                p[1] - rope * cos(ang)
            }
            else -> return
        }
        setModel(v)
        drawer.computeSkin(v.pose, v.look.body)
        drawer.boneWorld(Bones.HAND_L, t3); var hand = t3[1]
        drawer.boneWorld(Bones.HAND_R, t3); hand = max(hand, t3[1])
        v.wy += grip - (hand + 0.06f)
    }

    private fun dangerNear(r: Racer): Boolean {
        val sim = controller.sim
        for (hz in sim.hazards) {
            if (r.s < hz.zoneStart - 2.5f || r.s > hz.zoneEnd || !hz.lethal) continue
            if (hz.telegraph(sim.time) > 0.6f) return true
        }
        return false
    }

    private fun placeOnPodium(v: RacerVis) {
        val r = v.racer
        val sim = controller.sim
        val order = sim.standings()
        val idx = order.indexOf(r)
        val ps = course.finishS + 9f
        val (d, hgt) = when (idx) { 0 -> 0f to 1.2f; 1 -> -1.65f to 0.85f; 2 -> 1.65f to 0.6f; else -> (-3f + (idx - 3) * 1.2f) to 0f }
        // podium model faces back toward the arch; course right (+d) maps to its left side
        course.world(ps + (if (idx >= 3) -2.3f else 0f), -d, hgt, p)
        v.wx = p[0]; v.wy = p[1]; v.wz = p[2]
    }

    // ------------------------------------------------------------------------------------ camera
    private fun updateCamera(dt: Float) {
        val sim = controller.sim
        val pl = sim.player
        val pv = vis[pl.index]
        cam.aspect = res.width.toFloat() / max(1, res.height)
        val phase = controller.phase
        val fy = course.yawAt(pl.s + 3f)
        val fwdX = sin(fy); val fwdZ = cos(fy)
        var tx: Float; var ty: Float; var tz: Float
        var cx: Float; var cy: Float; var cz: Float
        var fov = 55f
        val portrait = cam.aspect < 0.8f
        // high, pulled-back chase cam: the runner sits a little below centre with the course ahead in view
        val back = if (portrait) 7.4f else 6.2f
        val up = if (portrait) 6.6f else 4.8f
        if (phase == RacePhase.INTRO) {
            val t = smoothstep(0f, 1f, controller.phaseT / controller.introDuration)
            course.world(course.startS + 18f - 16f * t, 0f, 0f, p)
            tx = p[0]; ty = p[1] + 1f; tz = p[2]
            val ang = course.yawAt(course.startS) + PI_F * (1f - t) * 0.9f + 0.2f
            val dist = lerp(11f, back, t)
            cx = pv.wx - sin(ang) * dist; cz = pv.wz - cos(ang) * dist; cy = pv.wy + lerp(9.5f, up, t)
            tx = lerp(pv.wx + fwdX * 4f, pv.wx + fwdX * 3f, t); tz = lerp(pv.wz + fwdZ * 4f, pv.wz + fwdZ * 3f, t); ty = pv.wy + 1f
            camInit = false
        } else if (phase == RacePhase.FINISHED && controller.phaseT > 0.6f) {
            course.world(course.finishS + 9f, 0f, 1.3f, p)
            tx = p[0]; ty = p[1]; tz = p[2]
            val base = course.yawAt(course.finishS)
            val a = base + 0.35f * sin(time * 0.35f)
            cx = tx - sin(a) * 8.5f; cz = tz - cos(a) * 8.5f; cy = ty + 2.4f
            fov = 50f
        } else {
            var b = back; var u = up
            var lookAhead = 2.4f
            when (pl.mode) {
                // look up the wall so the coconut baskets above it stay in view while climbing
                Mode.CLIMB -> { b = 8.0f; u = 0.5f; lookAhead = 0.5f }
                Mode.ZIP, Mode.SWING -> { b = 8.5f; u = 4.6f; lookAhead = 4f; fov = 58f }
                Mode.AIR -> { b = 8.2f; u = 6.2f }
                Mode.SLIDE -> { b = 7.4f; u = 4.2f; fov = 60f }
                Mode.FALL, Mode.SPLASHED -> { b = 8.4f; u = 6.6f }
                else -> Unit
            }
            if (pl.turboTime > 0f) fov += 7f
            tx = pv.wx + fwdX * lookAhead; tz = pv.wz + fwdZ * lookAhead; ty = pv.wy + if (pl.mode == Mode.CLIMB) 2.6f else 0.6f
            if (pl.mode == Mode.FALL || pl.mode == Mode.SPLASHED) { course.world(pl.checkpoint, 0f, 0f, p); ty = max(p[1], ty) }
            cx = tx - fwdX * (b + lookAhead); cz = tz - fwdZ * (b + lookAhead); cy = ty + u
        }
        if (!camInit) { camPos[0] = cx; camPos[1] = cy; camPos[2] = cz; camTgt[0] = tx; camTgt[1] = ty; camTgt[2] = tz; camInit = phase != RacePhase.INTRO }
        val lam = if (phase == RacePhase.INTRO) 30f else 5.5f
        camPos[0] = damp(camPos[0], cx, lam, dt); camPos[1] = damp(camPos[1], cy, lam * 0.8f, dt); camPos[2] = damp(camPos[2], cz, lam, dt)
        camTgt[0] = damp(camTgt[0], tx, lam * 1.4f, dt); camTgt[1] = damp(camTgt[1], ty, lam, dt); camTgt[2] = damp(camTgt[2], tz, lam * 1.4f, dt)
        // never dip the camera under the water
        if (camPos[1] < waterY + 1.2f) camPos[1] = waterY + 1.2f
        val sh = shake * shake
        cam.x = camPos[0] + sin(time * 47f) * sh * 0.18f
        cam.y = camPos[1] + cos(time * 53f) * sh * 0.14f
        cam.z = camPos[2]
        cam.tx = camTgt[0]; cam.ty = camTgt[1]; cam.tz = camTgt[2]
        cam.fovDeg = damp(cam.fovDeg, fov, 4f, dt)
        cam.near = 0.3f; cam.far = 420f
        cam.update()
        cam.updateFrustum()
    }

    // ------------------------------------------------------------------------------------ render
    override fun render() {
        val sim = controller.sim
        val pl = sim.player
        val st = sim.time
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)
        GLES30.glDisable(GLES30.GL_BLEND)

        // ---------------- queue dynamic instances
        res.meshes.clearBatches()
        val camS = pl.s
        for (h in sim.hazards) hazVis.draw(h, st, camS, hazHit[h.id] ?: 0f)
        // coins
        val coinB = res.meshes.batch("coin")
        for (c in sim.coins) {
            if (c.taken || abs(c.s - camS) > 60f) continue
            course.world(c.s, c.d, c.h + sin(time * 3f + c.s) * 0.08f, p)
            Inst.add(coinB, p[0], p[1], p[2], time * 3f + c.s, 0f, 0f, 1f, tintA = 0xFFFFFF, emissive = 0.15f)
        }
        // checkpoint flags
        val flagB = res.meshes.batch("flag_cloth")
        setup.checkpoints.forEachIndexed { i, cs ->
            if (abs(cs - camS) > 70f) return@forEachIndexed
            // pole stands just off the deck edge so it never sits in a runnable lane
            course.world(cs, -course.segmentAt(cs).halfWidth - 0.12f, 0f, p)
            val reached = pl.checkpointIdx >= i
            Inst.add(flagB, p[0], p[1], p[2], course.yawAt(cs) + sin(time * 2f + i) * 0.15f, tintA = if (reached) controller.playerLook.primary else 0xF4F6FA, sway = 0.6f)
        }
        // set-piece moving parts: zip handles, swing ropes
        for (v in vis) {
            val r = v.racer
            if (!v.visible) continue
            if (r.mode == Mode.ZIP) {
                course.world(r.s, r.d, SetPieceGeometry.ZIP_CABLE_H, p)
                Inst.add(res.meshes.batch("zip_handle"), p[0], p[1], p[2], course.yawAt(r.s))
            } else if (r.mode == Mode.SWING) {
                val seg = course.segmentAt(r.s.coerceAtMost(course.length))
                if (seg.kind == SegKind.SWING) {
                    val mid = (seg.s0 + seg.s1) / 2f
                    val half = seg.length / 2f
                    val rope = sqrt(half * half + (seg.p0 - 1f) * (seg.p0 - 1f))
                    val ang = asin(((r.s - mid) / rope).coerceIn(-1f, 1f))
                    course.world(mid, r.d, seg.p0, p)
                    // rope swings along the course: its lower end follows the racer (pitch -ang puts
                    // the end rope*sin(ang) ahead of the beam, exactly where the racer is)
                    Inst.add(res.meshes.batch("rope"), p[0], p[1], p[2], course.yawAt(mid), -ang, 0f, 1.6f, rope, 1.6f)
                }
            }
        }
        // chasers
        sim.chaser?.let { c ->
            val cs = c.s(st)
            if (c.duck) {
                val bounce = abs(sin(time * 3.2f))
                course.world(cs, 0f, 1.8f + bounce * 2.2f, p)
                Inst.add(res.meshes.batch("duck"), p[0], p[1], p[2], course.yawAt(cs), 0f, sin(time * 3.2f) * 0.1f, 3.4f, 3.4f * (1f - (1f - bounce) * 0.15f), 3.4f, tintA = 0xFFD23F, tintB = 0xFF8A3D, wobble = 0.5f, phase = time)
            } else {
                course.world(cs, 0f, -course.heightAt(cs) - 0.5f, p)
                val hgt = course.heightAt(cs) + 3.2f
                Inst.add(res.meshes.batch("wave"), p[0], p[1], p[2], course.yawAt(cs) - PI_F / 2f, 0f, 0f, 3.2f, hgt, (setup.spec.width + 8f) / 3.2f)
            }
        }
        // character parts
        for (v in vis) {
            if (!v.visible) continue
            setModel(v)
            drawer.computeSkin(v.pose, v.look.body)
            drawer.queueParts(v.look, v.expr, v.blink, 1f, outline = true)
        }

        // ---------------- shadow pass
        var shadowDone = false
        if (res.beginShadowPass()) {
            val pv = vis[pl.index]
            res.shadow.update(rig.sunDir[0], rig.sunDir[1], rig.sunDir[2], pv.wx + sin(course.yawAt(pl.s)) * 9f, pv.wy, pv.wz + cos(course.yawAt(pl.s)) * 9f, 18f)
            res.depth.mat4("uViewProj", res.shadow.lightViewProj)
            res.depth.mat4("uLightVP", res.shadow.lightViewProj)
            // static track pieces only cast shadows on strong GPUs: with the soft flat light their shadows
            // barely show, while racers and hurdles (the shadows that matter) always cast
            if (res.tier == QualityTier.HIGH) for (c in chunks) {
                val dx = c.cx - pv.wx; val dz = c.cz - pv.wz
                if (dx * dx + dz * dz < (c.radius + 25f) * (c.radius + 25f)) c.mesh.draw()
            }
            for (bt in res.meshes.allBatches()) bt.draw()
            CharacterPass.beginSkinDepth(res)
            for (v in vis) { if (!v.visible) continue; setModel(v); drawer.computeSkin(v.pose, v.look.body); drawer.drawBodyDepth(res.skinDepth, v.look) }
            res.endShadowPass()
            shadowDone = true
        }
        GLES30.glViewport(0, 0, res.width, res.height)
        val sky = rig.skyHorizon
        GLES30.glClearColor(sky[0], sky[1], sky[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        // ---------------- opaque: static chunks
        res.common(res.lit, cam, rig, waterY)
        res.lit.f("uSwayAll", 1f)
        for (c in chunks) if (cam.sphereVisible(c.cx, c.cy, c.cz, c.radius)) c.mesh.draw()
        for (c in scenery) if (cam.sphereVisible(c.cx, c.cy, c.cz, c.radius)) c.mesh.draw()
        res.lit.f("uSwayAll", 0f)
        // dynamic instances: hazards fade out when the camera swings close to them
        res.common(res.litFade, cam, rig, waterY)
        res.litFade.f("uSwayAll", 0f)
        res.litFade.f("uFadeNear", 4.2f)
        for (bt in res.meshes.allBatches()) bt.draw()
        // characters: clean shading, no ink outlines (crisper, matches the toy-like look)
        CharacterPass.beginSkin(res, cam, rig, waterY)
        for (v in vis) {
            if (!v.visible) continue
            setModel(v); drawer.computeSkin(v.pose, v.look.body)
            drawer.drawBody(res.skin, v.look, flash = v.racer.flash * 0.5f, fadeNear = if (v.racer.isPlayer) 0f else 3.4f)
        }

        // ---------------- water + sky
        val pv = vis[pl.index]
        res.drawWater(cam, rig, waterY, pv.wx + sin(course.yawAt(pl.s)) * 20f, pv.wz + cos(course.yawAt(pl.s)) * 20f, shoreTex, shoreRect)
        res.drawSky(cam, rig, waterY)

        // ---------------- transparent: decals, particles, labels
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        decals?.let { d ->
            res.common(res.decal, cam, rig, waterY)
            for (h in sim.hazards) if (h.id < 48) zoneVals[h.id] = h.telegraph(st)
            res.decal.floatArray("uZones", zoneVals, 48)
            GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
            GLES30.glPolygonOffset(-1f, -2f)
            d.draw()
            GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        }
        // clouds
        res.sky3d.clear()
        val night = rig.night
        for (c in clouds) {
            val drift = (time * 0.8f) % 400f
            val col = if (night > 0.5f) 0x9AA6C8 else 0xFFFFFF
            res.sky3d.add(c[0] + drift * c[4], c[1], c[2], c[3], (0xFF shl 24) or col, 0.9f, res.particleAtlas.rects[ParticleAtlas.CLOUD_BIG], 0f, 1.8f)
        }
        res.bill.clear(); res.ground.clear(); res.labelBill.clear()
        particles.fill(res.particleAtlas, res.bill, res.ground)
        // contact shadows on low tier and for airborne racers
        for (v in vis) {
            if (!v.visible) continue
            val r = v.racer
            if (!r.mode.let { it == Mode.RUN || it == Mode.STUN || it == Mode.AIR || it == Mode.FINISHED || it == Mode.RAFT || it == Mode.TUMBLE }) continue
            course.world(r.s, r.d, 0.05f, p)
            val air = (v.wy - p[1]).coerceIn(0f, 4f)
            if (!shadowDone || air > 0.3f) res.ground.add(p[0], p[1] + 0.02f, p[2], 1.1f - air * 0.1f, 0x55000000, 1f - air * 0.15f, res.particleAtlas.rects[ParticleAtlas.BLOB])
        }
        // name tags + player marker
        for (v in vis) {
            if (!v.visible || controller.phase == RacePhase.FINISHED) continue
            val r = v.racer
            val ux = names.rects[r.index]
            if (r.isPlayer) {
                res.bill.add(v.wx, v.wy + 1.85f + sin(time * 5f) * 0.06f, v.wz, 0.32f, 0xFFFFD23F.toInt(), 1f, res.particleAtlas.rects[ParticleAtlas.ARROW])
            } else {
                val dist = abs(r.s - pl.s)
                if (dist < 18f && r.s > pl.s - 0.8f) res.labelBill.add(v.wx, v.wy + 1.78f, v.wz, 0.36f, 0xFFFFFFFF.toInt(), (1f - dist / 18f).coerceIn(0f, 1f), ux)
            }
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        res.beginBillboards(cam, false)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, res.particleAtlas.texture)
        res.sky3d.draw()
        res.beginBillboards(cam, true)
        res.ground.draw()
        res.beginBillboards(cam, false)
        res.bill.draw()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, names.texture)
        res.labelBill.draw()
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun setModel(v: RacerVis) {
        val r = v.racer
        var sx = 1f; var sy = 1f
        if (r.squash > 0f) { val q = r.squash; sy = 1f - q * 0.35f; sx = 1f + q * 0.22f }
        val hop = v.dance[0]
        val scale = if (r.isPlayer) controller.rules.playerScale else 1f
        drawer.setModel(v.wx, v.wy + hop, v.wz, v.yaw + v.dance[1] + v.spinYaw, v.spinPitch + v.dance[2], v.spinRoll, sx, sy, sx, scale)
    }

    override fun release() {
        for (c in chunks) c.mesh.release()
        for (c in scenery) c.mesh.release()
        decals?.release()
        TextureUtil.delete(shoreTex)
        TextureUtil.delete(names.texture)
        controller.release()
    }

    @Suppress("unused")
    private fun unusedRefs() { SetPieceMath.toString(); Danger.SAFE; Bones.HEAD; World.LAGOON }
}
