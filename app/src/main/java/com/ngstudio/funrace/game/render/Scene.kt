package com.ngstudio.funrace.game.render

import com.ngstudio.funrace.core.Mat4
import com.ngstudio.funrace.core.Quat

/** A renderable world that lives on the GL thread. */
interface Scene {
    fun update(dt: Float)
    fun render()
    fun release()
}

/** Helpers to push instances with TRS transforms. */
object Inst {
    private val q = FloatArray(4)

    fun add(
        b: InstanceBatch, x: Float, y: Float, z: Float,
        yaw: Float = 0f, pitch: Float = 0f, roll: Float = 0f,
        sx: Float = 1f, sy: Float = sx, sz: Float = sx,
        tintA: Int = 0xFFFFFF, tintB: Int = 0xFFFFFF, alpha: Float = 1f,
        emissive: Float = 0f, wobble: Float = 0f, phase: Float = 0f, gloss: Float = 0f, sway: Float = 0f,
    ): Int {
        val o = b.next()
        Quat.euler(q, yaw, pitch, roll)
        Mat4.trs(b.data, o, x, y, z, q[0], q[1], q[2], q[3], sx, sy, sz)
        b.tintA(o, tintA, alpha)
        b.tintB(o, tintB, gloss)
        b.params(o, emissive, sway, wobble, phase)
        return o
    }

    fun addQuat(
        b: InstanceBatch, x: Float, y: Float, z: Float, rot: FloatArray,
        sx: Float = 1f, sy: Float = sx, sz: Float = sx,
        tintA: Int = 0xFFFFFF, tintB: Int = 0xFFFFFF, alpha: Float = 1f,
        emissive: Float = 0f, wobble: Float = 0f, phase: Float = 0f, gloss: Float = 0f,
    ): Int {
        val o = b.next()
        Mat4.trs(b.data, o, x, y, z, rot[0], rot[1], rot[2], rot[3], sx, sy, sz)
        b.tintA(o, tintA, alpha)
        b.tintB(o, tintB, gloss)
        b.params(o, emissive, 0f, wobble, phase)
        return o
    }

    /** Pushes an instance with an explicit column-major matrix. */
    fun addMatrix(b: InstanceBatch, m: FloatArray, tintA: Int = 0xFFFFFF, tintB: Int = 0xFFFFFF, alpha: Float = 1f, emissive: Float = 0f, wobble: Float = 0f, phase: Float = 0f): Int {
        val o = b.next()
        System.arraycopy(m, 0, b.data, o, 16)
        b.tintA(o, tintA, alpha)
        b.tintB(o, tintB, 0f)
        b.params(o, emissive, 0f, wobble, phase)
        return o
    }
}
