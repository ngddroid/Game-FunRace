package com.ngstudio.funrace.game.render

import com.ngstudio.funrace.core.Mat4
import com.ngstudio.funrace.core.Quat
import com.ngstudio.funrace.core.Rng
import com.ngstudio.funrace.core.TAU
import com.ngstudio.funrace.game.sim.Course
import com.ngstudio.funrace.game.sim.SegKind
import com.ngstudio.funrace.game.sim.Segment
import com.ngstudio.funrace.game.sim.SetPieceGeometry
import com.ngstudio.funrace.level.RaceSetup
import com.ngstudio.funrace.level.World
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A static piece of the level (track or scenery) with a bounding sphere for culling. */
class Chunk(val mesh: Mesh, val cx: Float, val cy: Float, val cz: Float, val radius: Float, val shadow: Boolean)

/** Footprints used to paint the foam/shallows mask in the water shader. */
class Footprint(val x: Float, val z: Float, val r: Float)

/**
 * Turns a [RaceSetup] into static geometry: the inflatable track (decks, trims, rails, pillars,
 * slides, set-piece rigs) and the world's scenery, merged into culled chunks.
 */
class TrackBuilder(private val setup: RaceSetup, private val world: World) {
    private val course = setup.course
    private val chunkLen = 24f
    private val chunkCount = (course.length / chunkLen).toInt() + 1
    private val chunks = Array(chunkCount) { MeshData(2048, 4096) }
    private val sceneryChunks = ArrayList<MeshData>()
    val footprints = ArrayList<Footprint>()
    private val p = FloatArray(3)
    private val q = FloatArray(3)
    private val m = FloatArray(16)
    private val quat = FloatArray(4)
    private val rng = Rng(setup.spec.seed xor 0x1234567L)

    private fun chunkFor(s: Float) = chunks[(s / chunkLen).toInt().coerceIn(0, chunkCount - 1)]

    /** Deck ranges replaced by dynamic hazard pieces (trapdoors, tilting decks). */
    private val cutouts: List<FloatArray> = setup.hazards.mapNotNull { SetPieceGeometry.deckHole(it) }

    private fun cut(s: Float): Boolean { for (c in cutouts) if (s > c[0] && s < c[1]) return true; return false }

    fun build(): Pair<List<Chunk>, List<Chunk>> {
        for (seg in course.segments) buildSegment(seg)
        buildPillars()
        buildSetPieces()
        buildScenery()
        val track = ArrayList<Chunk>()
        for (md in chunks) if (!md.isEmpty) track += Chunk(Mesh(md), md.centerX(), md.centerY(), md.centerZ(), md.boundRadius(), true)
        val scen = ArrayList<Chunk>()
        for (md in sceneryChunks) if (!md.isEmpty) scen += Chunk(Mesh(md), md.centerX(), md.centerY(), md.centerZ(), md.boundRadius(), false)
        return track to scen
    }

    // ------------------------------------------------------------------------------- decks
    private fun w(s: Float, d: Float, h: Float, out: FloatArray) = course.world(s, d, h, out)

    private fun buildSegment(seg: Segment) {
        when (seg.kind) {
            SegKind.DECK, SegKind.TURN, SegKind.RAMP, SegKind.START, SegKind.FINISH -> deck(seg, seg.halfWidth, true)
            SegKind.BEAM -> deck(seg, seg.halfWidth, false)
            SegKind.SLIDE -> slide(seg)
            else -> Unit
        }
    }

    /** Clean alternating 1 m bands across the deck (subtle, so hazards and zones read first). */
    private fun stripeAt(s: Float): Boolean = floor(s / BAND).toInt() % 2 == 0

