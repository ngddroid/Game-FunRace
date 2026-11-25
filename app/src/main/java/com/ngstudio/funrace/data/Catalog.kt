package com.ngstudio.funrace.data

enum class Rarity(val label: String, val color: Long, val dark: Long) {
    COMMON("Common", 0xFF7BE36B, 0xFF34B54A),
    RARE("Rare", 0xFF2E9BFF, 0xFF1B6FD1),
    EPIC("Epic", 0xFF9B6BFF, 0xFF6B3FD9),
    LEGENDARY("Legendary", 0xFFFFD23F, 0xFFF2A90F),
}

enum class Currency { COINS, GEMS, REWARD }

/** Costume accessory mesh attached to a bone of the Dashling rig. */
data class Accessory(val mesh: String, val bone: Int, val color: Int = 0, val color2: Int = 0)

data class CharacterDef(
    val id: String,
    val name: String,
    val rarity: Rarity,
    val price: Int,
    val currency: Currency,
    /** Onesie colour (Dashling) or shirt colour (kid). */
    val primary: Int,
    /** Belly / hood-rim / cuffs (Dashling) or pants colour (kid). */
    val secondary: Int,
    val skin: Int = 0xFFD9B3,
    val shoe: Int = 0xFFFFFF,
    val accessories: List<Accessory> = emptyList(),
    val blurb: String = "",
    /** Skinned body mesh: [Catalog.BODY_DASHLING] (chibi hooded onesie) or [Catalog.BODY_HUMAN] (kid racer). */
    val body: String = Catalog.BODY_DASHLING,
)

data class HatDef(val id: String, val name: String, val rarity: Rarity, val price: Int, val currency: Currency, val mesh: String, val color: Int, val color2: Int)

data class TrailDef(val id: String, val name: String, val rarity: Rarity, val price: Int, val currency: Currency, val sprite: Int, val colors: IntArray, val additive: Boolean = false)

data class DanceDef(val id: String, val name: String, val rarity: Rarity, val price: Int, val currency: Currency)

/** Skeleton bone indices of the Dashling rig (must match tools/blender/dashling.py). */
object Bones {
    const val HIPS = 0; const val CHEST = 1; const val HEAD = 2
    const val ARM_L = 3; const val HAND_L = 4; const val ARM_R = 5; const val HAND_R = 6
    const val THIGH_L = 7; const val SHIN_L = 8; const val FOOT_L = 9
    const val THIGH_R = 10; const val SHIN_R = 11; const val FOOT_R = 12
    const val COUNT = 13
}

object Catalog {
    const val BODY_DASHLING = "dashling"
    /** Human-proportioned kid racer body (its own longer skeleton; head parts are fitted onto it). */
    const val BODY_HUMAN = "human"
    /** New players start as Kai (one of the kid racers); existing saves keep their character. */
    const val DEFAULT_CHARACTER = "kai"
    const val NO_HAT = "none"
    const val NO_TRAIL = "none"
    const val DEFAULT_DANCE = "hop"
    /** Owned-item keys: characters by id, others prefixed ("hat:", "trail:", "dance:"). */
    val STARTER_ITEMS = setOf(DEFAULT_CHARACTER, "hat:$NO_HAT", "trail:$NO_TRAIL", "dance:$DEFAULT_DANCE")

    fun itemName(key: String): String = when {
        key.startsWith("hat:") -> hat(key.removePrefix("hat:")).name
        key.startsWith("trail:") -> trail(key.removePrefix("trail:")).name
        key.startsWith("dance:") -> dance(key.removePrefix("dance:")).name
        else -> character(key).name
    }

    private fun acc(mesh: String, bone: Int = Bones.HEAD, color: Int = 0, color2: Int = 0) = Accessory(mesh, bone, color, color2)

    /** A kid racer: shirt/pants colours, a hairstyle (colour + accent) and an optional backpack. */
    private fun kid(
        id: String, name: String, rarity: Rarity, price: Int, currency: Currency,
        shirt: Int, pants: Int, skin: Int, shoe: Int, hair: String, hairColor: Int, hairAccent: Int,
        backpack: Pair<Int, Int>? = null, blurb: String = "",
    ) = CharacterDef(
        id, name, rarity, price, currency, shirt, pants, skin, shoe,
        listOfNotNull(acc(hair, color = hairColor, color2 = hairAccent), backpack?.let { acc("acc_backpack", Bones.CHEST, it.first, it.second) }),
        blurb, BODY_HUMAN,
    )

