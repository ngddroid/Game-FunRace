package com.ngstudio.funrace.game.sim

import com.ngstudio.funrace.core.DEG2RAD
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** How racers move through a stretch of course. */
enum class SegKind {
    START, DECK, TURN, RAMP, FINISH,
    JUMP, TRAMPOLINE, ZIP, SWING, RAFT, MONKEY, BEAM, CLIMB, SLIDE,
}

/** Whether a segment has a walkable deck under the racers. */
val SegKind.hasDeck: Boolean get() = when (this) {
    SegKind.START, SegKind.DECK, SegKind.TURN, SegKind.RAMP, SegKind.FINISH, SegKind.BEAM, SegKind.SLIDE -> true
    else -> false
}

class Segment(
    val index: Int,
    val kind: SegKind,
    val s0: Float,
    val s1: Float,
    val width: Float,
    val railL: Boolean,
    val railR: Boolean,
    /** Kind-specific numbers (gap drop, swing pivot height, raft speed...). */
    val p0: Float = 0f,
    val p1: Float = 0f,
    val p2: Float = 0f,
) {
    val length get() = s1 - s0
    val halfWidth get() = width / 2f
    fun contains(s: Float) = s >= s0 && s < s1
}

/**
 * The course centreline, sampled every [STEP] metres. Each sample stores position, a
 * horizontal heading (yaw, 0 = +Z) and the track up/forward slope. Racers live in course space:
 * distance [s] along the line and lateral offset [d] (positive = racer's right).
 */
class Course(val segments: List<Segment>, private val px: FloatArray, private val py: FloatArray, private val pz: FloatArray, private val yaw: FloatArray) {
    val count = px.size
    val length: Float = (count - 1) * STEP
    val finishS: Float = segments.first { it.kind == SegKind.FINISH }.s0 + 2f
    val startS: Float = segments.first { it.kind == SegKind.START }.s0 + 3f

    companion object {
        const val STEP = 0.25f
        const val WATER_Y = 0f
    }

    fun segmentAt(s: Float): Segment {
        // binary search
        var lo = 0; var hi = segments.size - 1
        val c = s.coerceIn(0f, length)
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (segments[mid].s0 <= c) lo = mid else hi = mid - 1
        }
        return segments[lo]
    }

    private fun idx(s: Float): Int = (s / STEP).toInt().coerceIn(0, count - 2)

    /** Writes the centreline point at s into out[0..2]. */
    fun point(s: Float, out: FloatArray) {
        val c = s.coerceIn(0f, length)
        val i = idx(c)
        val t = (c - i * STEP) / STEP
        out[0] = px[i] + (px[i + 1] - px[i]) * t
        out[1] = py[i] + (py[i + 1] - py[i]) * t
        out[2] = pz[i] + (pz[i + 1] - pz[i]) * t
    }

    fun yawAt(s: Float): Float {
        val c = s.coerceIn(0f, length)
        val i = idx(c)
        val t = (c - i * STEP) / STEP
        return yaw[i] + (yaw[i + 1] - yaw[i]) * t
    }

    fun heightAt(s: Float): Float {
        val c = s.coerceIn(0f, length)
        val i = idx(c)
        val t = (c - i * STEP) / STEP
        return py[i] + (py[i + 1] - py[i]) * t
    }

    /** Slope (dy/ds) at s. */
    fun slopeAt(s: Float): Float {
        val i = idx(s.coerceIn(0f, length))
        return (py[i + 1] - py[i]) / STEP
    }

    /** World position of course-space (s, d, h): h is height above the centreline. */
    fun world(s: Float, d: Float, h: Float, out: FloatArray) {
        point(s, out)
        val y = yawAt(s)
        // right vector = (-cos yaw, 0, sin yaw)
        out[0] += -cos(y) * d
        out[2] += sin(y) * d
        out[1] += h
    }

    fun rawX(i: Int) = px[i]
    fun rawY(i: Int) = py[i]
    fun rawZ(i: Int) = pz[i]
    fun rawYaw(i: Int) = yaw[i]

    /** Axis-aligned bounds of the centreline (x0, z0, x1, z1). */
    fun bounds(): FloatArray {
        var x0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        for (i in 0 until count) { x0 = min(x0, px[i]); x1 = max(x1, px[i]); z0 = min(z0, pz[i]); z1 = max(z1, pz[i]) }
        return floatArrayOf(x0, z0, x1, z1)
    }

    /** Minimum distance between non-adjacent parts of the centreline (self-intersection check). */
    fun minSelfClearance(skip: Float = 40f): Float {
        var best = Float.MAX_VALUE
        val stride = 8
        val skipN = (skip / STEP).toInt()
        var i = 0
        while (i < count) {
            var j = i + skipN
            while (j < count) {
                val dx = px[i] - px[j]; val dz = pz[i] - pz[j]
                val dd = sqrt(dx * dx + dz * dz)
                if (dd < best) best = dd
                j += stride
            }
            i += stride
        }
        return best
    }
}

