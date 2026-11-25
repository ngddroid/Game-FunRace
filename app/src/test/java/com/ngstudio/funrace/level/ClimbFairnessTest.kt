package com.ngstudio.funrace.level

import com.ngstudio.funrace.game.sim.Coconuts
import com.ngstudio.funrace.game.sim.HazType
import com.ngstudio.funrace.game.sim.Mode
import com.ngstudio.funrace.game.sim.RaceRules
import com.ngstudio.funrace.game.sim.SegKind
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coconuts treat every racer the same: whoever a coconut visibly touches (player or bot) is bonked
 * that same tick, and nobody is bonked by a coconut that misses them. Positions are taken the way
 * the renderer draws them (coconut from the hazard's state, climber from its course position).
 */
class ClimbFairnessTest {
    private fun touching(nutH: Float, nutD: Float, a: Float, d: Float): Float {
        val dy = when {
            nutH < a + Coconuts.BODY_LO -> a + Coconuts.BODY_LO - nutH
            nutH > a + Coconuts.BODY_HI -> nutH - (a + Coconuts.BODY_HI)
            else -> 0f
        }
        val dd = d - nutD
        return kotlin.math.sqrt(dy * dy + dd * dd) - Coconuts.CONTACT   // < 0 = overlapping
    }

    @Test
    fun coconutsHitWhatTheyTouchForEveryRacer() {
        val levels = ArrayList<LevelSpec>()
        levels += Handcrafted.get(6)
        var n = 11
        while (levels.size < 9 && n < 600) {
            val s = LevelGenerator.generate(n, 0)
            if (s.pieces.any { it is Piece.Climb && it.coconutPeriod > 0f }) levels += s
            n++
        }
        var climbTicks = 0; var hits = 0; var missedTouches = 0; var ghostHits = 0
        val problems = ArrayList<String>()
        for (spec in levels) for (skill in floatArrayOf(0.4f, 0.85f)) {
            val setup = LevelBuilder.build(spec)
            val sim = LevelBuilder.createSim(setup, RaceRules())
            sim.autopilot = true; sim.player.skill = skill; sim.started = true
            val nuts = setup.hazards.filterIsInstance<Coconuts>()
            val st = FloatArray(12)
            var k = 0
            while (!sim.over && k < 60 * 200) {
                // snapshot climbers before the tick, then check what the tick did to them
                val before = sim.racers.map { r -> Triple(r.mode, r.bonks, if (r.mode == Mode.CLIMB) r.s - sim.course.segmentAt(r.s).s0 else 0f) }
                val dBefore = sim.racers.map { it.d }
                sim.step(1f / 60f); k++
                for (r in sim.racers) {
                    val (mode0, bonks0, _) = before[r.index]
                    if (mode0 != Mode.CLIMB) continue
                    val seg = sim.course.segmentAt(r.s.coerceAtLeast(0f))
                    val wasHit = r.bonks > bonks0
                    if (wasHit) hits++
                    if (r.mode == Mode.CLIMB) climbTicks++
                    // where the coconuts are drawn this tick vs where this climber is drawn
                    val nut = nuts.firstOrNull { kotlin.math.abs(it.s0 - (if (seg.kind == SegKind.CLIMB) seg.s0 else r.climbBaseS)) < 0.5f } ?: continue
                    nut.state(sim.time, st)
                    val a = if (wasHit) before[r.index].third else r.s - nut.s0
                    val d = if (wasHit) dBefore[r.index] else r.d
                    var closest = 9f
                    for (j in 0..1) if (st[j * 2] < 90f) closest = minOf(closest, touching(st[j * 2], st[j * 2 + 1], a, d))
                    val invulnerable = r.invuln > 0f || r.shield > 0
                    if (!wasHit && r.mode == Mode.CLIMB && closest < -0.06f && !invulnerable) {
                        missedTouches++
                        if (problems.size < 5) problems += "L${spec.level} ${if (r.isPlayer) "player" else r.name} touched by a coconut (overlap %.2f m) but not bonked at t=%.2f".format(-closest, sim.time)
                    }
                    if (wasHit && closest > 0.12f) {
                        ghostHits++
                        if (problems.size < 5) problems += "L${spec.level} ${if (r.isPlayer) "player" else r.name} bonked by a coconut %.2f m away at t=%.2f".format(closest, sim.time)
                    }
                }
            }
        }
        println("CLIMB levels=${levels.map { it.level }} climbTicks=$climbTicks hits=$hits missedTouches=$missedTouches ghostHits=$ghostHits")
        problems.forEach { println("CLIMB  $it") }
        assertTrue("checked no climbs", climbTicks > 500)
        assertTrue(problems.joinToString("\n"), missedTouches == 0 && ghostHits == 0)
    }

    /**
     * The baskets hang over the wall top: a climber's raised hands at the very top of the wall, and
     * the head of a racer stepping onto the top deck, must stay below the basket's bottom.
     * (Basket model: 0.25 below its pivot, scaled 1.4.)
     */
    @Test
    fun climbersPassUnderTheBaskets() {
        val basketBottom = Coconuts.BASKET_ABOVE - 0.25f * 1.4f
        assertTrue("basket bottom %.2f over the wall top hits climbers' hands (%.2f)".format(basketBottom, Coconuts.BODY_HI), basketBottom > Coconuts.BODY_HI + 0.1f)
        assertTrue("coconuts must start inside the basket", Coconuts.DROP_ABOVE in (basketBottom - 0.05f)..(Coconuts.BASKET_ABOVE + 0.1f))
    }

    @Test
    fun playerCanSlideAlongTheWallToDodge() {
        val setup = LevelBuilder.build(Handcrafted.get(6))
        val sim = LevelBuilder.createSim(setup, RaceRules())
        sim.started = true
        val p = sim.player
        p.hold = true
        var k = 0
        while (p.mode != Mode.CLIMB && k < 60 * 120) {
            // let the autopilot carry the player to the wall
            sim.autopilot = true; sim.step(1f / 60f); k++
        }
        sim.autopilot = false
        assertTrue("never reached the wall", p.mode == Mode.CLIMB)
        val start = p.d
        p.hold = false
        p.steer = start + 1.2f
        repeat(60) { sim.step(1f / 60f) }
        println("CLIMB slide from d=%.2f to d=%.2f".format(start, p.d))
        assertTrue("could not slide along the wall", p.d > start + 0.8f || p.mode != Mode.CLIMB)
    }
}
