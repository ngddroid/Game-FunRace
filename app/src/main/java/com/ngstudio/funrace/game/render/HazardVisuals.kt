package com.ngstudio.funrace.game.render

import com.ngstudio.funrace.core.Mat4
import com.ngstudio.funrace.core.PI_F
import com.ngstudio.funrace.core.Quat
import com.ngstudio.funrace.game.sim.Barrels
import com.ngstudio.funrace.game.sim.Bollards
import com.ngstudio.funrace.game.sim.Cannon
import com.ngstudio.funrace.game.sim.Coconuts
import com.ngstudio.funrace.game.sim.Conveyor
import com.ngstudio.funrace.game.sim.Course
import com.ngstudio.funrace.game.sim.Fan
import com.ngstudio.funrace.game.sim.Gates
import com.ngstudio.funrace.game.sim.Gloves
import com.ngstudio.funrace.game.sim.Hazard
import com.ngstudio.funrace.game.sim.PaddleWheel
import com.ngstudio.funrace.game.sim.Pendulum
import com.ngstudio.funrace.game.sim.Press
import com.ngstudio.funrace.game.sim.Pusher
import com.ngstudio.funrace.game.sim.Raft
import com.ngstudio.funrace.game.sim.Sweeper
import com.ngstudio.funrace.game.sim.Tilt
import com.ngstudio.funrace.game.sim.Trapdoor
import com.ngstudio.funrace.game.sim.Turntable
import com.ngstudio.funrace.game.sim.Wave
import com.ngstudio.funrace.level.World
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Pushes instances for every visible hazard each frame. Course-space placement:
 * local +Z = course forward, local +X = course left (racer's left), so lateral d maps to -X.
 */
class HazardVisuals(private val res: RenderResources, private val course: Course, private val world: World) {
    private val st = FloatArray(12)
    private val p = FloatArray(3)
    private val q = FloatArray(4)
    private val m = FloatArray(16)
    private val A = world.hazA
    private val B = world.hazB
    private val pad = world.pad

    private fun b(name: String) = res.meshes.batch(name)

    /** Places a model at course (s, d, h) with local yaw/pitch/roll relative to the course frame. */
    private fun put(name: String, s: Float, d: Float, h: Float, yaw: Float = 0f, pitch: Float = 0f, roll: Float = 0f,
                    sx: Float = 1f, sy: Float = sx, sz: Float = sx, tA: Int = A, tB: Int = B, wobble: Float = 0f, phase: Float = 0f, emissive: Float = 0f) {
        course.world(s, d, h, p)
        Inst.add(b(name), p[0], p[1], p[2], course.yawAt(s) + yaw, pitch, roll, sx, sy, sz, tA, tB, 1f, emissive, wobble, phase)
    }

    private fun bobFor(variant: Int): String = when (world) {
        World.LAGOON -> if (variant % 3 == 2) "beachball" else "duck"
        World.CANDY -> "candy"
        World.JUNGLE -> "idol"
        World.PIRATE -> "anchor"
        World.FROSTY -> if (variant % 2 == 0) "icecube" else "beachball"
        World.VOLCANO -> "magma"
        World.SKY -> if (variant % 2 == 0) "duck" else "beachball"
        World.NEON -> "discoball"
    }

    /** Side gantry posts and cross beam over the track at s (used by pendulums, presses). */
    private fun gantry(s: Float, hw: Float, height: Float) {
        for (side in floatArrayOf(-1f, 1f)) put("post", s, side * (hw + 0.45f), -0.7f, sx = 1.1f, sy = height + 0.7f, sz = 1.1f)
        for (side in floatArrayOf(-1f, 1f)) put("post_cap", s, side * (hw + 0.45f), height, tA = A, tB = B)
        put("beam", s, 0f, height - 0.15f, sx = hw * 2f + 0.9f, sy = 1.1f, sz = 1.1f)
    }

    fun draw(h: Hazard, t: Float, camS: Float, hit: Float) {
        if (abs(h.s0 - camS) > 75f) return
        h.state(t, st)
        val hw = h.hw
        val s0 = h.s0
        val wob = hit
        when (h) {
            is Raft -> {
                val c = st[0]
                val bob = sin(t * 2.2f) * 0.04f
                put("raft", c, 0f, -0.02f + bob, pitch = sin(t * 1.7f) * 0.02f, roll = sin(t * 1.3f) * 0.03f, sx = 1f, sy = 1f, sz = 1f, tA = A, tB = pad)
            }
            is Pusher -> {
                val side = st[1]
                val e = st[0]
                val yaw = if (side > 0f) 0f else PI_F
                put("side_box", s0, side * hw, 0f, yaw)
                val face = side * (hw - e)
                val origin = face + side * 0.6f
                if (e > 0.02f) put("piston", s0, side * hw, 0.62f, yaw, sx = max(0.05f, e - 0.55f))
                put("pusher_pad", s0, origin, 0.0f, yaw, tA = pad, tB = A, wobble = wob, phase = s0)
            }
            is Gloves -> {
                val side = st[1]
                val e = st[0]
                val yaw = if (side > 0f) 0f else PI_F
                put("side_box", s0, side * hw, 0f, yaw)
                val len = max(0.15f, e + 0.25f)
                val shake = if (st[2] in 0.5f..0.72f) sin(t * 60f) * 0.03f else 0f
                put("spring", s0, side * hw, 0.75f + shake, yaw, sx = len, sy = 1.6f, sz = 1.6f)
                put("glove", s0, side * (hw - len), 0.75f + shake, yaw, sx = 1.2f, tA = world.danger, tB = B, wobble = wob, phase = s0)
            }
            is Pendulum -> {
                gantry(s0, hw, h.pivotH)
                val th = st[0]
                val roll = -th
                put("rope", s0, 0f, h.pivotH, roll = roll, sx = 1f, sy = h.rope, sz = 1f)
                val bx = h.rope * sin(th)
                val by = h.pivotH - h.rope * cos(th)
                put(bobFor(st[3].toInt()), s0, bx, by - 0.05f, roll = roll * 0.6f, sx = 1f, tA = if (world == World.LAGOON) 0xFFD23F else A, tB = if (world == World.LAGOON) 0xFF8A3D else B, wobble = wob * 0.6f, phase = s0)
            }
            is Turntable -> {
                put("turntable", s0, 0f, 0.0f, yaw = st[0], sx = h.armLen + 0.35f, sy = 1f, sz = h.armLen + 0.35f, tA = A, tB = B)
                sweeperArms(h, st, s0, 0.1f)
            }
            is Sweeper -> {
                put("sweeper_hub", s0, st[3], 0f, sx = 0.75f, sy = 0.95f, sz = 0.75f, tA = A, tB = B)
                sweeperArms(h, st, s0, 0f)
            }
            is Press -> {
                gantry(s0, hw, h.top + 1.25f)
                val bottom = st[0]
                val shake = st[1] * sin(t * 50f) * 0.04f
                put("press_block", s0, shake, bottom, sx = hw, sy = 1f, sz = 1f, tA = pad, tB = A, wobble = if (bottom < 0.05f) 0.4f else 0f, phase = s0)
            }
            is Trapdoor -> {
                for (r in 0..1) for (c in 0..1) {
                    val k = r * 2 + c
                    val open = st[k]
                    val shake = st[4 + k] * sin(t * 45f + k) * 0.03f
                    val a = if (r == 0) -h.tileLen / 2f else h.tileLen / 2f
                    val side = if (c == 0) -1f else 1f
                    val yaw = if (side > 0f) 0f else PI_F
                    put("tile", s0 + a, side * hw, shake, yaw, roll = -open * 1.45f, sx = hw, sy = 1f, sz = h.tileLen - 0.06f,
                        tA = if ((r + c) % 2 == 0) world.deck else world.stripe, tB = world.danger)
                }
            }
            is PaddleWheel -> {
                for (side in floatArrayOf(-1f, 1f)) put("axle_support", s0, side * (hw + 0.35f), -0.3f, sx = 1f, sy = (h.axleH + 0.3f) / 2.6f, sz = 1f)
                put("paddle_wheel", s0, 0f, h.axleH, pitch = st[0], sx = hw * 2f + 0.2f, sy = 1f, sz = 1f, tA = pad, tB = A)
            }
            is Cannon -> {
                val side = st[1]
                val yaw = if (side > 0f) 0f else PI_F
                val recoil = st[2]
                put("cannon", s0, side * (hw + 0.6f + recoil * 0.25f), 0f, yaw, sx = 1f, sy = 1f + recoil * 0.06f, sz = 1f)
                val bd = st[0]
                if (bd < 90f) put("beachball", s0, bd, 0.7f, roll = -side * st[3], tA = world.danger, tB = 0xFFD23F)
            }
            is Tilt -> {
                put("tilt_deck", s0, 0f, 0f, roll = st[0], sx = hw * 2f, sy = 1f, sz = st[1] * 2f, tA = world.deck, tB = world.trim)
            }
            is Fan -> {
                val side = st[2]
                val yaw = if (side > 0f) 0f else PI_F
                put("fan", s0, side * (hw + 0.4f), 0f, yaw)
                spin[h.id] = (spin[h.id] ?: 0f) + (0.08f + st[0] * 0.9f)
                put("fan_blades", s0, side * (hw + 0.25f), 1.2f, yaw, pitch = spin[h.id] ?: 0f, tA = A, tB = B)
            }
            is Bollards -> {
                val cols = h.colD
                var k = 0
                for (r in 0..1) for (c in 0..2) {
                    val hh = st[k++]
                    if (hh > 0.01f) put("bollard", s0 + h.rowA[r], cols[c], (hh - 1f) * 1.0f, tA = world.danger, tB = 0xFFFFFF)
                }
            }
            is Gates -> {
                val c = st[0]
                for (side in floatArrayOf(-1f, 1f)) {
                    put("post", s0, side * (hw + 0.25f), -0.2f, sx = 1f, sy = 2.6f, sz = 1f)
                    val len = max(0.05f, hw * c + 0.15f)
                    val yaw = if (side > 0f) 0f else PI_F
                    put("gate_door", s0, side * (hw + 0.1f), 0f, yaw, sx = len, sy = 1f, sz = 1f, tA = pad, tB = A)
                }
                put("beam", s0, 0f, 2.45f, sx = hw * 2f + 0.6f)
            }
            is Wave -> {
                val side = st[2]
                val yaw = if (side > 0f) 0f else PI_F
                put("wave_tank", s0, side * (hw + 0.55f), -0.4f, yaw, sy = 1f + st[1] * 0.08f, sz = st[3] * 2f / 3.2f)
                val cd = st[0]
                if (cd < 90f) put("wave", s0, cd, 0.0f, yaw, sz = st[3] * 2f / 3.2f, sy = 1.1f)
            }
            is Barrels -> {
                for (k in 0..2) {
                    val a = st[k * 3]
                    if (a > 90f) continue
                    val bs = s0 + a
                    put("barrel", bs, st[k * 3 + 1], h.radius, pitch = -st[k * 3 + 2], sx = h.hw / 1.1f, sy = 1f, sz = 1f, tA = A, tB = B)
                }
            }
            is Coconuts -> {
                for (k in 0..1) {
                    val nh = st[k * 2]
                    if (nh > 90f) continue
                    course.world(s0, st[k * 2 + 1], 0f, p)
                    val yaw = course.yawAt(s0 - 0.5f)
                    Inst.add(b("coconut"), p[0] - sin(yaw) * 0.45f, p[1] + nh - 0.0f, p[2] - cos(yaw) * 0.45f, t * 4f, t * 3f, 0f, 1.2f)
                }
                course.world(s0, 0f, h.wallH, p)
                put("coconut_dropper", s0 - 0.3f, -hw * 0.45f, h.wallH + 0.25f, roll = 0.2f, sx = 1.4f)
                put("coconut_dropper", s0 - 0.3f, hw * 0.45f, h.wallH + 0.25f, roll = -0.2f, sx = 1.4f)
            }
            is Conveyor -> {
                for (side in floatArrayOf(-1f, 1f)) put("conveyor_roller", s0 - h.halfLen, 0f, -0.05f, sx = hw * 2f)
                put("conveyor_roller", s0 + h.halfLen, 0f, -0.05f, sx = hw * 2f)
            }
            else -> Unit
        }
    }

    private val spin = HashMap<Int, Float>()

    private fun sweeperArms(h: Sweeper, st: FloatArray, s0: Float, hOff: Float) {
        val arms = st[1].toInt()
        val len = st[2]
        val hubD = st[3]
        for (k in 0 until arms) {
            val al = st[0] + 2f * PI_F * k / arms
            val yaw = -al - PI_F / 2f
            put("noodle", s0, hubD, 0.55f + hOff, yaw = yaw, sx = len, sy = 1f, sz = 1f, tA = pad, tB = B, wobble = 0.15f, phase = al)
            val ta = cos(al) * len
            val td = sin(al) * len
            put("noodle_tip", s0 + ta, hubD + td, 0.55f + hOff, tA = pad, tB = B)
        }
    }

    @Suppress("unused")
    private fun matrix(s: Float, d: Float, h: Float, yaw: Float) {
        course.world(s, d, h, p)
        Quat.euler(q, course.yawAt(s) + yaw, 0f, 0f)
        Mat4.trs(m, 0, p[0], p[1], p[2], q[0], q[1], q[2], q[3], 1f, 1f, 1f)
    }
}