/** Turtle that lays the centreline down segment by segment. */
class CourseBuilder(startY: Float = 3f) {
    private val xs = ArrayList<Float>()
    private val ys = ArrayList<Float>()
    private val zs = ArrayList<Float>()
    private val yaws = ArrayList<Float>()
    private val segs = ArrayList<Segment>()
    private var x = 0f
    private var y = startY
    private var z = 0f
    private var h = 0f // heading
    val s: Float get() = (xs.size - 1).coerceAtLeast(0) * Course.STEP
    val heading: Float get() = h
    val height: Float get() = y

    init { push() }

    private fun push() { xs += x; ys += y; zs += z; yaws += h }

    private fun advance(len: Float, dyTotal: Float = 0f, dYaw: Float = 0f, vertical: Boolean = false) {
        val n = max(1, (len / Course.STEP).toInt())
        val dy = dyTotal / n
        val dh = dYaw / n
        for (k in 0 until n) {
            if (vertical) {
                y += Course.STEP
            } else {
                h += dh
                x += sin(h) * Course.STEP
                z += cos(h) * Course.STEP
                y += dy
            }
            push()
        }
    }

    fun segment(kind: SegKind, len: Float, width: Float, railL: Boolean = false, railR: Boolean = false, rise: Float = 0f, turnDeg: Float = 0f, p0: Float = 0f, p1: Float = 0f, p2: Float = 0f): Segment {
        val s0 = s
        when (kind) {
            SegKind.CLIMB -> advance(len, vertical = true)
            else -> advance(len, rise, turnDeg * DEG2RAD)
        }
        val seg = Segment(segs.size, kind, s0, s, width, railL, railR, p0, p1, p2)
        segs += seg
        return seg
    }

    /** Slide: drops [drop] metres over [len] with an S-bend. */
    fun slide(len: Float, drop: Float, width: Float, bendDeg: Float): Segment {
        val s0 = s
        val n = max(1, (len / Course.STEP).toInt())
        for (k in 0 until n) {
            val t = (k + 0.5f) / n
            // ease the drop (steeper in the middle) and swing the heading left/right
            val dy = -drop * (sin(t * Math.PI.toFloat()) * Math.PI.toFloat() / 2f) / n
            h += bendDeg * DEG2RAD * cos(t * Math.PI.toFloat() * 2f) * (2f * Math.PI.toFloat()) / n * 0.5f
            x += sin(h) * Course.STEP
            z += cos(h) * Course.STEP
            y += dy
            push()
        }
        val seg = Segment(segs.size, SegKind.SLIDE, s0, s, width, true, true, drop)
        segs += seg
        return seg
    }

    fun build(): Course {
        val n = xs.size
        return Course(segs.toList(), FloatArray(n) { xs[it] }, FloatArray(n) { ys[it] }, FloatArray(n) { zs[it] }, FloatArray(n) { yaws[it] })
    }
}

internal fun frac(x: Float): Float = x - floor(x)

internal fun absMin(a: Float, b: Float) = if (abs(a) < abs(b)) a else b
