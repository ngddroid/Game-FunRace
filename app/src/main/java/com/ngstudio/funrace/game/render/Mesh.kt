package com.ngstudio.funrace.game.render

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Per-vertex material bytes (packed into an Int as 4 unsigned bytes):
 *  - tint: 0 none, 1 tintA/primary, 2 tintB/secondary, 3 skin, 4 shoe (stored as tint*51)
 *  - gloss 0..1, emissive 0..1, wobble 0..1 (inflatable jiggle / vegetation sway weight)
 */
object Mat {
    const val NONE = 0
    const val TINT_A = 1
    const val TINT_B = 2
    const val SKIN = 3
    const val SHOE = 4

    fun of(tint: Int = NONE, gloss: Float = 0f, emissive: Float = 0f, wobble: Float = 0f): Int {
        val t = (tint * 51).coerceIn(0, 255)
        val g = (gloss * 255f).roundToInt().coerceIn(0, 255)
        val e = (emissive * 255f).roundToInt().coerceIn(0, 255)
        val w = (wobble * 255f).roundToInt().coerceIn(0, 255)
        return t or (g shl 8) or (e shl 16) or (w shl 24)
    }

    val MATTE = of()
    val VINYL = of(gloss = 0.55f)
    val VINYL_A = of(TINT_A, gloss = 0.55f)
    val VINYL_B = of(TINT_B, gloss = 0.55f)
}

/**
 * CPU-side geometry in the engine's vertex format. Used both for procedural meshes (track,
 * water, decals) and for meshes loaded from `.dmesh` assets, which can be merged into big
 * static chunks so a level draws in a handful of calls.
 */