    /** Kid racers: the default look for new players and for rival bots. */
    val kids: List<CharacterDef> = listOf(
        kid("kai", "Kai", Rarity.COMMON, 0, Currency.COINS, 0xFF8A3D, 0x2B3A66, 0xEDB48C, 0xFFFFFF, "hair_spiky", 0x3B2A20, 0x3B2A20, blurb = "Spikes up, sneakers laced, ready to dash."),
        kid("mia", "Mia", Rarity.COMMON, 0, Currency.COINS, 0xFF6FA8, 0xF4F6FA, 0xF7C6A6, 0xFF5FA8, "hair_ponytail", 0x8A4B2A, 0xFF5FA8, blurb = "Her ponytail is always a step behind."),
        kid("leo", "Leo", Rarity.COMMON, 0, Currency.COINS, 0xFFD23F, 0x3FAF6A, 0x8D5A3B, 0xFF6B57, "hair_curly", 0x2A2230, 0x2A2230, backpack = 0x2E9BFF to 0xFFD23F, blurb = "Packs snacks for every race."),
        kid("zoe", "Zoe", Rarity.COMMON, 800, Currency.COINS, 0x5FE3C0, 0x7A5CFF, 0xEDB48C, 0xFFFFFF, "hair_buns", 0x1E1E28, 0x5FE3C0),
        kid("ravi", "Ravi", Rarity.COMMON, 800, Currency.COINS, 0x19C6C2, 0xD9C29B, 0xA8714A, 0xFFFFFF, "hair_crop", 0x221A18, 0x221A18, backpack = 0xFFD23F to 0xFF6B57),
        kid("ana", "Ana", Rarity.COMMON, 800, Currency.COINS, 0xFFD23F, 0x3F6FD8, 0xF6C9A0, 0xFF6B57, "hair_bob", 0xD9482B, 0xFFD23F),
        kid("sofia", "Sofia", Rarity.COMMON, 800, Currency.COINS, 0xFF6B57, 0xF4F6FA, 0xF6C8A8, 0xFFFFFF, "hair_braid", 0xF2C94C, 0xFF6B57),
        kid("sam", "Sam", Rarity.COMMON, 800, Currency.COINS, 0x2E9BFF, 0xFFD23F, 0xF6C8A8, 0xFFFFFF, "hair_pigtails", 0xF2A65A, 0x7BE36B),
        kid("max", "Max", Rarity.RARE, 2000, Currency.COINS, 0x2B2B33, 0xE8433A, 0xF6C9A0, 0xFFD23F, "hair_mohawk", 0x2E9BFF, 0x6B4A3A, blurb = "Blue mohawk, zero brakes."),
        kid("jin", "Jin", Rarity.RARE, 2000, Currency.COINS, 0xF4F6FA, 0x2B2B33, 0xEDBF95, 0xE8433A, "hair_topbun", 0x1A1A22, 0xE8433A),
        kid("tomi", "Tomi", Rarity.RARE, 2000, Currency.COINS, 0x7BE36B, 0x2B3A66, 0xF6C9A0, 0xFFFFFF, "hair_wavy", 0x6B4226, 0x6B4226, backpack = 0xE8433A to 0xFFFFFF),
        kid("nia", "Nia", Rarity.RARE, 2000, Currency.COINS, 0x4FB0F0, 0xFF8FC7, 0x6B4430, 0xFFFFFF, "hair_puffs", 0x2A1E1A, 0xFFD23F, blurb = "Two puffs, twice the bounce."),
        kid("luna", "Luna", Rarity.EPIC, 5000, Currency.COINS, 0x9B6BFF, 0xF4F6FA, 0xF5CDB0, 0xC9A6FF, "hair_long", 0xC9A6FF, 0xFFFFFF, blurb = "Lilac hair that shimmers in the sun."),
    )

