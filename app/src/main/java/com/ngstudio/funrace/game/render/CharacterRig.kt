package com.ngstudio.funrace.game.render

import android.opengl.GLES30
import com.ngstudio.funrace.core.Mat4
import com.ngstudio.funrace.core.PI_F
import com.ngstudio.funrace.core.Quat
import com.ngstudio.funrace.core.TAU
import com.ngstudio.funrace.core.clamp01
import com.ngstudio.funrace.data.Bones
import com.ngstudio.funrace.data.Catalog
import com.ngstudio.funrace.data.CharacterDef
import com.ngstudio.funrace.data.HatDef
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Local bone rotations (quaternions in the parent's rest frame) plus a hip offset. */
class Pose {
    val q = FloatArray(Bones.COUNT * 4).also { for (i in 0 until Bones.COUNT) it[i * 4 + 3] = 1f }
    var ox = 0f; var oy = 0f; var oz = 0f

    fun reset() {
        for (i in 0 until Bones.COUNT) { q[i * 4] = 0f; q[i * 4 + 1] = 0f; q[i * 4 + 2] = 0f; q[i * 4 + 3] = 1f }
        ox = 0f; oy = 0f; oz = 0f
    }

    /** Sets bone [b] to an XYZ euler rotation (applied X then Y then Z in the bone's rest frame). */
    fun rot(b: Int, x: Float, y: Float = 0f, z: Float = 0f) {
        val cx = cos(x * 0.5f); val sx = sin(x * 0.5f)
        val cy = cos(y * 0.5f); val sy = sin(y * 0.5f)
        val cz = cos(z * 0.5f); val sz = sin(z * 0.5f)
        // q = qz * qy * qx
        val o = b * 4
        q[o] = cz * cy * sx - sz * sy * cx
        q[o + 1] = cz * sy * cx + sz * cy * sx
        q[o + 2] = sz * cy * cx - cz * sy * sx
        q[o + 3] = cz * cy * cx + sz * sy * sx
    }

    fun copyFrom(p: Pose) { System.arraycopy(p.q, 0, q, 0, q.size); ox = p.ox; oy = p.oy; oz = p.oz }

    /** this = lerp(this, other, t) with per-bone nlerp. */
    fun blendTo(p: Pose, t: Float) {
        if (t <= 0f) return
        if (t >= 1f) { copyFrom(p); return }
        for (i in 0 until Bones.COUNT) {
            val o = i * 4
            var dot = q[o] * p.q[o] + q[o + 1] * p.q[o + 1] + q[o + 2] * p.q[o + 2] + q[o + 3] * p.q[o + 3]
            val s = if (dot < 0f) -1f else 1f
            var x = q[o] + (p.q[o] * s - q[o]) * t
            var y = q[o + 1] + (p.q[o + 1] * s - q[o + 1]) * t
            var z = q[o + 2] + (p.q[o + 2] * s - q[o + 2]) * t
            var w = q[o + 3] + (p.q[o + 3] * s - q[o + 3]) * t
            val l = sqrt(x * x + y * y + z * z + w * w).let { if (it < 1e-6f) 1f else it }
            x /= l; y /= l; z /= l; w /= l
            q[o] = x; q[o + 1] = y; q[o + 2] = z; q[o + 3] = w
            dot = 0f
        }
        ox += (p.ox - ox) * t; oy += (p.oy - oy) * t; oz += (p.oz - oz) * t
    }
}

enum class Expression { HAPPY, FOCUS, SHOCK, DIZZY, SAD, JOY }

enum class AnimState { IDLE, RUN, CLIMB, HANG, ZIP, SLIDE, AIR, TUMBLE, FALL, SWING, BALANCE, STUN, DANCE, SAD, WAVE, CHEER, SIT, PUSHED }

/**
 * Procedural animation library. Conventions: character faces +Z, +X is the character's left.
 * Negative X rotation swings a limb forward; +Z raises the left arm sideways (-Z the right).
 */
object Anim {
    private fun s(x: Float) = sin(x)
    private fun c(x: Float) = cos(x)

    fun idle(p: Pose, t: Float, seed: Float) {
        p.reset()
        val br = s(t * 2.2f + seed)
        p.oy = br * 0.006f
        p.rot(Bones.CHEST, -0.04f + br * 0.025f)
        val look = s(t * 0.45f + seed * 3f)
        p.rot(Bones.HEAD, 0.05f + s(t * 0.9f + seed) * 0.03f, look * 0.35f * smoothPulse(t * 0.3f + seed), s(t * 0.7f) * 0.04f)
        p.rot(Bones.ARM_L, 0.05f + br * 0.03f, 0f, -0.08f + br * 0.02f)
        p.rot(Bones.ARM_R, 0.05f + br * 0.03f, 0f, 0.08f - br * 0.02f)
        p.rot(Bones.HAND_L, -0.15f); p.rot(Bones.HAND_R, -0.15f)
        p.rot(Bones.THIGH_L, 0f, 0f, 0.03f); p.rot(Bones.THIGH_R, 0f, 0f, -0.03f)
    }

    private fun smoothPulse(x: Float): Float { val v = s(x); return v * v * v }

    /** [phase] in radians (two steps per 2π), [k] 0..1 stride intensity, [lean] extra forward lean. */
    fun run(p: Pose, phase: Float, k: Float, lean: Float = 0f) {
        p.reset()
        val sp = s(phase); val cp = c(phase)
        val a = 0.95f * k
        val thL = -sp * a; val thR = sp * a
        val shL = (0.2f + 1.25f * max(0f, cp)) * k
        val shR = (0.2f + 1.25f * max(0f, -cp)) * k
        p.rot(Bones.THIGH_L, thL - 0.1f * k, 0f, 0.04f)
        p.rot(Bones.THIGH_R, thR - 0.1f * k, 0f, -0.04f)
        p.rot(Bones.SHIN_L, shL); p.rot(Bones.SHIN_R, shR)
        p.rot(Bones.FOOT_L, -(thL + shL) * 0.45f); p.rot(Bones.FOOT_R, -(thR + shR) * 0.45f)
        p.rot(Bones.ARM_L, sp * 0.9f * k, 0f, -0.12f - 0.1f * k)
        p.rot(Bones.ARM_R, -sp * 0.9f * k, 0f, 0.12f + 0.1f * k)
        p.rot(Bones.HAND_L, -0.9f * k - 0.2f); p.rot(Bones.HAND_R, -0.9f * k - 0.2f)
        p.rot(Bones.HIPS, 0f, -sp * 0.18f * k, 0f)
        p.rot(Bones.CHEST, 0.16f * k + lean, sp * 0.22f * k, 0f)
        p.rot(Bones.HEAD, -0.1f * k - lean * 0.6f, -sp * 0.08f * k, 0f)
        p.oy = abs(sp) * 0.055f * k - 0.02f * k
    }

    fun climb(p: Pose, phase: Float, moving: Float) {
        p.reset()
        val sp = s(phase) * moving
        p.rot(Bones.ARM_L, -2.5f - sp * 0.45f, 0f, -0.25f)
        p.rot(Bones.ARM_R, -2.5f + sp * 0.45f, 0f, 0.25f)
        p.rot(Bones.HAND_L, -0.4f + sp * 0.3f); p.rot(Bones.HAND_R, -0.4f - sp * 0.3f)
        p.rot(Bones.THIGH_L, -0.6f + sp * 0.5f, 0f, 0.15f); p.rot(Bones.THIGH_R, -0.6f - sp * 0.5f, 0f, -0.15f)
        p.rot(Bones.SHIN_L, 1.0f - sp * 0.3f); p.rot(Bones.SHIN_R, 1.0f + sp * 0.3f)
        p.rot(Bones.CHEST, -0.1f)
        p.rot(Bones.HEAD, -0.35f)
    }

    fun hang(p: Pose, t: Float, swing: Float) {
        p.reset()
        val sw = s(t * 3.1f) * swing
        p.rot(Bones.ARM_L, -3.0f, 0f, -0.18f); p.rot(Bones.ARM_R, -3.0f, 0f, 0.18f)
        p.rot(Bones.HAND_L, -0.2f); p.rot(Bones.HAND_R, -0.2f)
        p.rot(Bones.THIGH_L, -0.25f + sw * 0.4f, 0f, 0.08f); p.rot(Bones.THIGH_R, -0.15f - sw * 0.4f, 0f, -0.08f)
        p.rot(Bones.SHIN_L, 0.5f + sw * 0.2f); p.rot(Bones.SHIN_R, 0.45f - sw * 0.2f)
        p.rot(Bones.HEAD, -0.15f)
    }

    fun monkey(p: Pose, phase: Float, moving: Float) {
        p.reset()
        val sp = s(phase) * moving
        p.rot(Bones.ARM_L, -3.0f + max(0f, sp) * 0.6f, 0f, -0.2f)
        p.rot(Bones.ARM_R, -3.0f + max(0f, -sp) * 0.6f, 0f, 0.2f)
        p.rot(Bones.THIGH_L, -0.3f + sp * 0.5f); p.rot(Bones.THIGH_R, -0.3f - sp * 0.5f)
        p.rot(Bones.SHIN_L, 0.6f); p.rot(Bones.SHIN_R, 0.6f)
        p.rot(Bones.CHEST, sp * 0.1f, sp * 0.15f, 0f)
        p.rot(Bones.HEAD, -0.2f)
    }

    fun zip(p: Pose, t: Float) {
        hang(p, t, 0.3f)
        p.rot(Bones.THIGH_L, -1.2f, 0f, 0.2f); p.rot(Bones.THIGH_R, -1.1f, 0f, -0.2f)
        p.rot(Bones.SHIN_L, 0.3f); p.rot(Bones.SHIN_R, 0.4f)
    }

    fun slide(p: Pose, t: Float) {
        p.reset()
        val w = s(t * 6f)
        p.rot(Bones.HIPS, 0.15f)
        p.rot(Bones.THIGH_L, -1.45f, 0f, 0.12f); p.rot(Bones.THIGH_R, -1.45f, 0f, -0.12f)
        p.rot(Bones.SHIN_L, 0.15f); p.rot(Bones.SHIN_R, 0.15f)
        p.rot(Bones.ARM_L, -2.2f + w * 0.3f, 0f, 0.5f); p.rot(Bones.ARM_R, -2.2f - w * 0.3f, 0f, -0.5f)
        p.rot(Bones.CHEST, -0.35f)
        p.rot(Bones.HEAD, 0.15f)
        p.oy = -0.28f
    }

    fun air(p: Pose, t: Float, up: Float) {
        p.reset()
        val f = s(t * 14f) * 0.3f
        p.rot(Bones.ARM_L, -2.4f + f, 0f, 0.6f); p.rot(Bones.ARM_R, -2.4f - f, 0f, -0.6f)
        p.rot(Bones.THIGH_L, -0.9f * up - 0.2f); p.rot(Bones.THIGH_R, -0.4f * up)
        p.rot(Bones.SHIN_L, 1.3f * up + 0.2f); p.rot(Bones.SHIN_R, 0.8f * up + 0.4f)
        p.rot(Bones.CHEST, 0.1f)
        p.rot(Bones.HEAD, -0.2f)
    }

    fun tumble(p: Pose, t: Float) {
        p.reset()
        p.rot(Bones.ARM_L, s(t * 17f) * 1.2f - 1.0f, 0f, 0.9f + s(t * 11f) * 0.5f)
        p.rot(Bones.ARM_R, c(t * 15f) * 1.2f - 1.0f, 0f, -0.9f - s(t * 13f) * 0.5f)
        p.rot(Bones.THIGH_L, s(t * 13f) * 0.8f - 0.4f, 0f, 0.3f)
        p.rot(Bones.THIGH_R, c(t * 12f) * 0.8f - 0.4f, 0f, -0.3f)
        p.rot(Bones.SHIN_L, 0.8f); p.rot(Bones.SHIN_R, 0.6f)
        p.rot(Bones.HEAD, s(t * 9f) * 0.3f, 0f, c(t * 8f) * 0.3f)
    }

    fun fall(p: Pose, t: Float) {
        p.reset()
        // windmilling arms, bicycling legs
        p.rot(Bones.ARM_L, -t * 16f, 0f, 1.0f); p.rot(Bones.ARM_R, -t * 16f + PI_F, 0f, -1.0f)
        p.rot(Bones.THIGH_L, s(t * 18f) * 0.9f - 0.5f); p.rot(Bones.THIGH_R, -s(t * 18f) * 0.9f - 0.5f)
        p.rot(Bones.SHIN_L, 1.0f + c(t * 18f) * 0.4f); p.rot(Bones.SHIN_R, 1.0f - c(t * 18f) * 0.4f)
        p.rot(Bones.HEAD, -0.4f)
    }

    fun swing(p: Pose, t: Float, angle: Float) {
        p.reset()
        p.rot(Bones.ARM_L, -3.05f, 0f, -0.1f); p.rot(Bones.ARM_R, -3.05f, 0f, 0.1f)
        p.rot(Bones.THIGH_L, -0.7f - angle * 0.5f); p.rot(Bones.THIGH_R, -0.7f - angle * 0.5f)
        p.rot(Bones.SHIN_L, 0.5f + angle * 0.3f); p.rot(Bones.SHIN_R, 0.5f + angle * 0.3f)
        p.rot(Bones.CHEST, -0.1f)
        p.rot(Bones.HEAD, -0.2f + angle * 0.2f)
    }

    fun balance(p: Pose, phase: Float, k: Float, t: Float) {
        run(p, phase, k * 0.6f)
        val w = s(t * 3.3f)
        p.rot(Bones.ARM_L, w * 0.2f, 0f, 1.35f + w * 0.25f)
        p.rot(Bones.ARM_R, -w * 0.2f, 0f, -1.35f + w * 0.25f)
        p.rot(Bones.HAND_L, 0f, 0f, 0.2f); p.rot(Bones.HAND_R, 0f, 0f, -0.2f)
        p.rot(Bones.CHEST, 0.05f, 0f, w * 0.12f)
    }

    fun stun(p: Pose, t: Float) {
        idle(p, t, 0f)
        val w = s(t * 6f)
        p.rot(Bones.HEAD, 0.1f, w * 0.25f, c(t * 6f) * 0.25f)
        p.rot(Bones.CHEST, 0.05f, 0f, w * 0.1f)
        p.rot(Bones.ARM_L, 0.3f, 0f, 0.35f); p.rot(Bones.ARM_R, 0.3f, 0f, -0.35f)
    }

    fun pushed(p: Pose, t: Float) {
        p.reset()
        p.rot(Bones.CHEST, -0.45f)
        p.rot(Bones.HEAD, -0.3f)
        p.rot(Bones.ARM_L, -1.6f, 0f, 1.2f); p.rot(Bones.ARM_R, -1.6f, 0f, -1.2f)
        p.rot(Bones.THIGH_L, -0.4f); p.rot(Bones.THIGH_R, 0.3f)
        p.rot(Bones.SHIN_L, 0.5f); p.rot(Bones.SHIN_R, 0.3f)
    }

    fun sad(p: Pose, t: Float) {
        p.reset()
        p.rot(Bones.HEAD, 0.45f + s(t * 1.5f) * 0.05f, s(t * 0.6f) * 0.2f, 0f)
        p.rot(Bones.CHEST, 0.2f)
        p.rot(Bones.ARM_L, 0.1f, 0f, -0.15f); p.rot(Bones.ARM_R, 0.1f, 0f, 0.15f)
        p.rot(Bones.HAND_L, -0.1f); p.rot(Bones.HAND_R, -0.1f)
        p.oy = -0.02f
    }

    fun wave(p: Pose, t: Float) {
        idle(p, t, 0f)
        p.rot(Bones.ARM_R, -0.2f, 0f, -2.5f + s(t * 9f) * 0.35f)
        p.rot(Bones.HAND_R, 0f, 0f, -0.4f + s(t * 9f) * 0.4f)
        p.rot(Bones.HEAD, 0f, -0.2f, 0.1f)
    }

    fun sit(p: Pose, t: Float) {
        p.reset()
        p.rot(Bones.THIGH_L, -1.5f, 0f, 0.15f); p.rot(Bones.THIGH_R, -1.5f, 0f, -0.15f)
        p.rot(Bones.SHIN_L, 1.45f); p.rot(Bones.SHIN_R, 1.45f)
        p.rot(Bones.ARM_L, -0.6f, 0f, -0.1f); p.rot(Bones.ARM_R, -0.6f, 0f, 0.1f)
        p.rot(Bones.HEAD, s(t * 1.2f) * 0.08f, s(t * 0.5f) * 0.25f, 0f)
        p.oy = -0.2f
    }

    /**
     * Victory dances. Returns extra model transform hints via [out]: [0] = hop height,
     * [1] = extra yaw, [2] = extra pitch (for flips).
     */
    fun dance(p: Pose, id: String, t: Float, out: FloatArray) {
        out[0] = 0f; out[1] = 0f; out[2] = 0f
        p.reset()
        when (id) {
            "wave" -> {
                p.rot(Bones.ARM_L, 0.1f, 0f, -0.1f)
                p.rot(Bones.ARM_R, -0.2f, 0f, -2.6f + s(t * 8f) * 0.4f)
                p.rot(Bones.HAND_R, 0f, 0f, -0.5f + s(t * 8f) * 0.5f)
                p.rot(Bones.CHEST, 0f, 0f, s(t * 4f) * 0.08f)
                p.rot(Bones.HEAD, 0f, -0.15f, s(t * 4f) * 0.12f)
                out[0] = abs(s(t * 4f)) * 0.06f
            }
            "cheer" -> {
                // arms pump up in a V, a touch behind the head, so the hands never cover the face
                val pump = abs(s(t * 6f))
                p.rot(Bones.ARM_L, 0.15f, 0f, 1.9f + pump * 0.35f); p.rot(Bones.ARM_R, 0.15f, 0f, -1.9f - pump * 0.35f)
                p.rot(Bones.HAND_L, 0f, 0f, 0.35f * pump); p.rot(Bones.HAND_R, 0f, 0f, -0.35f * pump)
                p.rot(Bones.HEAD, -0.15f)
                p.rot(Bones.THIGH_L, -0.1f, 0f, 0.15f); p.rot(Bones.THIGH_R, -0.1f, 0f, -0.15f)
                out[0] = pump * 0.18f
            }
            "spin" -> {
                p.rot(Bones.ARM_L, 0f, 0f, 1.45f); p.rot(Bones.ARM_R, 0f, 0f, -1.45f)
                p.rot(Bones.HEAD, -0.15f)
                p.rot(Bones.THIGH_L, -0.3f); p.rot(Bones.SHIN_L, 0.9f)
                out[1] = t * 7f
                out[0] = 0.03f + abs(s(t * 3.5f)) * 0.05f
            }
            "flex" -> {
                val f = s(t * 3f) * 0.5f + 0.5f
                p.rot(Bones.ARM_L, -0.1f, 0f, 1.5f); p.rot(Bones.ARM_R, -0.1f, 0f, -1.5f)
                p.rot(Bones.HAND_L, -1.6f - f * 0.6f, 0f, 0f); p.rot(Bones.HAND_R, -1.6f - f * 0.6f, 0f, 0f)
                p.rot(Bones.CHEST, -0.1f, s(t * 1.5f) * 0.3f, 0f)
                p.rot(Bones.HEAD, -0.15f, s(t * 1.5f) * 0.3f, 0f)
                p.rot(Bones.THIGH_L, 0f, 0f, 0.25f); p.rot(Bones.THIGH_R, 0f, 0f, -0.25f)
            }
            "jacks" -> {
                val j = abs(s(t * 5f))
                p.rot(Bones.ARM_L, 0f, 0f, 0.2f + j * 2.6f); p.rot(Bones.ARM_R, 0f, 0f, -0.2f - j * 2.6f)
                p.rot(Bones.THIGH_L, 0f, 0f, j * 0.45f); p.rot(Bones.THIGH_R, 0f, 0f, -j * 0.45f)
                out[0] = j * 0.12f
            }
            "robot" -> {
                val step = floor(t * 3f)
                val a = if (step.toInt() % 2 == 0) 1f else -1f
                p.rot(Bones.ARM_L, -1.57f * (if (a > 0) 1f else 0.2f), 0f, 0f)
                p.rot(Bones.HAND_L, -1.57f * (if (a > 0) 0f else 1f))
                p.rot(Bones.ARM_R, -1.57f * (if (a < 0) 1f else 0.2f), 0f, 0f)
                p.rot(Bones.HAND_R, -1.57f * (if (a < 0) 0f else 1f))
                p.rot(Bones.HEAD, 0f, 0.4f * a, 0f)
                p.rot(Bones.CHEST, 0f, 0.15f * a, 0f)
            }
            "chicken" -> {
                val f = s(t * 10f)
                p.rot(Bones.ARM_L, 0.2f, 0f, 0.5f + f * 0.45f); p.rot(Bones.ARM_R, 0.2f, 0f, -0.5f - f * 0.45f)
                p.rot(Bones.HAND_L, -2.2f); p.rot(Bones.HAND_R, -2.2f)
                p.rot(Bones.HEAD, 0.1f + s(t * 5f) * 0.25f)
                p.rot(Bones.CHEST, 0.2f)
                p.rot(Bones.THIGH_L, -0.3f * max(0f, s(t * 5f)), 0f, 0.15f); p.rot(Bones.THIGH_R, -0.3f * max(0f, -s(t * 5f)), 0f, -0.15f)
                p.rot(Bones.SHIN_L, 0.5f * max(0f, s(t * 5f))); p.rot(Bones.SHIN_R, 0.5f * max(0f, -s(t * 5f)))
                p.oy = -0.04f
            }
            "sway" -> {
                val w = s(t * 3f)
                p.rot(Bones.HIPS, 0f, 0f, w * 0.18f)
                p.rot(Bones.CHEST, 0f, 0f, -w * 0.25f)
                p.rot(Bones.ARM_L, -0.3f, 0f, 2.4f + w * 0.3f); p.rot(Bones.ARM_R, -0.3f, 0f, -2.4f + w * 0.3f)
                p.rot(Bones.HEAD, 0f, 0f, w * 0.2f)
                p.ox = w * 0.05f
            }
            "backflip" -> {
                val cyc = t % 2.2f
                val f = clamp01((cyc - 0.35f) / 0.9f)
                val air = if (cyc in 0.35f..1.25f) s(f * PI_F) else 0f
                out[0] = air * 0.9f
                out[2] = -f * TAU
                if (cyc < 0.35f || cyc > 1.25f) {
                    val crouch = if (cyc < 0.35f) cyc / 0.35f else max(0f, 1f - (cyc - 1.25f) * 4f)
                    p.rot(Bones.THIGH_L, -0.8f * crouch); p.rot(Bones.THIGH_R, -0.8f * crouch)
                    p.rot(Bones.SHIN_L, 1.4f * crouch); p.rot(Bones.SHIN_R, 1.4f * crouch)
                    p.rot(Bones.ARM_L, 0.6f * crouch, 0f, 0.3f + (1f - crouch) * 2f); p.rot(Bones.ARM_R, 0.6f * crouch, 0f, -0.3f - (1f - crouch) * 2f)
                    p.oy = -0.12f * crouch
                } else {
                    p.rot(Bones.THIGH_L, -1.6f); p.rot(Bones.THIGH_R, -1.6f)
                    p.rot(Bones.SHIN_L, 1.8f); p.rot(Bones.SHIN_R, 1.8f)
                    p.rot(Bones.ARM_L, -1.2f); p.rot(Bones.ARM_R, -1.2f)
                }
            }
            else -> { // "hop"
                val h = abs(s(t * 5f))
                p.rot(Bones.ARM_L, -0.2f, 0f, 1.8f + s(t * 10f) * 0.6f); p.rot(Bones.ARM_R, -0.2f, 0f, -1.8f + s(t * 10f) * 0.6f)
                p.rot(Bones.THIGH_L, -0.35f * h); p.rot(Bones.THIGH_R, -0.35f * h)
                p.rot(Bones.SHIN_L, 0.7f * h); p.rot(Bones.SHIN_R, 0.7f * h)
                p.rot(Bones.HEAD, -0.25f)
                out[0] = h * 0.3f
            }
        }
    }
}

/** A rigid part attached to a bone (authored in rest-pose model space). */
class LookPart(val mesh: String, val bone: Int, val color: Int, val color2: Int)

/**
 * How a skinned body is fitted: its skeleton comes from its own mesh; head parts (face, hats, hair)
 * are authored on the Dashling head and mapped onto this body's head by [headFit]; whole-body pose
 * offsets (crouch, bob) scale with leg length by [rootScale].
 */
class BodySpec(val mesh: String, val headFit: FloatArray?, val rootScale: Float)

object Bodies {
    val DASHLING = BodySpec(Catalog.BODY_DASHLING, null, 1f)

    /** Dashling head (centre y 1.0, r 0.33) -> human head (centre y 1.235, r ~0.2). Must match m_kids.py. */
    val HUMAN = BodySpec(Catalog.BODY_HUMAN, fit(0f, 1f, 0f, 0f, 1.235f, 0f, 0.606f, 0.636f, 0.59f), 0.62f / 0.42f)

    fun of(mesh: String): BodySpec = if (mesh == HUMAN.mesh) HUMAN else DASHLING

    /** T(dst) * S(s) * T(-src), column-major. */
    private fun fit(sx0: Float, sy0: Float, sz0: Float, dx: Float, dy: Float, dz: Float, kx: Float, ky: Float, kz: Float): FloatArray {
        val m = FloatArray(16)
        m[0] = kx; m[5] = ky; m[10] = kz; m[15] = 1f
        m[12] = dx - kx * sx0; m[13] = dy - ky * sy0; m[14] = dz - kz * sz0
        return m
    }
}

/** Everything that defines how one Dashling looks (resolved from the catalog). */
class Look(
    val primary: Int, val secondary: Int, val skin: Int, val shoe: Int,
    val accessories: List<LookPart>,
    val hat: LookPart?,
    /** Skinned body mesh (all bodies share the Dashling skeleton). */
    val body: String = Catalog.BODY_DASHLING,
) {
    companion object {
        fun of(c: CharacterDef, h: HatDef?): Look = Look(
            c.primary, c.secondary, c.skin, c.shoe,
            c.accessories.map { LookPart(it.mesh, it.bone, it.color, it.color2) },
            if (h == null || h.mesh.isEmpty()) null else LookPart(h.mesh, Bones.HEAD, h.color, h.color2),
            c.body,
        )
    }
}

/**
 * Draws skinned Dashlings. Computes bone matrices from a [Pose] + model transform and renders
 * body (with inverted-hull outline), costume accessories, hat and an expressive face.
 */
class CharacterDrawer(private val res: RenderResources) {
    private var spec = Bodies.DASHLING
    private var bones = ModelLibrary.get(Catalog.BODY_DASHLING).bones
    private val boneCount = Bones.COUNT
    private val global = FloatArray(boneCount * 16)
    val skinMats = FloatArray(boneCount * 16)
    private val tmp = FloatArray(16)
    private val tmp2 = FloatArray(16)
    private val local = FloatArray(16)
    private val tints = FloatArray(15)
    val modelM = FloatArray(16)
    private val q = FloatArray(4)

    private val faceParts = listOf("face_eye_l", "face_eye_r", "face_mouth_smile", "face_mouth_open", "face_brow_l", "face_brow_r", "face_blush_l", "face_blush_r")
    private val centers = HashMap<String, FloatArray>()

    init {
        for (n in faceParts) {
            val md = ModelLibrary.get(n).mesh
            centers[n] = floatArrayOf(md.centerX(), md.centerY(), md.centerZ())
        }
    }

    /** Builds [modelM] from position, yaw/pitch/roll and squash scale. */
    fun setModel(x: Float, y: Float, z: Float, yaw: Float, pitch: Float, roll: Float, sx: Float, sy: Float, sz: Float, scale: Float = 1f) {
        Quat.euler(q, yaw, pitch, roll)
        Mat4.trs(modelM, 0, x, y, z, q[0], q[1], q[2], q[3], sx * scale, sy * scale, sz * scale)
    }

    /** Computes skinning matrices (model space) from [pose] for the skeleton of [body]. */
    fun computeSkin(pose: Pose, body: String = Catalog.BODY_DASHLING) {
        spec = Bodies.of(body)
        bones = ModelLibrary.get(spec.mesh).bones
        val rs = spec.rootScale
        if (bones.size < boneCount) { for (i in 0 until boneCount) Mat4.identity(tmp).also { System.arraycopy(tmp, 0, skinMats, i * 16, 16) }; return }
        for (i in 0 until boneCount) {
            val b = bones[i]
            val o = i * 4
            if (b.parent < 0) {
                Mat4.trs(local, 0, b.hx + pose.ox * rs, b.hy + pose.oy * rs, b.hz + pose.oz * rs, pose.q[o], pose.q[o + 1], pose.q[o + 2], pose.q[o + 3], 1f, 1f, 1f)
                System.arraycopy(local, 0, global, i * 16, 16)
            } else {
                val p = bones[b.parent]
                Mat4.trs(local, 0, b.hx - p.hx, b.hy - p.hy, b.hz - p.hz, pose.q[o], pose.q[o + 1], pose.q[o + 2], pose.q[o + 3], 1f, 1f, 1f)
                System.arraycopy(global, b.parent * 16, tmp, 0, 16)
                mulInto(global, i * 16, tmp, local)
            }
            // skin = global * T(-head)
            System.arraycopy(global, i * 16, tmp, 0, 16)
            Mat4.identity(tmp2); tmp2[12] = -b.hx; tmp2[13] = -b.hy; tmp2[14] = -b.hz
            mulInto(skinMats, i * 16, tmp, tmp2)
        }
    }

    private fun mulInto(out: FloatArray, off: Int, a: FloatArray, b: FloatArray) {
        for (c in 0 until 4) {
            val b0 = b[c * 4]; val b1 = b[c * 4 + 1]; val b2 = b[c * 4 + 2]; val b3 = b[c * 4 + 3]
            out[off + c * 4] = a[0] * b0 + a[4] * b1 + a[8] * b2 + a[12] * b3
            out[off + c * 4 + 1] = a[1] * b0 + a[5] * b1 + a[9] * b2 + a[13] * b3
            out[off + c * 4 + 2] = a[2] * b0 + a[6] * b1 + a[10] * b2 + a[14] * b3
            out[off + c * 4 + 3] = a[3] * b0 + a[7] * b1 + a[11] * b2 + a[15] * b3
        }
    }

    private val fitTmp = FloatArray(16)
    private val boneTmp = FloatArray(16)

    /** World position of bone [b]'s joint in the current pose (after [setModel] + [computeSkin]). */
    fun boneWorld(b: Int, out: FloatArray) {
        System.arraycopy(skinMats, b * 16, tmp, 0, 16)
        mulInto(boneTmp, 0, modelM, tmp)
        val bn = bones[b]
        out[0] = boneTmp[0] * bn.hx + boneTmp[4] * bn.hy + boneTmp[8] * bn.hz + boneTmp[12]
        out[1] = boneTmp[1] * bn.hx + boneTmp[5] * bn.hy + boneTmp[9] * bn.hz + boneTmp[13]
        out[2] = boneTmp[2] * bn.hx + boneTmp[6] * bn.hy + boneTmp[10] * bn.hz + boneTmp[14]
    }

    /** World matrix of a rigidly attached part on bone [b] (authored in rest-pose model space). */
    fun attachMatrix(b: Int, out: FloatArray) {
        System.arraycopy(skinMats, b * 16, tmp, 0, 16)
        val fit = if (b == Bones.HEAD) spec.headFit else null
        if (fit == null) { mulInto(out, 0, modelM, tmp); return }
        // head parts are authored on the Dashling head: fit them onto this body's head first
        mulInto(fitTmp, 0, tmp, fit)
        mulInto(out, 0, modelM, fitTmp)
    }

    private val partM = FloatArray(16)
    private val exprM = FloatArray(16)
    private val world = FloatArray(16)

    /** Queues accessory, hat and face instances for the current skin state. */
    fun queueParts(look: Look, expr: Expression, blink: Float, alpha: Float = 1f, outline: Boolean = true) {
        for (a in look.accessories) {
            attachMatrix(a.bone, world)
            Inst.addMatrix(res.meshes.batch(a.mesh), world, a.color, a.color2, if (outline) alpha else -alpha)
        }
        look.hat?.let { h ->
            attachMatrix(h.bone, world)
            Inst.addMatrix(res.meshes.batch(h.mesh), world, h.color, h.color2, if (outline) alpha else -alpha)
        }
        // face: expression transforms around each part's rest centre
        attachMatrix(com.ngstudio.funrace.data.Bones.HEAD, world)
        val eyeScaleY = when (expr) { Expression.SHOCK -> 1.25f; Expression.JOY -> 0.28f; Expression.DIZZY -> 0.5f; else -> 1f } * (1f - blink * 0.9f)
        val eyeScaleX = when (expr) { Expression.SHOCK -> 1.18f; Expression.JOY -> 1.1f; else -> 1f }
        face("face_eye_l", eyeScaleX, eyeScaleY, 0f, 0f, alpha)
        face("face_eye_r", eyeScaleX, eyeScaleY, 0f, 0f, alpha)
        when (expr) {
            Expression.SHOCK, Expression.JOY -> face("face_mouth_open", if (expr == Expression.JOY) 1.1f else 0.85f, if (expr == Expression.JOY) 0.9f else 1.15f, 0f, 0f, alpha)
            Expression.SAD -> face("face_mouth_smile", 0.8f, -0.7f, 0f, -0.01f, alpha)
            Expression.FOCUS -> face("face_mouth_smile", 0.65f, 0.45f, 0f, 0f, alpha)
            Expression.DIZZY -> face("face_mouth_open", 0.6f, 0.5f, 0f, 0f, alpha)
            Expression.HAPPY -> face("face_mouth_smile", 1f, 1f, 0f, 0f, alpha)
        }
        val browY = when (expr) { Expression.FOCUS -> -0.018f; Expression.SHOCK -> 0.03f; Expression.SAD -> 0.01f; else -> 0f }
        val browRot = when (expr) { Expression.FOCUS -> 0.35f; Expression.SAD -> -0.35f; else -> 0f }
        face("face_brow_l", 1f, 1f, browRot, browY, alpha)
        face("face_brow_r", 1f, 1f, -browRot, browY, alpha)
        face("face_blush_l", 1f, 1f, 0f, 0f, alpha)
        face("face_blush_r", 1f, 1f, 0f, 0f, alpha)
    }

    private fun face(name: String, sx: Float, sy: Float, roll: Float, dy: Float, alpha: Float) {
        val c = centers[name] ?: return
        // exprM = T(c + dy) * Rz(roll) * S(sx, sy, 1) * T(-c)
        Quat.euler(q, 0f, 0f, roll)
        Mat4.trs(partM, 0, c[0], c[1] + dy, c[2], q[0], q[1], q[2], q[3], sx, sy, 1f)
        Mat4.identity(tmp2); tmp2[12] = -c[0]; tmp2[13] = -c[1]; tmp2[14] = -c[2]
        mulInto(exprM, 0, partM, tmp2)
        mulInto(tmp, 0, world, exprM)
        Inst.addMatrix(res.meshes.batch(name), tmp, 0xFFFFFF, 0xFFFFFF, -alpha)
    }

    private fun setTints(look: Look) {
        LightRig.rgb(look.primary, f3); tints[3] = f3[0]; tints[4] = f3[1]; tints[5] = f3[2]
        LightRig.rgb(look.secondary, f3); tints[6] = f3[0]; tints[7] = f3[1]; tints[8] = f3[2]
        LightRig.rgb(look.skin, f3); tints[9] = f3[0]; tints[10] = f3[1]; tints[11] = f3[2]
        LightRig.rgb(look.shoe, f3); tints[12] = f3[0]; tints[13] = f3[1]; tints[14] = f3[2]
    }
    private val f3 = FloatArray(3)

    /** Draws the skinned body; the skin/outline program must already have common uniforms set. */
    fun drawBody(p: ShaderProgram, look: Look, flash: Float = 0f, alpha: Float = 1f, fadeNear: Float = 0f) {
        p.f("uFadeNear", fadeNear)
        p.mat4("uModel", modelM)
        p.mat4Array("uBones", skinMats, boneCount)
        setTints(look)
        p.vec3Array("uTints", tints, 5)
        p.f("uFlash", flash)
        p.f("uAlpha", alpha)
        res.meshes.mesh(look.body).draw()
    }

    fun drawBodyDepth(p: ShaderProgram, look: Look) {
        p.mat4("uModel", modelM)
        p.mat4Array("uBones", skinMats, boneCount)
        res.meshes.mesh(look.body).draw()
    }
}

/** Shared skinned-draw helpers so every scene renders characters the same way. */
object CharacterPass {
    fun beginSkin(res: RenderResources, cam: Camera, rig: LightRig, waterY: Float) {
        res.common(res.skin, cam, rig, waterY)
    }

    fun beginSkinOutline(res: RenderResources, cam: Camera, rig: LightRig, waterY: Float, width: Float = 0.018f) {
        res.common(res.skinOutline, cam, rig, waterY)
        res.skinOutline.f("uOutline", width)
        res.skinOutline.vec3("uOutlineColor", rig.outline)
        GLES30.glCullFace(GLES30.GL_FRONT)
    }

    fun endOutline() { GLES30.glCullFace(GLES30.GL_BACK) }

    fun beginSkinDepth(res: RenderResources) {
        res.skinDepth.use()
        res.skinDepth.mat4("uViewProj", res.shadow.lightViewProj)
        res.skinDepth.mat4("uLightVP", res.shadow.lightViewProj)
    }

    @Suppress("unused")
    fun minOf(a: Float, b: Float) = min(a, b)
}