class MeshData(initialVerts: Int = 256, initialIdx: Int = 512, val skinned: Boolean = false) {
    var pos = FloatArray(initialVerts * 3)
    var nrm = ByteArray(initialVerts * 4)
    var col = ByteArray(initialVerts * 4)
    var mat = ByteArray(initialVerts * 4)
    var joints = if (skinned) ByteArray(initialVerts * 4) else ByteArray(0)
    var weights = if (skinned) ByteArray(initialVerts * 4) else ByteArray(0)
    var vCount = 0
    var idx = IntArray(initialIdx)
    var iCount = 0
    val minB = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
    val maxB = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)

    private fun grow(n: Int) {
        if (vCount + n <= pos.size / 3) return
        var cap = pos.size / 3
        while (cap < vCount + n) cap *= 2
        pos = pos.copyOf(cap * 3); nrm = nrm.copyOf(cap * 4); col = col.copyOf(cap * 4); mat = mat.copyOf(cap * 4)
        if (skinned) { joints = joints.copyOf(cap * 4); weights = weights.copyOf(cap * 4) }
    }

    private fun growIdx(n: Int) {
        if (iCount + n <= idx.size) return
        var cap = idx.size
        while (cap < iCount + n) cap *= 2
        idx = idx.copyOf(cap)
    }

    /** Adds a vertex. [color] is 0xRRGGBB, [ao] 0..1 (1 = fully lit), [m] from [Mat.of]. */
    fun v(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, color: Int, ao: Float = 1f, m: Int = 0): Int {
        grow(1)
        val i = vCount
        pos[i * 3] = x; pos[i * 3 + 1] = y; pos[i * 3 + 2] = z
        val l = sqrt(nx * nx + ny * ny + nz * nz).let { if (it < 1e-6f) 1f else it }
        nrm[i * 4] = (nx / l * 127f).roundToInt().toByte()
        nrm[i * 4 + 1] = (ny / l * 127f).roundToInt().toByte()
        nrm[i * 4 + 2] = (nz / l * 127f).roundToInt().toByte()
        nrm[i * 4 + 3] = 0
        col[i * 4] = ((color shr 16) and 0xFF).toByte()
        col[i * 4 + 1] = ((color shr 8) and 0xFF).toByte()
        col[i * 4 + 2] = (color and 0xFF).toByte()
        col[i * 4 + 3] = (ao.coerceIn(0f, 1f) * 255f).roundToInt().toByte()
        mat[i * 4] = (m and 0xFF).toByte()
        mat[i * 4 + 1] = ((m shr 8) and 0xFF).toByte()
        mat[i * 4 + 2] = ((m shr 16) and 0xFF).toByte()
        mat[i * 4 + 3] = ((m shr 24) and 0xFF).toByte()
        if (x < minB[0]) minB[0] = x; if (y < minB[1]) minB[1] = y; if (z < minB[2]) minB[2] = z
        if (x > maxB[0]) maxB[0] = x; if (y > maxB[1]) maxB[1] = y; if (z > maxB[2]) maxB[2] = z
        vCount++
        return i
    }

    fun tri(a: Int, b: Int, c: Int) {
        growIdx(3)
        idx[iCount++] = a; idx[iCount++] = b; idx[iCount++] = c
    }

    fun quad(a: Int, b: Int, c: Int, d: Int) { tri(a, b, c); tri(a, c, d) }

    /** Flat-shaded quad from 4 corners (counter-clockwise seen from the front). */
    fun quadFlat(
        ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float,
        color: Int, ao: Float = 1f, m: Int = 0, aoBottom: Float = ao,
    ) {
        val ux = bx - ax; val uy = by - ay; val uz = bz - az
        val wx = dx - ax; val wy = dy - ay; val wz = dz - az
        val nx = uy * wz - uz * wy; val ny = uz * wx - ux * wz; val nz = ux * wy - uy * wx
        val i0 = v(ax, ay, az, nx, ny, nz, color, aoBottom, m)
        val i1 = v(bx, by, bz, nx, ny, nz, color, aoBottom, m)
        val i2 = v(cx, cy, cz, nx, ny, nz, color, ao, m)
        val i3 = v(dx, dy, dz, nx, ny, nz, color, ao, m)
        quad(i0, i1, i2, i3)
    }

    /**
     * Appends [src] transformed by the column-major 4x4 [m] (rotation+translation+uniform or
     * non-uniform scale). Optional colour/material overrides recolour tinted parts at bake time.
     */
    fun append(src: MeshData, m: FloatArray, tintA: Int = -1, tintB: Int = -1, swayWeight: Float = -1f) {
        grow(src.vCount)
        growIdx(src.iCount)
        val base = vCount
        // inverse-transpose approximation: divide by squared axis scales
        val sx2 = m[0] * m[0] + m[1] * m[1] + m[2] * m[2]
        val sy2 = m[4] * m[4] + m[5] * m[5] + m[6] * m[6]
        val sz2 = m[8] * m[8] + m[9] * m[9] + m[10] * m[10]
        for (i in 0 until src.vCount) {
            val x = src.pos[i * 3]; val y = src.pos[i * 3 + 1]; val z = src.pos[i * 3 + 2]
            val wx = m[0] * x + m[4] * y + m[8] * z + m[12]
            val wy = m[1] * x + m[5] * y + m[9] * z + m[13]
            val wz = m[2] * x + m[6] * y + m[10] * z + m[14]
            val nx0 = src.nrm[i * 4] / 127f / sx2; val ny0 = src.nrm[i * 4 + 1] / 127f / sy2; val nz0 = src.nrm[i * 4 + 2] / 127f / sz2
            val nx = m[0] * nx0 + m[4] * ny0 + m[8] * nz0
            val ny = m[1] * nx0 + m[5] * ny0 + m[9] * nz0
            val nz = m[2] * nx0 + m[6] * ny0 + m[10] * nz0
            val o = i * 4
            var r = src.col[o].toInt() and 0xFF; var g = src.col[o + 1].toInt() and 0xFF; var b = src.col[o + 2].toInt() and 0xFF
            val ao = (src.col[o + 3].toInt() and 0xFF) / 255f
            var mm = (src.mat[o].toInt() and 0xFF) or ((src.mat[o + 1].toInt() and 0xFF) shl 8) or
                ((src.mat[o + 2].toInt() and 0xFF) shl 16) or ((src.mat[o + 3].toInt() and 0xFF) shl 24)
            val sel = ((mm and 0xFF) + 25) / 51
            val tint = if (sel == Mat.TINT_A) tintA else if (sel == Mat.TINT_B) tintB else -1
            if (tint >= 0) {
                r = r * ((tint shr 16) and 0xFF) / 255; g = g * ((tint shr 8) and 0xFF) / 255; b = b * (tint and 0xFF) / 255
                mm = mm and 0xFFFFFF00.toInt()
            }
            if (swayWeight >= 0f) {
                // wobble byte doubles as sway weight for merged vegetation; scale by height
                val w = ((mm ushr 24) and 0xFF) / 255f
                if (w > 0f) mm = (mm and 0x00FFFFFF) or (((w * swayWeight).coerceIn(0f, 1f) * 255).roundToInt() shl 24)
            }
            v(wx, wy, wz, nx, ny, nz, (r shl 16) or (g shl 8) or b, ao, mm)
        }
        for (k in 0 until src.iCount) idx[iCount++] = src.idx[k] + base
    }

    val isEmpty get() = iCount == 0

    fun stride() = if (skinned) 32 else 24

    /** Interleaves into the GPU layout (see [Mesh]). */
    fun pack(): ByteBuffer {
        val stride = stride()
        val bb = ByteBuffer.allocateDirect(vCount * stride).order(ByteOrder.nativeOrder())
        for (i in 0 until vCount) {
            bb.putFloat(pos[i * 3]); bb.putFloat(pos[i * 3 + 1]); bb.putFloat(pos[i * 3 + 2])
            bb.put(nrm, i * 4, 4)
            bb.put(col, i * 4, 4)
            bb.put(mat, i * 4, 4)
            if (skinned) { bb.put(joints, i * 4, 4); bb.put(weights, i * 4, 4) }
        }
        bb.position(0)
        return bb
    }

    fun radius(): Float {
        var r = 0f
        for (i in 0 until vCount) {
            val x = pos[i * 3]; val y = pos[i * 3 + 1]; val z = pos[i * 3 + 2]
            r = max(r, x * x + y * y + z * z)
        }
        return sqrt(r)
    }

    fun centerX() = (minB[0] + maxB[0]) * 0.5f
    fun centerY() = (minB[1] + maxB[1]) * 0.5f
    fun centerZ() = (minB[2] + maxB[2]) * 0.5f
    fun boundRadius(): Float {
        val dx = maxB[0] - minB[0]; val dy = maxB[1] - minB[1]; val dz = maxB[2] - minB[2]
        return sqrt(dx * dx + dy * dy + dz * dz) * 0.5f
    }

    fun clearBounds() {
        minB[0] = Float.MAX_VALUE; minB[1] = Float.MAX_VALUE; minB[2] = Float.MAX_VALUE
        maxB[0] = -Float.MAX_VALUE; maxB[1] = -Float.MAX_VALUE; maxB[2] = -Float.MAX_VALUE
    }

    fun recomputeBounds() {
        clearBounds()
        for (i in 0 until vCount) {
            for (a in 0..2) {
                val p = pos[i * 3 + a]
                minB[a] = min(minB[a], p); maxB[a] = max(maxB[a], p)
            }
        }
    }
}

