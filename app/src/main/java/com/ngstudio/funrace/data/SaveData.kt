package com.ngstudio.funrace.data

import kotlinx.serialization.Serializable

/**
 * The whole persistent game state. Immutable snapshot, replaced on every change through
 * [SaveRepository.update]. Unknown/missing fields fall back to defaults, so old saves keep loading.
 */
@Serializable
data class SaveData(
    val version: Int = 1,
    val profile: Profile = Profile(),
    val settings: Settings = Settings(),
    val wallet: Wallet = Wallet(),
    val progress: Progress = Progress(),
    val collection: Collection = Collection(),
    val daily: DailyState = DailyState(),
    val stats: Stats = Stats(),
    /** Achievement id -> highest tier claimed. */
    val achievements: Map<String, Int> = emptyMap(),
)

@Serializable
data class Profile(
    val name: String = "Player",
    val onboardingDone: Boolean = false,
    val character: String = Catalog.DEFAULT_CHARACTER,
    val hat: String = Catalog.NO_HAT,
    val trail: String = Catalog.NO_TRAIL,
    val dance: String = Catalog.DEFAULT_DANCE,
    val createdEpochDay: Long = 0,
)

@Serializable
data class Settings(
    val music: Boolean = true,
    val musicVolume: Float = 0.7f,
    val sfx: Boolean = true,
    val sfxVolume: Float = 0.9f,
    val haptics: Boolean = true,
    /** 0 auto, 1 low, 2 medium, 3 high */
    val quality: Int = 0,
    val reducedMotion: Boolean = false,
    val leftHanded: Boolean = false,
    val showFps: Boolean = false,
    /** Daily reward reminders and push messages. */
    val notifications: Boolean = true,
    /** Sends usage statistics (Google Analytics) and crash reports (Crashlytics). */
    val shareData: Boolean = true,
    /** The game has asked once for Android 13's notification permission. */
    val notifAsked: Boolean = false,
)

@Serializable
data class Wallet(val coins: Int = 0, val gems: Int = 0)

@Serializable
data class Progress(
    /** Highest level the player may play through normal progression (starts at 1). */
    val highestUnlocked: Int = 1,
    /** Levels unlocked out of order (debug: by watching a mock ad). */
    val extraUnlocked: Set<Int> = emptySet(),
    /** Stars per level as digits '0'..'3'; index 0 = level 1. Grows as levels are played. */
    val stars: String = "",
    /** Time-trial medals per level: 1 bronze, 2 silver, 3 gold. */
    val medals: Map<Int, Int> = emptyMap(),
    /** Best time-trial times in milliseconds. */
    val bestTimesMs: Map<Int, Int> = emptyMap(),
    val trophies: Int = 0,
    /** Number of Trophy Road rewards already claimed. */
    val trophyRoadClaimed: Int = 0,
    /** Number of 10-level milestone chests claimed. */
    val milestonesClaimed: Int = 0,
    val lastPlayed: Int = 1,
    /** Boosters owned (id -> count). */
    val boosters: Map<String, Int> = emptyMap(),
) {
    fun starsFor(level: Int): Int {
        val i = level - 1
        return if (i in stars.indices) stars[i] - '0' else 0
    }

    fun isUnlocked(level: Int): Boolean = level <= highestUnlocked || level in extraUnlocked

    fun withStars(level: Int, value: Int): Progress {
        val i = level - 1
        val sb = StringBuilder(stars)
        while (sb.length <= i) sb.append('0')
        if (value > sb[i] - '0') sb.setCharAt(i, ('0' + value.coerceIn(0, 3)))
        return copy(stars = sb.toString())
    }

    fun totalStars(): Int = stars.sumOf { it - '0' }
}

@Serializable
data class Collection(
    val owned: Set<String> = Catalog.STARTER_ITEMS,
    /** Items unlocked but not yet viewed (drives "new" badges). */
    val unseen: Set<String> = emptySet(),
)

@Serializable
data class MissionState(val id: String, val target: Int, val progress: Int = 0, val claimed: Boolean = false)

@Serializable
data class DailyState(
    /** Login calendar: index of the next day to claim (0..6), and the epoch day it was last claimed. */
    val loginIndex: Int = 0,
    val loginLastDay: Long = -1,
    val wheelDay: Long = -1,
    val wheelFreeUsed: Boolean = false,
    val wheelAdSpins: Int = 0,
    val missionsDay: Long = -1,
    val missions: List<MissionState> = emptyList(),
    val challengeDay: Long = -1,
    val challengeDone: Boolean = false,
    val challengeBestRank: Int = 0,
)

@Serializable
data class Stats(
    val races: Int = 0,
    val wins: Int = 0,
    val podiums: Int = 0,
    val perfects: Int = 0,
    val splashes: Int = 0,
    val bonks: Int = 0,
    val meters: Long = 0,
    val checkpoints: Int = 0,
    val coinsEarned: Long = 0,
    val playSeconds: Long = 0,
    val bossesBeaten: Int = 0,
    val dailyChallenges: Int = 0,
    val goldMedals: Int = 0,
    val turbos: Int = 0,
)
