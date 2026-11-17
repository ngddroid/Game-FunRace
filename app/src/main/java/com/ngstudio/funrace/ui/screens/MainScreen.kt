package com.ngstudio.funrace.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngstudio.funrace.R
import com.ngstudio.funrace.data.Catalog
import com.ngstudio.funrace.data.GameRules
import com.ngstudio.funrace.data.Progression
import com.ngstudio.funrace.game.sim.RaceKind
import com.ngstudio.funrace.level.LevelRepository
import com.ngstudio.funrace.ui.LocalNav
import com.ngstudio.funrace.ui.RaceRequest
import com.ngstudio.funrace.ui.Screen
import com.ngstudio.funrace.ui.Tab
import com.ngstudio.funrace.ui.components.BodyText
import com.ngstudio.funrace.ui.components.Buttons
import com.ngstudio.funrace.ui.components.CurrencyPill
import com.ngstudio.funrace.ui.components.GameBackground
import com.ngstudio.funrace.ui.components.GameButton
import com.ngstudio.funrace.ui.components.GlassPanel
import com.ngstudio.funrace.ui.components.LocalContainer
import com.ngstudio.funrace.ui.components.OutlinedText
import com.ngstudio.funrace.ui.components.RoundIconButton
import com.ngstudio.funrace.ui.components.UiIcon
import com.ngstudio.funrace.ui.components.pressable
import com.ngstudio.funrace.ui.theme.Palette
import com.ngstudio.funrace.ui.theme.Type
import kotlinx.coroutines.launch
import kotlin.math.sin

@Composable
fun MainScreen() {
    val nav = LocalNav.current
    val c = LocalContainer.current
    LaunchedEffect(Unit) { c.save.update { Progression.rollDaily(it) } }
    Box(Modifier.fillMaxSize()) {
        AnimatedContent(nav.tab, transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(200)) }, label = "tab") { t ->
            when (t) {
                Tab.HOME -> HomeTab()
                Tab.LEVELS -> LevelsTab()
                Tab.SHOP -> ShopTab()
                Tab.EVENTS -> EventsTab()
                Tab.PROFILE -> ProfileTab()
            }
        }
        TopBar()
        BottomBar(Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
fun TopBar() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val d by c.save.state.collectAsState()
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.clip(RoundedCornerShape(22.dp)).background(Color(0x990B2740)).border(2.dp, Color(0x55FFFFFF), RoundedCornerShape(22.dp))
                .pressable { nav.tab = Tab.PROFILE }.padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(d.profile.character, 42.dp)
            Spacer(Modifier.width(6.dp))
            Column {
                OutlinedText(d.profile.name, style = Type.Label, outlineWidth = 3.dp)
                OutlinedText("LV ${d.progress.highestUnlocked}", style = Type.Small, color = Palette.Sun, outlineWidth = 2.dp)
            }
        }
        Spacer(Modifier.weight(1f))
        CurrencyPill(R.drawable.ui_coin, d.wallet.coins)
        Spacer(Modifier.width(4.dp))
        CurrencyPill(R.drawable.ui_gem, d.wallet.gems)
        Spacer(Modifier.width(4.dp))
        Box(Modifier.size(42.dp).pressable { nav.push(Screen.Settings) }) { UiIcon(R.drawable.ui_gear, 42.dp) }
    }
}

@Composable
fun Avatar(characterId: String, size: Dp) {
    val ch = Catalog.character(characterId)
    Box(Modifier.size(size).clip(CircleShape).background(Color(0xFF000000.toInt() or ch.primary)).border(3.dp, Palette.Ink, CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size * 0.58f).offset(y = size * 0.04f).clip(CircleShape).background(Color(0xFF000000.toInt() or ch.skin)).border(2.dp, Color(0xFF000000.toInt() or ch.secondary), CircleShape), contentAlignment = Alignment.Center) {
            Row { Box(Modifier.size(size * 0.07f, size * 0.1f).clip(CircleShape).background(Palette.Ink)); Spacer(Modifier.width(size * 0.1f)); Box(Modifier.size(size * 0.07f, size * 0.1f).clip(CircleShape).background(Palette.Ink)) }
        }
    }
}

