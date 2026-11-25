package com.ngstudio.funrace.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngstudio.funrace.BuildConfig
import com.ngstudio.funrace.R
import com.ngstudio.funrace.ads.AdConfig
import com.ngstudio.funrace.audio.Music
import com.ngstudio.funrace.audio.Sfx
import com.ngstudio.funrace.data.Catalog
import com.ngstudio.funrace.data.GameRules
import com.ngstudio.funrace.data.Modifier as RaceMod
import com.ngstudio.funrace.data.Payout
import com.ngstudio.funrace.data.Progression
import com.ngstudio.funrace.data.Reward
import com.ngstudio.funrace.game.HudState
import com.ngstudio.funrace.game.RaceController
import com.ngstudio.funrace.game.RacePhase
import com.ngstudio.funrace.game.RaceResult
import com.ngstudio.funrace.game.render.Look
import com.ngstudio.funrace.game.sim.RaceKind
import com.ngstudio.funrace.game.sim.RaceRules
import com.ngstudio.funrace.level.LevelBuilder
import com.ngstudio.funrace.level.LevelGenerator
import com.ngstudio.funrace.level.LevelRepository
import com.ngstudio.funrace.level.LevelSpec
import com.ngstudio.funrace.ui.LaunchOptions
import com.ngstudio.funrace.ui.LocalNav
import com.ngstudio.funrace.ui.PlayMode
import com.ngstudio.funrace.ui.RaceRequest
import com.ngstudio.funrace.ui.Screen
import com.ngstudio.funrace.ui.Tab
import com.ngstudio.funrace.ui.components.Banner
import com.ngstudio.funrace.ui.components.BodyText
import com.ngstudio.funrace.ui.components.Buttons
import com.ngstudio.funrace.ui.components.GameButton
import com.ngstudio.funrace.ui.components.GameDialog
import com.ngstudio.funrace.ui.components.GameProgressBar
import com.ngstudio.funrace.ui.components.LocalContainer
import com.ngstudio.funrace.ui.components.OutlinedText
import com.ngstudio.funrace.ui.components.Panel
import com.ngstudio.funrace.ui.components.UiIcon
import com.ngstudio.funrace.ui.components.formatNum
import com.ngstudio.funrace.ui.components.pressable
import com.ngstudio.funrace.ui.theme.Palette
import com.ngstudio.funrace.ui.theme.Type
import com.ngstudio.funrace.services.GameEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Resolves which spec a request plays (campaign, daily challenge, time trial). */
fun specFor(req: RaceRequest): LevelSpec = when (req.mode) {
    PlayMode.DAILY -> {
        val day = Progression.today()
        val n = GameRules.challengeLevel(day)
        val base = LevelGenerator.generate(n, (day % 200).toInt())
        LevelSpec(base.level, base.seed, "Daily: " + GameRules.challengeModifier(day).title, base.world, base.time, RaceKind.DAILY, base.width, base.startY, base.pieces,
            base.rivals, base.rivalSkill, base.difficulty, intro = GameRules.challengeModifier(day).desc)
    }
    PlayMode.TIME_TRIAL -> LevelRepository.get(req.level).let { s ->
        LevelSpec(s.level, s.seed, "Time Trial: " + s.name, s.world, s.time, RaceKind.TIME_TRIAL, s.width, s.startY, s.pieces, 0, 0f, s.difficulty, intro = "Beat the clock for a medal!")
    }
    else -> LevelRepository.get(req.level)
}

/** Expected expert time for medals: distance at running pace plus a little per hazard. */
fun parTime(spec: LevelSpec): Float {
    val setup = LevelBuilder.build(spec)
    return (setup.course.finishS - setup.course.startS) / (4.6f * RaceRules.PLAYER_EDGE) + spec.hazardCount * 0.7f
}