    private fun deck(seg: Segment, hw: Float, trims: Boolean) {
        // break points: every 0.5 m plus stripe boundaries for crisp colour changes
        val pts = ArrayList<Float>()
        var s = seg.s0
        while (s < seg.s1) {
            pts += s
            val nextStripe = (floor(s / BAND) + 1f) * BAND
            s = min(min(s + 0.5f, seg.s1), if (nextStripe > s + 0.01f) nextStripe else s + 0.5f)
        }
        pts += seg.s1
        val deckC = world.deck
        // soft cool-grey bands with just a hint of the world colour; no seams (one clean slab)
        val stripeC = mix(deckC, mix(world.stripe, 0xAFBBCB, 0.75f), 0.38f)
        val seamC = deckC
        val railC = mix(world.stripe, 0xFFFFFF, 0.45f)
        val chambers = floatArrayOf(-hw + 0.1f, -hw / 3f - 0.03f, -hw / 3f + 0.03f, hw / 3f - 0.03f, hw / 3f + 0.03f, hw - 0.1f)
        for (i in 0 until pts.size - 1) {
            val a = pts[i]; val b = pts[i + 1]
            if (cut((a + b) / 2f)) continue
            val md = chunkFor(a)
            val mid = (a + b) / 2f
            val stripe = stripeAt(mid) && seg.kind != SegKind.BEAM
            // top: 3 inflated chambers separated by seams
            for (k in 0 until chambers.size - 1) {
                val d0 = chambers[k]; val d1 = chambers[k + 1]
                val seam = k % 2 == 1
                val col = when {
                    stripe -> stripeC
                    seam -> seamC
                    else -> deckC
                }
                quadTop(md, a, b, d0, d1, 0.02f, 0f, col, Mat.of(gloss = 0.12f))
            }
            // side skirts and bottom
            val skirtC = world.skirt
            skirt(md, a, b, -hw, -1f, skirtC)
            skirt(md, a, b, hw, 1f, skirtC)
            bottom(md, a, b, hw, mix(skirtC, 0x000000, 0.3f))
            if (trims) {
                tube(md, a, b, -hw + 0.08f, 0.04f, 0.15f, world.trim, Mat.of(gloss = 0.3f))
                tube(md, a, b, hw - 0.08f, 0.04f, 0.15f, world.trim, Mat.of(gloss = 0.3f))
            }
            if (seg.railL) tube(md, a, b, -hw + 0.12f, 0.78f, 0.08f, railC, Mat.of(gloss = 0.3f))
            if (seg.railR) tube(md, a, b, hw - 0.12f, 0.78f, 0.08f, railC, Mat.of(gloss = 0.3f))
        }
        // checkered start / finish line: two rows of small squares laid just above the deck
        if (seg.kind == SegKind.START) checkerLine(hw, course.startS + 1f)
        if (seg.kind == SegKind.FINISH) checkerLine(hw, course.finishS)
        // rail posts
        if (seg.railL || seg.railR) {
            var ps = seg.s0 + 1f
            while (ps < seg.s1 - 0.5f) {
                if (!cut(ps)) {
                    val md = chunkFor(ps)
                    if (seg.railL) post(md, ps, -hw + 0.12f, 0.0f, 0.78f, 0.07f, 0xFFFFFF)
                    if (seg.railR) post(md, ps, hw - 0.12f, 0.0f, 0.78f, 0.07f, 0xFFFFFF)
                }
                ps += 2.4f
            }
        }
    }

    private fun checkerLine(hw: Float, s: Float) {
        val md = chunkFor(s)
        val w = 2f * hw - 0.2f
        val n = max(2, (w / CHECKER_CELL).roundToInt())
        val cw = w / n
        for (row in 0..1) {
            val a = s - CHECKER_CELL + row * CHECKER_CELL
            for (i in 0 until n) {
                val d0 = -hw + 0.1f + i * cw
                val col = if ((i + row) % 2 == 0) 0x2A3352 else 0xFFFFFF
                quadTop(md, a, a + CHECKER_CELL, d0, d0 + cw, 0.035f, 0f, col, Mat.of(gloss = 0.12f))
            }
        }
    }

