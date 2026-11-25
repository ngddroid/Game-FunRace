package com.ngstudio.funrace.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.animation.animateColorAsState
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
import kotlin.math.min
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
    val hair = ch.accessories.firstOrNull { it.mesh.startsWith("hair_") }
    if (ch.body == Catalog.BODY_HUMAN && hair != null) {
        // kid racer: shirt-coloured badge, hair cap over a skin-tone face
        Box(Modifier.size(size).clip(CircleShape).background(Color(0xFF000000.toInt() or ch.primary)).border(3.dp, Palette.Ink, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(size * 0.66f).offset(y = size * 0.02f).clip(CircleShape).background(Color(0xFF000000.toInt() or hair.color)), contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.size(size * 0.54f).offset(y = size * 0.03f).clip(CircleShape).background(Color(0xFF000000.toInt() or ch.skin)), contentAlignment = Alignment.Center) {
                    Row { Box(Modifier.size(size * 0.07f, size * 0.1f).clip(CircleShape).background(Palette.Ink)); Spacer(Modifier.width(size * 0.1f)); Box(Modifier.size(size * 0.07f, size * 0.1f).clip(CircleShape).background(Palette.Ink)) }
                }
            }
        }
        return
    }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        // the tile is clipped to its rounded shape; the count/price sticker and the tick sit on an
        // outer box so they are never cut off
        Box(Modifier.size(72.dp, 68.dp)) {
            Box(
                Modifier.align(Alignment.TopCenter).size(58.dp).scale(s).clip(RoundedCornerShape(18.dp)).background(if (on) Palette.Sun else Color(0x990B2740))
                    .border(3.dp, if (on) Palette.Ink else Color(0x88FFFFFF), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (res != 0) UiIcon(res, 44.dp)
            }
            Row(
                Modifier.align(Alignment.BottomEnd).clip(RoundedCornerShape(10.dp)).background(if (owned > 0) Palette.LimeDeep else Palette.OceanDeep)
                    .border(2.dp, Palette.Ink, RoundedCornerShape(10.dp)).padding(start = if (owned > 0) 7.dp else 3.dp, end = 7.dp, top = 1.dp, bottom = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (owned <= 0) { UiIcon(R.drawable.ui_coin, 15.dp); Spacer(Modifier.width(2.dp)) }
                OutlinedText(if (owned > 0) "x$owned" else "$price", style = Type.Small.copy(fontSize = 13.sp), outlineWidth = 2.dp)
            }
            if (on) Box(Modifier.align(Alignment.TopEnd)) { UiIcon(R.drawable.ui_check, 22.dp) }
        }
        Spacer(Modifier.height(2.dp))
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
    // the tower climbs upward (level 1 at the bottom): open with your floor just above the menu bar
    LaunchedEffect(Unit) { state.scrollToItem((current - 2).coerceAtLeast(0)) }
    // the floor in the middle of the screen picks the world for the sky colour and the header
    val shown by remember { derivedStateOf { (state.firstVisibleItemIndex + 4).coerceIn(1, LevelRepository.MAX_LEVEL) } }
    val world = LevelRepository.world(shown)
    // the world's own sky, pulled toward the game's blue so pale skies still read as sky behind white clouds
    val skyTop by animateColorAsState(lerpColor(Color(0xFF000000.toInt() or world.skyTop), Color(0xFF2E9BFF), 0.2f), tween(600), label = "skyTop")
    val skyLow by animateColorAsState(lerpColor(Color(0xFF000000.toInt() or world.skyHorizon), Color(0xFF6CC7F5), 0.45f), tween(600), label = "skyLow")
    // the jump-to-my-floor button only shows when your floor is off screen
    val showJump by remember { derivedStateOf { state.layoutInfo.visibleItemsInfo.none { it.index == current - 1 } } }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(skyTop, skyLow)))) {
        LazyColumn(
            state = state,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 170.dp, bottom = 118.dp),
        ) {
            items(LevelRepository.MAX_LEVEL, key = { it }) { i ->
                val lv = i + 1
                Column {
                    TowerFloor(lv, d.progress.isUnlocked(lv), lv == current, d.progress.starsFor(lv), d.profile.character) {
                        if (d.progress.isUnlocked(lv)) nav.startRace(RaceRequest(lv)) else locked = lv
                    }
                    // each world starts with a gate you climb through
                    if ((lv - 1) % 25 == 0) WorldGate(lv)
                }
            }
        }
        TowerHeader(shown, current, { d.progress.starsFor(it) }, Modifier.align(Alignment.TopCenter))
        if (showJump) Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 110.dp, end = 14.dp), contentAlignment = Alignment.BottomEnd) {
            GameButton("", { scope.launch { state.animateScrollToItem((current - 2).coerceAtLeast(0)) } }, Modifier.width(64.dp), colors = Buttons.Sun, icon = R.drawable.ui_flag, height = 50.dp)
        }
    }
    locked?.let { lv -> LockedLevelDialog(lv, d.progress.highestUnlocked, { locked = null }, { locked = null; nav.startRace(RaceRequest(it)) }, { locked = null; nav.startRace(RaceRequest(it)) }) }
}

