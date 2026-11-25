package com.ngstudio.funrace.game.sim

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The real size and placement of every set piece, shared by the simulation, the track builder,
 * the race renderer and the glitch-audit test, so racers are always drawn on, under or hanging
 * from something that is actually there.
 */
object SetPieceGeometry {
    // ---------------------------------------------------------------- deck
    /** Deck slab thickness below the walking surface (TrackBuilder skirts run 0.02 .. -0.62). */
    const val DECK_DEPTH = 0.62f

    /** Visual body: radius and height of a racer for "is it inside something solid" checks. */
    const val BODY_R = 0.2f
    const val BODY_H = 1.55f

    /**
     * Along-course range of deck replaced by a moving hazard piece (flip tiles, tilting deck), or
     * null. The track builder leaves these ranges open; the hazard draws its own floor there.
     */
    fun deckHole(h: Hazard): FloatArray? = when (h) {
        is Trapdoor -> floatArrayOf(h.s0 - h.tileLen, h.s0 + h.tileLen)
        is Tilt -> floatArrayOf(h.zoneStart, h.zoneEnd)
        else -> null
    }

    /** Flip tiles swing down about their outer edge by up to this angle when fully open. */
    const val TILE_OPEN_ANGLE = 1.45f

    /** Height of a flip tile's top under (a, d) (a measured from the hazard centre). */
    fun tileSurface(h: Trapdoor, t: Float, a: Float, d: Float): Float {
        val open = h.open(t, if (a < 0f) 0 else 1, if (d < 0f) 0 else 1)
        return -max(0f, h.hw - abs(d)) * sin(open * TILE_OPEN_ANGLE)
    }

    /** Height of the tilting deck's top at lateral [d] (course space), roll > 0 lowers +d. */
    fun tiltSurface(h: Tilt, t: Float, d: Float): Float = -d * sin(h.roll(t))

    // ---------------------------------------------------------------- raft
    /** Raft model is 2.6 m square; it is stretched sideways so every lane rides on it. */
    const val RAFT_MODEL = 2.6f
    fun raftHalfWidth(hw: Float): Float = hw + 0.2f

    // ---------------------------------------------------------------- zipline
    /** Cable height above the course line, and the handle bar the racer grips (0.78 below it). */
    const val ZIP_CABLE_H = 2.42f
    const val ZIP_BAR_H = ZIP_CABLE_H - 0.78f

    /** One cable per lane group: left, centre, right. */
    fun zipCables(hw: Float): FloatArray = floatArrayOf(-hw * 0.6f, 0f, hw * 0.6f)

    fun nearestCable(hw: Float, d: Float): Float {
        var best = 0f
        for (c in zipCables(hw)) if (abs(c - d) < abs(best - d)) best = c
        return best
    }

    // ---------------------------------------------------------------- monkey bars
    const val MONKEY_BAR_H = 1.62f
    fun monkeyHalfWidth(hw: Float): Float = hw - 0.3f

    // ---------------------------------------------------------------- trampolines
    /** Trampoline model: rim radius 1.0 (+0.18 tube); stretched sideways to span the lanes. */
    const val TRAMPOLINE_MODEL_R = 1.18f
    fun trampolineHalfWidth(hw: Float): Float = hw

    // ---------------------------------------------------------------- water slide
    /** Channel radius for a slide segment of half-width [hw] (matches TrackBuilder.slide). */
    fun slideRadius(hw: Float): Float = hw + 0.25f

    /** Height of the slide's inner surface above the course line at lateral [d]. */
    fun slideSurface(hw: Float, d: Float): Float {
        val r = slideRadius(hw)
        val dd = d.coerceIn(-r * 0.96f, r * 0.96f)
        return r - sqrt(r * r - dd * dd) - 0.05f
    }
}