    private fun quadTop(md: MeshData, a: Float, b: Float, d0: Float, d1: Float, h: Float, bulge: Float, col: Int, mat: Int) {
        // two sub-strips so chambers look puffy (raised centre)
        val dm = (d0 + d1) / 2f
        val dd = (d1 - d0) / 2f
        val strips = floatArrayOf(d0, dm, d1)
        val hs = floatArrayOf(h, h + bulge, h)
        for (k in 0..1) {
            w(a, strips[k], hs[k], p); val ax = p[0]; val ay = p[1]; val az = p[2]
            w(b, strips[k], hs[k], p); val bx = p[0]; val by = p[1]; val bz = p[2]
            w(b, strips[k + 1], hs[k + 1], p); val cx = p[0]; val cy = p[1]; val cz = p[2]
            w(a, strips[k + 1], hs[k + 1], p); val dx = p[0]; val dy = p[1]; val dz = p[2]
            // normals tilt slightly toward the chamber's outer edges
            val tilt = if (bulge > 0f) (if (k == 0) -0.35f else 0.35f) else 0f
            val yaw = course.yawAt(a)
            val rx = -cos(yaw) * tilt; val rz = sin(yaw) * tilt
            val i0 = md.v(ax, ay, az, rx * (if (k == 0) 1f else 0f) + 0f, 1f, rz * (if (k == 0) 1f else 0f), col, 0.92f, mat)
            val i1 = md.v(bx, by, bz, rx * (if (k == 0) 1f else 0f), 1f, rz * (if (k == 0) 1f else 0f), col, 0.92f, mat)
            val i2 = md.v(cx, cy, cz, rx * (if (k == 1) 1f else 0f), 1f, rz * (if (k == 1) 1f else 0f), col, if (k == 1) 0.92f else 1f, mat)
            val i3 = md.v(dx, dy, dz, rx * (if (k == 1) 1f else 0f), 1f, rz * (if (k == 1) 1f else 0f), col, if (k == 1) 0.92f else 1f, mat)
            // winding: a->b along s, d increases to the right; keep the top facing up
            md.tri(i0, i2, i1); md.tri(i0, i3, i2)
            if (dd < 0f) Unit
        }
    }

    private fun skirt(md: MeshData, a: Float, b: Float, d: Float, side: Float, col: Int) {
        val y0 = 0.02f; val y1 = -0.62f
        w(a, d, y0, p); val ax = p[0]; val ay = p[1]; val az = p[2]
        w(b, d, y0, p); val bx = p[0]; val by = p[1]; val bz = p[2]
        w(b, d, y1, p); val cx = p[0]; val cy = p[1]; val cz = p[2]
        w(a, d, y1, p); val dx = p[0]; val dy = p[1]; val dz = p[2]
        val yaw = course.yawAt(a)
        val nx = -cos(yaw) * side; val nz = sin(yaw) * side
        val i0 = md.v(ax, ay, az, nx, 0f, nz, col, 1f, Mat.of(gloss = 0.3f))
        val i1 = md.v(bx, by, bz, nx, 0f, nz, col, 1f, Mat.of(gloss = 0.3f))
        val i2 = md.v(cx, cy, cz, nx, 0f, nz, col, 0.7f, Mat.of(gloss = 0.3f))
        val i3 = md.v(dx, dy, dz, nx, 0f, nz, col, 0.7f, Mat.of(gloss = 0.3f))
        if (side > 0f) { md.tri(i0, i2, i1); md.tri(i0, i3, i2) } else { md.tri(i0, i1, i2); md.tri(i0, i2, i3) }
    }

    private fun bottom(md: MeshData, a: Float, b: Float, hw: Float, col: Int) {
        val y = -0.62f
        w(a, -hw, y, p); val ax = p[0]; val ay = p[1]; val az = p[2]
        w(b, -hw, y, p); val bx = p[0]; val by = p[1]; val bz = p[2]
        w(b, hw, y, p); val cx = p[0]; val cy = p[1]; val cz = p[2]
        w(a, hw, y, p); val dx = p[0]; val dy = p[1]; val dz = p[2]
        val i0 = md.v(ax, ay, az, 0f, -1f, 0f, col, 0.6f)
        val i1 = md.v(bx, by, bz, 0f, -1f, 0f, col, 0.6f)
        val i2 = md.v(cx, cy, cz, 0f, -1f, 0f, col, 0.6f)
        val i3 = md.v(dx, dy, dz, 0f, -1f, 0f, col, 0.6f)
        md.tri(i0, i1, i2); md.tri(i0, i2, i3)
    }

    /** Tube along the course between a and b at lateral d, height h. */
    private fun tube(md: MeshData, a: Float, b: Float, d: Float, h: Float, r: Float, col: Int, mat: Int) {
        val sides = 8
        val base = md.vCount
        for ((k, s) in floatArrayOf(a, b).withIndex()) {
            val yaw = course.yawAt(s)
            val rx = -cos(yaw); val rz = sin(yaw)
            for (i in 0..sides) {
                val ang = i.toFloat() / sides * TAU
                val c = cos(ang); val sn = sin(ang)
                w(s, d + c * r, h + sn * r, p)
                md.v(p[0], p[1], p[2], rx * c, sn, rz * c, col, 0.75f + 0.25f * (sn * 0.5f + 0.5f), mat)
            }
            if (k == 0) Unit
        }
        for (i in 0 until sides) {
            val i0 = base + i; val i1 = base + i + 1
            val j0 = base + sides + 1 + i; val j1 = j0 + 1
            md.tri(i0, j1, i1); md.tri(i0, j0, j1)
        }
    }