/**
 * GPU mesh. Vertex layout (stride 24, or 32 when skinned):
 *  loc0 pos 3f | loc1 normal 4 x snorm8 | loc2 colour rgb + AO 4 x unorm8 | loc9 material 4 x unorm8
 *  [loc11 joints 4 x uint8 | loc12 weights 4 x unorm8]
 * Instance attributes (always present, divisor 1): loc3..6 model matrix, loc7 tintA (rgb, alpha),
 * loc8 tintB (rgb, gloss bonus), loc10 params (emissive, sway, wobble amplitude, wobble phase).
 */
class Mesh(data: MeshData) {
    val vao = Gl.genVao()
    private val vbo = Gl.genBuffer()
    private val ibo = Gl.genBuffer()
    val indexCount = data.iCount
    private val useInt = data.vCount > 65535
    val instanceVbo = Gl.genBuffer()
    val skinned = data.skinned
    val cx = data.centerX(); val cy = data.centerY(); val cz = data.centerZ()
    val radius = data.boundRadius()

    init {
        GLES30.glBindVertexArray(vao)
        val vb = data.pack()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vb.capacity(), vb, GLES30.GL_STATIC_DRAW)
        val stride = data.stride()
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_BYTE, true, stride, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_UNSIGNED_BYTE, true, stride, 16)
        GLES30.glEnableVertexAttribArray(9)
        GLES30.glVertexAttribPointer(9, 4, GLES30.GL_UNSIGNED_BYTE, true, stride, 20)
        if (data.skinned) {
            GLES30.glEnableVertexAttribArray(11)
            GLES30.glVertexAttribIPointer(11, 4, GLES30.GL_UNSIGNED_BYTE, stride, 24)
            GLES30.glEnableVertexAttribArray(12)
            GLES30.glVertexAttribPointer(12, 4, GLES30.GL_UNSIGNED_BYTE, true, stride, 28)
        }

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        if (useInt) {
            val ib = Gl.intBuffer(data.iCount); ib.put(data.idx, 0, data.iCount).position(0)
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, data.iCount * 4, ib, GLES30.GL_STATIC_DRAW)
        } else {
            val ib = Gl.shortBuffer(data.iCount)
            for (k in 0 until data.iCount) ib.put(data.idx[k].toShort())
            ib.position(0)
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, data.iCount * 2, ib, GLES30.GL_STATIC_DRAW)
        }

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, INSTANCE_FLOATS * 4, identityInstance(), GLES30.GL_DYNAMIC_DRAW)
        val istride = INSTANCE_FLOATS * 4
        for (c in 0 until 4) {
            GLES30.glEnableVertexAttribArray(3 + c)
            GLES30.glVertexAttribPointer(3 + c, 4, GLES30.GL_FLOAT, false, istride, c * 16)
            GLES30.glVertexAttribDivisor(3 + c, 1)
        }
        GLES30.glEnableVertexAttribArray(7)
        GLES30.glVertexAttribPointer(7, 4, GLES30.GL_FLOAT, false, istride, 64)
        GLES30.glVertexAttribDivisor(7, 1)
        GLES30.glEnableVertexAttribArray(8)
        GLES30.glVertexAttribPointer(8, 4, GLES30.GL_FLOAT, false, istride, 80)
        GLES30.glVertexAttribDivisor(8, 1)
        GLES30.glEnableVertexAttribArray(10)
        GLES30.glVertexAttribPointer(10, 4, GLES30.GL_FLOAT, false, istride, 96)
        GLES30.glVertexAttribDivisor(10, 1)
        GLES30.glBindVertexArray(0)
    }

    private var instUploaded = 1

    /** Draws a single instance using whatever is in the instance buffer (identity by default). */
    fun draw() {
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, indexCount, if (useInt) GLES30.GL_UNSIGNED_INT else GLES30.GL_UNSIGNED_SHORT, 0, 1)
    }

    fun drawInstanced(count: Int) {
        if (count <= 0) return
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, indexCount, if (useInt) GLES30.GL_UNSIGNED_INT else GLES30.GL_UNSIGNED_SHORT, 0, count)
    }

    fun uploadInstances(buf: FloatBuffer, count: Int, capacityBytes: Int) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, capacityBytes, null, GLES30.GL_STREAM_DRAW)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, count * INSTANCE_FLOATS * 4, buf)
        instUploaded = count
    }

    /** Restores the single identity instance (used after the mesh was drawn as a batch). */
    fun resetIdentity() {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, INSTANCE_FLOATS * 4, identityInstance(), GLES30.GL_DYNAMIC_DRAW)
    }

    fun release() {
        GLES30.glDeleteBuffers(3, intArrayOf(vbo, ibo, instanceVbo), 0)
        GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
    }

    companion object {
        /** mat4 (16) + tintA (4) + tintB (4) + params (4) */
        const val INSTANCE_FLOATS = 28

        private fun identityInstance(): FloatBuffer {
            val b = Gl.floatBuffer(INSTANCE_FLOATS)
            b.put(floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 0f, 0f, 0f, 0f, 0f))
            b.position(0)
            return b
        }
    }
}

