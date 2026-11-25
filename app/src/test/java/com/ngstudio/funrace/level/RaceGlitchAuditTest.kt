package com.ngstudio.funrace.level

import com.ngstudio.funrace.game.sim.Mode
import com.ngstudio.funrace.game.sim.Raft
import com.ngstudio.funrace.game.sim.RaceKind
import com.ngstudio.funrace.game.sim.RaceRules
import com.ngstudio.funrace.game.sim.SegKind
import com.ngstudio.funrace.game.sim.SetPieceGeometry
import com.ngstudio.funrace.game.sim.SetPieceGeometry.BODY_H
import com.ngstudio.funrace.game.sim.SetPieceGeometry.BODY_R
import com.ngstudio.funrace.game.sim.SetPieceGeometry.DECK_DEPTH
import com.ngstudio.funrace.game.sim.Tilt
import com.ngstudio.funrace.game.sim.Trapdoor
import com.ngstudio.funrace.game.sim.hasDeck
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * Races whole levels with every racer (player autopilot + bots) and checks, every tick, that each
 * racer is drawn on, under or hanging from something that is really there: deck, raft, trampoline,
 * zip cable, monkey bars, climbing wall or slide surface. Uses the same SetPieceGeometry the track
 * builder and renderer use.
 */
class RaceGlitchAuditTest {
    private class Tally { var ticks = 0; var bad = 0; var example = "" }

    /** No two racers in a race share a name (the results list and name tags must be unambiguous). */
    @Test
    fun racerNamesAreUniqueInEveryRace() {
        var dup = 0; var example = ""
        for (n in (1..10) + (11..5000 step 13)) {
            val spec = LevelRepository.get(n)
            val sim = LevelBuilder.createSim(LevelBuilder.build(spec), RaceRules())
            val names = sim.racers.map { it.name }
            if (names.toSet().size != names.size) { dup++; if (example.isEmpty()) example = "L$n: $names" }
        }
        println("AUDIT duplicate-name races=$dup $example")
        assertTrue("duplicate racer names, e.g. $example", dup == 0)
    }

