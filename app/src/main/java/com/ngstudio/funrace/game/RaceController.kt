package com.ngstudio.funrace.game

import com.ngstudio.funrace.audio.AudioEngine
import com.ngstudio.funrace.audio.Haptic
import com.ngstudio.funrace.audio.Haptics
import com.ngstudio.funrace.audio.Sfx
import com.ngstudio.funrace.game.render.Look
import com.ngstudio.funrace.game.sim.HazType
import com.ngstudio.funrace.game.sim.Mode
import com.ngstudio.funrace.game.sim.RaceKind
import com.ngstudio.funrace.game.sim.RaceRules
import com.ngstudio.funrace.game.sim.RaceSim
import com.ngstudio.funrace.game.sim.SimEvent
import com.ngstudio.funrace.level.LevelBuilder
import com.ngstudio.funrace.level.RaceSetup
import com.ngstudio.funrace.level.StepSound
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class RacePhase { INTRO, COUNTDOWN, RACING, FINISHED, PAUSED }

/** What the Compose HUD shows; published ~20 times per second. */
data class HudState(
    val phase: RacePhase = RacePhase.INTRO,
    val countdown: Int = 3,
    val progress: List<Pair<Float, Int>> = emptyList(), // (progress 0..1, look colour) per racer, player first
    val rank: Int = 1,
    val racers: Int = 1,
    val coins: Int = 0,
    val turbo: Float = 0f,
    val turboActive: Boolean = false,
    val checkpoint: Int = -1,
    val chaserProgress: Float = -1f,
    val stormWarn: Float = 0f,
    val holding: Boolean = false,
    val fps: Int = 0,
    val tutorial: String = "",
)

/** Short-lived popup words above the player ("PERFECT!", "SPLASH!"...). */
data class Popup(val text: String, val color: Long, val id: Int)

class RaceResult(
    val level: Int,
    val kind: RaceKind,
    val rank: Int,
    val finished: Boolean,
    val coins: Int,
    val totalCoins: Int,
    val perfects: Int,
    val splashes: Int,
    val bonks: Int,
    val timeMs: Int,
    val meters: Int,
    val turbos: Int,
    val standings: List<Pair<String, Int>>, // name, look index (-1 = player)
)

/**
 * Owns one race: runs the fixed-step simulation on the GL thread, turns sim events into sound,
 * haptics and HUD updates, and reports the [RaceResult] when it's over.
 */