    val characters: List<CharacterDef> = kids + listOf(
        CharacterDef("dash", "Dash", Rarity.COMMON, 0, Currency.COINS, 0x19C6C2, 0xF6FFFE, blurb = "Always first off the starting dock."),
        CharacterDef("coral", "Coral", Rarity.COMMON, 800, Currency.COINS, 0xFF6B57, 0xFFE9B8, skin = 0xF2C29B),
        CharacterDef("sunny", "Sunny", Rarity.COMMON, 800, Currency.COINS, 0xFFD23F, 0xFF8A3D, skin = 0xFFDDBB),
        CharacterDef("limey", "Limey", Rarity.COMMON, 800, Currency.COINS, 0x7BE36B, 0xFFFFFF, skin = 0xC98E66),
        CharacterDef("bubblegum", "Bubblegum", Rarity.COMMON, 800, Currency.COINS, 0xFF5FA8, 0xFFE3F1, skin = 0xFFD7C0),
        CharacterDef("grape", "Grape", Rarity.COMMON, 800, Currency.COINS, 0x9B6BFF, 0xE6DBFF, skin = 0x8D5A3B),
        CharacterDef("kitty", "Kitty", Rarity.RARE, 2500, Currency.COINS, 0xFFB86B, 0xFFF1DE, accessories = listOf(acc("acc_cat_ears", color = 0xFFB86B, color2 = 0xFF8FB0)), blurb = "Lands on its feet. Mostly."),
        CharacterDef("bear", "Bruno", Rarity.RARE, 2500, Currency.COINS, 0x9A6A47, 0xE8C9A6, accessories = listOf(acc("acc_bear_ears", color = 0x9A6A47, color2 = 0xE8C9A6))),
        CharacterDef("bunny", "Hopper", Rarity.RARE, 2500, Currency.COINS, 0xF6F3FF, 0xFFB3CF, accessories = listOf(acc("acc_bunny_ears", color = 0xF6F3FF, color2 = 0xFFB3CF))),
        CharacterDef("froggy", "Froggy", Rarity.RARE, 2500, Currency.COINS, 0x58C94F, 0xD7F7A6, accessories = listOf(acc("acc_frog_eyes", color = 0x58C94F, color2 = 0xFFFFFF)), blurb = "Ribbit-ready for any puddle."),
        CharacterDef("fox", "Foxy", Rarity.RARE, 2500, Currency.COINS, 0xFF7B2E, 0xFFF4E8, accessories = listOf(acc("acc_fox_ears", color = 0xFF7B2E, color2 = 0x3A2A2A))),
        CharacterDef("panda", "Pando", Rarity.RARE, 2500, Currency.COINS, 0xF7F7F2, 0x2E2E3A, accessories = listOf(acc("acc_panda_ears", color = 0x2E2E3A, color2 = 0x2E2E3A))),
        CharacterDef("duckling", "Ducky", Rarity.RARE, 2500, Currency.COINS, 0xFFE04A, 0xFFF6C8, accessories = listOf(acc("acc_duck_bill", color = 0xFF9A2E, color2 = 0xFF9A2E))),
        CharacterDef("penguin", "Pebble", Rarity.RARE, 2500, Currency.COINS, 0x2C3550, 0xF6F7FF, shoe = 0xFF9A2E, accessories = listOf(acc("acc_penguin_beak", color = 0xFF9A2E, color2 = 0xFF9A2E))),
        CharacterDef("bee", "Buzzy", Rarity.RARE, 2500, Currency.COINS, 0xFFC81E, 0x2B2B33, accessories = listOf(acc("acc_bee_antennae", color = 0x2B2B33, color2 = 0xFFC81E), acc("acc_bee_wings", Bones.CHEST, color = 0xDDF4FF, color2 = 0xFFFFFF))),
        CharacterDef("shark", "Chomp", Rarity.EPIC, 6000, Currency.COINS, 0x6F8FB5, 0xF2F6FF, accessories = listOf(acc("acc_shark_fin", color = 0x6F8FB5, color2 = 0xFFFFFF)), blurb = "The friendliest fin in the lagoon."),
        CharacterDef("pineapple", "Pina", Rarity.EPIC, 6000, Currency.COINS, 0xFFC93B, 0xE89B1E, accessories = listOf(acc("acc_pineapple_leaves", color = 0x3FBF4F, color2 = 0x2A9440))),
        CharacterDef("dino", "Rexy", Rarity.EPIC, 6000, Currency.COINS, 0x4FC46A, 0xF2E37A, accessories = listOf(acc("acc_dino_spikes", Bones.CHEST, color = 0xFF8A3D, color2 = 0xFFB43D))),
        CharacterDef("unicorn", "Twinkle", Rarity.EPIC, 6000, Currency.COINS, 0xFFFFFF, 0xC9B3FF, accessories = listOf(acc("acc_unicorn_horn", color = 0xFFD23F, color2 = 0xFF5FA8))),
        CharacterDef("octopus", "Inky", Rarity.EPIC, 6000, Currency.COINS, 0xB86BFF, 0xFFD6F3, accessories = listOf(acc("acc_octo_tentacles", color = 0xB86BFF, color2 = 0xFFD6F3))),
        CharacterDef("crab", "Pinchy", Rarity.EPIC, 6000, Currency.COINS, 0xFF4F3D, 0xFFD2B8, accessories = listOf(acc("acc_crab_claw", Bones.HAND_L, color = 0xFF4F3D, color2 = 0xFFD2B8), acc("acc_crab_claw_r", Bones.HAND_R, color = 0xFF4F3D, color2 = 0xFFD2B8), acc("acc_crab_eyes", color = 0xFF4F3D, color2 = 0xFFFFFF))),
        CharacterDef("turtle", "Shelly", Rarity.EPIC, 6000, Currency.COINS, 0x7FD36B, 0xF6E7A6, accessories = listOf(acc("acc_turtle_shell", Bones.CHEST, color = 0x3E8F57, color2 = 0xA6E27A))),
        CharacterDef("robot", "Bolt", Rarity.EPIC, 6000, Currency.COINS, 0xB9C7D6, 0x2E9BFF, skin = 0xD4E2EE, accessories = listOf(acc("acc_robot_antenna", color = 0x8A97A8, color2 = 0xFF6B57))),
        CharacterDef("alien", "Zib", Rarity.EPIC, 6000, Currency.COINS, 0x8BF06A, 0xD8FFC8, skin = 0xB8F59E, accessories = listOf(acc("acc_alien_antennae", color = 0x8BF06A, color2 = 0xFF5FA8))),
        CharacterDef("pirate", "Captain Ree", Rarity.EPIC, 6000, Currency.COINS, 0x2B3A66, 0xF2F2F2, accessories = listOf(acc("acc_pirate_bandana", color = 0xE8433A, color2 = 0xFFFFFF), acc("acc_eye_patch", color = 0x1E1E24, color2 = 0x1E1E24))),
        CharacterDef("astronaut", "Cosmo", Rarity.LEGENDARY, 80, Currency.GEMS, 0xF4F6FB, 0xFF6B57, accessories = listOf(acc("acc_astro_helmet", color = 0xE8F7FF, color2 = 0xB9C7D6)), blurb = "One small step for a Dashling."),
        CharacterDef("dragon", "Blaze", Rarity.LEGENDARY, 80, Currency.GEMS, 0x8A4DFF, 0xFFC93B, accessories = listOf(acc("acc_dragon_horns", color = 0xFFE9B8, color2 = 0xFFC93B), acc("acc_dragon_wings", Bones.CHEST, color = 0x8A4DFF, color2 = 0xFF8FD0))),
        CharacterDef("mermaid", "Marina", Rarity.LEGENDARY, 80, Currency.GEMS, 0x19C6C2, 0xFF8FD0, accessories = listOf(acc("acc_shell_crown", color = 0xFFC2D9, color2 = 0xFFE9B8))),
        CharacterDef("goldie", "Goldie", Rarity.LEGENDARY, 0, Currency.REWARD, 0xFFCB2E, 0xFFF2B0, accessories = listOf(acc("acc_star_halo", color = 0xFFE36B, color2 = 0xFFFFFF)), blurb = "Awarded for clearing Rising Tide."),
        CharacterDef("rainbow", "Prism", Rarity.LEGENDARY, 0, Currency.REWARD, 0xFF5FA8, 0x7BE36B, accessories = listOf(acc("acc_unicorn_horn", color = 0xFFFFFF, color2 = 0x2E9BFF)), blurb = "Found at the end of the Trophy Road."),
    )

