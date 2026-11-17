package com.ngstudio.funrace.game.sim

import kotlin.math.max
import kotlin.math.min

/**
 * Rival / autopilot decision making. Each think, a racer predicts its own motion for the next
 * ~1.3 s twice (keep running vs. stop) against the exact hazard timelines, perturbed by a
 * skill-dependent timing error, and picks whichever stays safe longer.
 */
class AiBrain(private val sim: RaceSim) {
    private val horizon = 1.35f
    private val stepT = 1f / 30f

    fun decide(r: Racer, dt: Float): Boolean {
        if (r.finished) return false
        when (r.mode) {
            Mode.RUN, Mode.RAFT, Mode.CLIMB, Mode.MONKEY -> Unit
            else -> return true
        }
        r.aiNextThink -= dt
        if (r.aiNextThink > 0f) return r.aiDecision
        val skill = r.skill
        r.aiNextThink = 0.035f + (1f - skill) * 0.2f + sim.rng.nextFloat() * 0.03f

        // per-hazard timing error and recklessness, re-rolled when the next hazard changes
        val next = nextHazard(r)
        if (next != r.aiErrHaz) {
            r.aiErrHaz = next
            r.aiErr = sim.rng.soft() * (1f - skill) * 0.32f
            val recklessP = (1f - skill) * (if (sim.rules.guaranteeWin) 0.55f else 0.22f)
            r.aiPatience = if (sim.rng.chance(recklessP)) -1f else 0f
        }
        if (r.aiPatience < 0f) { r.aiDecision = true; return true }

        val tRun = firstDanger(r, true, r.aiErr)
        val go = if (tRun == null) true else {
            val tStay = firstDanger(r, false, r.aiErr)
            tStay != null && tStay < tRun - 0.05f
        }
        if (!go) r.aiPatience += r.aiNextThink else r.aiPatience = 0f
        // never deadlock: after a long wait, take the chance
        r.aiDecision = go || r.aiPatience > 7f
        return r.aiDecision
    }

    private fun nextHazard(r: Racer): Int {
        var best = -1
        var bs = Float.MAX_VALUE
        for (h in sim.hazards) {
            if (h.zoneEnd < r.s) continue
            if (h.zoneStart < bs) { bs = h.zoneStart; best = h.id }
        }
        return best
    }

    private val hit = Hit()

    private fun insideZone(s: Float): Boolean {
        for (h in sim.hazards) if (h.type != HazType.CONVEYOR && s >= h.zoneStart - RR && s <= h.zoneEnd + RR) return true
        val k = sim.course.segmentAt(s).kind
        return k == SegKind.CLIMB || k == SegKind.MONKEY || k == SegKind.RAFT
    }

    /** Seconds until the first predicted danger, or null if the horizon looks clear. */
    fun firstDanger(r: Racer, running: Boolean, err: Float): Float? {
        var s = r.s
        var v = r.speed
        var t = 0f
        val course = sim.course
        val vmax = when (r.mode) { Mode.CLIMB -> 1.8f; Mode.MONKEY -> 2.6f; else -> 0f }
        // look until the predicted path is clear of every hazard zone (long ramps, tilts, climbs)
        while (t <= horizon || (t <= 4f && insideZone(s))) {
            val seg = course.segmentAt(s)
            val y = if (seg.kind == SegKind.MONKEY) 0.55f else 0f
            val when_ = sim.time + t + err
            // chaser from behind
            sim.chaser?.let { c -> if (c.s(sim.time + t) > s - 0.6f) return t }
            when (seg.kind) {
                SegKind.JUMP, SegKind.TRAMPOLINE, SegKind.ZIP, SegKind.SWING, SegKind.SLIDE -> return null
                SegKind.CLIMB -> {
                    val a = s - seg.s0
                    for (h in sim.hazards) if (h.type == HazType.COCONUTS && kotlin.math.abs(h.s0 - seg.s0) < 0.5f &&
                        h.danger(when_, a, r.d, 0f, null) == Danger.KNOCK) return t
                }
                else -> {
                    for (h in sim.hazards) {
                        if (s < h.zoneStart - RR || s > h.zoneEnd + RR || h.type == HazType.COCONUTS) continue
                        val k = h.danger(when_, s - h.s0, r.d, y, null)
                        if (k == Danger.KNOCK || k == Danger.FALL) return t
                    }
                }
            }
            if (s >= course.finishS) return null
            val segMax = when (seg.kind) {
                SegKind.CLIMB -> 1.8f
                SegKind.MONKEY -> 2.6f
                SegKind.RAFT -> sim.maxSpeed(r, seg) * 0.8f
                else -> if (vmax > 0f) vmax else sim.maxSpeed(r, seg)
            }
            v = if (running) min(segMax, v + 24f * stepT) else max(0f, v - 48f * stepT)
            s += v * stepT
            t += stepT
        }
        return null
    }
}