@Composable
fun BottomBar(modifier: Modifier = Modifier) {
    val nav = LocalNav.current
    val c = LocalContainer.current
    val d by c.save.state.collectAsState()
    val eventsDot = GameRules.calendarAvailable(d.daily, Progression.today()) || !d.daily.wheelFreeUsed || d.daily.missions.any { it.progress >= it.target && !it.claimed }
    val profileDot = d.progress.trophyRoadClaimed < GameRules.trophyRoad.size && d.progress.trophies >= GameRules.trophyRoad[d.progress.trophyRoadClaimed].trophies
    val shopDot = d.collection.unseen.isNotEmpty()
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp).height(84.dp)) {
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(66.dp).shadow(10.dp, RoundedCornerShape(26.dp)).clip(RoundedCornerShape(26.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF1E7FD6), Color(0xFF13457E)))).border(3.dp, Palette.Ink, RoundedCornerShape(26.dp)),
        )
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
            TabItem(Tab.SHOP, R.drawable.ui_shop, "Shop", shopDot)
            TabItem(Tab.EVENTS, R.drawable.ui_events, "Events", eventsDot)
            HomeBubble()
            TabItem(Tab.LEVELS, R.drawable.ui_levels, "Levels", false)
            TabItem(Tab.PROFILE, R.drawable.ui_profile, "Profile", profileDot)
        }
    }
}

@Composable
private fun TabItem(tab: Tab, icon: Int, label: String, dot: Boolean) {
    val nav = LocalNav.current
    val sel = nav.tab == tab
    val s by animateFloatAsState(if (sel) 1.18f else 1f, spring(dampingRatio = 0.45f), label = "tab$label")
    Column(Modifier.width(64.dp).padding(bottom = 6.dp).pressable { nav.tab = tab }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            UiIcon(icon, 38.dp, Modifier.scale(s).offset(y = if (sel) (-4).dp else 0.dp))
            if (dot) Box(Modifier.align(Alignment.TopEnd).size(13.dp).clip(CircleShape).background(Color(0xFFFF3B5C)).border(2.dp, Color.White, CircleShape))
        }
        OutlinedText(label, style = Type.Small, outlineWidth = 2.dp, color = if (sel) Palette.Sun else Color.White)
    }
}

@Composable
private fun HomeBubble() {
    val nav = LocalNav.current
    val sel = nav.tab == Tab.HOME
    val inf = rememberInfiniteTransition(label = "home")
    val bob by inf.animateFloat(0f, 4f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "hb")
    Box(Modifier.size(78.dp).offset(y = (-6 - if (sel) bob else 0f).dp).pressable { nav.tab = Tab.HOME }, contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().shadow(8.dp, CircleShape).clip(CircleShape).background(Brush.verticalGradient(listOf(Color(0xFFA6F58F), Palette.LimeDeep))).border(4.dp, Palette.Ink, CircleShape))
        UiIcon(R.drawable.ui_home, 50.dp)
    }
}

