package com.ngstudio.funrace.game.sim

import com.ngstudio.funrace.core.PI_F
import com.ngstudio.funrace.core.TAU
import com.ngstudio.funrace.core.clamp01
import com.ngstudio.funrace.core.smoothstep
import com.ngstudio.funrace.core.wrapAngle
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

enum class HazType(val label: String) {
    PUSHER("Bumper Pusher"), PENDULUM("Duck Pendulum"), SWEEPER("Noodle Sweeper"), GLOVES("Boxing Gloves"),
    PRESS("Squish Press"), TRAPDOOR("Flip Tiles"), PADDLE("Paddle Wheel"), CANNON("Beach-Ball Cannon"),
    TILT("Tilt Deck"), FAN("Fan Gust"), BOLLARDS("Pop-up Bollards"), GATES("Sliding Gates"), WAVE("Wave Machine"),
    CONVEYOR("Lazy River"), BARRELS("Barrel Roll"), TURNTABLE("Turntable"), COCONUTS("Coconut Drop"), RAFT("Floaty Ferry"),
}

/** Result of a racer touching a hazard. */
class Hit {
    var kind = Danger.SAFE
    var vs = 0f; var vd = 0f; var vy = 0f
    var stun = 0f
    var squash = false
    fun set(k: Int, vs: Float, vd: Float, vy: Float, stun: Float = 0.6f, squash: Boolean = false): Int {
        kind = k; this.vs = vs; this.vd = vd; this.vy = vy; this.stun = stun; this.squash = squash
        return k
    }
}

object Danger {
    const val SAFE = 0
    /** Solid but harmless: stops the racer (gates, raised bollards, a pressed-down block). */
    const val BLOCK = 1
    /** Knocked flying (may end in the water). */
    const val KNOCK = 2
    /** Floor vanished (trapdoor) or racer is swept off. */
    const val FALL = 3
}

const val RR = 0.3f          // racer radius
const val RH = 1.2f          // racer height

/** Rectangle in hazard-local coords used for the red telegraph decals. */
class Zone(val a0: Float, val a1: Float, val d0: Float, val d1: Float)

/**
 * A hazard anchored at course distance [s0]. Everything is a pure function of race time, so the
 * AI, the autopilot and the level validator can predict it exactly.
 * Local coords: a = s - s0 (along), d (lateral, + right), y (height above the deck).
 */
abstract class Hazard(val type: HazType, val s0: Float, val hw: Float, val period: Float, val phase: Float) {
    /** Along-course half extent of the danger area (used for culling and the safe-pad rule). */
    abstract val reach: Float
    open val lethal: Boolean get() = true
    val zoneStart get() = s0 - reach
    val zoneEnd get() = s0 + reach
    var id = 0

    protected fun u(t: Float, off: Float = 0f): Float = frac((t + phase) / period + off)
    protected fun cycle(t: Float): Int = floor((t + phase) / period).toInt()

    /** Danger at (t, a, d, y). Fills [hit] when non-null and returns the [Danger] kind. */
    abstract fun danger(t: Float, a: Float, d: Float, y: Float, hit: Hit?): Int

    /** Lateral drift (m/s) applied to racers standing in the zone (wind, tilt). */
    open fun drift(t: Float, a: Float, d: Float): Float = 0f

    /** Along-course conveyor speed (m/s, negative = pushes back). */
    open fun conveyor(t: Float, a: Float, d: Float): Float = 0f

    /** 0..1 how imminent the hazard is (drives decal pulse / sounds). */
    abstract fun telegraph(t: Float): Float

    /** Fills renderer state (type specific, see each class). */
    abstract fun state(t: Float, out: FloatArray)

    /** Decal zones in local coordinates. */
    abstract fun zones(): List<Zone>

    /** True for a time-step when a sound-worthy event begins (punch, fire, slam...). */
    open fun event(tPrev: Float, t: Float): Boolean = false

    protected fun crossed(tPrev: Float, t: Float, at: Float): Boolean {
        val a = u(tPrev); val b = u(t)
        return if (b >= a) at in a..b else (at >= a || at <= b)
    }
}

// ------------------------------------------------------------------------------------ PUSHER
/** Padded block sliding out of a side box. side +1 = from the right edge. state: [0] extension (m). */
class Pusher(s0: Float, hw: Float, period: Float, phase: Float, val side: Float, val reachFrac: Float) : Hazard(HazType.PUSHER, s0, hw, period, phase) {
    override val reach = 0.5f + RR + 0.1f
    private val maxExt get() = hw * 2f * reachFrac

