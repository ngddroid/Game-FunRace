package com.ngstudio.funrace.level

import com.ngstudio.funrace.game.sim.Danger
import com.ngstudio.funrace.game.sim.Mode
import com.ngstudio.funrace.game.sim.RaceSim
import com.ngstudio.funrace.game.sim.SimEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Any hurdle hit sends the racer back to its last checkpoint (or the start), even when it lands on the deck. */
class RespawnOnHitTest {
    private val dt = 1f / 60f

    private fun newSim(): RaceSim {
        val sim = LevelBuilder.createSim(LevelBuilder.build(Handcrafted.get(1)))
        sim.started = true
        return sim
    }

    /** Steps until the player is running again; returns true if a POOF (vanish) event was seen. */
    private fun stepUntilRunning(sim: RaceSim): Boolean {
        var poof = false
        var steps = 0
        do {
            sim.step(dt)
            if (sim.events.any { it.type == SimEvent.Type.POOF && it.racer == sim.player.index }) poof = true
            steps++
        } while (sim.player.mode != Mode.RUN && steps < 600)
        return poof
    }

    @Test
    fun hitOnDeckAfterCheckpointRespawnsAtCheckpoint() {
        val sim = newSim()
        val p = sim.player
        p.hold = true
        var steps = 0
        while (p.checkpointIdx < 0 && steps < 6000) { sim.step(dt); steps++ }
        assertTrue("player reached the first checkpoint", p.checkpointIdx == 0)
        p.hold = false
        val cp = p.checkpoint
        p.s = cp + 3f
        sim.applyHit(p, Danger.KNOCK, 0)
        assertTrue(p.respawnPending)
        assertTrue("vanished after the knock", stepUntilRunning(sim))
        assertFalse(p.respawnPending)
        assertEquals("back at the checkpoint", cp, p.s, 0.6f)
    }

    @Test
    fun hitBeforeAnyCheckpointRespawnsAtStart() {
        val sim = newSim()
        val p = sim.player
        val start = p.checkpoint
        p.s = start + 4f
        sim.applyHit(p, Danger.KNOCK, 0)
        assertTrue("vanished after the knock", stepUntilRunning(sim))
        assertEquals("back at the start", start, p.s, 0.6f)
    }
}