    val hats: List<HatDef> = listOf(
        HatDef(NO_HAT, "No Hat", Rarity.COMMON, 0, Currency.COINS, "", 0, 0),
        HatDef("cap", "Beach Cap", Rarity.COMMON, 400, Currency.COINS, "hat_cap", 0xFF6B57, 0xFFFFFF),
        HatDef("bucket", "Bucket Hat", Rarity.COMMON, 400, Currency.COINS, "hat_bucket", 0xFFD23F, 0xFF8A3D),
        HatDef("straw", "Straw Sun Hat", Rarity.COMMON, 600, Currency.COINS, "hat_straw", 0xF2D28A, 0xFF5FA8),
        HatDef("flower", "Hibiscus", Rarity.COMMON, 600, Currency.COINS, "hat_flower", 0xFF4F7B, 0xFFD23F),
        HatDef("visor", "Lifeguard Visor", Rarity.RARE, 1500, Currency.COINS, "hat_visor", 0xE8433A, 0xFFFFFF),
        HatDef("party", "Party Cone", Rarity.RARE, 1500, Currency.COINS, "hat_party", 0x9B6BFF, 0xFFD23F),
        HatDef("propeller", "Propeller Cap", Rarity.RARE, 1800, Currency.COINS, "hat_propeller", 0x2E9BFF, 0xFFD23F),
        HatDef("headphones", "Beat Cans", Rarity.RARE, 1800, Currency.COINS, "hat_headphones", 0x2B2B33, 0x19C6C2),
        HatDef("snorkel", "Snorkel Mask", Rarity.RARE, 1800, Currency.COINS, "hat_snorkel", 0xFFD23F, 0x9FE7FF),
        HatDef("chef", "Chef Hat", Rarity.EPIC, 4000, Currency.COINS, "hat_chef", 0xFFFFFF, 0xE6EEF5),
        HatDef("pirate_hat", "Tricorn", Rarity.EPIC, 4000, Currency.COINS, "hat_pirate", 0x2B2B33, 0xFFD23F),
        HatDef("cowboy", "Ranch Hat", Rarity.EPIC, 4000, Currency.COINS, "hat_cowboy", 0xB07A4A, 0x5A3A22),
        HatDef("tophat", "Top Hat", Rarity.EPIC, 4000, Currency.COINS, "hat_tophat", 0x2B2B33, 0xFF5FA8),
        HatDef("viking", "Foam Viking", Rarity.EPIC, 4500, Currency.COINS, "hat_viking", 0xB9C7D6, 0xFFE9B8),
        HatDef("crown", "Royal Crown", Rarity.LEGENDARY, 50, Currency.GEMS, "hat_crown", 0xFFD23F, 0xFF5FA8),
        HatDef("halo", "Halo", Rarity.LEGENDARY, 50, Currency.GEMS, "hat_halo", 0xFFF2A6, 0xFFFFFF),
        HatDef("wizard", "Star Wizard", Rarity.LEGENDARY, 50, Currency.GEMS, "hat_wizard", 0x3B3FBF, 0xFFD23F),
    )