    fun extension(t: Float): Float {
        val x = u(t)
        return maxExt * when {
            x < 0.45f -> 0f
            x < 0.55f -> smoothstep(0.45f, 0.55f, x)
            x < 0.68f -> 1f
            else -> 1f - smoothstep(0.68f, 1f, x)
        }
    }

    private fun pushing(t: Float): Boolean { val x = u(t); return x in 0.45f..0.68f }

    override fun danger(t: Float, a: Float, d: Float, y: Float, hit: Hit?): Int {
        if (abs(a) > 0.5f + RR || y > 1.15f) return Danger.SAFE
        val e = extension(t)
        if (e < 0.05f) return Danger.SAFE
        val face = side * (hw - e)            // inner face position
        val inside = if (side > 0) d + RR > face else d - RR < face
        if (!inside) return Danger.SAFE
        return if (pushing(t)) hit?.set(Danger.KNOCK, 0f, -side * 9.5f, 4.5f) ?: Danger.KNOCK
        else hit?.set(Danger.BLOCK, 0f, -side * 2f, 0f) ?: Danger.BLOCK
    }

    override fun telegraph(t: Float) = val0(u(t), 0.3f, 0.45f, 0.68f)
    override fun state(t: Float, out: FloatArray) { out[0] = extension(t); out[1] = side }
    override fun zones() = listOf(Zone(-0.6f, 0.6f, if (side > 0) hw - maxExt else -hw, if (side > 0) hw else -hw + maxExt))
    override fun event(tPrev: Float, t: Float) = crossed(tPrev, t, 0.45f)
}

/** 0..1 ramp that rises before [start], stays 1 until [end]. */
internal fun val0(x: Float, pre: Float, start: Float, end: Float): Float = when {
    x < pre -> 0f
    x < start -> (x - pre) / (start - pre)
    x < end -> 1f
    else -> max(0f, 1f - (x - end) * 6f)
}

// ------------------------------------------------------------------------------------ PENDULUM
/** Bob swinging across the track. state: [0] angle (rad), [1] rope length, [2] pivot height. */
class Pendulum(s0: Float, hw: Float, period: Float, phase: Float, val amp: Float, val bob: Int = 0) : Hazard(HazType.PENDULUM, s0, hw, period, phase) {
    val rope = 3.6f
    val pivotH = 4.85f
    val bobR = 0.68f
    override val reach = bobR + RR + 0.05f

    fun angle(t: Float) = amp * sin(TAU * (t + phase) / period)
    private fun angVel(t: Float) = amp * cos(TAU * (t + phase) / period) * TAU / period

    override fun danger(t: Float, a: Float, d: Float, y: Float, hit: Hit?): Int {
        if (abs(a) > bobR + RR) return Danger.SAFE
        val th = angle(t)
        val bx = rope * sin(th)
        val by = pivotH - rope * cos(th)
        // distance from bob centre to the racer's vertical capsule
        val cy = by.coerceIn(y + 0.3f, y + 1.0f)
        val dd = (d - bx) * (d - bx) + (cy - by) * (cy - by) + a * a * 0.6f
        val r = bobR + RR
        if (dd > r * r) return Danger.SAFE
        val v = angVel(t)
        val dir = if (abs(v) > 0.2f) sign(v) else sign(d - bx).let { if (it == 0f) 1f else it }
        return hit?.set(Danger.KNOCK, 0f, dir * 10f, 5f) ?: Danger.KNOCK
    }

    override fun telegraph(t: Float): Float = 1f - min(1f, abs(sin(TAU * (t + phase) / period)) * 1.3f)
    override fun state(t: Float, out: FloatArray) { out[0] = angle(t); out[1] = rope; out[2] = pivotH; out[3] = bob.toFloat() }
    override fun zones(): List<Zone> {
        val w = min(hw, rope * sin(amp) + bobR * 0.6f)
        return listOf(Zone(-0.8f, 0.8f, -w, w))
    }
    override fun event(tPrev: Float, t: Float): Boolean {
        val a = sin(TAU * (tPrev + phase) / period); val b = sin(TAU * (t + phase) / period)
        return (a < 0f) != (b < 0f)
    }
}