// ================================================================================== HOME
@Composable
fun HomeTab() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val d by c.save.state.collectAsState()
    var locked by remember { mutableStateOf<Int?>(null) }
    val level = d.progress.lastPlayed.coerceIn(1, d.progress.highestUnlocked.coerceAtLeast(1)).let { if (d.progress.isUnlocked(it)) it else d.progress.highestUnlocked }
    val kind = LevelRepository.kind(level)
    val world = LevelRepository.world(level)
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) { detectDragGestures { _, drag -> c.stage.drag(drag.x * 0.012f) } }
            .pointerInput(Unit) { detectTapGestures { c.stage.wave(); c.audio.play(com.ngstudio.funrace.audio.Sfx.SQUEAK, 0.6f) } },
    ) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x550B2740), Color(0x000B2740), Color(0x000B2740), Color(0xCC0B2740)))))
        // side buttons
        Column(Modifier.statusBarsPadding().padding(top = 70.dp, start = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundIconButton(R.drawable.ui_calendar, { nav.tab = com.ngstudio.funrace.ui.Tab.EVENTS }, label = "Daily", dot = GameRules.calendarAvailable(d.daily, Progression.today()))
            RoundIconButton(R.drawable.ui_wheel, { nav.tab = com.ngstudio.funrace.ui.Tab.EVENTS }, label = "Spin", dot = !d.daily.wheelFreeUsed)
            RoundIconButton(R.drawable.ui_mission, { nav.tab = com.ngstudio.funrace.ui.Tab.EVENTS }, label = "Missions", badge = d.daily.missions.count { it.progress >= it.target && !it.claimed })
        }
        Column(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 70.dp, end = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundIconButton(R.drawable.ui_daily, { nav.tab = com.ngstudio.funrace.ui.Tab.EVENTS }, label = "Challenge", dot = !d.daily.challengeDone)
            RoundIconButton(R.drawable.ui_road, { nav.tab = com.ngstudio.funrace.ui.Tab.PROFILE }, label = "Trophies")
            RoundIconButton(R.drawable.ui_stopwatch, { nav.tab = com.ngstudio.funrace.ui.Tab.EVENTS }, label = "Trials")
        }
        Column(Modifier.fillMaxSize().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 104.dp), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
            // level card
            val title = when (kind) { RaceKind.BOSS_TIDE, RaceKind.BOSS_DUCK, RaceKind.BOSS_STORM -> "BOSS LEVEL $level"; RaceKind.BONUS -> "BONUS LEVEL $level"; else -> "LEVEL $level" }
            OutlinedText(title, style = Type.Title.copy(fontSize = 34.sp), color = if (kind == RaceKind.NORMAL || kind == RaceKind.TUTORIAL) Color.White else Palette.Sun)
            OutlinedText(LevelRepository.get(level).name + "  •  " + world.title, style = Type.Label, outlineWidth = 3.dp)
            Spacer(Modifier.height(4.dp))
            Row { for (k in 1..3) UiIcon(if (d.progress.starsFor(level) >= k) R.drawable.ui_star else R.drawable.ui_star_empty, 26.dp) }
            Spacer(Modifier.height(10.dp))
            // boosters
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (b in GameRules.boosters) {
                    val owned = d.progress.boosters[b.id] ?: 0
                    val on = b.id in nav.boosters
                    BoosterChip(b.icon, b.name, owned, b.price, on) {
                        if (on) nav.boosters = nav.boosters - b.id
                        else if (owned > 0) nav.boosters = nav.boosters + b.id
                        else if (Progression.buyBooster(c.save, b.id)) { nav.boosters = nav.boosters + b.id; c.audio.play(com.ngstudio.funrace.audio.Sfx.COIN) }
                        else nav.showToast("Not enough coins for ${b.name}")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            GameButton("PLAY", {
                val req = RaceRequest(level, boosters = nav.boosters)
                nav.boosters = emptySet()
                nav.startRace(req)
            }, Modifier.fillMaxWidth(0.82f), colors = Buttons.Green, icon = R.drawable.ui_play, height = 70.dp, textStyle = Type.Title, shine = true)
        }
        locked?.let { lv -> LockedLevelDialog(lv, d.progress.highestUnlocked, { locked = null }, { locked = null; nav.startRace(RaceRequest(it)) }, { locked = null; nav.startRace(RaceRequest(it)) }) }
    }
}

@Composable
private fun BoosterChip(icon: String, name: String, owned: Int, price: Int, on: Boolean, onClick: () -> Unit) {
    val ctx = LocalContainer.current.app
    @Suppress("DiscouragedApi") val res = ctx.resources.getIdentifier(icon, "drawable", ctx.packageName)
    val s by animateFloatAsState(if (on) 1.12f else 1f, spring(dampingRatio = 0.45f), label = "boost")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.pressable(onClick = onClick)) {
        Box(
            Modifier.size(58.dp).scale(s).clip(RoundedCornerShape(18.dp)).background(if (on) Palette.Sun else Color(0x990B2740))
                .border(3.dp, if (on) Palette.Ink else Color(0x88FFFFFF), RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (res != 0) UiIcon(res, 44.dp)
            Box(Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp).clip(RoundedCornerShape(8.dp)).background(if (owned > 0) Palette.Lime else Palette.Ocean).border(2.dp, Palette.Ink, RoundedCornerShape(8.dp)).padding(horizontal = 5.dp)) {
                OutlinedText(if (owned > 0) "x$owned" else "$price", style = Type.Small, outlineWidth = 2.dp)
            }
            if (on) Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp)) { UiIcon(R.drawable.ui_check, 22.dp) }
        }
        OutlinedText(name, style = Type.Small.copy(fontSize = 10.sp), outlineWidth = 2.dp)
    }
}

// ================================================================================== LEVELS
@Composable
fun LevelsTab() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val d by c.save.state.collectAsState()
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var locked by remember { mutableStateOf<Int?>(null) }
    val current = d.progress.highestUnlocked
    LaunchedEffect(Unit) { state.scrollToItem((current - 3).coerceAtLeast(0)) }
    GameBackground {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 120.dp, bottom = 130.dp)) {
            items(LevelRepository.MAX_LEVEL, key = { it }) { i ->
                val lv = i + 1
                Column {
                    if ((lv - 1) % 25 == 0) WorldBanner(lv)
                    LevelNode(lv, d.progress.isUnlocked(lv), lv == current, d.progress.starsFor(lv)) {
                        if (d.progress.isUnlocked(lv)) nav.startRace(RaceRequest(lv)) else locked = lv
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 110.dp, end = 14.dp), contentAlignment = Alignment.BottomEnd) {
            GameButton("", { scope.launch { state.animateScrollToItem((current - 3).coerceAtLeast(0)) } }, Modifier.width(64.dp), colors = Buttons.Sun, icon = R.drawable.ui_flag, height = 50.dp)
        }
    }
    locked?.let { lv -> LockedLevelDialog(lv, d.progress.highestUnlocked, { locked = null }, { locked = null; nav.startRace(RaceRequest(it)) }, { locked = null; nav.startRace(RaceRequest(it)) }) }
}