    private fun post(md: MeshData, s: Float, d: Float, h0: Float, h1: Float, r: Float, col: Int) {
        w(s, d, 0f, p)
        Shapes.cylinder(md, p[0], p[1] + h0, p[2], r, p[1] + h1, col, Mat.of(gloss = 0.4f), seg = 8, capTop = true)
    }

    // ------------------------------------------------------------------------------- slide
    private fun slide(seg: Segment) {
        val R = seg.halfWidth + 0.25f
        val n = 12
        var s = seg.s0
        while (s < seg.s1) {
            val a = s; val b = min(seg.s1, s + 0.6f)
            val md = chunkFor(a)
            for (shell in 0..1) {
                val base = md.vCount
                val rr = if (shell == 0) R else R + 0.14f
                for (ss in floatArrayOf(a, b)) {
                    val yaw = course.yawAt(ss)
                    val rx = -cos(yaw); val rz = sin(yaw)
                    for (i in 0..n) {
                        val ang = -1.85f + 3.7f * i / n // channel arc
                        val d = sin(ang) * rr
                        val h = R - cos(ang) * rr - 0.05f
                        w(ss, d, h, p)
                        val inner = shell == 0 && abs(ang) < 0.45f
                        val col = if (shell == 1) world.skirt else if (inner) 0x8FE9FF else if (abs(ang) < 1.2f) world.stripe else world.trim
                        val sg = if (shell == 0) 1f else -1f
                        md.v(p[0], p[1], p[2], -rx * sin(ang) * sg, cos(ang) * sg, -rz * sin(ang) * sg, col, if (shell == 1) 0.8f else 1f,
                            if (inner) Mat.of(gloss = 0.9f, emissive = 0.25f) else Mat.of(gloss = 0.6f))
                    }
                }
                for (i in 0 until n) {
                    val i0 = base + i; val i1 = i0 + 1; val j0 = base + n + 1 + i; val j1 = j0 + 1
                    if (shell == 0) { md.tri(i0, i1, j1); md.tri(i0, j1, j0) } else { md.tri(i0, j1, i1); md.tri(i0, j0, j1) }
                }
            }
            // outer shell (simple)
            s = b
        }
        var ps = seg.s0 + 2f
        while (ps < seg.s1) {
            course.world(ps, 0f, 0f, p)
            pillar(chunkFor(ps), p[0], p[1] - 0.15f, p[2], 0.28f)
            ps += 6f
        }
    }

    // ------------------------------------------------------------------------------- pillars
    private fun buildPillars() {
        for (seg in course.segments) {
            if (!seg.kind.let { it == SegKind.DECK || it == SegKind.TURN || it == SegKind.RAMP || it == SegKind.START || it == SegKind.FINISH || it == SegKind.BEAM }) continue
            var s = seg.s0 + 1.5f
            val step = if (seg.kind == SegKind.BEAM) 4f else 6f
            while (s < seg.s1 - 0.5f) {
                if (!cut(s)) {
                    val off = if (seg.kind == SegKind.BEAM) 0f else seg.halfWidth - 0.7f
                    for (d in if (off == 0f) floatArrayOf(0f) else floatArrayOf(-off, off)) {
                        course.world(s, d, -0.6f, p)
                        pillar(chunkFor(s), p[0], p[1], p[2], if (seg.kind == SegKind.BEAM) 0.22f else 0.34f)
                    }
                }
                s += step
            }
        }
    }

    private fun pillar(md: MeshData, x: Float, top: Float, z: Float, r: Float) {
        val bottom = -3f
        if (top <= 0.2f) return
        Shapes.cylinder(md, x, bottom, z, r, top, world.pillar, Mat.of(gloss = 0.35f), seg = 10, capTop = false, aoBottom = 0.85f)
        // painted band just under the deck
        Shapes.cylinder(md, x, top - 0.35f, z, r + 0.03f, top - 0.1f, world.pillarBand, Mat.of(gloss = 0.4f), seg = 10, capTop = false, aoBottom = 1f)
        footprints += Footprint(x, z, r)
    }