// ------------------------------------------------------------------------------------ SWEEPER / TURNTABLE
/** Arms rotating around a central hub. state: [0] base angle, [1] arms, [2] arm length, [3] hub d. */
open class Sweeper(type: HazType, s0: Float, hw: Float, val omega: Float, phase: Float, val arms: Int, val armLen: Float, val hubD: Float = 0f) :
    Hazard(type, s0, hw, TAU / abs(omega), phase) {
    constructor(s0: Float, hw: Float, omega: Float, phase: Float, arms: Int, armLen: Float) : this(HazType.SWEEPER, s0, hw, omega, phase, arms, armLen)
    val hubR = 0.3f
    override val reach get() = armLen + RR + 0.2f

    fun baseAngle(t: Float) = omega * t + phase * abs(omega)

    override fun danger(t: Float, a: Float, d: Float, y: Float, hit: Hit?): Int {
        val ld = d - hubD
        val r = sqrt(a * a + ld * ld)
        if (r > armLen + RR + 0.2f) return Danger.SAFE
        if (r < hubR + RR) return Danger.SAFE // the hub just nudges racers aside (see drift)
        if (y > 0.82f) return Danger.SAFE // jumped over the arm
        val beta = atan2(ld, a)
        val base = baseAngle(t)
        val half = 0.2f + RR
        for (k in 0 until arms) {
            val al = base + TAU * k / arms
            val delta = wrapAngle(beta - al)
            if (abs(delta) * r < half && abs(delta) < 1.2f) {
                // push along the direction of rotation (tangent)
                val sg = sign(omega)
                val ta = -sin(al) * sg
                val td = cos(al) * sg
                return hit?.set(Danger.KNOCK, ta * 6f, td * 10f, 4.5f) ?: Danger.KNOCK
            }
        }
        return Danger.SAFE
    }

    override fun drift(t: Float, a: Float, d: Float): Float {
        val ld = d - hubD
        val r = sqrt(a * a + ld * ld)
        if (r >= hubR + RR + 0.1f) return 0f
        return (if (ld >= 0f) 1f else -1f) * 4f
    }

    override fun telegraph(t: Float): Float = 0.6f + 0.4f * sin(t * 6f)
    override fun state(t: Float, out: FloatArray) { out[0] = baseAngle(t); out[1] = arms.toFloat(); out[2] = armLen; out[3] = hubD }
    override fun zones() = listOf(Zone(-armLen, armLen, max(-hw, hubD - armLen), min(hw, hubD + armLen)))
}

class Turntable(s0: Float, hw: Float, omega: Float, phase: Float) : Sweeper(HazType.TURNTABLE, s0, hw, omega, phase, 2, hw + 0.7f) {
    override fun drift(t: Float, a: Float, d: Float): Float {
        return super.drift(t, a, d)
    }
}

// ------------------------------------------------------------------------------------ GLOVES
/** Spring-loaded boxing glove punching from a side box. state: [0] extension (negative = wind-up). */
class Gloves(s0: Float, hw: Float, period: Float, phase: Float, val side: Float, val reachFrac: Float) : Hazard(HazType.GLOVES, s0, hw, period, phase) {
    override val reach = 0.4f + RR + 0.05f
    private val maxExt get() = hw * 2f * reachFrac

    fun extension(t: Float): Float {
        val x = u(t)
        return when {
            x < 0.5f -> 0f
            x < 0.72f -> -0.35f * smoothstep(0.5f, 0.72f, x) // wind-up (pulls back)
            x < 0.78f -> -0.35f + (maxExt + 0.35f) * smoothstep(0.72f, 0.78f, x)
            x < 0.84f -> maxExt
            else -> maxExt * (1f - smoothstep(0.84f, 1f, x))
        }
    }

    override fun danger(t: Float, a: Float, d: Float, y: Float, hit: Hit?): Int {
        if (abs(a) > 0.4f + RR || y > 1.05f) return Danger.SAFE
        val x = u(t)
        if (x !in 0.72f..0.86f) return Danger.SAFE
        val e = extension(t)
        if (e < 0.1f) return Danger.SAFE
        val face = side * (hw - e)
        val inside = if (side > 0) d + RR > face else d - RR < face
        if (!inside) return Danger.SAFE
        return hit?.set(Danger.KNOCK, 0f, -side * 11f, 5f) ?: Danger.KNOCK
    }

