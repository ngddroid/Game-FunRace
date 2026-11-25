package com.ngstudio.funrace.game.sim

import com.ngstudio.funrace.core.PI_F
import com.ngstudio.funrace.core.Quat
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Every hurdle must be solid where it is drawn: a runner standing anywhere its visible parts are
 * must be blocked or bonked, never walk through. The visible parts are rebuilt here exactly as
 * HazardVisuals places the models (same positions, yaw/pitch/roll and scales) from simple shapes
 * fitted to the Blender models in tools/blender/m_hazards.py.
 *
 * Also reports "ghost hits" (bonked with nothing visible within 25 cm) so the two stay in step.
 */
class HazardSolidityTest {
    private val tol = 0.12f        // ignore grazing contacts shallower than this
    private val ghostGap = 0.25f

    /** A solid in hazard-local course coordinates: segment (or point) with radius, or an axis-aligned box. */
    private sealed class S {
        class Seg(val pts: List<FloatArray>, val r: Float) : S()   // sampled axis points (a, d, y)
        class Box(val a0: Float, val a1: Float, val d0: Float, val d1: Float, val y0: Float, val y1: Float) : S()
    }

    /** Penetration depth of a standing racer (capsule radius RR, height RH) at (a, d). */
    private fun depth(s: S, a: Float, d: Float): Float {
        val lo = RR; val hi = RH - RR
        return when (s) {
            is S.Seg -> {
                var best = -9f
                for (p in s.pts) {
                    val cy = p[2].coerceIn(lo, hi)
                    val da = p[0] - a; val dd = p[1] - d; val dy = p[2] - cy
                    best = max(best, s.r + RR - sqrt(da * da + dd * dd + dy * dy))
                }
                best
            }
            is S.Box -> {
                val dy = max(0f, max(s.y0 - hi, lo - s.y1))
                val ia = a in s.a0..s.a1; val id = d in s.d0..s.d1
                if (ia && id && dy == 0f) RR + min(min(a - s.a0, s.a1 - a), min(d - s.d0, s.d1 - d))
                else {
                    val da = max(0f, max(s.a0 - a, a - s.a1)); val dd = max(0f, max(s.d0 - d, d - s.d1))
                    RR - sqrt(da * da + dd * dd + dy * dy)
                }
            }
        }
    }

    // ------------------------------------------------------------------ model -> course-local placement
    private val q = FloatArray(4)

    /** Same mapping as HazardVisuals.put on a straight course: model +Z = forward (a), +X = left (-d). */
    private inner class Put(val s: Float, val d: Float, val h: Float, yaw: Float = 0f, pitch: Float = 0f, roll: Float = 0f,
                            val sx: Float = 1f, val sy: Float = sx, val sz: Float = sx) {
        private val qq = FloatArray(4).also { Quat.euler(it, yaw, pitch, roll) }
        fun p(mx: Float, my: Float, mz: Float): FloatArray {
            val v = rot(qq, mx * sx, my * sy, mz * sz)
            return floatArrayOf(s + v[2], d - v[0], h + v[1])
        }
        fun seg(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, r: Float, n: Int = 24): S.Seg =
            S.Seg((0..n).map { k -> val t = k / n.toFloat(); p(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, z0 + (z1 - z0) * t) }, r)
        fun ball(x: Float, y: Float, z: Float, r: Float) = S.Seg(listOf(p(x, y, z)), r)
        /** Axis-aligned box (valid for yaw 0 / PI without pitch or roll). */
        fun box(x0: Float, x1: Float, y0: Float, y1: Float, z0: Float, z1: Float): S.Box {
            val c = listOf(p(x0, y0, z0), p(x1, y1, z1))
            return S.Box(min(c[0][0], c[1][0]), max(c[0][0], c[1][0]), min(c[0][1], c[1][1]), max(c[0][1], c[1][1]), min(c[0][2], c[1][2]), max(c[0][2], c[1][2]))
        }
    }

    private fun rot(q: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        val qx = q[0]; val qy = q[1]; val qz = q[2]; val qw = q[3]
        // v' = v + 2w(q x v) + 2 q x (q x v)
        val cx = qy * z - qz * y; val cy = qz * x - qx * z; val cz = qx * y - qy * x
        val c2x = qy * cz - qz * cy; val c2y = qz * cx - qx * cz; val c2z = qx * cy - qy * cx
        return floatArrayOf(x + 2f * (qw * cx + c2x), y + 2f * (qw * cy + c2y), z + 2f * (qw * cz + c2z))
    }

    // ------------------------------------------------------------------ visible solids per hurdle (mirrors HazardVisuals)
    private val st = FloatArray(12)