@Composable
fun RaceScreen(req: RaceRequest) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var controller by remember { mutableStateOf<RaceController?>(null) }
    var result by remember { mutableStateOf<Pair<RaceResult, Payout>?>(null) }
    var paused by remember { mutableStateOf(false) }
    var spec by remember { mutableStateOf<LevelSpec?>(null) }
    var par by remember { mutableStateOf(0f) }

    LaunchedEffect(req) {
        val built = withContext(Dispatchers.Default) {
            val s = specFor(req)
            val setup = LevelBuilder.build(s)
            Triple(s, setup, if (req.mode == PlayMode.TIME_TRIAL) parTime(s) else 0f)
        }
        spec = built.first
        par = built.third
        val d = c.save.data
        val rules = RaceRules()
        if (req.mode == PlayMode.DAILY) when (GameRules.challengeModifier(Progression.today())) {
            RaceMod.MOON -> rules.gravity = 11f
            RaceMod.SLIPPERY -> rules.stopDecel = 14f
            RaceMod.TINY -> { rules.playerScale = 0.72f; rules.runSpeed = 5.6f }
            RaceMod.BIG_HEADS -> rules.bigHeads = true
            RaceMod.MIRROR -> rules.mirror = true
        }
        // bots race as the kid racers: a different kid each, never the one you're wearing
        val rivals = Catalog.rivalPool.filter { it.id != d.profile.character }
        val seedIdx = built.first.seed.toInt().let { if (it < 0) -it else it }
        val rivalLooks = (0 until 6).map { i ->
            val ch = rivals[(seedIdx + i * 5) % rivals.size]
            val hat = if ((seedIdx + i) % 3 == 0) Catalog.rivalHats[(i + built.first.level) % Catalog.rivalHats.size] else null
            Look.of(ch, hat)
        }
        val boosters = req.boosters
        Progression.consumeBoosters(c.save, boosters)
        val ctrl = RaceController(
            setup = built.second, rules = rules, playerLook = c.playerLook(), rivalLooks = rivalLooks,
            playerTrail = Catalog.trails.indexOfFirst { it.id == d.profile.trail }.coerceAtLeast(0), playerDance = d.profile.dance,
            audio = c.audio, haptics = c.haptics, reducedMotion = d.settings.reducedMotion,
            autopilot = BuildConfig.DEBUG && LaunchOptions.autopilot, boosters = boosters,
        ) { r ->
            scope.launch {
                val payout = Progression.applyRace(c.save, r, timeTrialPar = par, dailyChallenge = req.mode == PlayMode.DAILY)
                GameEvents.raceEnd(r.level, req.mode.name.lowercase(), r.kind.name.lowercase(), r.finished, payout.cleared, r.rank, r.timeMs, payout.stars, payout.coins, r.bonks, r.splashes)
                c.save.data.let { GameEvents.progress(it.progress.highestUnlocked, it.profile.character) }
                result = r to payout
            }
        }
        controller = ctrl
        GameEvents.raceStart(built.first.level, req.mode.name.lowercase(), built.first.kind.name.lowercase(), built.first.world.name.lowercase(), boosters)
        c.stage.showRace(ctrl)
        val m = when (built.first.kind) {
            RaceKind.BOSS_TIDE, RaceKind.BOSS_DUCK, RaceKind.BOSS_STORM -> Music.BOSS
            RaceKind.BONUS -> Music.BONUS
            else -> if (built.first.time == com.ngstudio.funrace.level.TimeOfDay.NIGHT) Music.RACE_NIGHT else Music.RACE
        }
        c.stage.playMusic(m)
    }
    DisposableEffect(Unit) { onDispose { controller?.paused = true } }

    val ctrl = controller
    var jumpDown by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize().pointerInput(ctrl, paused, result) {
            val zone = 132.dp.toPx()
            val swipe = 42.dp.toPx()
            awaitPointerEventScope {
                // per pointer: startX, baseY, baseTime, startD, isJumpButton
                val ptrs = HashMap<androidx.compose.ui.input.pointer.PointerId, FloatArray>()
                while (true) {
                    val ev = awaitPointerEvent()
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    for (ch in ev.changes) {
                        val now = ch.uptimeMillis.toFloat()
                        if (ch.pressed && !ch.previousPressed) {
                            val onJump = ch.position.x > w - zone && ch.position.y > h - zone
                            ptrs[ch.id] = floatArrayOf(ch.position.x, ch.position.y, now, ctrl?.playerD ?: 0f, if (onJump) 1f else 0f)
                            if (onJump && !paused && result == null) ctrl?.jump()
                        } else if (!ch.pressed) {
                            ptrs.remove(ch.id)
                        } else {
                            val st = ptrs[ch.id] ?: continue
                            if (st[4] == 0f) {
                                // flick up = jump; re-arms after each flick
                                if (st[1] - ch.position.y > swipe && now - st[2] < 420f) {
                                    if (!paused && result == null) ctrl?.jump()
                                    st[1] = ch.position.y; st[2] = now
                                } else if (ch.position.y > st[1] || now - st[2] > 420f) { st[1] = ch.position.y; st[2] = now }
                            }
                        }
                    }
                    jumpDown = ptrs.values.any { it[4] == 1f }
                    val run = ptrs.entries.firstOrNull { it.value[4] == 0f }
                    val active = !paused && result == null
                    ctrl?.touching = active && run != null
                    if (run != null && active) {
                        val pos = ev.changes.firstOrNull { it.id == run.key }?.position
                        if (pos != null) ctrl?.steer = run.value[3] + (pos.x - run.value[0]) / w * 7.5f
                    } else ctrl?.steer = Float.NaN
                }
            }
        },
    ) {
        if (ctrl == null) {
            LoadingOverlay()
        } else {
            val hud by ctrl.hud.collectAsState()
            val popup by ctrl.popup.collectAsState()
            Hud(hud, spec, jumpDown, onPause = { paused = true; ctrl.paused = true; ctrl.touching = false; c.audio.duckMusic(0.4f, 600000) })
            PopupLayer(popup)
            if (hud.phase == RacePhase.COUNTDOWN) Countdown(hud.countdown)
            if (hud.phase == RacePhase.INTRO) IntroCard(spec)
            if (hud.phase == RacePhase.RACING && hud.tutorial.isNotEmpty()) TutorialHint(hud.tutorial)
        }
        if (paused && result == null) PauseMenu(
            onResume = { paused = false; ctrl?.paused = false; c.audio.duckMusic(1f, 1) },
            onRestart = { c.audio.duckMusic(1f, 1); nav.startRace(req.copy(boosters = emptySet())) },
            onHome = { c.audio.duckMusic(1f, 1); nav.replaceAll(Screen.Main) },
        )
        result?.let { (r, payout) -> ResultScreen(r, payout, req, spec, par) }
    }
}