/** Collects instances of one mesh for a frame, then uploads and draws them in one call. */
class InstanceBatch(val mesh: Mesh, initial: Int = 32) {
    var data = FloatArray(initial * Mesh.INSTANCE_FLOATS)
    var count = 0
    private var buf: FloatBuffer = Gl.floatBuffer(initial * Mesh.INSTANCE_FLOATS)
    private var uploaded = -1

    fun clear() { count = 0; uploaded = -1 }

    /** Reserves a slot and returns its float offset (matrix +0, tintA +16, tintB +20, params +24). */
    fun next(): Int {
        if ((count + 1) * Mesh.INSTANCE_FLOATS > data.size) data = data.copyOf(data.size * 2)
        val o = count * Mesh.INSTANCE_FLOATS
        count++
        // defaults
        data[o + 16] = 1f; data[o + 17] = 1f; data[o + 18] = 1f; data[o + 19] = 1f
        data[o + 20] = 1f; data[o + 21] = 1f; data[o + 22] = 1f; data[o + 23] = 0f
        data[o + 24] = 0f; data[o + 25] = 0f; data[o + 26] = 0f; data[o + 27] = 0f
        return o
    }

    fun tintA(o: Int, rgb: Int, alpha: Float = 1f) {
        data[o + 16] = ((rgb shr 16) and 0xFF) / 255f; data[o + 17] = ((rgb shr 8) and 0xFF) / 255f
        data[o + 18] = (rgb and 0xFF) / 255f; data[o + 19] = alpha
    }

    fun tintB(o: Int, rgb: Int, glossBonus: Float = 0f) {
        data[o + 20] = ((rgb shr 16) and 0xFF) / 255f; data[o + 21] = ((rgb shr 8) and 0xFF) / 255f
        data[o + 22] = (rgb and 0xFF) / 255f; data[o + 23] = glossBonus
    }

    fun params(o: Int, emissive: Float = 0f, sway: Float = 0f, wobble: Float = 0f, phase: Float = 0f) {
        data[o + 24] = emissive; data[o + 25] = sway; data[o + 26] = wobble; data[o + 27] = phase
    }

    fun upload() {
        if (uploaded == count) return
        val need = count * Mesh.INSTANCE_FLOATS
        if (buf.capacity() < need) buf = Gl.floatBuffer(data.size)
        buf.position(0)
        buf.put(data, 0, need)
        buf.position(0)
        mesh.uploadInstances(buf, count, buf.capacity() * 4)
        uploaded = count
    }

    fun draw() {
        if (count == 0) return
        upload()
        mesh.drawInstanced(count)
    }
}