    // ------------------------------------------------------------------------------- set pieces
    private fun place(md: MeshData, name: String, x: Float, y: Float, z: Float, yaw: Float, sx: Float, sy: Float = sx, sz: Float = sx, tintA: Int = -1, tintB: Int = -1, pitch: Float = 0f, roll: Float = 0f) {
        Quat.euler(quat, yaw, pitch, roll)
        Mat4.trs(m, 0, x, y, z, quat[0], quat[1], quat[2], quat[3], sx, sy, sz)
        md.append(ModelLibrary.get(name).mesh, m, tintA, tintB, 1f)
    }

    private fun buildSetPieces() {
        val hzA = world.hazA; val hzB = world.hazB
        for (seg in course.segments) {
            val yaw = course.yawAt(seg.s0)
            when (seg.kind) {
                SegKind.JUMP -> {
                    for (d in floatArrayOf(-seg.halfWidth * 0.5f, seg.halfWidth * 0.5f)) {
                        course.world(seg.s0 - 1.3f, d, 0f, p)
                        place(chunkFor(seg.s0), "jump_pad", p[0], p[1] - 0.25f, p[2], yaw, seg.halfWidth * 0.45f, 1f, seg.halfWidth * 0.45f, hzA, hzB)
                    }
                }
                SegKind.TRAMPOLINE -> {
                    val hops = max(1, seg.p1.toInt())
                    for (k in 1 until hops) {
                        val s = seg.s0 + seg.length * k / hops
                        course.world(s, 0f, -0.55f, p)
                        // oval trampoline spanning every lane
                        place(chunkFor(s), "trampoline", p[0], p[1], p[2], yaw, SetPieceGeometry.trampolineHalfWidth(seg.halfWidth) / SetPieceGeometry.TRAMPOLINE_MODEL_R, 1f, 1.3f, hzA, hzB)
                        pillar(chunkFor(s), p[0], p[1], p[2], 0.25f)
                    }
                    for (d in floatArrayOf(-seg.halfWidth * 0.5f, seg.halfWidth * 0.5f)) {
                        course.world(seg.s0 - 1.3f, d, 0f, p)
                        place(chunkFor(seg.s0), "jump_pad", p[0], p[1] - 0.25f, p[2], yaw, seg.halfWidth * 0.45f, 1f, seg.halfWidth * 0.45f, hzA, hzB)
                    }
                }
                SegKind.ZIP -> {
                    val md = chunkFor(seg.s0)
                    for (end in floatArrayOf(seg.s0 - 0.6f, seg.s1 + 0.6f)) {
                        for (d in floatArrayOf(-seg.halfWidth + 0.2f, seg.halfWidth - 0.2f)) {
                            course.world(end, d, 0f, p)
                            place(md, "post", p[0], p[1], p[2], yaw, 1f, 2.9f, 1f, hzA, hzB)
                        }
                        course.world(end, 0f, 2.9f, p)
                        place(md, "beam", p[0], p[1], p[2], course.yawAt(end), seg.width - 0.4f, 1f, 1f, hzA, hzB)
                    }
                    // one cable per lane group (racers grab the nearest)
                    for (cd in SetPieceGeometry.zipCables(seg.halfWidth)) {
                        var s = seg.s0 - 0.6f
                        while (s < seg.s1 + 0.6f) {
                            val b = min(seg.s1 + 0.6f, s + 1f)
                            tubeAbs(md, s, b, cd, SetPieceGeometry.ZIP_CABLE_H, 0.035f, 0x9AA6B5)
                            s = b
                        }
                    }
                }
                SegKind.SWING -> {
                    val md = chunkFor(seg.s0)
                    val mid = (seg.s0 + seg.s1) / 2f
                    for (d in floatArrayOf(-seg.halfWidth - 0.4f, seg.halfWidth + 0.4f)) {
                        course.world(mid, d, -course.heightAt(mid) - 1f, p)
                        place(md, "post", p[0], p[1], p[2], yaw, 1.3f, course.heightAt(mid) + seg.p0 + 1.2f, 1.3f, hzA, hzB)
                    }
                    course.world(mid, 0f, seg.p0, p)
                    place(md, "beam", p[0], p[1], p[2], course.yawAt(mid), seg.width + 1.0f, 1.3f, 1.3f, hzA, hzB)
                }
                SegKind.MONKEY -> {
                    val md = chunkFor(seg.s0)
                    // bars as wide as the lanes so every racer hangs from a rung
                    val mh = SetPieceGeometry.monkeyHalfWidth(seg.halfWidth)
                    for (d in floatArrayOf(-mh, mh)) {
                        var s = seg.s0 - 0.6f
                        while (s < seg.s1 + 0.6f) { val b = min(seg.s1 + 0.6f, s + 1f); tubeAbs(md, s, b, d, SetPieceGeometry.MONKEY_BAR_H, 0.09f, hzA); s = b }
                    }
                    var s = seg.s0 + 0.3f
                    while (s < seg.s1) {
                        course.world(s, 0f, SetPieceGeometry.MONKEY_BAR_H, p)
                        place(md, "monkey_rung", p[0], p[1], p[2], course.yawAt(s), mh * 2f, 1f, 1f)
                        s += 0.6f
                    }
                    for (end in floatArrayOf(seg.s0 - 0.6f, seg.s1 + 0.6f)) for (d in floatArrayOf(-mh, mh)) {
                        course.world(end, d, 0f, p)
                        place(md, "post", p[0], p[1], p[2], yaw, 0.6f, 1.7f, 0.6f, hzA, hzB)
                    }
                }
                SegKind.CLIMB -> {
                    val md = chunkFor(seg.s0)
                    course.world(seg.s0, 0f, 0f, p)
                    // wall stands in front of the racers; its face looks back down the course
                    place(md, "climb_wall", p[0] + sin(yaw) * 0.25f, p[1] - 0.6f, p[2] + cos(yaw) * 0.25f, yaw + Math.PI.toFloat(), seg.width / 4f, seg.length + 0.6f, 1f, world.deck, hzB)
                    val n = (seg.width * seg.length * 1.4f).toInt()
                    for (k in 0 until n) {
                        val d = rng.range(-seg.halfWidth + 0.3f, seg.halfWidth - 0.3f)
                        val h = rng.range(0.2f, seg.length - 0.1f)
                        course.world(seg.s0, d, h - 0.6f + 0.6f, p)
                        place(md, "climb_hold", p[0] - sin(yaw) * 0.0f, p[1], p[2] - cos(yaw) * 0.0f, yaw + Math.PI.toFloat(), 1.2f, 1.2f, 1.2f, -1,
                            listOf(world.hazA, world.hazB, world.stripe, world.pad)[k % 4])
                    }
                }
                else -> Unit
            }
        }
        // finish arch and podium. No start arch: the racers start under it, so the high chase camera
        // would stare into its banner for the first seconds; the checkered start line marks the start.
        course.world(course.finishS, 0f, 0f, p)
        place(chunkFor(course.finishS), "finish_arch", p[0], p[1], p[2], course.yawAt(course.finishS), (setup.spec.width + 2.5f) / 5.2f, 2.1f, 1f, world.hazA, world.stripe)
        course.world(course.finishS + 9f, 0f, 0f, p)
        place(chunkFor(course.finishS + 9f), "podium", p[0], p[1], p[2], course.yawAt(course.finishS) + Math.PI.toFloat(), 1f)
        // checkpoint poles
        for (cp in setup.checkpoints) {
            val seg = course.segmentAt(cp)
            course.world(cp, -seg.halfWidth + 0.2f, 0f, p)
            place(chunkFor(cp), "flag_pole", p[0], p[1], p[2], course.yawAt(cp), 1f)
        }
    }