/** Sticky header under the top bar: the world in view, your floor in it and its stars. */
@Composable
private fun TowerHeader(shown: Int, current: Int, starsOf: (Int) -> Int, modifier: Modifier) {
    val w = LevelRepository.world(shown)
    val first = (shown - 1) / 25 * 25 + 1
    val last = min(first + 24, LevelRepository.MAX_LEVEL)
    val stars = (first..last).sumOf { starsOf(it) }
    val done = (first..last).count { it < current }
    Box(modifier.fillMaxWidth().statusBarsPadding().padding(top = 62.dp, start = 14.dp, end = 14.dp)) {
        GlassPanel(Modifier.fillMaxWidth(), padding = 10.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or w.hazA)).border(3.dp, Palette.Ink, CircleShape))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    OutlinedText(w.title, style = Type.Label, outlineWidth = 3.dp, align = TextAlign.Start)
                    BodyText(if (current in first..last) "Floor ${current - first + 1} of ${last - first + 1}" else "Levels $first–$last", color = Color.White, style = Type.BodySmall)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        UiIcon(R.drawable.ui_star, 18.dp)
                        Spacer(Modifier.width(4.dp))
                        OutlinedText("$stars/${(last - first + 1) * 3}", style = Type.Small, outlineWidth = 2.dp)
                    }
                    Spacer(Modifier.height(5.dp))
                    Box(Modifier.width(86.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x55FFFFFF))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(done / (last - first + 1f)).clip(RoundedCornerShape(4.dp)).background(Palette.Lime))
                    }
                }
            }
        }
    }
}

private val FLOOR_H = 84.dp
private val SLAB_TOP = 40.dp
private val SLAB_H = 32.dp

