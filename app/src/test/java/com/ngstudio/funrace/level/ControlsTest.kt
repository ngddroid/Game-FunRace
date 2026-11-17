package com.ngstudio.funrace.level

import com.ngstudio.funrace.game.sim.Danger
import com.ngstudio.funrace.game.sim.HazType
import com.ngstudio.funrace.game.sim.Mode
import com.ngstudio.funrace.game.sim.RaceRules
import com.ngstudio.funrace.game.sim.Sweeper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlsTest {
    @Test
    fun jumpLiftsAndLands() {
        val sim = LevelBuilder.createSim(LevelBuilder.build(Handcrafted.get(3)), RaceRules())
        sim.started = true
        val p = sim.player
        p.hold = true
        repeat(30) { sim.step(1 / 60f) }
        p.jumpQueued = true
        var maxY = 0f
        repeat(60) { sim.step(1 / 60f); maxY = maxOf(maxY, p.y) }
        assertTrue("jump apex $maxY", maxY > 0.8f)
        assertEquals(0f, p.y, 0.001f)
        assertEquals(Mode.RUN, p.mode)
    }

    @Test
    fun jumpClearsSweeperArm() {
        val sw = Sweeper(10f, 2.3f, 1.5f, 0f, 1, 2.65f)
        // find a moment the arm sweeps through (a=0, d=-1.2)
        var t = 0f
        while (sw.danger(t, 0f, -1.2f, 0f, null) == Danger.SAFE && t < 10f) t += 0.01f
        assertTrue(sw.danger(t, 0f, -1.2f, 0f, null) == Danger.KNOCK)
        assertEquals(Danger.SAFE, sw.danger(t, 0f, -1.2f, 0.9f, null))
    }

    @Test
    fun steeringMovesButNeverOffTheEdge() {
        val sim = LevelBuilder.createSim(LevelBuilder.build(Handcrafted.get(2)), RaceRules())
        sim.started = true
        val p = sim.player
        p.hold = true
        p.steer = 99f
        repeat(120) { sim.step(1 / 60f) }
        assertTrue("moved right: d=${p.d}", p.d > 1.5f)
        assertEquals(Mode.RUN, p.mode)
        p.steer = -99f
        repeat(120) { sim.step(1 / 60f) }
        assertTrue("moved left: d=${p.d}", p.d < -1.5f)
        assertTrue(p.mode != Mode.FALL && p.mode != Mode.SPLASHED)
        @Suppress("UNUSED_VARIABLE") val u = HazType.SWEEPER
    }
}
