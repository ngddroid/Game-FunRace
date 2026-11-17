package com.ngstudio.funrace.level

import com.ngstudio.funrace.game.sim.Danger
import com.ngstudio.funrace.game.sim.HazType
import com.ngstudio.funrace.game.sim.Hazard
import com.ngstudio.funrace.game.sim.RaceKind
import com.ngstudio.funrace.game.sim.RaceRules
import com.ngstudio.funrace.game.sim.SegKind
import kotlin.math.max
import kotlin.math.min

/**
 * Proves levels are fair. Used by the generator (window tuning), by unit tests (all 5,000
 * levels) and by the debug menu.
 */
object LevelValidator {
    const val RUN_SPEED = 5.2f
    private const val DT = 1f / 60f

    /**
     * Longest interval of departure times (seconds) from rest just before the group from which a
     * racer at [laneD] running flat out gets through every hazard untouched.
     */
    fun groupWindow(specs: List<HazSpec>, hw: Float, laneD: Float): Float {
        val hz = specs.mapNotNull { LevelBuilder.makeHazard(it, 0f, hw) }
        val lethal = hz.filter { it.type != HazType.CONVEYOR }
        if (lethal.isEmpty()) return 99f
        return window(hz, laneD)
    }

    fun window(hz: List<Hazard>, laneD: Float): Float {
        val start = hz.minOf { it.zoneStart } - 0.45f
        val end = hz.maxOf { it.zoneEnd } + 0.3f
        val maxPeriod = hz.filter { it.type != HazType.CONVEYOR }.maxOf { it.period }
        val scan = min(9f, maxPeriod * 2f)
        val n = (scan / DT).toInt()
        var best = 0
        var run = 0
        for (k in 0 until n) {
            if (passes(hz, start, end, k * DT, laneD)) { run++; best = max(best, run) } else run = 0
        }
        return best * DT
    }

    private fun passes(hz: List<Hazard>, start: Float, end: Float, t0: Float, d: Float): Boolean {
        var s = start
        var v = 0f
        var t = 0f
        while (s < end) {
            if (t > 6f) return false
            for (h in hz) {
                if (s < h.zoneStart - 0.4f || s > h.zoneEnd + 0.4f) continue
                val k = h.danger(t0 + t, s - h.s0, d, 0f, null)
                if (k != Danger.SAFE) return false
            }
            v = min(RUN_SPEED, v + 24f * DT)
            var conv = 0f
            for (h in hz) conv += h.conveyor(t0 + t, s - h.s0, d)
            s += (v + conv) * DT
            t += DT
        }
        return true
    }

    class Report(val level: Int, val ok: Boolean, val problems: List<String>, val rank: Int = 0, val time: Float = 0f)

    /** Static checks: geometry, checkpoints, safe pads and safe windows for every hazard group. */
    fun check(spec: LevelSpec, withAutopilot: Boolean, autopilotSkill: Float = 0.85f): Report {
        val problems = ArrayList<String>()
        val setup = LevelBuilder.build(spec)
        val c = setup.course
        if (c.minSelfClearance() < 9f) problems += "course overlaps itself"
        val hw = spec.width / 2f
        val lanes = LevelBuilder.lanes(spec.rivals + 1, hw)
        val playerD = lanes[if (spec.rivals + 1 >= 4) 1 else if (spec.rivals == 0) 0 else (spec.rivals + 1) / 2]
        val minWin = if (spec.level <= 10) 1.0f else DifficultyCurve.Metrics(spec.difficulty).minWindow * 0.98f
        // group hazards that share a deck without a pad between them
        val sorted = setup.hazards.filter { it.type != HazType.COCONUTS && it.type != HazType.RAFT && it.type != HazType.BARRELS && it.type != HazType.CONVEYOR }.sortedBy { it.s0 }
        val belts = setup.hazards.filter { it.type == HazType.CONVEYOR }
        var i = 0
        while (i < sorted.size) {
            val group = arrayListOf(sorted[i])
            var j = i + 1
            while (j < sorted.size && sorted[j].zoneStart < group.maxOf { it.zoneEnd } + 1.2f) { group += sorted[j]; j++ }
            run {
                val seg = c.segmentAt(group[0].s0)
                val d = if (seg.kind == SegKind.BEAM) 0f else playerD
                val lo = group.minOf { it.zoneStart }; val hi = group.maxOf { it.zoneEnd }
                val w = window(group + belts.filter { it.zoneEnd > lo && it.zoneStart < hi }, d)
                if (w < minWin) problems += "window %.2fs < %.2fs at s=%.0f (%s)".format(w, minWin, group[0].s0, group.joinToString { it.type.name })
                // a calm pad must exist before the group
                val padStart = group[0].zoneStart - 1.4f
                for (h in sorted) if (h !in group && h.zoneEnd > padStart && h.zoneStart < group[0].zoneStart) problems += "no safe pad before s=%.0f".format(group[0].s0)
            }
            i = j
        }
        if (spec.kind != RaceKind.BONUS && setup.checkpoints.isEmpty()) problems += "no checkpoints"
        var rank = 0
        var time = 0f
        if (withAutopilot && problems.isEmpty()) {
            val sim = LevelBuilder.createSim(setup, RaceRules())
            sim.autopilot = true
            sim.player.skill = autopilotSkill
            sim.started = true
            var steps = 0
            while (!sim.over && steps < 60 * 300) { sim.step(DT); steps++ }
            rank = if (sim.player.finished) sim.player.rank else 99
            time = sim.time
            val need = if (spec.kind == RaceKind.BONUS) 1 else 3
            if (rank > need) problems += "autopilot finished #$rank"
            if (!sim.player.finished) problems += "autopilot did not finish in 300 s (stuck at s=%.1f mode=%s seg=%s)".format(sim.player.s, sim.player.mode, c.segmentAt(sim.player.s).kind)
        }
        return Report(spec.level, problems.isEmpty(), problems, rank, time)
    }
}