    private fun solids(h: Hazard, t: Float): List<S> {
        h.state(t, st)
        val hw = h.hw
        val out = ArrayList<S>()
        when (h) {
            is Pusher -> {
                val side = st[1]; val e = st[0]
                val yaw = if (side > 0f) 0f else PI_F
                val face = side * (hw - e)
                out += Put(0f, face + side * 0.6f, 0f, yaw).box(0f, 0.6f, 0.02f, 1.22f, -0.5f, 0.5f)
            }
            is Gloves -> {
                val side = st[1]; val e = st[0]
                val yaw = if (side > 0f) 0f else PI_F
                val g = HazardGeometry.glove(hw, side, e)
                // spring coil: flat-ended, so keep the capsule's round ends inside the coil length
                if (g.springLen > 0.55f) { val inset = 0.25f / g.springLen; out += Put(0f, side * hw, 0.75f, yaw, sx = g.springLen, sy = 1.6f, sz = 1.6f).seg(inset, 0f, 0f, 1f - inset, 0f, 0f, 0.25f) }
                val gp = Put(0f, g.gloveD, 0.75f, yaw, sx = 1.2f)
                out += gp.ball(0.4f, 0f, 0f, 0.37f)
                out += gp.ball(0.05f, 0f, 0f, 0.27f)
            }
            is Pendulum -> {
                val th = st[0]
                val bx = h.rope * sin(th); val by = h.pivotH - h.rope * cos(th)
                val p = Put(0f, bx, by - 0.05f, roll = -th * 0.6f)
                out += p.ball(0f, -0.1f, 0f, 0.6f)
                out += p.ball(0f, 0.55f, 0.38f, 0.38f)
            }
            is Hammer -> out += Put(0f, 0f, h.pivotH, roll = -st[0]).seg(-0.95f, -h.arm, 0f, 0.95f, -h.arm, 0f, 0.74f)
            is Turntable -> {
                val hub = Put(0f, st[3], 0f, sx = 0.75f, sy = 0.95f, sz = 0.75f)
                out += hub.seg(0f, 0f, 0f, 0f, 0.25f, 0f, 0.41f, 4)
                out += hub.seg(0f, 0.2f, 0f, 0f, 1.25f, 0f, 0.3f, 8)
                arms(h, out, 0.1f)
            }
            is Sweeper -> {
                val hub = Put(0f, st[3], 0f, sx = 0.75f, sy = 0.95f, sz = 0.75f)
                out += hub.seg(0f, 0f, 0f, 0f, 0.25f, 0f, 0.41f, 4)
                out += hub.seg(0f, 0.2f, 0f, 0f, 1.25f, 0f, 0.3f, 8)
                arms(h, out, 0f)
            }
            is Press -> out += Put(0f, 0f, st[0], sx = hw, sy = 1f, sz = 1f).box(-1f, 1f, 0f, 1.1f, -0.8f, 0.8f)
            is Bollards -> {
                val cols = h.colD
                var k = 0
                for (r in 0..1) for (c in 0..2) {
                    val hh = st[k++]
                    if (hh > 0.01f) out += Put(h.rowA[r], cols[c], (hh - 1f)).seg(0f, 0f, 0f, 0f, 1f, 0f, 0.32f, 8)
                }
            }
            is Gates -> for (side in floatArrayOf(-1f, 1f)) {
                val len = max(0.05f, hw * st[0] + 0.15f)
                val yaw = if (side > 0f) 0f else PI_F
                out += Put(0f, side * (hw + 0.1f), 0f, yaw, sx = len, sy = 1f, sz = 1f).box(0f, 1f, 0f, 2.2f, -0.15f, 0.15f)
            }
            is Cannon -> if (st[0] < 90f) out += Put(0f, st[0], 0.7f).ball(0f, 0f, 0f, 0.7f)
            is Wave -> if (st[0] < 90f) {
                val yaw = if (st[2] > 0f) 0f else PI_F
                out += Put(0f, st[0], 0f, yaw, sz = st[3] * 2f / 3.2f, sy = 1.1f, sx = 1f).box(-0.4f, 0.4f, 0f, 0.95f, -1.6f, 1.6f)
            }
            is Barrels -> for (k in 0..2) {
                val a = st[k * 3]
                if (a > 90f) continue
                // flat-ended barrel: inset the axis so the capsule's round ends stop at the barrel ends
                val sxb = h.hw / 1.1f; val inset = 0.48f / sxb
                out += Put(a, st[k * 3 + 1], h.radius, pitch = -st[k * 3 + 2], sx = sxb, sy = 1f, sz = 1f).seg(-0.55f + inset, 0f, 0f, 0.55f - inset, 0f, 0f, 0.48f, 10)
            }
            is Spinner -> {
                val ang = st[0]; val len = st[1]; val hubD = st[2]; val hgt = st[3]
                val yaw = -ang - PI_F / 2f
                out += Put(0f, hubD, hgt, yaw, sx = len, sy = 1f, sz = 1f).seg(0f, 0f, 0f, 1f - 0.2f / len, 0f, 0f, 0.2f, 40)
                out += Put(cos(ang) * len, hubD + sin(ang) * len, hgt).ball(0f, 0f, 0f, 0.28f)
                out += Put(0f, hubD, hgt, yaw).ball(-1.15f, 0.7f, 0f, 0.34f)   // counterweight
            }
            is Rollers -> for (r in 0..1) {
                val sa = h.rowA[r]
                for (side in floatArrayOf(-1f, 1f)) {
                    val yaw = if (side > 0f) 0f else PI_F
                    val rd = HazardGeometry.rollerOrigin(hw, st[r], side)
                    // roller body + rounded end caps (cap reaches 0.119 of the model length past each end)
                    val inset = 0.34f / hw   // the capsule's round end then reaches exactly the cap tip (1.119)
                    out += Put(sa, rd, h.rollH, yaw, pitch = st[2] * side, sx = hw, sy = 1f, sz = 1f).seg(-0.119f + inset, 0f, 0f, 1.119f - inset, 0f, 0f, 0.34f, 30)
                }
            }
            is PaddleWheel -> {
                val p = Put(0f, 0f, h.axleH, pitch = HazardGeometry.paddleVisualPitch(st[0]), sx = hw * 2f + 0.2f, sy = 1f, sz = 1f)
                for (i in 0 until 3) {
                    val a = i * 2f * PI_F / 3f
                    val cy = cos(a); val cz = sin(a)
                    // paddle pillow: 1.1 long along its own Y axis, rotated rot=(-a, 0, 0) in Blender's GL frame
                    val ay = HazardGeometry.paddleAxisY(a); val az = HazardGeometry.paddleAxisZ(a)
                    for (j in 0..18) {
                        val x = -0.45f + 0.9f * j / 18f
                        out += p.seg(x, cy - ay * 0.43f, cz - az * 0.43f, x, cy + ay * 0.43f, cz + az * 0.43f, 0.12f, 10)
                    }
                }
            }
            else -> Unit
        }
        return out
    }