    private fun tubeAbs(md: MeshData, a: Float, b: Float, d: Float, h: Float, r: Float, col: Int) = tube(md, a, b, d, h, r, col, Mat.of(gloss = 0.6f))

    // ------------------------------------------------------------------------------- scenery
    private class Prop(val name: String, val radius: Float, val minDist: Float, val maxDist: Float, val count: Int, val y: Float = 0f, val scaleMin: Float = 1f, val scaleMax: Float = 1f, val tintA: Int = -1, val tintB: Int = -1)

    private fun propsFor(w: World): List<Prop> = when (w) {
        World.LAGOON -> listOf(
            Prop("island_a", 12f, 30f, 70f, 3), Prop("island_b", 8f, 22f, 60f, 4), Prop("island_c", 16f, 60f, 110f, 2),
            Prop("flamingo_float", 1.3f, 9f, 26f, 4), Prop("donut_float", 1.2f, 8f, 24f, 4), Prop("buoy", 0.6f, 7f, 20f, 8),
            Prop("sailboat", 2f, 25f, 70f, 3), Prop("rock_a", 1.4f, 10f, 40f, 6, -0.2f, 0.8f, 1.8f), Prop("lifeguard_tower", 1.2f, 18f, 35f, 1),
        )
        World.CANDY -> listOf(
            Prop("candy_island", 10f, 25f, 70f, 5), Prop("lollipop", 1.2f, 9f, 30f, 8, -0.5f, 0.9f, 1.4f, 0xFF5FA8, 0xFFFFFF),
            Prop("candy_cane", 1f, 9f, 30f, 6, -0.5f), Prop("gumdrop", 1f, 8f, 26f, 10, -0.3f, 0.8f, 1.6f, 0x7BE36B), Prop("donut_float", 1.2f, 8f, 24f, 6),
        )
        World.JUNGLE -> listOf(
            Prop("island_b", 8f, 22f, 60f, 5), Prop("jungle_tree", 2f, 12f, 40f, 10, -0.6f, 0.9f, 1.4f), Prop("ruin_column", 1f, 10f, 34f, 8, -0.8f),
            Prop("ruin_arch", 3f, 16f, 45f, 3, -0.8f), Prop("bush", 1.5f, 9f, 30f, 8, -0.4f), Prop("rock_a", 1.4f, 10f, 40f, 6, -0.2f, 0.8f, 1.8f),
        )
        World.PIRATE -> listOf(
            Prop("ship", 4f, 26f, 70f, 3), Prop("island_a", 12f, 30f, 70f, 3), Prop("dock", 2.5f, 12f, 30f, 3, 0f),
            Prop("treasure_chest", 1f, 10f, 26f, 4, 0.2f), Prop("barrel", 0.6f, 8f, 22f, 8, 0.2f, 1f, 1f, 0x8A5A32, 0x2B2B33), Prop("rock_b", 1f, 9f, 35f, 8, -0.1f, 0.8f, 1.8f),
        )
        World.FROSTY -> listOf(
            Prop("ice_floe", 3.2f, 10f, 50f, 10, 0.1f, 0.7f, 1.6f), Prop("pine_snow", 1.5f, 12f, 45f, 10, 0.4f, 0.9f, 1.4f), Prop("igloo", 2f, 18f, 50f, 3, 0.4f),
            Prop("penguin_prop", 0.6f, 10f, 30f, 8, 0.45f),
        )
        World.VOLCANO -> listOf(
            Prop("volcano", 30f, 110f, 160f, 1), Prop("basalt", 2f, 10f, 45f, 10, 0f, 0.8f, 1.6f), Prop("rock_a", 1.4f, 9f, 40f, 10, -0.2f, 0.8f, 2.2f),
        )
        World.SKY -> listOf(
            Prop("cloud_island", 7f, 22f, 70f, 6, -1f), Prop("hot_air_balloon", 2.4f, 14f, 50f, 6, 2f, 0.8f, 1.3f, 0xFF5FA8, 0xFFFFFF),
            Prop("floating_rock", 3f, 14f, 50f, 6, 1f, 0.6f, 1.2f),
        )
        World.NEON -> listOf(
            Prop("ferris_wheel", 6f, 40f, 80f, 2, -0.5f), Prop("neon_arch", 3f, 12f, 40f, 5, 0f), Prop("lamp_post", 0.5f, 6f, 20f, 14, 0.6f),
            Prop("buoy", 0.6f, 7f, 20f, 8), Prop("island_b", 8f, 26f, 60f, 3),
        )
    }

