package com.ngstudio.funrace.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngstudio.funrace.BuildConfig
import com.ngstudio.funrace.R
import com.ngstudio.funrace.ads.MockAdProvider
import com.ngstudio.funrace.audio.Sfx
import com.ngstudio.funrace.data.Catalog
import com.ngstudio.funrace.data.Progression
import com.ngstudio.funrace.game.render.MenuMode
import com.ngstudio.funrace.ui.LaunchOptions
import com.ngstudio.funrace.ui.LocalNav
import com.ngstudio.funrace.ui.RaceRequest
import com.ngstudio.funrace.ui.Screen
import com.ngstudio.funrace.ui.components.Banner
import com.ngstudio.funrace.ui.components.BodyText
import com.ngstudio.funrace.ui.components.BubblesLayer
import com.ngstudio.funrace.ui.components.Buttons
import com.ngstudio.funrace.ui.components.GameButton
import com.ngstudio.funrace.ui.components.GameDialog
import com.ngstudio.funrace.ui.components.GameProgressBar
import com.ngstudio.funrace.ui.components.LocalContainer
import com.ngstudio.funrace.ui.components.OutlinedText
import com.ngstudio.funrace.ui.components.Panel
import com.ngstudio.funrace.ui.components.UiIcon
import com.ngstudio.funrace.ui.components.pressable
import com.ngstudio.funrace.ui.theme.Palette
import com.ngstudio.funrace.ui.theme.Type
import kotlinx.coroutines.delay
import kotlin.math.sin

/** The game's wordmark: chunky gradient letters with a wave underline. */
@Composable
fun Logo(modifier: Modifier = Modifier, scale: Float = 1f) {
    val inf = rememberInfiniteTransition(label = "logo")
    val wob by inf.animateFloat(-2f, 2f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "lw")
    Column(modifier.graphicsLayer { rotationZ = wob * 0.6f; scaleX = scale; scaleY = scale }, horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedText("SPLASH", style = Type.Huge.copy(fontSize = 62.sp, letterSpacing = 2.sp), outlineWidth = 9.dp,
            brush = Brush.verticalGradient(listOf(Color(0xFFFFF4A8), Palette.Sun, Color(0xFFFF9E2E))))
        OutlinedText("DASH", style = Type.Huge.copy(fontSize = 76.sp, letterSpacing = 3.sp), outlineWidth = 10.dp, modifier = Modifier.offset(y = (-18).dp),
            brush = Brush.verticalGradient(listOf(Color(0xFFB8FFF8), Palette.Lagoon, Palette.Ocean)))
        Box(Modifier.offset(y = (-22).dp).clip(RoundedCornerShape(14.dp)).background(Palette.Coral).border(3.dp, Palette.Ink, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 2.dp)) {
            OutlinedText("OBSTACLE PARTY", style = Type.Label.copy(fontSize = 16.sp, letterSpacing = 2.sp), outlineWidth = 3.dp)
        }
    }
}

@Composable
fun SplashScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val progress by c.loadProgress.collectAsState()
    val ready by (c.stage.renderer?.ready ?: kotlinx.coroutines.flow.MutableStateFlow(true)).collectAsState()
    val drop = remember { Animatable(0f) }
    LaunchedEffect(Unit) { drop.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 180f)); c.audio.play(Sfx.GO, 0.6f) }
    LaunchedEffect(progress, ready) {
        if (progress >= 1f && ready) {
            delay(900)
            c.save.update { Progression.rollDaily(it) }
            val d = c.save.data
            if (BuildConfig.DEBUG && LaunchOptions.autoLevel > 0) {
                nav.replaceAll(Screen.Main)
                nav.startRace(RaceRequest(LaunchOptions.autoLevel))
            } else nav.replaceAll(if (d.profile.onboardingDone) Screen.Main else Screen.Onboarding)
        }
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x660B2740), Color(0x000B2740), Color(0x000B2740), Color(0xAA0B2740)))))
        BubblesLayer(count = 16)
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Logo(Modifier.graphicsLayer { translationY = (1f - drop.value) * -500f })
        }
        Column(Modifier.fillMaxSize().navigationBarsPadding().padding(32.dp), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
            OutlinedText(if (progress < 1f) "Pumping up the floaties..." else "Ready!", style = Type.Label, outlineWidth = 3.dp)
            Spacer(Modifier.height(8.dp))
            GameProgressBar(progress, Modifier.fillMaxWidth(0.8f), height = 20.dp, fill = Brush.horizontalGradient(listOf(Palette.Sun, Palette.Coral)))
        }
    }
}

private class OnboardPage(val title: String, val body: String, val mode: MenuMode, val icon: Int)

