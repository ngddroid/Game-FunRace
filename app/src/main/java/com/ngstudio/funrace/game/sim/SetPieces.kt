package com.ngstudio.funrace.game.sim

import com.ngstudio.funrace.core.smoothstep
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Ferry raft shuttling across a RAFT gap. Racers may only stand on it; stepping into the gap
 * when it isn't there means a splash. state: [0] raft centre s, [1] velocity.
 */
class Raft(val seg: Segment, hw: Float, period: Float, phase: Float) : Hazard(HazType.RAFT, (seg.s0 + seg.s1) / 2f, hw, period, phase) {
    val halfLen = 1.3f
    private val nearC get() = seg.s0 + halfLen
    private val farC get() = seg.s1 - halfLen
    override val reach get() = seg.length / 2f + 0.15f
    override val lethal get() = true

    /** Raft centre (absolute s). Dwell 22% at each dock, travel in between. */
    fun centre(t: Float): Float {
        val x = u(t)
        val k = when {
            x < 0.22f -> 0f
            x < 0.5f -> smoothstep(0.22f, 0.5f, x)
            x < 0.72f -> 1f
            else -> 1f - smoothstep(0.72f, 1f, x)
        }
        return nearC + (farC - nearC) * k
    }

    fun velocity(t: Float): Float = (centre(t + 0.02f) - centre(t - 0.02f)) / 0.04f

    fun docked(t: Float, near: Boolean): Boolean = abs(centre(t) - (if (near) nearC else farC)) < 0.08f

    fun onRaft(t: Float, s: Float): Boolean = abs(s - centre(t)) <= halfLen + 0.02f

    /** a is relative to the gap centre (danger is absolute-s based). */
    override fun danger(t: Float, a: Float, d: Float, y: Float, hit: Hit?): Int {
        val s = s0 + a
        if (s <= seg.s0 + 0.12f || s >= seg.s1 - 0.12f) return Danger.SAFE
        return if (onRaft(t, s)) Danger.SAFE else (hit?.set(Danger.FALL, 0f, 0f, 0f) ?: Danger.FALL)
    }

    override fun conveyor(t: Float, a: Float, d: Float): Float = if (onRaft(t, s0 + a)) velocity(t) else 0f
    override fun telegraph(t: Float) = 0f
    override fun state(t: Float, out: FloatArray) { out[0] = centre(t); out[1] = velocity(t) }
    override fun zones() = emptyList<Zone>()
}

/** Boss chaser: rising tide or a giant bouncing duck that sweeps up from behind. */
class Chaser(val duck: Boolean, val startS: Float, val delay: Float, val speed: Float, val endS: Float) {
    fun s(t: Float): Float = if (t < delay) startS else min(endS, startS + (t - delay) * speed)
}

/** Storm gusts that sweep every deck at once. */
class Storm(val period: Float, val phase: Float, val strength: Float) {
    fun gust(t: Float): Float {
        val x = frac((t + phase) / period)
        return if (x in 0.7f..0.88f) 1f else 0f
    }
    fun warn(t: Float): Float {
        val x = frac((t + phase) / period)
        return if (x in 0.55f..0.7f) (x - 0.55f) / 0.15f else if (x in 0.7f..0.88f) 1f else 0f
    }
    fun dir(t: Float): Float = if (((t + phase) / period).toInt() % 2 == 0) 1f else -1f
}

/** Pick-ups placed along the course (course-space). */
class CoinSpot(val s: Float, val d: Float, val h: Float, val value: Int = 1) { var taken = false }

/** Static info for set pieces the renderer needs (zip cable, swing pivot, climb wall...). */
object SetPieceMath {
    /** Swing: returns rope angle (rad) for progress p (0..1) between -theta0..theta0, pendulum-like easing. */
    fun swingAngle(p: Float, theta0: Float): Float = -theta0 + 2f * theta0 * ((1f - kotlin.math.cos(p * Math.PI.toFloat())) / 2f)

    fun jumpHeight(p: Float, apex: Float, hops: Int): Float =
        if (hops <= 1) apex * 4f * p * (1f - p) else apex * abs(kotlin.math.sin(p * Math.PI.toFloat() * hops)) * (0.85f + 0.15f * max(0f, 1f - p))
}