@Composable
private fun LoadingOverlay() {
    Box(Modifier.fillMaxSize().background(Palette.bgGradient), contentAlignment = Alignment.Center) {
        val inf = rememberInfiniteTransition(label = "load")
        val s by inf.animateFloat(0.9f, 1.1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "ls")
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            UiIcon(R.drawable.ui_lifebuoy, 90.dp, Modifier.scale(s))
            Spacer(Modifier.height(12.dp))
            OutlinedText("Inflating the course...", style = Type.Heading)
        }
    }
}

@Composable
private fun Hud(h: HudState, spec: LevelSpec?, jumpPressed: Boolean, onPause: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).pressable(onClick = onPause)) { UiIcon(R.drawable.ui_pause, 46.dp) }
            Spacer(Modifier.width(8.dp))
            ProgressTrack(h, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            if (h.racers > 1) RankPill(h.rank, h.racers)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0x990B2740)).border(2.dp, Color(0x55FFFFFF), RoundedCornerShape(16.dp)).padding(horizontal = 10.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UiIcon(R.drawable.ui_coin, 26.dp)
                Spacer(Modifier.width(4.dp))
                OutlinedText("${h.coins}", style = Type.Label.copy(fontSize = 17.sp), outlineWidth = 3.dp)
            }
            Spacer(Modifier.weight(1f))
            spec?.let { OutlinedText(if (it.kind == RaceKind.DAILY || it.kind == RaceKind.TIME_TRIAL) it.name else "Level ${it.level}", style = Type.Label, outlineWidth = 3.dp) }
            if (h.fps > 0 && BuildConfig.DEBUG) { Spacer(Modifier.width(8.dp)); OutlinedText("${h.fps} fps", style = Type.Small, outlineWidth = 2.dp) }
        }
        if (h.chaserProgress >= 0f) ChaserWarning(h)
        if (h.stormWarn > 0f) {
            Spacer(Modifier.height(6.dp))
            OutlinedText("STORM GUST!", style = Type.Heading, color = Palette.Sun, modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = h.stormWarn })
        }
    }
    // turbo gauge
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(16.dp), contentAlignment = Alignment.BottomStart) {
        TurboGauge(h.turbo, h.turboActive)
    }
    if (h.phase == RacePhase.RACING || h.phase == RacePhase.COUNTDOWN) Box(Modifier.fillMaxSize().navigationBarsPadding().padding(18.dp), contentAlignment = Alignment.BottomEnd) {
        JumpButton(jumpPressed)
    }
    // hold to run hint at the start
    if (h.phase == RacePhase.RACING && !h.holding && h.tutorial.isEmpty() && h.progress.firstOrNull()?.first ?: 0f < 0.02f) {
        Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 120.dp), contentAlignment = Alignment.BottomCenter) {
            val inf = rememberInfiniteTransition(label = "hold")
            val s by inf.animateFloat(0.95f, 1.08f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "hs")
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                OutlinedText("HOLD TO RUN", style = Type.Title, modifier = Modifier.scale(s))
                OutlinedText("drag to steer • swipe up to jump", style = Type.Label, outlineWidth = 3.dp)
            }
        }
    }
}