    override fun telegraph(t: Float) = val0(u(t), 0.5f, 0.72f, 0.84f)
    override fun state(t: Float, out: FloatArray) { out[0] = extension(t); out[1] = side; out[2] = u(t) }
    override fun zones() = listOf(Zone(-0.5f, 0.5f, if (side > 0) hw - maxExt else -hw, if (side > 0) hw else -hw + maxExt))
    override fun event(tPrev: Float, t: Float) = crossed(tPrev, t, 0.72f)
}

// ------------------------------------------------------------------------------------ PRESS
/** Block that slams down. state: [0] block bottom height, [1] shake. */
class Press(s0: Float, hw: Float, period: Float, phase: Float) : Hazard(HazType.PRESS, s0, hw, period, phase) {
    val halfLen = 0.8f
    override val reach = halfLen + RR + 0.05f
    val top = 2.7f

    fun bottom(t: Float): Float {
        val x = u(t)
        return when {
            x < 0.62f -> top
            x < 0.67f -> top * (1f - ((x - 0.62f) / 0.05f).let { it * it })
            x < 0.8f -> 0f
            else -> top * smoothstep(0.8f, 1f, x)
        }
    }

    override fun danger(t: Float, a: Float, d: Float, y: Float, hit: Hit?): Int {
        if (abs(a) > halfLen + RR) return Danger.SAFE
        val b = bottom(t)
        if (b > y + RH + 0.1f) return Danger.SAFE
        val x = u(t)
        return if (x in 0.62f..0.68f) hit?.set(Danger.KNOCK, if (a < 0f) -7f else 7f, 0f, 3f, 0.9f, squash = true) ?: Danger.KNOCK
        else hit?.set(Danger.BLOCK, if (a < 0f) -3f else 3f, 0f, 0f) ?: Danger.BLOCK
    }

    override fun telegraph(t: Float) = val0(u(t), 0.45f, 0.62f, 0.8f)
    override fun state(t: Float, out: FloatArray) { out[0] = bottom(t); out[1] = val0(u(t), 0.48f, 0.62f, 0.62f) }
    override fun zones() = listOf(Zone(-halfLen, halfLen, -hw, hw))
    override fun event(tPrev: Float, t: Float) = crossed(tPrev, t, 0.66f)
}

// ------------------------------------------------------------------------------------ TRAPDOOR
/** 2 rows x 2 columns of flip tiles. state: [0..3] open angle per tile (row-major). */
class Trapdoor(s0: Float, hw: Float, period: Float, phase: Float, val checker: Boolean) : Hazard(HazType.TRAPDOOR, s0, hw, period, phase) {
    val tileLen = 1.7f
    override val reach = tileLen + 0.1f

    private fun tileOffset(row: Int, col: Int): Float = if (checker) (if ((row + col) % 2 == 0) 0f else 0.5f) else (if (row == 0) 0f else 0.5f)

    /** 0 closed .. 1 fully open. */
    fun open(t: Float, row: Int, col: Int): Float {
        val x = frac((t + phase) / period + tileOffset(row, col))
        return when {
            x < 0.55f -> 0f
            x < 0.6f -> smoothstep(0.55f, 0.6f, x)
            x < 0.88f -> 1f
            x < 0.95f -> 1f - smoothstep(0.88f, 0.95f, x)
            else -> 0f
        }
    }

    fun shake(t: Float, row: Int, col: Int): Float {
        val x = frac((t + phase) / period + tileOffset(row, col))
        return if (x in 0.38f..0.55f) (x - 0.38f) / 0.17f else 0f
    }

    override fun danger(t: Float, a: Float, d: Float, y: Float, hit: Hit?): Int {
        if (a < -tileLen || a > tileLen || y > 0.3f) return Danger.SAFE
        val row = if (a < 0f) 0 else 1
        val col = if (d < 0f) 0 else 1
        if (open(t, row, col) > 0.35f) return hit?.set(Danger.FALL, 0f, 0f, 0f) ?: Danger.FALL
        return Danger.SAFE
    }

    override fun telegraph(t: Float): Float {
        var m = 0f
        for (r in 0..1) for (c in 0..1) m = max(m, max(shake(t, r, c), open(t, r, c)))
        return m
    }

    override fun state(t: Float, out: FloatArray) {
        var k = 0
        for (r in 0..1) for (c in 0..1) { out[k] = open(t, r, c); out[4 + k] = shake(t, r, c); k++ }
    }

