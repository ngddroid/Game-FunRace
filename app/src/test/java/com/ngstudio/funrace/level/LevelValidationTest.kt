package com.ngstudio.funrace.level

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Validates generated levels and writes assets/levels/salts.bin (one byte per level: the
 * first salt whose level passes every check). Full run: -DfullLevelValidation=true.
 */
class LevelValidationTest {
    @Test
    fun validateLevels() {
        val full = System.getProperty("fullLevelValidation") == "true"
        val levels = if (full) (1..LevelRepository.MAX_LEVEL).toList() else (1..10) + (11..LevelRepository.MAX_LEVEL step 17)
        val salts = ByteArray(LevelRepository.MAX_LEVEL)
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceAtMost(8))
        val futures = ArrayList<Pair<Int, Future<Pair<Int, String>>>>()
        for (n in levels) {
            futures += n to pool.submit<Pair<Int, String>> {
                if (n <= 10) {
                    val rep = LevelValidator.check(Handcrafted.get(n), true, 0.5f)
                    return@submit (if (rep.ok) 0 else -1) to rep.problems.joinToString()
                }
                var last = ""
                for (salt in 0 until 40) {
                    val rep = LevelValidator.check(LevelGenerator.generate(n, salt), true, 0.85f)
                    if (rep.ok) return@submit salt to ""
                    last = rep.problems.joinToString()
                }
                -1 to last
            }
        }
        var failed = 0
        var rerolled = 0
        for ((n, f) in futures) {
            val (salt, why) = f.get()
            if (salt < 0) { failed++; println("FAIL L$n: $why") } else { salts[n - 1] = salt.toByte(); if (salt > 0) rerolled++ }
        }
        pool.shutdown()
        println("validated=${levels.size} rerolled=$rerolled failed=$failed")
        if (full) {
            val out = File("src/main/assets/levels/salts.bin")
            out.parentFile.mkdirs()
            out.writeBytes(salts)
            println("wrote ${out.absolutePath}")
        }
        assertTrue("$failed levels failed validation", failed == 0)
    }
}
