package com.ngstudio.funrace.level

import com.ngstudio.funrace.game.sim.RaceKind

/** Single access point for every level (1..5000). Generated levels use a validated salt table. */
object LevelRepository {
    const val MAX_LEVEL = 5000
    private val cache = object : LinkedHashMap<Int, LevelSpec>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, LevelSpec>?) = size > 48
    }
    @Volatile private var salts: ByteArray? = null

    /** Installs assets/levels/salts.bin: one byte per level (salt that passed validation). */
    fun installSalts(bytes: ByteArray) { salts = bytes; synchronized(cache) { cache.clear() } }

    fun salt(level: Int): Int = salts?.let { s -> if (level - 1 in s.indices) s[level - 1].toInt() and 0xFF else 0 } ?: 0

    fun get(level: Int): LevelSpec {
        val n = level.coerceIn(1, MAX_LEVEL)
        synchronized(cache) { cache[n]?.let { return it } }
        val spec = if (n <= 10) Handcrafted.get(n) else LevelGenerator.generate(n, salt(n))
        synchronized(cache) { cache[n] = spec }
        return spec
    }

    /** Lightweight info for the level map (no generation needed). */
    fun kind(level: Int): RaceKind = if (level <= 10) (if (level == 10) RaceKind.BOSS_TIDE else RaceKind.TUTORIAL) else LevelGenerator.kindFor(level)
    fun world(level: Int): World = if (level <= 10) World.LAGOON else World.forLevel(level)
}