    override fun zones() = listOf(Zone(-tileLen, tileLen, -hw, hw))
}

// ------------------------------------------------------------------------------------ PADDLE WHEEL
/** Wheel above the track turning about the lateral axis. state: [0] angle. */
class PaddleWheel(s0: Float, hw: Float, val omega: Float, phase: Float) : Hazard(HazType.PADDLE, s0, hw, TAU / abs(omega) / 3f, phase) {
    val axleH = 2.15f
    val radius = 1.6f
    val paddles = 3
    override val reach = radius + RR

    fun angle(t: Float) = omega * (t + phase)

    override fun danger(t: Float, a: Float, d: Float, y: Float, hit: Hit?): Int {
        if (abs(a) > radius + RR) return Danger.SAFE
        val ang0 = angle(t)
        val py = y + 0.7f
        val ry = py - axleH
        val r = sqrt(a * a + ry * ry)
        if (r < 0.3f || r > radius + 0.55f + RR) return Danger.SAFE
        val beta = atan2(a, -ry) // 0 = straight down
        for (k in 0 until paddles) {
            val al = ang0 + TAU * k / paddles
            if (cos(al) < 0.15f) continue // paddle is up in the air
            val delta = wrapAngle(beta - al)
            if (abs(delta) * r < 0.12f + RR + 0.05f) {
                val dir = sign(omega) // positive omega: bottom paddle moves toward +a
                return hit?.set(Danger.KNOCK, dir * 7.5f, 0f, 3.5f, 0.7f) ?: Danger.KNOCK
            }
        }
        return Danger.SAFE
    }

    override val lethal get() = false
    override fun telegraph(t: Float) = 0.7f
    override fun state(t: Float, out: FloatArray) { out[0] = angle(t); out[1] = axleH }
    override fun zones() = listOf(Zone(-1.2f, 1.2f, -hw, hw))
}

// ------------------------------------------------------------------------------------ CANNON
/** Fires a rolling beach ball across the track. state: [0] ball d (or 99), [1] recoil, [2] spin. */
class Cannon(s0: Float, hw: Float, period: Float, phase: Float, val side: Float, val speed: Float = 6.5f) : Hazard(HazType.CANNON, s0, hw, period, phase) {
    val ballR = 0.7f
    override val reach = ballR + RR
    private val fireAt = 0.4f
    private val travel get() = (hw * 2f + 2.4f) / speed

    fun ballD(t: Float): Float {
        val x = u(t)
        val since = (x - fireAt) * period
        if (since < 0f || since > travel) return 99f
        return side * (hw + 1.2f) - side * speed * since
    }

    override fun danger(t: Float, a: Float, d: Float, y: Float, hit: Hit?): Int {
        if (abs(a) > ballR + RR || y > 1.25f) return Danger.SAFE
        val bd = ballD(t)
        if (bd > 90f) return Danger.SAFE
        if (abs(d - bd) > ballR + RR) return Danger.SAFE
        return hit?.set(Danger.KNOCK, 0f, -side * 9f, 5f) ?: Danger.KNOCK
    }

    override fun telegraph(t: Float) = val0(u(t), 0.2f, 0.4f, 0.4f + travel / period)
    override fun state(t: Float, out: FloatArray) {
        val x = u(t)
        out[0] = ballD(t); out[1] = side
        out[2] = if (x in 0.25f..0.4f) (x - 0.25f) / 0.15f else if (x in 0.4f..0.5f) 1f - (x - 0.4f) * 10f else 0f
        out[3] = (x - fireAt) * period * speed / ballR
    }
    override fun zones() = listOf(Zone(-0.8f, 0.8f, -hw, hw))
    override fun event(tPrev: Float, t: Float) = crossed(tPrev, t, fireAt)
}

// ------------------------------------------------------------------------------------ TILT
/** Deck section that rolls left/right. state: [0] roll angle. */
class Tilt(s0: Float, hw: Float, period: Float, phase: Float, val halfLen: Float = 2.5f, val maxRoll: Float = 0.45f) : Hazard(HazType.TILT, s0, hw, period, phase) {
    override val reach get() = halfLen