    // Trail sprite ids match game.render.ParticleAtlas constants.
    val trails: List<TrailDef> = listOf(
        TrailDef(NO_TRAIL, "No Trail", Rarity.COMMON, 0, Currency.COINS, 0, intArrayOf()),
        TrailDef("bubbles", "Bubbles", Rarity.COMMON, 700, Currency.COINS, 16, intArrayOf(0xFFBFF4FF.toInt(), 0xFFFFFFFF.toInt())),
        TrailDef("sparkles", "Sparkles", Rarity.COMMON, 900, Currency.COINS, 4, intArrayOf(0xFFFFF2A6.toInt(), 0xFFFFFFFF.toInt()), additive = true),
        TrailDef("hearts", "Hearts", Rarity.RARE, 2000, Currency.COINS, 2, intArrayOf(0xFFFF5FA8.toInt(), 0xFFFF8FC7.toInt())),
        TrailDef("stars", "Stars", Rarity.RARE, 2000, Currency.COINS, 1, intArrayOf(0xFFFFD23F.toInt(), 0xFFFFB13D.toInt())),
        TrailDef("notes", "Music Notes", Rarity.RARE, 2200, Currency.COINS, 5, intArrayOf(0xFF9B6BFF.toInt(), 0xFF2E9BFF.toInt(), 0xFFFF5FA8.toInt())),
        TrailDef("confetti", "Confetti", Rarity.EPIC, 4500, Currency.COINS, 8, intArrayOf(0xFFFF6B57.toInt(), 0xFFFFD23F.toInt(), 0xFF7BE36B.toInt(), 0xFF2E9BFF.toInt(), 0xFFFF5FA8.toInt())),
        TrailDef("petals", "Petals", Rarity.EPIC, 4500, Currency.COINS, 17, intArrayOf(0xFFFF8FC7.toInt(), 0xFFFFC2D9.toInt())),
        TrailDef("leaves", "Palm Leaves", Rarity.EPIC, 4500, Currency.COINS, 18, intArrayOf(0xFF4FC46A.toInt(), 0xFF7BE36B.toInt())),
        TrailDef("rainbow", "Rainbow", Rarity.EPIC, 5000, Currency.COINS, 0, intArrayOf(0xFFFF6B57.toInt(), 0xFFFFD23F.toInt(), 0xFF7BE36B.toInt(), 0xFF2E9BFF.toInt(), 0xFF9B6BFF.toInt()), additive = true),
        TrailDef("flames", "Toasty", Rarity.LEGENDARY, 60, Currency.GEMS, 19, intArrayOf(0xFFFFD23F.toInt(), 0xFFFF6B2E.toInt()), additive = true),
        TrailDef("coins", "Coin Shower", Rarity.LEGENDARY, 60, Currency.GEMS, 20, intArrayOf(0xFFFFD23F.toInt())),
        TrailDef("snowflakes", "Snowflakes", Rarity.LEGENDARY, 60, Currency.GEMS, 3, intArrayOf(0xFFFFFFFF.toInt(), 0xFFBFE8FF.toInt())),
    )