    private fun buildScenery() {
        val placed = ArrayList<FloatArray>() // x, z, r
        val b = course.bounds()
        val samples = (course.length / 2f).toInt()
        fun clearOfCourse(x: Float, z: Float, r: Float): Boolean {
            var i = 0
            while (i < course.count) {
                val dx = course.rawX(i) - x; val dz = course.rawZ(i) - z
                if (dx * dx + dz * dz < (r + 6f) * (r + 6f)) return false
                i += 8
            }
            for (pp in placed) {
                val dx = pp[0] - x; val dz = pp[1] - z
                if (dx * dx + dz * dz < (pp[2] + r + 1.5f) * (pp[2] + r + 1.5f)) return false
            }
            return true
        }
        var current = MeshData(4096, 8192)
        var count = 0
        for (prop in propsFor(world)) {
            // sparse and set back from the course so the track and hurdles read cleanly
            val want = max(1, (prop.count * SCENERY_DENSITY).toInt())
            var made = 0
            var tries = 0
            while (made < want && tries < want * 30) {
                tries++
                val s = rng.range(0f, course.length)
                val side = rng.sign()
                val dist = rng.range(prop.minDist * 1.6f, prop.maxDist * 1.3f)
                course.world(s, side * dist, 0f, p)
                val x = p[0]; val z = p[2]
                val sc = rng.range(prop.scaleMin, prop.scaleMax)
                val r = prop.radius * sc
                if (!clearOfCourse(x, z, r)) continue
                placed += floatArrayOf(x, z, r)
                place(current, prop.name, x, prop.y * sc, z, rng.range(0f, TAU), sc, sc, sc, prop.tintA, prop.tintB)
                if (prop.radius > 2f || prop.name.contains("float") || prop.name == "buoy" || prop.name.startsWith("rock")) footprints += Footprint(x, z, r * 0.85f)
                made++
                count++
                if (current.vCount > 40000) { sceneryChunks += current; current = MeshData(4096, 8192) }
            }
        }
        if (!current.isEmpty) sceneryChunks += current
        if (b[0] > b[2]) Unit
        if (samples < 0) Unit
    }