    @Test
    fun everyRacerIsAlwaysSupported() {
        val specs = ArrayList<LevelSpec>()
        for (n in 1..10) specs += Handcrafted.get(n)
        // generated levels until every set piece has been raced a few times
        val want = listOf("TRAMPOLINE", "MONKEY", "BEAM", "RAFT", "ZIP", "SWING", "SLIDE", "CLIMB", "JUMP", "TILT", "TRAPDOOR", "PRESS", "WAVE", "BOLLARDS")
            .associateWith { 3 }
        val seen = HashMap<String, Int>()
        var n = 11
        while (want.any { (k, v) -> (seen[k] ?: 0) < v } && n < 1500) {
            val spec = LevelGenerator.generate(n, LevelRepository.salt(n))
            if (spec.kind != RaceKind.BONUS) {
                val built = LevelBuilder.build(spec)
                val tags = built.course.segments.map { it.kind.name }.toSet() + built.hazards.map { it.type.name }.toSet()
                if (tags.any { k -> (want[k] ?: 0) > (seen[k] ?: 0) }) {
                    specs += spec
                    for (k in tags) seen[k] = (seen[k] ?: 0) + 1
                }
            }
            n += if (n < 120) 1 else 7
        }
        val tally = HashMap<String, Tally>()
        fun check(what: String, ok: Boolean, msg: () -> String) {
            val t = tally.getOrPut(what) { Tally() }
            t.ticks++
            if (!ok) { t.bad++; if (t.example.isEmpty()) t.example = msg() }
        }
        for (spec in specs) {
            val setup = LevelBuilder.build(spec)
            val sim = LevelBuilder.createSim(setup, RaceRules())
            sim.autopilot = true; sim.player.skill = 0.8f; sim.started = true
            val c = setup.course
            val holes = setup.hazards.mapNotNull { h -> SetPieceGeometry.deckHole(h)?.let { h to it } }
            // what solid floor (deck slab, closed flip tile, tilting deck) a body at (s, d, y) is stuck in
            fun insideFloor(s: Float, d: Float, y: Float): String? {
                val seg = c.segmentAt(s.coerceIn(0f, c.length))
                val hw = seg.halfWidth
                if (abs(d) >= hw + BODY_R) return null
                for ((h, hole) in holes) if (s > hole[0] && s < hole[1]) {
                    if (h is Tilt) {
                        val surf = SetPieceGeometry.tiltSurface(h, sim.time, d.coerceIn(-hw, hw))
                        return if (y < surf - 0.08f && y + BODY_H > surf - 0.3f) "inside the tilting deck (%.2f below its top)".format(surf - y) else null
                    }
                    if (h is Trapdoor) {
                        val a = s - h.s0
                        val open = h.open(sim.time, if (a < 0f) 0 else 1, if (d < 0f) 0 else 1)
                        return if (open < 0.2f && y < -0.08f && y + BODY_H > -0.3f) "inside a closed flip tile" else null
                    }
                }
                if (!seg.kind.hasDeck || seg.kind == SegKind.SLIDE) return null
                return if (y < -0.08f && y + BODY_H > -DECK_DEPTH) "inside the deck slab (%.2f below its top)".format(-y) else null
            }
            var k = 0
            while (!sim.over && k < 60 * 200) {
                sim.step(1f / 60f); k++
                for (r in sim.racers) {
                    val seg = c.segmentAt(r.s.coerceIn(0f, c.length))
                    val hw = seg.halfWidth
                    val who = "L${spec.level} ${if (r.isPlayer) "player" else r.name} s=%.1f d=%.2f y=%.2f".format(r.s, r.d, r.y)
                    when (r.mode) {
                        Mode.RUN, Mode.STUN -> if (r.y < 0.05f && seg.kind.hasDeck) check("deck", abs(r.d) <= hw + 0.05f) { "$who off the deck (hw=%.2f)".format(hw) }
                        Mode.RAFT -> {
                            val raft = setup.hazards.firstOrNull { it is Raft && it.seg.index == seg.index } as? Raft
                            if (raft != null && r.s > seg.s0 + 0.12f && r.s < seg.s1 - 0.12f) {
                                val on = abs(r.s - raft.centre(sim.time)) <= raft.halfLen + 0.05f && abs(r.d) <= SetPieceGeometry.raftHalfWidth(raft.hw) - 0.25f
                                check("raft", on) { "$who rides beside the raft (raft centre=%.2f, seg %.1f..%.1f, in RAFT for %.2fs)".format(raft.centre(sim.time), seg.s0, seg.s1, r.modeT) }
                            }
                        }
                        Mode.AIR -> if (seg.kind == SegKind.TRAMPOLINE && r.y < 0.12f) {
                            val hops = max(1, seg.p1.toInt())
                            val p = (r.s - seg.s0) / seg.length
                            if (p in 0.08f..0.92f && hops > 1) {
                                var near = 99f
                                for (h in 1 until hops) near = minOf(near, abs(r.s - (seg.s0 + seg.length * h / hops)))
                                check("trampoline", near <= 1.3f * SetPieceGeometry.TRAMPOLINE_MODEL_R && abs(r.d) <= SetPieceGeometry.trampolineHalfWidth(hw) - 0.15f) { "$who bounces on thin air" }
                            }
                        }
                        Mode.ZIP -> if (r.modeT > 0.3f) check("zipline", abs(r.d - SetPieceGeometry.nearestCable(hw, r.d)) <= 0.06f) { "$who hangs %.2f m from the nearest cable".format(abs(r.d - SetPieceGeometry.nearestCable(hw, r.d))) }
                        Mode.MONKEY -> check("monkey bars", abs(r.d) <= SetPieceGeometry.monkeyHalfWidth(hw) - 0.1f) { "$who hangs outside the bars" }
                        Mode.CLIMB -> check("climb wall", abs(r.d) <= hw - 0.3f) { "$who climbs beside the wall" }
                        Mode.FALL, Mode.TUMBLE, Mode.AIR -> {
                            val inside = insideFloor(r.s, r.d, r.y)
                            check("no clipping", inside == null) { "$who ${r.mode} $inside" }
                        }
                        Mode.SLIDE -> if (seg.kind == SegKind.SLIDE) {
                            val surf = SetPieceGeometry.slideSurface(hw, r.d)
                            check("slide", abs(r.y - surf) <= 0.06f) { "$who feet %.2f m off the slide surface".format(r.y - surf) }
                        }
                        else -> Unit
                    }
                }
            }
        }
        val problems = ArrayList<String>()
        for ((what, t) in tally.toSortedMap()) {
            println("AUDIT %-12s ticks=%7d glitches=%6d %s".format(what, t.ticks, t.bad, t.example))
            if (t.bad > 0) problems += "$what: ${t.bad} glitch ticks, e.g. ${t.example}"
        }
        println("AUDIT levels=" + specs.map { it.level } + " set pieces seen=" + seen)
        for (k in listOf("deck", "raft", "trampoline", "zipline", "monkey bars", "climb wall", "slide", "no clipping")) assertTrue("never exercised: $k", (tally[k]?.ticks ?: 0) > 0)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