    fun roll(t: Float): Float {
        val x = u(t)
        val dir = if (cycle(t) % 2 == 0) 1f else -1f
        val k = when {
            x < 0.45f -> 0f
            x < 0.6f -> smoothstep(0.45f, 0.6f, x)
            x < 0.78f -> 1f
            x < 0.92f -> 1f - smoothstep(0.78f, 0.92f, x)
            else -> 0f
        }
        return maxRoll * k * dir
    }

    override fun danger(t: Float, a: Float, d: Float, y: Float, hit: Hit?): Int {
        if (abs(a) > halfLen || y > 0.2f) return Danger.SAFE
        return if (abs(roll(t)) > 0.12f) Danger.FALL else Danger.SAFE
    }

    override fun drift(t: Float, a: Float, d: Float): Float {
        if (abs(a) > halfLen) return 0f
        val r = roll(t)
        return if (abs(r) > 0.1f) sin(r) * 9f else 0f
    }

    override fun telegraph(t: Float) = val0(u(t), 0.3f, 0.45f, 0.92f)
    override fun state(t: Float, out: FloatArray) { out[0] = roll(t); out[1] = halfLen }
    override fun zones() = listOf(Zone(-halfLen, halfLen, -hw, hw))
}

// ------------------------------------------------------------------------------------ FAN
/** Big fan blowing racers sideways. state: [0] blade spin speed 0..1, [1] gust 0..1. */
class Fan(s0: Float, hw: Float, period: Float, phase: Float, val side: Float, val halfLen: Float = 1.5f, val strength: Float = 3.8f) : Hazard(HazType.FAN, s0, hw, period, phase) {
    override val reach get() = halfLen

    fun gust(t: Float): Float {
        val x = u(t)
        return if (x in 0.55f..0.9f) 1f else 0f
    }

    override fun danger(t: Float, a: Float, d: Float, y: Float, hit: Hit?): Int =
        if (abs(a) <= halfLen && gust(t) > 0f) Danger.FALL else Danger.SAFE

    override fun drift(t: Float, a: Float, d: Float): Float = if (abs(a) <= halfLen) -side * strength * gust(t) else 0f
    override fun telegraph(t: Float) = val0(u(t), 0.38f, 0.55f, 0.9f)
    override fun state(t: Float, out: FloatArray) { out[0] = val0(u(t), 0.38f, 0.55f, 0.9f); out[1] = gust(t); out[2] = side }
    override fun zones() = listOf(Zone(-halfLen, halfLen, -hw, hw))
    override fun event(tPrev: Float, t: Float) = crossed(tPrev, t, 0.55f)
}

// ------------------------------------------------------------------------------------ BOLLARDS
/** Two rows x 3 columns of pop-up posts. state: [0..5] height 0..1 per post. */
class Bollards(s0: Float, hw: Float, period: Float, phase: Float) : Hazard(HazType.BOLLARDS, s0, hw, period, phase) {
    val rowA = floatArrayOf(-0.9f, 0.9f)
    val colD get() = floatArrayOf(-hw * 0.62f, 0f, hw * 0.62f)
    val postR = 0.32f
    override val reach = 0.9f + postR + RR

    private fun offset(row: Int, col: Int): Float = frac(row * 0.2f + (if (col % 2 == 0) 0f else 0.5f))

    fun height(t: Float, row: Int, col: Int): Float {
        val x = frac((t + phase) / period + offset(row, col))
        return when {
            x < 0.5f -> 0f
            x < 0.56f -> smoothstep(0.5f, 0.56f, x)
            x < 0.92f -> 1f
            else -> 1f - smoothstep(0.92f, 1f, x)
        }
    }

    private fun rising(t: Float, row: Int, col: Int): Boolean {
        val x = frac((t + phase) / period + offset(row, col))
        return x in 0.5f..0.57f
    }

    override fun danger(t: Float, a: Float, d: Float, y: Float, hit: Hit?): Int {
        if (abs(a) > reach) return Danger.SAFE
        val cols = colD
        for (r in 0..1) for (c in 0..2) {
            val da = a - rowA[r]; val dd = d - cols[c]
            if (da * da + dd * dd > (postR + RR) * (postR + RR)) continue
            val h = height(t, r, c)
            if (h < 0.15f || y > h * 0.95f) continue
            if (rising(t, r, c)) return hit?.set(Danger.KNOCK, 0f, (if (d >= cols[c]) 1f else -1f) * 4.5f, 8.5f) ?: Danger.KNOCK
            return hit?.set(Danger.BLOCK, if (da < 0f) -2.5f else 2.5f, 0f, 0f) ?: Danger.BLOCK
        }
        return Danger.SAFE
    }