/** Wooden ladder centred on [x] from [y0] down to [y1]; rungs line up across floors. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.ladder(x: Float, y0: Float, y1: Float) {
    if (y1 <= y0) return
    val half = 11.dp.toPx()
    val step = 12.dp.toPx()
    for (side in floatArrayOf(-1f, 1f)) {
        drawLine(Palette.Ink, Offset(x + side * half, y0), Offset(x + side * half, y1), strokeWidth = 7.dp.toPx() * 0.75f)
        drawLine(Color(0xFFC98B55), Offset(x + side * half, y0), Offset(x + side * half, y1), strokeWidth = 3.dp.toPx())
    }
    var y = (kotlin.math.ceil(y0 / step) * step)
    while (y < y1) {
        drawLine(Palette.Ink, Offset(x - half, y), Offset(x + half, y), strokeWidth = 5.dp.toPx() * 0.8f)
        drawLine(Color(0xFFE0A86E), Offset(x - half, y), Offset(x + half, y), strokeWidth = 2.dp.toPx())
        y += step
    }
}

/** A soft cloud of three puffs centred on (x, y). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.cloud(x: Float, y: Float, r: Float) {
    val c = Color(0xCCFFFFFF)
    drawCircle(c, r, Offset(x, y))
    drawCircle(c, r * 0.8f, Offset(x - r * 1.1f, y + r * 0.25f))
    drawCircle(c, r * 0.75f, Offset(x + r * 1.1f, y + r * 0.3f))
    drawRoundRect(c, Offset(x - r * 1.8f, y + r * 0.2f), Size(r * 3.6f, r * 0.8f), CornerRadius(r * 0.4f))
}

/** One floor of the tower: a platform on the left or right (boss floors span the width), ladders to the next and previous floors, and you standing on your current floor. */
@Composable
private fun TowerFloor(level: Int, unlocked: Boolean, current: Boolean, stars: Int, character: String, onClick: () -> Unit) {
    val kind = LevelRepository.kind(level)
    val boss = kind == RaceKind.BOSS_TIDE || kind == RaceKind.BOSS_DUCK || kind == RaceKind.BOSS_STORM
    val bonus = kind == RaceKind.BONUS
    val left = level % 2 == 1
    val x0 = if (boss) 0.06f else if (left) 0.06f else 0.42f
    val x1 = if (boss) 0.94f else if (left) 0.58f else 0.94f
    val (topC, deepC) = when {
        !unlocked -> Color(0xFFC3CED8) to Color(0xFF8A9BAA)
        current -> Color(0xFFA6F58F) to Palette.LimeDeep
        boss -> Color(0xFFFF9BCB) to Palette.PinkDeep
        bonus -> Color(0xFFFFE27A) to Palette.SunDeep
        else -> Color(0xFF8CCBFF) to Palette.OceanDeep
    }
    val pulse = if (current) {
        val inf = rememberInfiniteTransition(label = "floor")
        inf.animateFloat(1f, 1.06f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "fp").value
    } else 1f
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(FLOOR_H)) {
        val w = maxWidth
        Canvas(Modifier.fillMaxSize()) {
            if (!boss && level % 3 == 0) cloud(size.width * (if (left) 0.8f else 0.2f), 30.dp.toPx(), 13.dp.toPx())
            val lx = size.width * 0.5f
            if (level < LevelRepository.MAX_LEVEL) ladder(lx, 0f, (SLAB_TOP + 6.dp).toPx())
            if (level > 1) ladder(lx, (SLAB_TOP + SLAB_H).toPx(), size.height)
        }
        val shape = RoundedCornerShape(12.dp)
        Box(Modifier.offset(x = w * x0, y = SLAB_TOP).size(w * (x1 - x0), SLAB_H + 6.dp).scale(pulse).pressable(onClick = onClick)) {
            Box(Modifier.fillMaxWidth().height(SLAB_H).offset(y = 6.dp).clip(shape).background(deepC).border(3.dp, Palette.Ink, shape))
            Box(
                Modifier.fillMaxWidth().height(SLAB_H).clip(shape).background(Brush.verticalGradient(listOf(topC, lerpColor(topC, deepC, 0.35f)))).border(3.dp, Palette.Ink, shape),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        !unlocked -> { UiIcon(R.drawable.ui_lock, 20.dp); Spacer(Modifier.width(4.dp)); OutlinedText(if (boss) "Boss $level" else "$level", style = Type.Label, color = Color(0xFFEFF3F6), outlineWidth = 2.dp) }
                        boss -> { UiIcon(R.drawable.ui_boss, 24.dp); Spacer(Modifier.width(6.dp)); OutlinedText("Boss $level", style = Type.Label, outlineWidth = 3.dp) }
                        bonus -> { UiIcon(R.drawable.ui_bonus, 22.dp); Spacer(Modifier.width(4.dp)); OutlinedText("$level", style = Type.Label, outlineWidth = 3.dp) }
                        else -> OutlinedText("$level", style = Type.Label.copy(fontSize = if (level >= 1000) 16.sp else 18.sp), outlineWidth = 3.dp)
                    }
                    if (unlocked && !current && stars > 0) {
                        Spacer(Modifier.width(6.dp))
                        for (k in 1..3) UiIcon(if (stars >= k) R.drawable.ui_star else R.drawable.ui_star_empty, 14.dp)
                    }
                }
            }
        }
        if (current) {
            // you, standing on your floor, ready to go
            val cx = w * (x0 + x1) / 2f
            Row(Modifier.offset(x = cx - 48.dp, y = SLAB_TOP - 36.dp).pressable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
                Avatar(character, 34.dp)
                Spacer(Modifier.width(6.dp))
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Palette.LimeDeep).border(2.dp, Palette.Ink, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 3.dp)) {
                    OutlinedText("PLAY", style = Type.Small, outlineWidth = 2.dp)
                }
            }
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f)

/** Coloured band below a world's first floor: the world you climb into next. */
@Composable
private fun WorldGate(level: Int) {
    val w = LevelRepository.world(level)
    Box(Modifier.fillMaxWidth().height(62.dp)) {
        Canvas(Modifier.fillMaxSize()) { if (level > 1) ladder(size.width * 0.5f, 0f, size.height) }
        val shape = RoundedCornerShape(14.dp)
        Box(
            Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 10.dp).height(46.dp).clip(shape)
                .background(Color(0xFF000000.toInt() or w.hazA)).border(3.dp, Palette.Ink, shape),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                OutlinedText(w.title, style = Type.Label, outlineWidth = 3.dp)
                OutlinedText("Levels $level–${min(level + 24, LevelRepository.MAX_LEVEL)}", style = Type.Small, outlineWidth = 2.dp)
            }
        }
    }
}

@Suppress("unused")
private fun drawRounded(c: androidx.compose.ui.graphics.drawscope.DrawScope) { c.drawRoundRect(Color.White, size = Size(1f, 1f), cornerRadius = CornerRadius(1f), style = Stroke(1f)) }

@Suppress("unused")
private fun noop(m: Modifier) = m.fillMaxHeight()

@Suppress("unused")
private val unusedAlign = TextAlign.Center