    private fun arms(h: Sweeper, out: ArrayList<S>, hOff: Float) {
        val arms = st[1].toInt(); val len = st[2]; val hubD = st[3]
        for (k in 0 until arms) {
            val al = st[0] + 2f * PI_F * k / arms
            out += Put(0f, hubD, 0.55f + hOff, -al - PI_F / 2f, sx = len, sy = 1f, sz = 1f).seg(0f, 0f, 0f, 1f, 0f, 0f, 0.17f, 30)
            out += Put(cos(al) * len, hubD + sin(al) * len, 0.55f + hOff).ball(0f, 0f, 0f, 0.24f)
        }
    }

    // ------------------------------------------------------------------ the sweep
    private class Result(val name: String, var samples: Int = 0, var through: Int = 0, var ghost: Int = 0, var example: String = "", var ghostExample: String = "")

    private fun sweep(name: String, h: Hazard): Result {
        val res = Result(name)
        val lim = h.hw - 0.36f
        val steps = 160
        val span = 2f * h.period
        for (i in 0 until steps) {
            val t = span * i / steps
            val sol = solids(h, t)
            var a = -h.reach - 0.6f
            while (a <= h.reach + 0.6f) {
                var d = -lim
                while (d <= lim + 1e-4f) {
                    var dep = -9f
                    for (s in sol) dep = max(dep, depth(s, a, d))
                    val k = h.danger(t, a, d, 0f, null)
                    res.samples++
                    if (dep > tol && k == Danger.SAFE) {
                        res.through++
                        if (res.example.isEmpty()) res.example = "t=%.2f a=%.2f d=%.2f depth=%.2f".format(t, a, d, dep)
                    }
                    if ((k == Danger.KNOCK) && dep < -ghostGap) {
                        res.ghost++
                        if (res.ghostExample.isEmpty()) res.ghostExample = "t=%.2f a=%.2f d=%.2f gap=%.2f".format(t, a, d, -dep)
                    }
                    d += 0.08f
                }
                a += 0.08f
            }
        }
        return res
    }

