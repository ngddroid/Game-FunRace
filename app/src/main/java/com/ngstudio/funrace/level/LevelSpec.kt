package com.ngstudio.funrace.level

import com.ngstudio.funrace.game.sim.HazType
import com.ngstudio.funrace.game.sim.RaceKind

/**
 * One hazard placed on a piece. [at] = offset from the piece start (metres). Meaning of p/q:
 * PUSHER/GLOVES reach fraction, PENDULUM amplitude (rad), SWEEPER/TURNTABLE/PADDLE angular
 * speed (rad/s, signed) with q = arm count, CONVEYOR speed, BARRELS roll speed.
 */
data class HazSpec(
    val type: HazType,
    val at: Float,
    val period: Float = 3f,
    val phase: Float = 0f,
    val side: Float = 1f,
    val p: Float = 0f,
    val q: Float = 0f,
)

sealed class Piece {
    data class Deck(val len: Float, val hazards: List<HazSpec> = emptyList(), val rails: Boolean = false, val coins: Int = 0) : Piece()
    data class Turn(val deg: Float, val len: Float = 14f, val coins: Int = 0, val rails: Boolean = true) : Piece()
    data class Ramp(val len: Float, val rise: Float, val barrels: HazSpec? = null, val rails: Boolean = true) : Piece()
    data class Jump(val len: Float, val apex: Float = 2.6f, val hops: Int = 1) : Piece()
    data class Zip(val len: Float, val drop: Float = 1.5f) : Piece()
    data class Swing(val len: Float, val pivotH: Float = 6.5f) : Piece()
    data class Raft(val len: Float, val period: Float, val phase: Float = 0f) : Piece()
    data class Monkey(val len: Float, val hazards: List<HazSpec> = emptyList()) : Piece()
    data class Beam(val len: Float, val hazards: List<HazSpec> = emptyList()) : Piece()
    data class Climb(val height: Float, val coconutPeriod: Float = 0f, val phase: Float = 0f) : Piece()
    data class Slide(val len: Float, val drop: Float, val bend: Float = 25f, val coins: Int = 10) : Piece()
    data object Checkpoint : Piece()
}

class LevelSpec(
    val level: Int,
    val seed: Long,
    val name: String,
    val world: World,
    val time: TimeOfDay,
    val kind: RaceKind,
    val width: Float,
    val startY: Float,
    val pieces: List<Piece>,
    val rivals: Int,
    val rivalSkill: Float,
    val difficulty: Float,
    val guaranteeWin: Boolean = false,
    val chaserSpeed: Float = 0f,
    val chaserDelay: Float = 4f,
    val stormStrength: Float = 0f,
    /** Short tip shown on the level card / first-time intro. */
    val intro: String = "",
    /** Hazard type introduced by this level (shown with a NEW badge). */
    val newThing: String = "",
) {
    val isBoss get() = kind == RaceKind.BOSS_TIDE || kind == RaceKind.BOSS_DUCK || kind == RaceKind.BOSS_STORM
    val isBonus get() = kind == RaceKind.BONUS
    val hazardCount: Int get() = pieces.sumOf { p ->
        when (p) {
            is Piece.Deck -> p.hazards.size
            is Piece.Monkey -> p.hazards.size
            is Piece.Beam -> p.hazards.size
            is Piece.Ramp -> if (p.barrels != null) 1 else 0
            is Piece.Climb -> if (p.coconutPeriod > 0f) 1 else 0
            is Piece.Raft -> 1
            else -> 0
        }
    }
}