    companion object {
        private const val BAND = 1.0f
        private const val CHECKER_CELL = 0.45f
        private const val SCENERY_DENSITY = 0.5f

        fun mix(a: Int, b: Int, t: Float): Int {
            val r = ((a shr 16) and 0xFF) + ((((b shr 16) and 0xFF) - ((a shr 16) and 0xFF)) * t).toInt()
            val g = ((a shr 8) and 0xFF) + ((((b shr 8) and 0xFF) - ((a shr 8) and 0xFF)) * t).toInt()
            val bl = (a and 0xFF) + (((b and 0xFF) - (a and 0xFF)) * t).toInt()
            return (r shl 16) or (g shl 8) or bl
        }

        /** Bakes the shore/foam mask (R8, 256x256) around footprints. Returns (pixels, rect x0,z0,w,h). */
        fun shoreMask(course: Course, prints: List<Footprint>, size: Int = 256): Pair<java.nio.ByteBuffer, FloatArray> {
            val b = course.bounds()
            val margin = 70f
            val x0 = b[0] - margin; val z0 = b[1] - margin
            val wdt = max(b[2] - b[0], b[3] - b[1]) + margin * 2
            val buf = java.nio.ByteBuffer.allocateDirect(size * size)
            val field = FloatArray(size * size) { 99f }
            val cell = wdt / size
            for (fp in prints) {
                val reach = fp.r + 6f
                val i0 = ((fp.x - reach - x0) / cell).toInt().coerceIn(0, size - 1)
                val i1 = ((fp.x + reach - x0) / cell).toInt().coerceIn(0, size - 1)
                val j0 = ((fp.z - reach - z0) / cell).toInt().coerceIn(0, size - 1)
                val j1 = ((fp.z + reach - z0) / cell).toInt().coerceIn(0, size - 1)
                for (j in j0..j1) for (i in i0..i1) {
                    val cx = x0 + (i + 0.5f) * cell; val cz = z0 + (j + 0.5f) * cell
                    val d = sqrt((cx - fp.x) * (cx - fp.x) + (cz - fp.z) * (cz - fp.z)) - fp.r
                    if (d < field[j * size + i]) field[j * size + i] = d
                }
            }
            for (k in 0 until size * size) {
                val d = field[k]
                val v = (1f - (d.coerceAtLeast(0f) / 6f)).coerceIn(0f, 1f)
                buf.put((v * 255f).toInt().toByte())
            }
            buf.position(0)
            return buf to floatArrayOf(x0, z0, wdt, wdt)
        }
    }
}