    override fun telegraph(t: Float): Float {
        var m = 0f
        for (r in 0..1) for (c in 0..2) m = max(m, height(t, r, c))
        return m
    }

    override fun state(t: Float, out: FloatArray) { var k = 0; for (r in 0..1) for (c in 0..2) out[k++] = height(t, r, c) }
    override fun zones(): List<Zone> {
        val z = ArrayList<Zone>()
        for (r in 0..1) for (c in colD) z += Zone(rowA[r] - 0.45f, rowA[r] + 0.45f, c - 0.45f, c + 0.45f)
        return z
    }
}

// ------------------------------------------------------------------------------------ GATES
/** Doors sliding in from both sides. state: [0] closure 0 (open) .. 1 (closed). */
class Gates(s0: Float, hw: Float, period: Float, phase: Float) : Hazard(HazType.GATES, s0, hw, period, phase) {
    override val reach = 0.15f + RR + 0.05f
    override val lethal get() = false

    fun closure(t: Float): Float {
        val x = u(t)
        return when {
            x < 0.4f -> 0f
            x < 0.5f -> smoothstep(0.4f, 0.5f, x)
            x < 0.85f -> 1f
            x < 0.95f -> 1f - smoothstep(0.85f, 0.95f, x)
            else -> 0f
        }
    }

    override fun danger(t: Float, a: Float, d: Float, y: Float, hit: Hit?): Int {
        if (abs(a) > 0.15f + RR) return Danger.SAFE
        val c = closure(t)
        if (c < 0.05f) return Danger.SAFE
        val gap = hw * (1f - c) // opening half-width around the centre
        if (abs(d) + RR * 0.6f < gap) return Danger.SAFE
        return hit?.set(Danger.BLOCK, if (a < 0f) -3f else 3f, 0f, 0f) ?: Danger.BLOCK
    }

    override fun telegraph(t: Float) = val0(u(t), 0.3f, 0.4f, 0.95f)
    override fun state(t: Float, out: FloatArray) { out[0] = closure(t) }
    override fun zones() = listOf(Zone(-0.35f, 0.35f, -hw, hw))
    override fun event(tPrev: Float, t: Float) = crossed(tPrev, t, 0.4f)
}

// ------------------------------------------------------------------------------------ WAVE
/** A wave crest rolling across the track from a side tank. state: [0] crest d (or 99), [1] tank swell. */
class Wave(s0: Float, hw: Float, period: Float, phase: Float, val side: Float, val speed: Float = 7f) : Hazard(HazType.WAVE, s0, hw, period, phase) {
    val halfLen = 1.6f
    override val reach get() = halfLen + RR
    private val launchAt = 0.5f
    private val travel get() = (hw * 2f + 1.8f) / speed

    fun crestD(t: Float): Float {
        val since = (u(t) - launchAt) * period
        if (since < 0f || since > travel) return 99f
        return side * (hw + 0.9f) - side * speed * since
    }

    override fun danger(t: Float, a: Float, d: Float, y: Float, hit: Hit?): Int {
        if (abs(a) > halfLen + RR || y > 1.0f) return Danger.SAFE
        val c = crestD(t)
        if (c > 90f || abs(d - c) > 0.45f + RR) return Danger.SAFE
        return hit?.set(Danger.KNOCK, 0f, -side * 8.5f, 3f) ?: Danger.KNOCK
    }

    override fun telegraph(t: Float) = val0(u(t), 0.33f, 0.5f, 0.5f + travel / period)
    override fun state(t: Float, out: FloatArray) { out[0] = crestD(t); out[1] = val0(u(t), 0.33f, 0.5f, 0.55f); out[2] = side; out[3] = halfLen }
    override fun zones() = listOf(Zone(-halfLen, halfLen, -hw, hw))
    override fun event(tPrev: Float, t: Float) = crossed(tPrev, t, launchAt)
}

// ------------------------------------------------------------------------------------ CONVEYOR
/** Lazy-river deck that drags racers backward. state: [0] belt offset. */
class Conveyor(s0: Float, hw: Float, val halfLen: Float, val speed: Float) : Hazard(HazType.CONVEYOR, s0, hw, 1f, 0f) {
    override val reach get() = halfLen
    override val lethal get() = false
    override fun danger(t: Float, a: Float, d: Float, y: Float, hit: Hit?) = Danger.SAFE
    override fun conveyor(t: Float, a: Float, d: Float) = if (abs(a) <= halfLen) -speed else 0f
    override fun telegraph(t: Float) = 0.4f
    override fun state(t: Float, out: FloatArray) { out[0] = t * speed; out[1] = halfLen }
    override fun zones() = emptyList<Zone>()
}