@Composable
fun OnboardingScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val pages = remember {
        listOf(
            OnboardPage("Hold, Steer, Jump", "Hold anywhere to run. Drag left or right to steer, and swipe up (or tap JUMP) to hop over low obstacles.", MenuMode.ONBOARD_RUN, R.drawable.ui_hand),
            OnboardPage("Let Go to Stop", "Every obstacle has a rhythm. Release to wait, then dash through the gap. Slip past at the last moment for a PERFECT!", MenuMode.ONBOARD_DODGE, R.drawable.ui_turbo),
            OnboardPage("Bounce, Zip & Climb", "Bounce pads, ziplines, rope swings, water slides and climbing walls. Beat your rivals to the podium!", MenuMode.ONBOARD_FLY, R.drawable.ui_trophy),
            OnboardPage("Collect & Dress Up", "Earn coins, unlock 30 Dashlings, hats, trails and victory dances, and conquer 5,000 levels.", MenuMode.ONBOARD_STYLE, R.drawable.ui_hat),
        )
    }
    var page by remember { mutableIntStateOf(0) }
    var naming by remember { mutableStateOf(false) }
    LaunchedEffect(page, naming) { c.stage.showMenu(if (naming) MenuMode.SHOP else pages[page].mode) }
    BackHandler(enabled = page > 0 || naming) { c.back(); if (naming) naming = false else page-- }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x000B2740), Color(0x000B2740), Color(0xCC0B2740)))))
        if (!naming) {
            Column(Modifier.fillMaxSize().navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.Bottom) {
                AnimatedContent(page, transitionSpec = {
                    (slideInHorizontally(tween(320)) { it / 2 } + fadeIn(tween(300))) togetherWith (slideOutHorizontally(tween(260)) { -it / 2 } + fadeOut(tween(220)))
                }, label = "ob") { i ->
                    val p = pages[i]
                    Panel(Modifier.fillMaxWidth(), padding = 18.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            UiIcon(p.icon, 52.dp)
                            Spacer(Modifier.width(10.dp))
                            OutlinedText(p.title, style = if (p.title.length > 14) Type.Heading else Type.Title, color = Palette.Sun)
                        }
                        Spacer(Modifier.height(8.dp))
                        BodyText(p.body)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    for (k in pages.indices) {
                        Box(Modifier.padding(4.dp).size(if (k == page) 26.dp else 12.dp, 12.dp).clip(RoundedCornerShape(6.dp)).background(if (k == page) Palette.Sun else Color(0x88FFFFFF)).border(2.dp, Palette.Ink, RoundedCornerShape(6.dp)))
                    }
                }
                Spacer(Modifier.height(14.dp))
                GameButton(if (page < pages.size - 1) "Next" else "Let's Dash!", {
                    if (page < pages.size - 1) page++ else naming = true
                }, Modifier.fillMaxWidth(), colors = if (page < pages.size - 1) Buttons.Ocean else Buttons.Green, shine = page == pages.size - 1, trailingIcon = R.drawable.ui_play)
            }
        } else NamePick(onDone = { name, ch ->
            c.save.update { d -> d.copy(profile = d.profile.copy(name = name, character = ch, onboardingDone = true), collection = d.collection.copy(owned = d.collection.owned + ch)) }
            c.audio.play(Sfx.UNLOCK)
            nav.replaceAll(Screen.Main)
        })
    }
}