@Composable
private fun WorldBanner(level: Int) {
    val w = LevelRepository.world(level)
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        GlassPanel(Modifier.fillMaxWidth(), padding = 10.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or w.hazA)).border(3.dp, Palette.Ink, CircleShape))
                Spacer(Modifier.width(10.dp))
                Column {
                    OutlinedText(w.title, style = Type.Heading, outlineWidth = 3.dp)
                    BodyText("Levels $level–${level + 24}", color = Color.White, style = Type.BodySmall)
                }
            }
        }
    }
}

@Composable
private fun LevelNode(level: Int, unlocked: Boolean, current: Boolean, stars: Int, onClick: () -> Unit) {
    val kind = LevelRepository.kind(level)
    val boss = kind == RaceKind.BOSS_TIDE || kind == RaceKind.BOSS_DUCK || kind == RaceKind.BOSS_STORM
    val bonus = kind == RaceKind.BONUS
    val xFrac = 0.5f + 0.3f * sin(level * 0.9f)
    val nextX = 0.5f + 0.3f * sin((level + 1) * 0.9f)
    val size = if (boss) 74.dp else 62.dp
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(86.dp)) {
        val w = maxWidth
        Canvas(Modifier.fillMaxSize()) {
            val x0 = this.size.width * xFrac
            val x1 = this.size.width * nextX
            drawLine(Color(0x88FFFFFF), Offset(x0, this.size.height / 2f), Offset(x1, this.size.height * 1.5f), strokeWidth = 8f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 14f)))
        }
        val inf = rememberInfiniteTransition(label = "node")
        val pulse by inf.animateFloat(1f, 1.12f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "np")
        Box(Modifier.offset(x = w * xFrac - size / 2, y = (86.dp - size) / 2).size(size).scale(if (current) pulse else 1f).pressable(onClick = onClick), contentAlignment = Alignment.Center) {
            val fill = when {
                !unlocked -> Brush.verticalGradient(listOf(Color(0xFF9DB0C0), Color(0xFF6E8396)))
                boss -> Brush.verticalGradient(listOf(Color(0xFFFF9BCB), Palette.PinkDeep))
                bonus -> Brush.verticalGradient(listOf(Color(0xFFFFEA92), Palette.SunDeep))
                current -> Brush.verticalGradient(listOf(Color(0xFFA6F58F), Palette.LimeDeep))
                else -> Brush.verticalGradient(listOf(Color(0xFF8CCBFF), Palette.OceanDeep))
            }
            Box(Modifier.fillMaxSize().shadow(6.dp, CircleShape).clip(CircleShape).background(fill).border(4.dp, Palette.Ink, CircleShape))
            when {
                !unlocked -> UiIcon(R.drawable.ui_lock, size * 0.55f)
                boss -> Column(horizontalAlignment = Alignment.CenterHorizontally) { UiIcon(R.drawable.ui_boss, size * 0.5f); OutlinedText("$level", style = Type.Small, outlineWidth = 2.dp) }
                bonus -> Column(horizontalAlignment = Alignment.CenterHorizontally) { UiIcon(R.drawable.ui_bonus, size * 0.45f); OutlinedText("$level", style = Type.Small, outlineWidth = 2.dp) }
                else -> OutlinedText("$level", style = Type.Heading.copy(fontSize = if (level >= 1000) 17.sp else 22.sp), outlineWidth = 3.dp)
            }
            if (unlocked && stars > 0) Row(Modifier.align(Alignment.BottomCenter).offset(y = 12.dp)) { for (k in 1..3) UiIcon(if (stars >= k) R.drawable.ui_star else R.drawable.ui_star_empty, 18.dp) }
        }
    }
}

@Suppress("unused")
private fun drawRounded(c: androidx.compose.ui.graphics.drawscope.DrawScope) { c.drawRoundRect(Color.White, size = Size(1f, 1f), cornerRadius = CornerRadius(1f), style = Stroke(1f)) }

@Suppress("unused")
private fun noop(m: Modifier) = m.fillMaxHeight()

@Suppress("unused")
private val unusedAlign = TextAlign.Center