@Composable
private fun ProgressTrack(h: HudState, modifier: Modifier) {
    Box(modifier.height(30.dp), contentAlignment = Alignment.CenterStart) {
        Canvas(Modifier.fillMaxWidth().height(30.dp)) {
            val y = size.height / 2f
            val r = CornerRadius(9f)
            drawRoundRect(Color(0xAA0B2740), topLeft = Offset(0f, y - 9f), size = Size(size.width, 18f), cornerRadius = r)
            drawRoundRect(Color(0x55FFFFFF), topLeft = Offset(0f, y - 9f), size = Size(size.width, 18f), cornerRadius = r, style = Stroke(3f))
            // finish flag checker
            val fx = size.width - 16f
            for (i in 0 until 3) for (j in 0 until 2) drawRect(if ((i + j) % 2 == 0) Color.White else Palette.Ink, topLeft = Offset(fx + i * 5f, y - 8f + j * 8f), size = Size(5f, 8f))
            if (h.chaserProgress >= 0f) {
                val cx = (h.chaserProgress.coerceIn(0f, 1f)) * (size.width - 28f) + 8f
                drawRoundRect(Brush.horizontalGradient(listOf(Color(0x002E9BFF), Color(0xCC2E9BFF))), topLeft = Offset(0f, y - 9f), size = Size(cx, 18f), cornerRadius = r)
            }
            // racers (player last so it's on top)
            for (k in h.progress.indices.reversed()) {
                val (pr, col) = h.progress[k]
                val x = pr * (size.width - 28f) + 8f
                val c = Color(0xFF000000.toInt() or col)
                val rad = if (k == 0) 12f else 9f
                drawCircle(Palette.Ink, rad + 3f, Offset(x, y))
                drawCircle(c, rad, Offset(x, y))
                if (k == 0) drawCircle(Palette.Sun, rad + 5f, Offset(x, y), style = Stroke(3.5f))
            }
        }
    }
}

@Composable
private fun RankPill(rank: Int, total: Int) {
    val col = when (rank) { 1 -> Palette.Sun; 2 -> Color(0xFFDDE6F0); 3 -> Color(0xFFF2A46B); else -> Color(0xFFB2C2CF) }
    val bump = remember { Animatable(1f) }
    LaunchedEffect(rank) { bump.snapTo(1.3f); bump.animateTo(1f, spring(dampingRatio = 0.4f)) }
    Box(
        Modifier.scale(bump.value).clip(RoundedCornerShape(14.dp)).background(col).border(3.dp, Palette.Ink, RoundedCornerShape(14.dp)).padding(horizontal = 10.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedText("$rank", style = Type.Heading.copy(fontSize = 24.sp), outlineWidth = 3.dp)
            OutlinedText(ordinal(rank) + "/$total", style = Type.Small, outlineWidth = 2.dp, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

private fun ordinal(n: Int) = when (n) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }

@Composable
private fun ChaserWarning(h: HudState) {
    val gap = (h.progress.firstOrNull()?.first ?: 0f) - h.chaserProgress
    if (gap < 0.12f) {
        val inf = rememberInfiniteTransition(label = "warn")
        val a by inf.animateFloat(0.4f, 1f, infiniteRepeatable(tween(300), RepeatMode.Reverse), label = "wa")
        Spacer(Modifier.height(6.dp))
        OutlinedText("IT'S RIGHT BEHIND YOU!", style = Type.Label, color = Palette.Coral, modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = a })
    }
}

@Composable
private fun JumpButton(pressed: Boolean) {
    val s by animateFloatAsState(if (pressed) 0.88f else 1f, spring(dampingRatio = 0.45f, stiffness = 900f), label = "jb")
    Box(Modifier.size(92.dp).scale(s), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            drawCircle(Color(0x55000000), r, center = Offset(r, r + 6f))
            drawCircle(Palette.Ink, r)
            drawCircle(Brush.verticalGradient(listOf(Color(0xFF8CCBFF), Palette.OceanDeep)), r - 5f)
            drawCircle(Color(0x55FFFFFF), r * 0.55f, center = Offset(r, r * 0.62f))
            // chunky up arrow
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(r, r * 0.42f); lineTo(r * 1.42f, r * 0.95f); lineTo(r * 1.14f, r * 0.95f); lineTo(r * 1.14f, r * 1.32f)
                lineTo(r * 0.86f, r * 1.32f); lineTo(r * 0.86f, r * 0.95f); lineTo(r * 0.58f, r * 0.95f); close()
            }
            drawPath(p, Color.White)
            drawPath(p, Palette.Ink, style = Stroke(5f))
        }
        OutlinedText("JUMP", style = Type.Small, outlineWidth = 2.dp, modifier = Modifier.offset(y = 30.dp))
    }
}