// ------------------------------------------------------------------------------------ BARRELS
/** Barrels rolling down a ramp, alternating halves. state: pairs (a, d) for up to 3 barrels. */
class Barrels(s0: Float, hw: Float, period: Float, phase: Float, val rampHalf: Float, val rollSpeed: Float = 4f) : Hazard(HazType.BARRELS, s0, hw, period, phase) {
    override val reach get() = rampHalf
    override val lethal get() = false
    private val life get() = rampHalf * 2f / rollSpeed
    val radius = 0.48f

    /** Barrel k is released every period/2 alternating sides; returns a (along) or 99 if inactive. */
    fun barrel(t: Float, k: Int, out: FloatArray): Boolean {
        val half = period / 2f
        val tt = t + phase
        val n = floor(tt / half).toInt() - k
        val since = tt - n * half
        if (since > life) return false
        out[0] = rampHalf - since * rollSpeed
        out[1] = if (n % 2 == 0) hw * 0.5f else -hw * 0.5f
        out[2] = since * rollSpeed / radius
        return true
    }

    private val tmp = FloatArray(3)

    override fun danger(t: Float, a: Float, d: Float, y: Float, hit: Hit?): Int {
        if (abs(a) > rampHalf || y > 0.9f) return Danger.SAFE
        for (k in 0..2) {
            if (!barrel(t, k, tmp)) continue
            if (abs(a - tmp[0]) < radius + RR && abs(d - tmp[1]) < hw * 0.5f + RR * 0.5f)
                return hit?.set(Danger.KNOCK, -7.5f, 0f, 4f, 0.8f) ?: Danger.KNOCK
        }
        return Danger.SAFE
    }

    override fun telegraph(t: Float) = 0.6f
    override fun state(t: Float, out: FloatArray) {
        for (k in 0..2) {
            if (barrel(t, k, tmp)) { out[k * 3] = tmp[0]; out[k * 3 + 1] = tmp[1]; out[k * 3 + 2] = tmp[2] } else out[k * 3] = 99f
        }
    }
    override fun zones() = listOf(Zone(-rampHalf, rampHalf, -hw, hw))
}

// ------------------------------------------------------------------------------------ COCONUTS (climb wall)
/** Coconuts dropping down a climbing wall, alternating halves. a = height on wall from its base. state: (h, d) x2. */
class Coconuts(s0: Float, hw: Float, period: Float, phase: Float, val wallH: Float) : Hazard(HazType.COCONUTS, s0, hw, period, phase) {
    override val reach get() = wallH
    private val fall = 0.9f

    fun nut(t: Float, k: Int, out: FloatArray): Boolean {
        val half = period / 2f
        val tt = t + phase
        val n = floor(tt / half).toInt() - k
        val since = tt - n * half
        if (since > fall) return false
        out[0] = wallH * (1f - (since / fall).let { it * it })
        out[1] = if (n % 2 == 0) hw * 0.45f else -hw * 0.45f
        return true
    }

    private val tmp = FloatArray(2)

    /** Here [a] is the racer's height on the wall measured from the wall's base. */
    override fun danger(t: Float, a: Float, d: Float, y: Float, hit: Hit?): Int {
        if (a < 0f) return Danger.SAFE
        for (k in 0..1) {
            if (!nut(t, k, tmp)) continue
            if (abs(tmp[0] - (a + 0.9f)) < 0.6f && abs(d - tmp[1]) < hw * 0.45f + 0.15f)
                return hit?.set(Danger.KNOCK, -3f, 0f, 1f, 0.9f) ?: Danger.KNOCK
        }
        return Danger.SAFE
    }

    override fun telegraph(t: Float) = 0.5f
    override fun state(t: Float, out: FloatArray) {
        for (k in 0..1) { if (nut(t, k, tmp)) { out[k * 2] = tmp[0]; out[k * 2 + 1] = tmp[1] } else out[k * 2] = 99f }
    }
    override fun zones() = emptyList<Zone>()
}
