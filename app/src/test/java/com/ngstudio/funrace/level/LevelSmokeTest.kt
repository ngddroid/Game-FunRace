package com.ngstudio.funrace.level

import org.junit.Test

class LevelSmokeTest {
    @Test
    fun handcraftedAndSample() {
        val levels = (1..10) + listOf(11, 12, 15, 20, 23, 30, 47, 50, 75, 100, 150, 222, 300, 500, 1000, 2500, 5000)
        var fails = 0
        for (n in levels) {
            val t0 = System.nanoTime()
            val spec = LevelRepository.get(n)
            val rep = if (n <= 10) LevelValidator.checkTutorial(spec) else LevelValidator.check(spec, withAutopilot = true, autopilotSkill = 0.85f)
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("L%-5d %-22s %-10s D=%.2f haz=%2d rivals=%d rank=%-2d t=%5.1fs ok=%s %dms %s".format(n, spec.name, spec.kind, spec.difficulty, spec.hazardCount, spec.rivals, rep.rank, rep.time, rep.ok, ms, rep.problems.joinToString("; ")))
            if (!rep.ok) fails++
        }
        println("fails=$fails")
    }
}