@Composable
private fun TurboGauge(v: Float, active: Boolean) {
    val shown by animateFloatAsState(if (active) 1f else v, tween(300), label = "tg")
    val inf = rememberInfiniteTransition(label = "turbo")
    val glow by inf.animateFloat(0.7f, 1.15f, infiniteRepeatable(tween(260), RepeatMode.Reverse), label = "tglow")
    Box(Modifier.size(78.dp).scale(if (shown >= 1f) glow else 1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color(0xAA0B2740))
            drawArc(Color(0x55FFFFFF), -90f, 360f, false, style = Stroke(10f))
            drawArc(Brush.sweepGradient(listOf(Palette.Sun, Palette.Coral, Palette.Sun)), -90f, 360f * shown, false, style = Stroke(10f))
        }
        UiIcon(R.drawable.ui_turbo, 44.dp, alpha = 0.5f + 0.5f * shown)
    }
}

@Composable
private fun PopupLayer(p: com.ngstudio.funrace.game.Popup?) {
    if (p == null) return
    val anim = remember(p.id) { Animatable(0f) }
    LaunchedEffect(p.id) { anim.animateTo(1f, tween(900)) }
    val t = anim.value
    if (t >= 1f) return
    val scale = if (t < 0.15f) 0.4f + t / 0.15f * 0.8f else 1.2f - (t - 0.15f) * 0.25f
    Box(Modifier.fillMaxSize().padding(bottom = 120.dp), contentAlignment = Alignment.Center) {
        OutlinedText(
            p.text, style = Type.Huge.copy(fontSize = if (p.text.length > 8) 38.sp else 52.sp), color = Color(p.color), outlineWidth = 6.dp,
            modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (t > 0.75f) (1f - t) / 0.25f else 1f; translationY = -t * 120f },
        )
    }
}

@Composable
private fun Countdown(n: Int) {
    val anim = remember(n) { Animatable(0f) }
    LaunchedEffect(n) { anim.animateTo(1f, tween(780, easing = FastOutSlowInEasing)) }
    val t = anim.value
    val col = when (n) { 3 -> Palette.Coral; 2 -> Palette.Sun; else -> Palette.Lime }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        OutlinedText("$n", style = Type.Huge.copy(fontSize = 120.sp), color = col, outlineWidth = 8.dp,
            modifier = Modifier.graphicsLayer { val s = 1.6f - t * 0.6f; scaleX = s; scaleY = s; alpha = 1f - t * 0.4f })
    }
}