    val dances: List<DanceDef> = listOf(
        DanceDef("hop", "Happy Hop", Rarity.COMMON, 0, Currency.COINS),
        DanceDef("wave", "Big Wave", Rarity.COMMON, 500, Currency.COINS),
        DanceDef("cheer", "Cheerleader", Rarity.COMMON, 800, Currency.COINS),
        DanceDef("spin", "Twirl", Rarity.RARE, 1800, Currency.COINS),
        DanceDef("flex", "Muscle Flex", Rarity.RARE, 1800, Currency.COINS),
        DanceDef("jacks", "Jumping Jacks", Rarity.RARE, 2000, Currency.COINS),
        DanceDef("robot", "Robot", Rarity.EPIC, 4000, Currency.COINS),
        DanceDef("chicken", "Chicken Strut", Rarity.EPIC, 4000, Currency.COINS),
        DanceDef("sway", "Island Sway", Rarity.EPIC, 4000, Currency.COINS),
        DanceDef("backflip", "Backflip", Rarity.LEGENDARY, 70, Currency.GEMS),
    )

    private val charMap = characters.associateBy { it.id }
    private val hatMap = hats.associateBy { it.id }
    private val trailMap = trails.associateBy { it.id }
    private val danceMap = dances.associateBy { it.id }

    fun character(id: String) = charMap[id] ?: characters[0]
    fun hat(id: String) = hatMap[id] ?: hats[0]
    fun trail(id: String) = trailMap[id] ?: trails[0]
    fun dance(id: String) = danceMap[id] ?: dances[0]

    /** Rival bots race as the kid racers (never as reward-only characters). */
    val rivalPool: List<CharacterDef> = kids

    /** Headwear that suits the kids (rivals sometimes wear one). */
    val rivalHats: List<HatDef> = listOf("cap", "bucket", "visor", "headphones", "propeller").map { hat(it) }
}
