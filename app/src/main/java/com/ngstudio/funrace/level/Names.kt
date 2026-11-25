package com.ngstudio.funrace.level

import com.ngstudio.funrace.core.hash2

object Names {
    private val rivals = listOf(
        "Mango", "Pippa", "Bolt", "Kiki", "Taro", "Luna", "Ziggy", "Nori", "Coco", "Juno", "Pip", "Rio",
        "Momo", "Bean", "Sunny", "Waffles", "Tiki", "Bubbles", "Noodle", "Peaches", "Gizmo", "Zuzu", "Fizz",
        "Pickle", "Marlo", "Dottie", "Sprout", "Tofu", "Wiggles", "Kona", "Pebbles", "Jelly", "Biscuit", "Zappa",
        "Mochi", "Yuki", "Nacho", "Pudding", "Rocket", "Lolli", "Flip", "Splashy", "Hula", "Banjo", "Cleo", "Pogo",
    )

    /** Rival [i] of a race: one starting point per race, then a fixed stride, so names never repeat in a race. */
    fun rival(seed: Long, i: Int): String {
        val h = hash2(seed.toInt() xor (seed ushr 32).toInt(), 7919)
        val base = (h and 0x7FFFFFFF) % rivals.size
        return rivals[(base + i * 7) % rivals.size]   // 7 and the list size share no factor
    }

    private val first = mapOf(
        World.LAGOON to listOf("Lagoon", "Noodle", "Splash", "Floatie", "Coral", "Bubble", "Sunny", "Duck"),
        World.CANDY to listOf("Gumdrop", "Sugar", "Lolli", "Sprinkle", "Taffy", "Cocoa", "Jelly", "Fizz"),
        World.JUNGLE to listOf("Vine", "Idol", "Temple", "Toucan", "Mossy", "Ruin", "Monkey", "Canopy"),
        World.PIRATE to listOf("Anchor", "Plank", "Parrot", "Barrel", "Treasure", "Galleon", "Cutlass", "Cove"),
        World.FROSTY to listOf("Frosty", "Penguin", "Igloo", "Snowball", "Glacier", "Icicle", "Polar", "Flurry"),
        World.VOLCANO to listOf("Magma", "Ember", "Lava", "Cinder", "Sizzle", "Basalt", "Ash", "Scorch"),
        World.SKY to listOf("Cloud", "Breezy", "Balloon", "Rainbow", "Feather", "Nimbus", "Kite", "Sky"),
        World.NEON to listOf("Neon", "Glow", "Disco", "Laser", "Pixel", "Arcade", "Midnight", "Synth"),
    )
    private val second = listOf("Dash", "Run", "Rush", "Hop", "Shuffle", "Sprint", "Scramble", "Gauntlet", "Gallop", "Zoom", "Bounce", "Jam", "Romp", "Derby", "Caper", "Spin")

    fun level(level: Int, world: World): String {
        val h = hash2(level, 4242) and 0x7FFFFFFF
        val f = first[world]!!
        return f[h % f.size] + " " + second[(h / 7) % second.size]
    }
}