class RaceController(
    val setup: RaceSetup,
    val rules: RaceRules,
    val playerLook: Look,
    val rivalLooks: List<Look>,
    val playerTrail: Int,
    val playerDance: String,
    private val audio: AudioEngine,
    private val haptics: Haptics,
    val reducedMotion: Boolean,
    val autopilot: Boolean,
    private val boosters: Set<String>,
    private val onFinished: (RaceResult) -> Unit,
) {
    val sim: RaceSim = LevelBuilder.createSim(setup, rules, IntArray(rivalLooks.size) { it })
    val spec = setup.spec
    @Volatile var touching = false
    /** Desired lateral position while dragging (NaN = not steering). */
    @Volatile var steer = Float.NaN
    @Volatile private var jumpRequest = false
    val playerD: Float get() = sim.player.d

    fun jump() { if (phase == RacePhase.RACING) jumpRequest = true }
    @Volatile var paused = false
    var phase = RacePhase.INTRO
        private set
    var phaseT = 0f
        private set
    var fps = 0
    private var accum = 0f
    private var hudClock = 0f
    private var resultSent = false
    var finishT = 0f
        private set
    private var turbos = 0
    private var popupSeq = 0
    private var stepAlt = false
    private var loopStream = 0
    private var loopSfx: Sfx? = null
    private var tutorialShown = 0f

    /** Events for the renderer (FX), drained on the GL thread each frame. */
    val fxEvents = ArrayList<SimEvent>(64)

    private val _hud = MutableStateFlow(HudState())
    val hud: StateFlow<HudState> = _hud.asStateFlow()
    val popups = ConcurrentLinkedQueue<Popup>()
    private val _popup = MutableStateFlow<Popup?>(null)
    val popup: StateFlow<Popup?> = _popup.asStateFlow()

    val introDuration = if (spec.level <= 3) 2.2f else 2.8f

    init {
        sim.autopilot = autopilot
        if (autopilot) sim.player.skill = 0.85f
        val p = sim.player
        if ("sneakers" in boosters) p.boostTime = 8f
        if ("shield" in boosters) p.shield = 1
        if ("magnet" in boosters) p.magnetTime = 30f
    }

    fun update(dt: Float) {
        fxEvents.clear()
        if (paused) { if (phase != RacePhase.PAUSED) Unit; return }
        phaseT += dt
        when (phase) {
            RacePhase.INTRO -> if (phaseT >= introDuration) { phase = RacePhase.COUNTDOWN; phaseT = 0f; audio.play(Sfx.COUNTDOWN); haptics.play(Haptic.TICK) }
            RacePhase.COUNTDOWN -> {
                val prev = 3 - ((phaseT - dt) / 0.8f).toInt()
                val now = 3 - (phaseT / 0.8f).toInt()
                if (now != prev && now in 1..2) { audio.play(Sfx.COUNTDOWN, pitch = 1f + (3 - now) * 0.06f); haptics.play(Haptic.TICK) }
                if (phaseT >= 2.4f) {
                    phase = RacePhase.RACING; phaseT = 0f; sim.started = true
                    audio.play(Sfx.GO); haptics.play(Haptic.CLICK)
                    pop("SPLASH!", 0xFF2E9BFF)
                }
            }
            RacePhase.RACING, RacePhase.FINISHED -> {
                if (!autopilot) {
                    sim.player.hold = touching && phase == RacePhase.RACING
                    sim.player.steer = if (phase == RacePhase.RACING) steer else Float.NaN
                    if (jumpRequest) { jumpRequest = false; sim.player.jumpQueued = true }
                }
                accum += dt
                var steps = 0
                while (accum >= SIM_DT && steps < 4) {
                    sim.step(SIM_DT)
                    handleEvents()
                    accum -= SIM_DT
                    steps++
                }
                if (steps == 4) accum = 0f
                updateLoops()
                if (phase == RacePhase.RACING && sim.over) {
                    phase = RacePhase.FINISHED; phaseT = 0f; finishT = sim.time
                    stopLoop()
                    val p = sim.player
                    val win = p.finished && p.rank <= podiumCutoff() || spec.kind == RaceKind.BONUS && p.finished
                    if (win) { audio.play(Sfx.WIN); audio.play(Sfx.CHEER, 0.7f); haptics.play(Haptic.SUCCESS) } else { audio.play(Sfx.LOSE); haptics.play(Haptic.ERROR) }
                    audio.duckMusic(0.25f, 2600)
                }
                if (phase == RacePhase.FINISHED && phaseT > 2.6f && !resultSent) {
                    resultSent = true
                    onFinished(result())
                }
            }
            RacePhase.PAUSED -> Unit
        }
        hudClock += dt
        if (hudClock > 0.05f) { hudClock = 0f; publishHud() }
    }

    fun podiumCutoff(): Int = if (sim.racers.size >= 4) 3 else max(1, sim.racers.size - 1)

    private fun pop(text: String, color: Long) {
        val p = Popup(text, color, ++popupSeq)
        _popup.value = p
    }

    private fun stepSfx(): Sfx = when (spec.world.step) {
        StepSound.VINYL -> if (stepAlt) Sfx.STEP_VINYL1 else Sfx.STEP_VINYL2
        StepSound.WOOD -> Sfx.STEP_WOOD
        StepSound.STONE -> Sfx.STEP_STONE
        StepSound.ICE -> Sfx.STEP_ICE
    }

    private fun handleEvents() {
        val p = sim.player
        for (e in sim.events) {
            fxEvents += e
            val mine = e.racer == p.index
            val near = e.racer >= 0 && abs(sim.racers[e.racer].s - p.s) < 12f
            when (e.type) {
                SimEvent.Type.STEP -> if (mine) { stepAlt = !stepAlt; audio.play(stepSfx(), 0.55f, 0.95f + (if (stepAlt) 0.05f else 0f)) }
                SimEvent.Type.KNOCK -> if (mine || near) {
                    audio.play(Sfx.BONK, if (mine) 1f else 0.45f, 0.95f + (e.racer % 3) * 0.06f)
                    audio.play(Sfx.SQUEAK, if (mine) 0.6f else 0.25f)
                    if (mine) { haptics.play(Haptic.BONK); pop(listOf("BONK!", "OOF!", "WHOOPS!", "BOING!")[(sim.time * 10).toInt() % 4], 0xFFFF6B57) }
                }
                SimEvent.Type.BLOCKED -> if (mine) audio.play(Sfx.SQUEAK, 0.4f, 1.2f)
                SimEvent.Type.SPLASH -> if (mine || near) {
                    audio.play(Sfx.SPLASH_BIG, if (mine) 1f else 0.5f)
                    if (mine) { haptics.play(Haptic.SPLASH); pop("SPLASH!", 0xFF19C6C2) }
                }
                SimEvent.Type.RESPAWN -> if (mine) audio.play(Sfx.POP, 0.7f)
                SimEvent.Type.CHECKPOINT -> if (mine) { audio.play(Sfx.CHECKPOINT); haptics.play(Haptic.TICK); pop("CHECKPOINT!", 0xFF7BE36B) }
                SimEvent.Type.COIN -> if (mine) audio.play(Sfx.COIN, 0.6f, 1f + (p.coins % 5) * 0.04f)
                SimEvent.Type.FINISH -> {
                    if (mine) { audio.play(Sfx.CHEER); haptics.play(Haptic.SUCCESS); pop(rankWord(e.v), 0xFFFFD23F) }
                }
                SimEvent.Type.NICE -> if (mine) { audio.play(Sfx.NICE); haptics.play(Haptic.TICK); pop("NICE!", 0xFF2E9BFF) }
                SimEvent.Type.PERFECT -> if (mine) { audio.play(Sfx.PERFECT); haptics.play(Haptic.CLICK); pop("PERFECT!", 0xFFFF5FA8) }
                SimEvent.Type.TURBO -> if (mine) { turbos++; audio.play(Sfx.TURBO); haptics.play(Haptic.HEAVY); pop("TURBO!", 0xFFFFD23F) }
                SimEvent.Type.SHIELD_POP -> if (mine) { audio.play(Sfx.SHIELD_POP); haptics.play(Haptic.CLICK); pop("SAVED!", 0xFF9FE7FF) }
                SimEvent.Type.JUMP -> if (e.v == -1) { if (mine) { audio.play(Sfx.WHOOSH, 0.7f, 1.25f); haptics.play(Haptic.TICK) } }
                    else if (mine || near) audio.play(if (e.v == com.ngstudio.funrace.game.sim.SegKind.TRAMPOLINE.ordinal) Sfx.TRAMPOLINE else Sfx.BOING, if (mine) 0.9f else 0.35f)
                SimEvent.Type.LAND -> if (mine) audio.play(stepSfx(), 0.8f, 0.8f)
                SimEvent.Type.GRAB -> if (mine) audio.play(Sfx.CLIMB, 0.6f)
                SimEvent.Type.ZIP -> if (mine) audio.play(Sfx.WHOOSH)
                SimEvent.Type.SWING -> if (mine) { audio.play(Sfx.ROPE_CREAK); audio.play(Sfx.WHOOSH, 0.6f) }
                SimEvent.Type.SLIDE -> if (mine) audio.play(Sfx.WHOOSH)
                SimEvent.Type.HAZARD -> {
                    val h = sim.hazards[e.v]
                    if (abs(h.s0 - p.s) < 16f) {
                        val v = (1f - abs(h.s0 - p.s) / 16f).coerceIn(0.15f, 1f)
                        when (h.type) {
                            HazType.PUSHER -> audio.play(Sfx.WHOOSH, v * 0.6f, 0.8f)
                            HazType.GLOVES -> audio.play(Sfx.GLOVE_PUNCH, v)
                            HazType.PRESS -> { audio.play(Sfx.PRESS_THUMP, v); if (v > 0.6f) haptics.play(Haptic.TICK) }
                            HazType.CANNON -> audio.play(Sfx.CANNON_POP, v)
                            HazType.FAN -> audio.play(Sfx.WIND_GUST, v)
                            HazType.GATES -> audio.play(Sfx.GATE, v)
                            HazType.WAVE -> audio.play(Sfx.WAVE_SURGE, v)
                            HazType.PENDULUM -> audio.play(Sfx.WHOOSH, v * 0.35f, 0.7f)
                            else -> Unit
                        }
                    }
                }
                SimEvent.Type.CAUGHT -> if (mine) { audio.play(Sfx.SPLASH_BIG); if (spec.kind == RaceKind.BOSS_DUCK) audio.play(Sfx.DUCK_QUACK); haptics.play(Haptic.SPLASH); pop("CAUGHT!", 0xFFFF6B57) }
                SimEvent.Type.GUST -> { audio.play(Sfx.WIND_GUST, 0.8f); audio.play(Sfx.THUNDER, 0.5f) }
            }
        }
    }

    private fun rankWord(r: Int) = when (r) { 1 -> "1ST!"; 2 -> "2ND!"; 3 -> "3RD!"; else -> "${r}TH" }

    private fun updateLoops() {
        val p = sim.player
        val want = when (p.mode) { Mode.ZIP -> Sfx.ZIP_LOOP; Mode.SLIDE -> Sfx.SLIDE_LOOP; else -> if (sim.chaser != null) Sfx.TIDE_LOOP else null }
        if (want != loopSfx) {
            stopLoop()
            loopSfx = want
            if (want != null) loopStream = audio.startLoop(want, 0.6f)
        }
        if (loopSfx == Sfx.TIDE_LOOP) {
            val c = sim.chaser!!
            val gap = p.s - c.s(sim.time)
            audio.setLoop(loopStream, ((14f - gap) / 14f).coerceIn(0.08f, 0.8f), 1f)
        } else if (loopSfx != null) audio.setLoop(loopStream, 0.65f, 0.9f + p.speed / 30f)
    }

    private fun stopLoop() { if (loopStream != 0) audio.stopLoop(loopStream); loopStream = 0; loopSfx = null }

    fun release() { stopLoop() }

    private fun publishHud() {
        val p = sim.player
        val order = sim.standings()
        val rank = order.indexOf(p) + 1
        val prog = ArrayList<Pair<Float, Int>>(sim.racers.size)
        prog += sim.progressOf(p) to playerLook.primary
        for (r in sim.racers) if (!r.isPlayer) prog += sim.progressOf(r) to rivalLooks.getOrElse(r.lookIndex) { playerLook }.primary
        val chaserP = sim.chaser?.let { c -> ((c.s(sim.time) - sim.course.startS) / (sim.course.finishS - sim.course.startS)).coerceIn(-0.05f, 1f) } ?: -1f
        var tip = ""
        if (phase == RacePhase.RACING && spec.level <= 3 && sim.time < 9f) tip = if (!touching) "Hold to run • drag to steer" else ""
        if (phase == RacePhase.RACING && spec.level == 3 && sim.time in 9f..24f) tip = "Swipe up or tap JUMP to hop over noodles!"
        if (phase == RacePhase.RACING && spec.level == 1 && sim.time in 9f..20f && p.speed < 0.2f && !touching) tip = "Let go to stop. Wait for the gap!"
        if (spec.level == 5 && phase == RacePhase.RACING && p.turbo > 0f && p.turbo < 1f && sim.time < 40f) tip = "PERFECTs charge your Turbo!"
        _hud.value = HudState(
            phase = phase,
            countdown = if (phase == RacePhase.COUNTDOWN) 3 - (phaseT / 0.8f).toInt() else 0,
            progress = prog,
            rank = rank,
            racers = sim.racers.size,
            coins = p.coins,
            turbo = p.turbo,
            turboActive = p.turboTime > 0f,
            checkpoint = p.checkpointIdx,
            chaserProgress = chaserP,
            stormWarn = sim.storm?.warn(sim.time) ?: 0f,
            holding = touching,
            fps = fps,
            tutorial = tip,
        )
    }

    fun result(): RaceResult {
        val p = sim.player
        val st = sim.standings()
        // racers that didn't finish are ranked by distance after the finishers
        val rank = if (p.finished) p.rank else st.indexOf(p) + 1
        return RaceResult(
            level = spec.level, kind = spec.kind, rank = rank, finished = p.finished,
            coins = p.coins, totalCoins = sim.totalCoins, perfects = p.perfects, splashes = p.splashes, bonks = p.bonks,
            timeMs = (if (p.finished) p.finishTime else sim.time).times(1000).toInt(), meters = p.meters.toInt(), turbos = turbos,
            standings = st.map { (if (it.isPlayer) "You" else it.name) to (if (it.isPlayer) -1 else it.lookIndex) },
        )
    }

    companion object { const val SIM_DT = 1f / 60f }

    @Suppress("unused")
    private fun clampHud(v: Float) = min(1f, max(0f, v))
}