@Composable
private fun NamePick(onDone: (String, String) -> Unit) {
    val c = LocalContainer.current
    val starters = listOf("dash", "coral", "sunny")
    var pick by remember { mutableStateOf("dash") }
    var name by remember { mutableStateOf(c.save.data.profile.name.takeIf { it != "Player" } ?: "") }
    val focus = LocalFocusManager.current
    LaunchedEffect(pick) { c.stage.showMenu(MenuMode.SHOP, characterId = pick) }
    Column(Modifier.fillMaxSize().navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.Bottom) {
        Banner("Meet your Dashling", Modifier.fillMaxWidth())
        Panel(Modifier.fillMaxWidth(), padding = 16.dp) {
            BodyText("Pick a starter (you'll unlock many more):", style = Type.BodySmall)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                for (id in starters) {
                    val ch = Catalog.character(id)
                    val sel = id == pick
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.pressable { pick = id }) {
                        Box(
                            Modifier.size(if (sel) 70.dp else 60.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or ch.primary)).border(if (sel) 4.dp else 3.dp, if (sel) Palette.Sun else Palette.Ink, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Box(Modifier.size(30.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or ch.skin)).border(2.dp, Palette.Ink, CircleShape)) }
                        OutlinedText(ch.name, style = Type.Label, outlineWidth = 3.dp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            BodyText("What should we call you?", style = Type.BodySmall)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White).border(3.dp, Palette.Ink, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (name.isEmpty()) BodyText("Your name", color = Palette.Muted)
                BasicTextField(
                    value = name, onValueChange = { v -> name = v.filter { it.isLetterOrDigit() || it == ' ' || it == '_' }.take(14) },
                    singleLine = true, textStyle = Type.Body.copy(color = Palette.Ink, fontSize = 18.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        GameButton("Start Racing!", { focus.clearFocus(); onDone(name.trim().ifEmpty { "Player" }, pick) }, Modifier.fillMaxWidth(), colors = Buttons.Green, shine = true)
    }
}

/** Debug-only fake rewarded ad: clearly labelled, with a countdown and a close button. */
@Composable
fun MockAdOverlay(provider: MockAdProvider) {
    val req by provider.request.collectAsState()
    val r = req ?: return
    var left by remember(r) { mutableIntStateOf(3) }
    LaunchedEffect(r) { while (left > 0) { delay(1000); left-- } }
    Box(Modifier.fillMaxSize().background(Color(0xF00B2740)).pressable(sound = false) {}, contentAlignment = Alignment.Center) {
        val inf = rememberInfiniteTransition(label = "ad")
        val a by inf.animateFloat(0f, 6.28f, infiniteRepeatable(tween(3000)), label = "ada")
        Canvas(Modifier.fillMaxSize()) {
            for (k in 0 until 6) {
                val y = size.height * (0.2f + k * 0.13f)
                val p = Path(); p.moveTo(0f, y)
                var x = 0f
                while (x <= size.width) { p.lineTo(x, y + sin(x / size.width * 8f + a + k) * 16f); x += 12f }
                drawPath(p, Color(0x2219C6C2), style = Stroke(10f))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Palette.Coral).padding(horizontal = 12.dp, vertical = 4.dp)) { OutlinedText("DEBUG AD", style = Type.Label, outlineWidth = 3.dp) }
            Spacer(Modifier.height(16.dp))
            UiIcon(R.drawable.ui_video, 110.dp)
            Spacer(Modifier.height(12.dp))
            OutlinedText("This is a simulated ad", style = Type.Heading)
            BodyText("Real ads will appear here once an ad network is added. Placement: ${r.placement}", color = Color.White, align = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            if (left > 0) OutlinedText("Reward in $left...", style = Type.Title, color = Palette.Sun)
            else GameButton("Claim Reward", { provider.complete(true) }, colors = Buttons.Green, icon = R.drawable.ui_check)
        }
        Box(Modifier.fillMaxSize().statusBarsPadding().padding(14.dp), contentAlignment = Alignment.TopEnd) {
            Box(Modifier.size(44.dp).pressable { provider.complete(false) }) { UiIcon(R.drawable.ui_close, 44.dp) }
        }
    }
}

/** Locked level: debug offers the mock ad unlock; release asks to clear the previous level. */
@Composable
fun LockedLevelDialog(level: Int, highest: Int, onDismiss: () -> Unit, onUnlocked: (Int) -> Unit, onGo: (Int) -> Unit) {
    val c = LocalContainer.current
    if (com.ngstudio.funrace.ads.AdConfig.rewardedUnlockEnabled && c.ads.rewardedAvailable) {
        GameDialog("Level $level", onDismiss = onDismiss, banner = Palette.Purple, bannerDark = Palette.PurpleDeep) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { UiIcon(R.drawable.ui_lock, 76.dp) }
            Spacer(Modifier.height(8.dp))
            BodyText("This level is locked. Watch an ad to unlock it right now?", align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            GameButton("Watch Ad", {
                c.ads.showRewarded("unlock_level_$level") { ok ->
                    if (ok) { Progression.unlockLevel(c.save, level); c.audio.play(Sfx.UNLOCK); onUnlocked(level) } else onDismiss()
                }
            }, Modifier.fillMaxWidth(), colors = Buttons.Purple, icon = R.drawable.ui_ad)
        }
    } else {
        GameDialog("Locked", onDismiss = onDismiss, banner = Palette.Ocean, bannerDark = Palette.OceanDeep) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { UiIcon(R.drawable.ui_lock, 76.dp) }
            Spacer(Modifier.height(8.dp))
            BodyText("Clear Level $highest first to unlock the next courses.", align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            GameButton("Go to Level $highest", { onGo(highest) }, Modifier.fillMaxWidth(), colors = Buttons.Green, icon = R.drawable.ui_play)
        }
    }
}
