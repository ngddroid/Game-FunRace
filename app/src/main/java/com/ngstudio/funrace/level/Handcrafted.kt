package com.ngstudio.funrace.level

import com.ngstudio.funrace.game.sim.HazType
import com.ngstudio.funrace.game.sim.RaceKind
import com.ngstudio.funrace.level.Piece.Checkpoint
import com.ngstudio.funrace.level.Piece.Deck
import com.ngstudio.funrace.level.Piece.Turn

/**
 * Levels 1..10, tuned by hand to teach one idea at a time: hazards are slow with wide safe windows
 * and checkpoints sit before every hazard. Rivals race for real from level 1 (a little gentler in
 * the first levels), so dawdling or getting bonked can cost you the podium.
 */
object Handcrafted {
    private fun H(t: HazType, at: Float, period: Float = 3.4f, phase: Float = 0f, side: Float = 1f, p: Float = 0f, q: Float = 0f) = HazSpec(t, at, period, phase, side, p, q)

    private fun spec(level: Int, name: String, intro: String, newThing: String, rivals: Int, pieces: List<Piece>, kind: RaceKind = RaceKind.TUTORIAL, chaser: Float = 0f, startY: Float = 3f) =
        LevelSpec(
            level = level, seed = 1000L + level, name = name, world = World.LAGOON, time = TimeOfDay.NOON, kind = kind,
            width = 4.6f, startY = startY, pieces = pieces, rivals = rivals, rivalSkill = 0.46f + level * 0.02f, difficulty = 0.05f + level * 0.01f,
            chaserSpeed = chaser, chaserDelay = 5f, intro = intro, newThing = newThing,
        )

