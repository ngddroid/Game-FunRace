package com.ngstudio.funrace.game.render

import com.ngstudio.funrace.core.TAU
import kotlin.math.cos
import kotlin.math.sin

/** Procedural primitives written straight into [MeshData] (runtime geometry and fallbacks). */
object Shapes {
    fun placeholder(): MeshData = MeshData(24, 36).also { box(it, 0f, 0.5f, 0f, 0.5f, 0.5f, 0.5f, 0xFF00FF) }

    /** Axis-aligned box centred at (cx,cy,cz) with half extents. */
    fun box(md: MeshData, cx: Float, cy: Float, cz: Float, hx: Float, hy: Float, hz: Float, color: Int, m: Int = 0, aoBottom: Float = 0.75f) {
        val x0 = cx - hx; val x1 = cx + hx; val y0 = cy - hy; val y1 = cy + hy; val z0 = cz - hz; val z1 = cz + hz
        md.quadFlat(x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, color, 1f, m, 1f) // top
        md.quadFlat(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, color, aoBottom, m, aoBottom) // bottom
        md.quadFlat(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, color, 1f, m, aoBottom) // +z
        md.quadFlat(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, color, 1f, m, aoBottom) // -z
        md.quadFlat(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, color, 1f, m, aoBottom) // +x
        md.quadFlat(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, color, 1f, m, aoBottom) // -x
    }

    /** UV sphere. */
    fun sphere(md: MeshData, cx: Float, cy: Float, cz: Float, r: Float, color: Int, m: Int = 0, seg: Int = 16, rings: Int = 10, sy: Float = 1f) {
        val base = md.vCount
        for (j in 0..rings) {
            val v = j.toFloat() / rings
            val phi = v * Math.PI.toFloat()
            val y = cos(phi); val rr = sin(phi)
            for (i in 0..seg) {
                val u = i.toFloat() / seg * TAU
                val x = cos(u) * rr; val z = sin(u) * rr
                val ao = 0.72f + 0.28f * (y * 0.5f + 0.5f)
                md.v(cx + x * r, cy + y * r * sy, cz + z * r, x, y / sy, z, color, ao, m)
            }
        }
        for (j in 0 until rings) for (i in 0 until seg) {
            val a = base + j * (seg + 1) + i
            val b = a + seg + 1
            md.tri(a, a + 1, b); md.tri(a + 1, b + 1, b)
        }
    }

    /** Vertical cylinder (y from y0 to y1), capped. */
    fun cylinder(md: MeshData, cx: Float, y0: Float, cz: Float, r: Float, y1: Float, color: Int, m: Int = 0, seg: Int = 16, capTop: Boolean = true, capBottom: Boolean = false, aoBottom: Float = 0.7f) {
        val base = md.vCount
        for (i in 0..seg) {
            val a = i.toFloat() / seg * TAU
            val x = cos(a); val z = sin(a)
            md.v(cx + x * r, y0, cz + z * r, x, 0f, z, color, aoBottom, m)
            md.v(cx + x * r, y1, cz + z * r, x, 0f, z, color, 1f, m)
        }
        for (i in 0 until seg) {
            val a = base + i * 2
            md.quad(a, a + 1, a + 3, a + 2)
        }
        if (capTop) disk(md, cx, y1, cz, r, color, m, seg, true)
        if (capBottom) disk(md, cx, y0, cz, r, color, m, seg, false)
    }

    fun disk(md: MeshData, cx: Float, y: Float, cz: Float, r: Float, color: Int, m: Int = 0, seg: Int = 16, up: Boolean = true, ao: Float = 1f) {
        val ny = if (up) 1f else -1f
        val c = md.v(cx, y, cz, 0f, ny, 0f, color, ao, m)
        val base = md.vCount
        for (i in 0..seg) {
            val a = i.toFloat() / seg * TAU
            md.v(cx + cos(a) * r, y, cz + sin(a) * r, 0f, ny, 0f, color, ao, m)
        }
        for (i in 0 until seg) if (up) md.tri(c, base + i + 1, base + i) else md.tri(c, base + i, base + i + 1)
    }

    /** Torus around the Y axis. */
    fun torus(md: MeshData, cx: Float, cy: Float, cz: Float, R: Float, r: Float, color: Int, m: Int = 0, seg: Int = 24, side: Int = 10) {
        val base = md.vCount
        for (i in 0..seg) {
            val u = i.toFloat() / seg * TAU
            val cu = cos(u); val su = sin(u)
            for (j in 0..side) {
                val v = j.toFloat() / side * TAU
                val cv = cos(v); val sv = sin(v)
                val nx = cu * cv; val ny = sv; val nz = su * cv
                md.v(cx + (R + r * cv) * cu, cy + r * sv, cz + (R + r * cv) * su, nx, ny, nz, color, 0.8f + 0.2f * (sv * 0.5f + 0.5f), m)
            }
        }
        for (i in 0 until seg) for (j in 0 until side) {
            val a = base + i * (side + 1) + j
            val b = a + side + 1
            md.tri(a, a + 1, b); md.tri(a + 1, b + 1, b)
        }
    }

    /** Flat ground quad grid (used for water). [n] cells per side, spanning [-size/2, size/2]. */
    fun grid(n: Int, size: Float): MeshData {
        val md = MeshData((n + 1) * (n + 1), n * n * 6)
        val h = size / 2f
        for (j in 0..n) for (i in 0..n) {
            // denser near the centre: quadratic spacing keeps detail where the camera looks
            val fx = i.toFloat() / n * 2f - 1f
            val fz = j.toFloat() / n * 2f - 1f
            val x = fx * kotlin.math.abs(fx) * h
            val z = fz * kotlin.math.abs(fz) * h
            md.v(x, 0f, z, 0f, 1f, 0f, 0xFFFFFF)
        }
        for (j in 0 until n) for (i in 0 until n) {
            val a = j * (n + 1) + i
            md.tri(a, a + n + 1, a + 1); md.tri(a + 1, a + n + 1, a + n + 2)
        }
        return md
    }
}