@Composable
private fun IntroCard(spec: LevelSpec?) {
    spec ?: return
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 60.dp, start = 24.dp, end = 24.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(true, enter = fadeIn() + scaleIn(initialScale = 0.8f)) {
            Panel(padding = 14.dp) {
                OutlinedText(spec.name, style = Type.Title, color = Palette.Sun, modifier = Modifier.fillMaxWidth())
                if (spec.newThing.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Palette.Pink).padding(horizontal = 8.dp, vertical = 2.dp)) { OutlinedText("NEW", style = Type.Small, outlineWidth = 2.dp) }
                        Spacer(Modifier.width(6.dp))
                        OutlinedText(spec.newThing, style = Type.Label, outlineWidth = 3.dp)
                    }
                }
                if (spec.intro.isNotEmpty()) { Spacer(Modifier.height(6.dp)); BodyText(spec.intro, align = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable
private fun TutorialHint(text: String) {
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 104.dp), contentAlignment = Alignment.TopCenter) {
        val inf = rememberInfiniteTransition(label = "tut")
        val y by inf.animateFloat(0f, 18f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "ty")
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xCC0B2740)).padding(horizontal = 12.dp, vertical = 6.dp)) {
            UiIcon(R.drawable.ui_hand, 40.dp, Modifier.offset(x = (y * 0.4f).dp))
            Spacer(Modifier.width(8.dp))
            OutlinedText(text, style = Type.Label, outlineWidth = 3.dp, maxLines = 2)
        }
    }
}

@Composable
private fun PauseMenu(onResume: () -> Unit, onRestart: () -> Unit, onHome: () -> Unit) {
    GameDialog("Paused", onDismiss = onResume, banner = Palette.Ocean, bannerDark = Palette.OceanDeep) {
        GameButton("Resume", onResume, Modifier.fillMaxWidth(), colors = Buttons.Green, icon = R.drawable.ui_play)
        Spacer(Modifier.height(12.dp))
        GameButton("Restart", onRestart, Modifier.fillMaxWidth(), colors = Buttons.Sun, icon = R.drawable.ui_restart)
        Spacer(Modifier.height(12.dp))
        GameButton("Home", onHome, Modifier.fillMaxWidth(), colors = Buttons.Ocean, icon = R.drawable.ui_home)
    }
}

