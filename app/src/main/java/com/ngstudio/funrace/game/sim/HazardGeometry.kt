package com.ngstudio.funrace.game.sim

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Where the moving parts of a hurdle are drawn, shared by HazardVisuals and the solidity test so
 * the picture and the collision rules can never drift apart.
 */
object HazardGeometry {
    class Glove(val springLen: Float, val gloveD: Float)

    /** Glove model (scaled 1.2): fist front 0.934 ahead of its origin, cuff back 0.12 behind. */
    private const val GLOVE_FRONT = 0.934f
    private const val GLOVE_BACK = 0.12f

    /**
     * Boxing glove at extension [e]: the glove's front sits exactly on the simulation's punch face
     * (hw - e); the spring fills the gap back to the side box (hidden inside the box when short).
     */
    fun glove(hw: Float, side: Float, e: Float): Glove {
        val front = hw - e
        val origin = front + GLOVE_FRONT
        return Glove(max(0.05f, hw - (origin + GLOVE_BACK)), side * origin)
    }

    /** Roller model's rounded tip reaches 0.119 of its length past x = 1. */
    private const val ROLLER_CAP = 0.119f

    /** Lateral position of a roller's outer end (model origin) so its tip sits exactly on the gap. */
    fun rollerOrigin(hw: Float, c: Float, side: Float): Float = side * (hw * (1f - c) + hw * (1f + ROLLER_CAP))

    /**
     * Visual pitch of the paddle-wheel model for the simulation's wheel angle. The sim measures
     * paddle angles from straight down toward +a; the model's paddle 0 points up, so mirror it.
     */
    fun paddleVisualPitch(angle: Float): Float = kotlin.math.PI.toFloat() - angle

    /** The paddle-wheel model's paddle i at angle a points radially along (0, cos a, sin a). */
    fun paddleAxisY(a: Float): Float = cos(a)
    fun paddleAxisZ(a: Float): Float = sin(a)
}