    private fun hazards(hw: Float): List<Pair<String, Hazard>> = listOf(
        "Pusher R" to Pusher(0f, hw, 3f, 0f, 1f, 0.75f),
        "Pusher L" to Pusher(0f, hw, 3f, 0.7f, -1f, 0.9f),
        "Gloves R" to Gloves(0f, hw, 3f, 0f, 1f, 0.75f),
        "Gloves L" to Gloves(0f, hw, 2.8f, 0.4f, -1f, 0.9f),
        "Pendulum" to Pendulum(0f, hw, 3.2f, 0f, 0.9f),
        "Mega Mallet" to Hammer(0f, hw, 3.6f, 0f, 0.95f),
        "Noodle Sweeper" to Sweeper(0f, hw, 1.5f, 0f, 1, hw + 0.35f),
        "Noodle Sweeper x2" to Sweeper(0f, hw, -1.8f, 0.3f, 2, hw + 0.35f),
        "Turntable" to Turntable(0f, hw, 1.3f, 0f),
        "Squish Press" to Press(0f, hw, 3f, 0f),
        "Bollards" to Bollards(0f, hw, 3f, 0f),
        "Gates" to Gates(0f, hw, 3.4f, 0f),
        "Cannon" to Cannon(0f, hw, 2.8f, 0f, 1f, 6.5f),
        "Wave" to Wave(0f, hw, 3.4f, 0f, -1f),
        "Barrels" to Barrels(0f, hw, 3.2f, 0f, 4.4f, 3.5f),
        "Spin Bar R" to Spinner(0f, hw, 2.0f, 0f, 1f),
        "Spin Bar L" to Spinner(0f, hw, -2.3f, 0.5f, -1f),
        "Roller Squeeze" to Rollers(0f, hw, 3f, 0f),
        "Paddle Wheel" to PaddleWheel(0f, hw, 1.4f, 0f),
    )

    /** A runner sprinting straight at the sweeper's centre stand bumps off it, slides around and carries on. */
    @Test
    fun runnerSlidesAroundSweeperStand() {
        val spec = com.ngstudio.funrace.level.LevelSpec(
            level = 900, seed = 9L, name = "stand", world = com.ngstudio.funrace.level.World.LAGOON, time = com.ngstudio.funrace.level.TimeOfDay.NOON,
            kind = RaceKind.TUTORIAL, width = 4.6f, startY = 3f,
            pieces = listOf(com.ngstudio.funrace.level.Piece.Deck(10f), com.ngstudio.funrace.level.Piece.Deck(12f, listOf(
                // arm parked over the left half (pointing to -d) so only the stand is in the way
                com.ngstudio.funrace.level.HazSpec(HazType.SWEEPER, 6f, phase = -PI_F / 2f / 0.001f, p = 0.001f, q = 1f)))),
            rivals = 1, rivalSkill = 0.5f, difficulty = 0.1f,
        )
        val setup = com.ngstudio.funrace.level.LevelBuilder.build(spec)
        val sim = com.ngstudio.funrace.level.LevelBuilder.createSim(setup)
        val p = sim.player
        p.laneD = 0f; p.d = 0f; p.hold = true
        sim.started = true
        val hub = setup.hazards.first() as Sweeper
        var minDist = 99f
        var t = 0f
        while (p.s < hub.s0 + 3f && t < 12f) {
            sim.step(1f / 60f); t += 1f / 60f
            if (p.mode == Mode.RUN) minDist = min(minDist, sqrt((p.s - hub.s0) * (p.s - hub.s0) + p.d * p.d))
        }
        println("STAND min distance to stand centre = %.2f (stand %.2f + racer %.2f), passed in %.1fs, bonks=%d".format(minDist, hub.hubR, RR, t, p.bonks))
        assertTrue("ran through the stand (min distance %.2f)".format(minDist), minDist >= hub.hubR + RR - 0.08f)
        assertTrue("got stuck at the stand", p.s >= hub.s0 + 3f)
        assertTrue("was bonked by the parked arm", p.bonks == 0)
    }

    @Test
    fun hurdlesAreSolidWhereTheyAreDrawn() {
        val problems = ArrayList<String>()
        for (hw in floatArrayOf(2.3f, 1.7f)) {
            for ((name, h) in hazards(hw)) {
                val r = sweep(name, h)
                val pct = 100f * r.through / r.samples
                println("SOLID hw=%.1f %-18s walk-through=%5d (%.2f%%) %s | ghost-hits=%5d %s".format(hw, name, r.through, pct, r.example, r.ghost, r.ghostExample))
                if (r.through > 0) problems += "hw=$hw $name: ${r.through} walk-through samples, e.g. ${r.example}"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