@Composable
private fun ResultScreen(r: RaceResult, payout: Payout, req: RaceRequest, spec: LevelSpec?, par: Float) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val win = payout.cleared || (req.mode == PlayMode.TIME_TRIAL && r.finished)
    var coinsShown by remember { mutableIntStateOf(0) }
    var starsShown by remember { mutableIntStateOf(0) }
    var tripled by remember { mutableStateOf(false) }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(250)
        enter.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 300f))
        for (k in 1..payout.stars) { delay(330); starsShown = k; c.audio.play(listOf(Sfx.STAR1, Sfx.STAR2, Sfx.STAR3)[k - 1]) }
        delay(250)
        val steps = 24
        for (i in 1..steps) { coinsShown = payout.coins * i / steps; c.audio.play(Sfx.COIN_TICK); delay(28) }
        coinsShown = payout.coins
        if (payout.unlockedNext) c.audio.play(Sfx.UNLOCK)
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x000B2740), Color(0xAA0B2740), Color(0xEE0B2740)))), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 14.dp).graphicsLayer { translationY = (1f - enter.value) * 600f },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val title = when {
                req.mode == PlayMode.TIME_TRIAL -> if (payout.medal > 0) listOf("", "BRONZE!", "SILVER!", "GOLD!")[payout.medal] else "TIME!"
                r.kind == RaceKind.BONUS -> "COIN RUSH!"
                !win -> "SO CLOSE!"
                r.rank == 1 -> "VICTORY!"
                else -> "PODIUM!"
            }
            Banner(title, Modifier.fillMaxWidth(0.9f), color = if (win) Palette.Lime else Palette.Coral, dark = if (win) Palette.LimeDeep else Palette.CoralDeep)
            Spacer(Modifier.height(6.dp))
            Panel(Modifier.fillMaxWidth(), padding = 14.dp) {
                if (req.mode != PlayMode.TIME_TRIAL) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        for (k in 1..3) {
                            val on = k <= starsShown
                            val s by animateFloatAsState(if (on) 1f else 0.85f, spring(dampingRatio = 0.35f), label = "star$k")
                            UiIcon(if (on) R.drawable.ui_star else R.drawable.ui_star_empty, if (k == 2) 64.dp else 52.dp, Modifier.scale(s).offset(y = if (k == 2) (-6).dp else 0.dp))
                        }
                    }
                } else {
                    val t = r.timeMs / 1000f
                    OutlinedText("%.2fs".format(t), style = Type.Title, modifier = Modifier.fillMaxWidth())
                    val m = GameRules.medalTimes(par)
                    BodyText("Gold %.1fs  •  Silver %.1fs  •  Bronze %.1fs".format(m[0], m[1], m[2]), align = TextAlign.Center, modifier = Modifier.fillMaxWidth(), style = Type.BodySmall)
                }
                Spacer(Modifier.height(8.dp))
                // standings
                if (r.standings.size > 1) {
                    r.standings.take(4).forEachIndexed { i, (name, _) ->
                        val me = name == "You"
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (me) Palette.Sun.copy(alpha = 0.55f) else Color(0x1A13304A)).padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedText("${i + 1}", style = Type.Label, outlineWidth = 3.dp, color = when (i) { 0 -> Palette.Sun; 1 -> Color(0xFFDDE6F0); 2 -> Color(0xFFF2A46B); else -> Color.White })
                            Spacer(Modifier.width(10.dp))
                            BodyText(name, Modifier.weight(1f))
                            if (i == 0) UiIcon(R.drawable.ui_crown, 24.dp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    UiIcon(R.drawable.ui_coin, 34.dp)
                    Spacer(Modifier.width(6.dp))
                    OutlinedText("+" + formatNum(if (tripled) coinsShown * 3 else coinsShown), style = Type.Title, color = Palette.Sun)
                    if (payout.trophies > 0 && req.mode == PlayMode.CAMPAIGN) {
                        Spacer(Modifier.width(16.dp)); UiIcon(R.drawable.ui_trophy, 30.dp); Spacer(Modifier.width(4.dp))
                        OutlinedText("+${payout.trophies}", style = Type.Heading)
                    }
                }
                if (r.perfects > 0) BodyText("${r.perfects} PERFECT dodge${if (r.perfects > 1) "s" else ""}!", align = TextAlign.Center, modifier = Modifier.fillMaxWidth(), color = Palette.PinkDeep)
                payout.milestone?.let { m ->
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Sand).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        UiIcon(R.drawable.ui_chest, 40.dp)
                        Spacer(Modifier.width(8.dp))
                        BodyText("Milestone chest: " + m.joinToString(", ") { it.label }, style = Type.BodySmall)
                    }
                }
                if (AdConfig.mockAds && win && !tripled && payout.coins > 0) {
                    Spacer(Modifier.height(8.dp))
                    GameButton("x3 Coins", {
                        c.ads.showRewarded("triple_coins") { ok ->
                            if (ok) { tripled = true; c.save.update { it.copy(wallet = it.wallet.copy(coins = it.wallet.coins + payout.coins * 2)) }; c.audio.play(Sfx.COIN) }
                        }
                    }, Modifier.fillMaxWidth(), colors = Buttons.Purple, icon = R.drawable.ui_ad, height = 50.dp, sub = "Watch a (debug) ad")
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GameButton("", { nav.replaceAll(Screen.Main); nav.tab = Tab.HOME }, Modifier.width(72.dp), colors = Buttons.Ocean, icon = R.drawable.ui_home)
                GameButton(if (req.mode == PlayMode.CAMPAIGN && payout.cleared) "" else "Retry", { nav.startRace(req.copy(boosters = emptySet())) }, if (req.mode == PlayMode.CAMPAIGN && payout.cleared) Modifier.width(72.dp) else Modifier.weight(1f), colors = Buttons.Sun, icon = R.drawable.ui_restart)
                if (req.mode == PlayMode.CAMPAIGN && payout.cleared && r.level < LevelRepository.MAX_LEVEL) {
                    GameButton("Next", { nav.startRace(RaceRequest(r.level + 1)) }, Modifier.weight(1.3f), colors = Buttons.Green, trailingIcon = R.drawable.ui_play)
                }
            }
        }
    }
    if (payout.newItems.isNotEmpty()) {
        var show by remember { mutableStateOf(true) }
        if (show) GameDialog("New Dashling!", onDismiss = { show = false }, banner = Palette.Pink, bannerDark = Palette.PinkDeep) {
            BodyText("You unlocked " + payout.newItems.joinToString { Catalog.itemName(it) } + "! Equip it in the Shop.", align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            GameButton("Awesome!", { show = false }, Modifier.fillMaxWidth(), colors = Buttons.Pink)
        }
    }
    @Suppress("UNUSED_VARIABLE") val unused = Reward.Coins(0)
}