    fun get(level: Int): LevelSpec = when (level) {
        1 -> spec(1, "First Splash", "Hold to run, let go to stop, drag to steer. Dodge the pushers!", "Bumper Pusher", 3, listOf(
            Deck(12f, rails = true, coins = 6),
            Checkpoint,
            Deck(12f, listOf(H(HazType.PUSHER, 6f, 3.6f, 0.4f, 1f, 0.55f)), rails = true),
            Turn(25f, 14f, coins = 6),
            Checkpoint,
            Deck(12f, listOf(H(HazType.PUSHER, 6f, 3.6f, 1.8f, -1f, 0.55f)), rails = true),
            Deck(8f, rails = true, coins = 5),
            Checkpoint,
            Deck(12f, listOf(H(HazType.PUSHER, 6f, 3.4f, 0.9f, 1f, 0.6f)), rails = true),
            Turn(-20f, 12f, coins = 5),
        ))
        2 -> spec(2, "Swing Time", "Rubber ducks and a giant mallet swing across. Watch them, then dash!", "Duck Pendulum + Mega Mallet", 3, listOf(
            Deck(10f, rails = true, coins = 5),
            Checkpoint,
            Deck(10f, listOf(H(HazType.PENDULUM, 5f, 3.6f, 0f, p = 0.82f))),
            Turn(30f, 14f, coins = 6),
            Checkpoint,
            Deck(12f, listOf(H(HazType.PENDULUM, 6f, 3.4f, 1.5f, p = 0.85f))),
            Deck(8f, rails = true, coins = 5),
            Checkpoint,
            Deck(10f, listOf(H(HazType.PENDULUM, 5f, 3.4f, 0.8f, p = 0.85f, q = 1f))),
            Checkpoint,
            Deck(10f, listOf(H(HazType.HAMMER, 5f, 4.0f, 0.4f, p = 0.9f))),
            Turn(-25f, 12f, coins = 5),
        ))
        3 -> spec(3, "Spin Cycle", "Noodles spin low. Swipe up or tap JUMP to hop right over them!", "Noodle Sweeper", 3, listOf(
            Deck(10f, rails = true, coins = 5),
            Checkpoint,
            Deck(12f, listOf(H(HazType.SWEEPER, 6f, phase = 0f, p = 1.45f, q = 1f))),
            Turn(30f, 14f, coins = 6),
            Checkpoint,
            Deck(12f, listOf(H(HazType.SWEEPER, 6f, phase = 1f, p = -1.2f, q = 1f))),
            Deck(8f, rails = true, coins = 6),
            Checkpoint,
            Deck(10f, listOf(H(HazType.PENDULUM, 5f, 3.4f, 0.6f, p = 0.85f))),
            Checkpoint,
            Deck(12f, listOf(H(HazType.SWEEPER, 6f, phase = 0.5f, p = 1.55f, q = 1f))),
            Turn(-20f, 12f, coins = 5),
        ))
        4 -> spec(4, "Bounce Bay", "Bounce pads launch you across the water. The spin bar is too high to hop: let it swing away, then go!", "Bounce Pads + Spin Bar", 3, listOf(
            Deck(10f, rails = true, coins = 5),
            Piece.Jump(7f, 2.4f),
            Deck(6f, rails = true),
            Checkpoint,
            Deck(12f, listOf(H(HazType.PUSHER, 6f, 3.4f, 0.5f, 1f, 0.6f)), rails = false),
            Piece.Jump(8f, 3f),
            Deck(5f, rails = true),
            Checkpoint,
            Deck(17f, listOf(H(HazType.SPINNER, 8.5f, phase = 0.6f, side = 1f, p = 1.6f))),
            Turn(25f, 14f, coins = 6),
            Checkpoint,
            Deck(12f, listOf(H(HazType.SWEEPER, 6f, phase = 0.3f, p = 1.5f, q = 1f))),
            Piece.Jump(10f, 2.6f, hops = 2),
            Deck(6f, rails = true, coins = 4),
        ))
        5 -> spec(5, "Slide Away", "Slip past a hazard at the last moment for a PERFECT and charge your Turbo!", "Water Slide + Turbo", 3, listOf(
            Deck(10f, rails = true, coins = 5),
            Checkpoint,
            Deck(10f, listOf(H(HazType.PENDULUM, 5f, 3.2f, 0.3f, p = 0.85f))),
            Piece.Ramp(10f, 3f),
            Deck(4f, rails = true),
            Piece.Slide(28f, 5f, 28f, 12),
            Deck(4f, rails = true),
            Checkpoint,
            Deck(12f, listOf(H(HazType.SWEEPER, 6f, phase = 0.2f, p = 1.25f, q = 1f))),
            Turn(25f, 14f, coins = 6),
            Checkpoint,
            Deck(14f, listOf(H(HazType.PUSHER, 5f, 3.2f, 0.4f, 1f, 0.6f), H(HazType.PUSHER, 10f, 3.2f, 1.9f, -1f, 0.6f))),
            Turn(-20f, 12f, coins = 5),
        ))
        6 -> spec(6, "Up We Go", "Rollers squeeze in from both sides: run through when they open. Then climb the wall and dodge the coconuts!", "Roller Squeeze + Climbing Wall", 4, listOf(
            Deck(10f, rails = true, coins = 5),
            Checkpoint,
            Deck(10f, listOf(H(HazType.ROLLERS, 5f, 3.4f, 0.7f))),
            Deck(4f, rails = true),
            Piece.Climb(4f, 3.2f, 0.4f),
            Deck(8f, rails = true, coins = 5),
            Checkpoint,
            Deck(10f, listOf(H(HazType.PENDULUM, 5f, 3.2f, 1.0f, p = 0.85f))),
            Turn(30f, 14f, coins = 6),
            Checkpoint,
            Deck(12f, listOf(H(HazType.SWEEPER, 6f, phase = 0.6f, p = 1.55f, q = 1f))),
            Piece.Ramp(12f, -3f),
            Deck(6f, rails = true, coins = 4),
        ))
        7 -> spec(7, "Floaty Crossing", "Wait for the floaty raft, hop on, ride across!", "Floaty Ferry", 4, listOf(
            Deck(10f, rails = true, coins = 5),
            Checkpoint,
            Deck(10f, listOf(H(HazType.PENDULUM, 5f, 3.2f, 0.4f, p = 0.85f))),
            Piece.Ramp(12f, -2.7f),
            Deck(5f, rails = true),
            Checkpoint,
            Piece.Raft(9f, 6.5f, 0.5f),
            Deck(5f, rails = true),
            Piece.Ramp(12f, 2.7f),
            Checkpoint,
            Deck(12f, listOf(H(HazType.PUSHER, 6f, 3.2f, 1.3f, 1f, 0.65f))),
            Turn(-30f, 14f, coins = 6),
            Checkpoint,
            Deck(12f, listOf(H(HazType.SWEEPER, 6f, phase = 2.0f, p = -1.25f, q = 1f))),
            Deck(6f, rails = true, coins = 4),
        ))
        8 -> spec(8, "Punch Party", "Gloves wind up before they punch. Spot the wiggle!", "Boxing Gloves", 4, listOf(
            Deck(10f, rails = true, coins = 5),
            Checkpoint,
            Deck(10f, listOf(H(HazType.GLOVES, 5f, 3.2f, 0.2f, 1f, 0.7f))),
            Turn(25f, 14f, coins = 6),
            Checkpoint,
            Deck(10f, listOf(H(HazType.GLOVES, 5f, 3.2f, 1.6f, -1f, 0.7f))),
            Checkpoint,
            Deck(14f, listOf(H(HazType.TURNTABLE, 7f, phase = 0f, p = 0.95f))),
            Deck(6f, rails = true, coins = 4),
            Checkpoint,
            Deck(12f, listOf(H(HazType.PENDULUM, 6f, 3.2f, 0.9f, p = 0.88f))),
            Piece.Jump(8f, 2.6f),
            Deck(4f, rails = true),
            Checkpoint,
            Deck(12f, listOf(H(HazType.GLOVES, 6f, 3.0f, 0.5f, 1f, 0.72f))),
            Turn(-25f, 12f, coins = 5),
        ))
        9 -> spec(9, "Zip & Swing", "Ziplines and rope swings carry you over the lagoon.", "Zipline + Rope Swing", 4, listOf(
            Deck(10f, rails = true, coins = 5),
            Checkpoint,
            Deck(10f, listOf(H(HazType.SWEEPER, 5f, phase = 0.3f, p = 1.6f, q = 1f))),
            Deck(3f, rails = true),
            Piece.Zip(16f, 1.6f),
            Deck(6f, rails = true, coins = 4),
            Checkpoint,
            Deck(10f, listOf(H(HazType.GLOVES, 5f, 3.0f, 2.0f, -1f, 0.72f))),
            Turn(30f, 14f, coins = 6),
            Checkpoint,
            Deck(10f, listOf(H(HazType.PENDULUM, 5f, 3.2f, 1.2f, p = 0.88f))),
            Deck(3f, rails = true),
            Piece.Swing(10f, 6.5f),
            Deck(6f, rails = true, coins = 4),
            Checkpoint,
            Deck(12f, listOf(H(HazType.PUSHER, 4f, 3.0f, 0.2f, 1f, 0.65f), H(HazType.PUSHER, 9f, 3.0f, 1.7f, -1f, 0.65f))),
            Turn(-20f, 12f, coins = 4),
        ))
        else -> spec(10, "Rising Tide", "The lagoon is flooding behind you. Keep moving!", "Boss: Rising Tide", 4, listOf(
            Deck(10f, rails = true, coins = 5),
            Checkpoint,
            Deck(10f, listOf(H(HazType.PUSHER, 5f, 3.0f, 0.4f, 1f, 0.6f))),
            Piece.Jump(7f, 2.4f),
            Deck(5f, rails = true),
            Checkpoint,
            Deck(10f, listOf(H(HazType.PENDULUM, 5f, 3.0f, 0.9f, p = 0.85f))),
            Turn(25f, 14f, coins = 6),
            Checkpoint,
            Deck(12f, listOf(H(HazType.SWEEPER, 6f, phase = 0.5f, p = 1.3f, q = 1f))),
            Piece.Ramp(10f, 2.5f),
            Deck(4f, rails = true),
            Piece.Slide(24f, 4f, 25f, 10),
            Deck(4f, rails = true),
            Checkpoint,
            Deck(12f, listOf(H(HazType.GLOVES, 6f, 3.0f, 0.3f, -1f, 0.7f))),
            Turn(-20f, 12f, coins = 5),
        ), kind = RaceKind.BOSS_TIDE, chaser = 2.6f)
    }
}
