package com.ngstudio.funrace.level

enum class TimeOfDay(val label: String) { MORNING("Morning"), NOON("Noon"), SUNSET("Sunset"), NIGHT("Night") }

enum class SeaStyle(val code: Float) { WATER(0f), LAVA(1f), CLOUDS(2f), SODA(3f) }

enum class StepSound { VINYL, WOOD, STONE, ICE }

/**
 * A themed world: palette for sky, sea, track and hazard skins, plus which scenery set dresses it.
 * Hazards keep the same rules everywhere; only their look changes.
 */
enum class World(
    val title: String,
    val skyTop: Int,
    val skyHorizon: Int,
    val fog: Int,
    val seaDeep: Int,
    val seaShallow: Int,
    val foam: Int,
    val seaStyle: SeaStyle,
    /** Track deck colours: main, stripe, edge trim, side skirt. */
    val deck: Int,
    val stripe: Int,
    val trim: Int,
    val skirt: Int,
    val pillar: Int,
    val pillarBand: Int,
    /** Hazard skin colours (tintA = body, tintB = accent), and the soft-padding colour. */
    val hazA: Int,
    val hazB: Int,
    val pad: Int,
    val danger: Int,
    val step: StepSound,
    val forcedTime: TimeOfDay? = null,
) {
    LAGOON(
        "Lagoon Park", 0x3E9BEF, 0xBDEBFF, 0xCDEFFF, 0x0A7FA8, 0x2FE0D2, 0xF4FFFF, SeaStyle.WATER,
        0xF7FBFF, 0x19C6C2, 0xFFD23F, 0x2E9BFF, 0xF2F5F8, 0xFF6B57,
        0xFF6B57, 0xFFD23F, 0x2E9BFF, 0xFF4F5E, StepSound.VINYL,
    ),
    CANDY(
        "Candy Carnival", 0xFF8FC7, 0xFFE3F1, 0xFFE6F2, 0xD43F8F, 0xFF9CCB, 0xFFF4FA, SeaStyle.SODA,
        0xFFF6E6, 0xFF5FA8, 0x9B6BFF, 0xFFB13D, 0xFFF0F6, 0x7BE36B,
        0x9B6BFF, 0x7BE36B, 0xFF5FA8, 0xFF3D6B, StepSound.VINYL,
    ),
    JUNGLE(
        "Jungle Ruins", 0x4BA8D8, 0xD6F2E0, 0xC8EBD8, 0x0F6E64, 0x3FC7A0, 0xEFFFF6, SeaStyle.WATER,
        0xD9CDB3, 0x8AA36B, 0x6F5A45, 0x9C8A70, 0xB9AA90, 0x5E8F4A,
        0xE07A3A, 0x6BBF4F, 0xC9A66B, 0xFF5A3D, StepSound.STONE,
    ),
    PIRATE(
        "Pirate Cove", 0x2F6FD0, 0xFFD9A8, 0xFFE2C0, 0x0C5E8C, 0x34B4D6, 0xFFF8EE, SeaStyle.WATER,
        0xC9935C, 0xA9713F, 0x5A3A22, 0x7A5032, 0x6E4A2E, 0xE8433A,
        0xE8433A, 0xFFD23F, 0x2B3A66, 0xFF4F3D, StepSound.WOOD, TimeOfDay.SUNSET,
    ),
    FROSTY(
        "Frosty Floe", 0x5AA8F0, 0xE6F6FF, 0xE8F6FF, 0x0F5F9A, 0x5FD0F0, 0xFFFFFF, SeaStyle.WATER,
        0xF2FAFF, 0x9FD8FF, 0x2E9BFF, 0xBFE6FF, 0xE6F4FF, 0x9B6BFF,
        0x2E9BFF, 0xFF5FA8, 0xBFE6FF, 0xFF4F7B, StepSound.ICE,
    ),
    VOLCANO(
        "Volcano Isle", 0x3A2D5C, 0xFF9A6B, 0xE07A5A, 0x2A1A22, 0xFF6A1E, 0xFFD27A, SeaStyle.LAVA,
        0x5A5560, 0xFF8A3D, 0x2E2A33, 0x45404D, 0x3A3540, 0xFF6A1E,
        0xFF8A3D, 0x4A4555, 0xD9A066, 0xFFD23F, StepSound.STONE, TimeOfDay.SUNSET,
    ),
    SKY(
        "Sky Garden", 0x6FB6FF, 0xFFF0FA, 0xF2F4FF, 0xC9D6F2, 0xFFFFFF, 0xFFFFFF, SeaStyle.CLOUDS,
        0xFFFFFF, 0xFFD23F, 0x9B6BFF, 0xC9B3FF, 0xF6F2FF, 0xFFD23F,
        0x9B6BFF, 0xFFD23F, 0xFF8FD0, 0xFF5FA8, StepSound.VINYL,
    ),
    NEON(
        "Neon Pier", 0x0A0A2E, 0x3A1A6B, 0x2A1450, 0x07082A, 0x1A3F8F, 0x9FF6FF, SeaStyle.WATER,
        0x2A2A44, 0x19F0E6, 0xFF3DD1, 0x1C1C33, 0x2F2F4A, 0x19F0E6,
        0xFF3DD1, 0x19F0E6, 0x6B3FD9, 0xFFE33D, StepSound.WOOD, TimeOfDay.NIGHT,
    );

    companion object {
        /** World for a level: changes every 25 levels and cycles through all 8. */
        fun forLevel(level: Int): World = entries[((level - 1) / 25) % entries.size]

        /** Time of day rotates on each pass through the 8-world cycle. */
        fun timeForLevel(level: Int): TimeOfDay {
            val w = forLevel(level)
            w.forcedTime?.let { return it }
            if (level <= 10) return TimeOfDay.NOON
            val cycle = (level - 1) / (25 * entries.size)
            return when (cycle % 4) { 0 -> TimeOfDay.NOON; 1 -> TimeOfDay.SUNSET; 2 -> TimeOfDay.MORNING; else -> TimeOfDay.NIGHT }
        }
    }
}
