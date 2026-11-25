package com.ngstudio.funrace.level

import com.ngstudio.funrace.game.sim.Mode
import com.ngstudio.funrace.game.sim.RaceRules
import com.ngstudio.funrace.game.sim.SimEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Running into a rival knocks them over; they get back up where they are (no respawn). */
class ShoveTest {
    @Test
    fun runningIntoARivalKnocksThemDown() {
        val sim = LevelBuilder.createSim(LevelBuilder.build(LevelRepository.get(1)), RaceRules())
        sim.started = true
        val p = sim.player
        val bot = sim.racers.first { !it.isPlayer }
        // both on the first straight, the rival just ahead in the same lane, the player running
        p.s = sim.course.startS + 4f; p.d = 0f; p.speed = 5f; p.hold = true
        bot.s = p.s + 0.35f; bot.d = 0.05f; bot.speed = 1f
        var shoved = false
        var k = 0
        while (k < 10 && !shoved) { sim.step(1f / 60f); shoved = sim.events.any { it.type == SimEvent.Type.SHOVE && it.racer == bot.index }; k++ }
        assertTrue("the rival was not knocked over", shoved)
        assertEquals(Mode.TUMBLE, bot.mode)
        assertTrue("the rival should be thrown away from the player", bot.vs > 0f && bot.vy > 0f)
        val before = bot.s
        // it lands, lies there a moment and gets back up without being sent back
        var up = false
        for (t in 0 until 60 * 4) {
            sim.step(1f / 60f)
            assertTrue("a shoved rival must not respawn", sim.events.none { it.type == SimEvent.Type.RESPAWN && it.racer == bot.index })
            if (bot.mode == Mode.RUN) { up = true; break }
        }
        assertTrue("the rival never got back up", up)
        assertTrue("the rival got up where it fell, not at a checkpoint", bot.s >= before - 0.5f)
    }
}
