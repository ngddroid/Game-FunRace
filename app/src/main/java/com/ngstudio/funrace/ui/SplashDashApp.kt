package com.ngstudio.funrace.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ngstudio.funrace.ads.MockAdProvider
import com.ngstudio.funrace.app.AppContainer
import com.ngstudio.funrace.audio.Music
import com.ngstudio.funrace.game.render.MenuMode
import com.ngstudio.funrace.ui.components.LocalContainer
import com.ngstudio.funrace.ui.components.OutlinedText
import com.ngstudio.funrace.ui.screens.LegalDoc
import com.ngstudio.funrace.ui.screens.LegalScreen
import com.ngstudio.funrace.ui.screens.MainScreen
import com.ngstudio.funrace.ui.screens.MockAdOverlay
import com.ngstudio.funrace.ui.screens.OnboardingScreen
import com.ngstudio.funrace.ui.screens.RaceScreen
import com.ngstudio.funrace.ui.screens.SettingsScreen
import com.ngstudio.funrace.ui.screens.SplashScreen
import com.ngstudio.funrace.ui.theme.Palette
import com.ngstudio.funrace.ui.theme.Type
import kotlinx.coroutines.delay

enum class PlayMode { CAMPAIGN, DAILY, TIME_TRIAL }

data class RaceRequest(val level: Int, val mode: PlayMode = PlayMode.CAMPAIGN, val boosters: Set<String> = emptySet())

sealed class Screen {
    data object Splash : Screen()
    data object Onboarding : Screen()
    data object Main : Screen()
    data object Settings : Screen()
    data class Legal(val doc: LegalDoc) : Screen()
    data class Race(val request: RaceRequest, val nonce: Int) : Screen()
}

enum class Tab { SHOP, EVENTS, HOME, LEVELS, PROFILE }

/** Debug-only launch options (adb extras). */
object LaunchOptions {
    var autoLevel = 0
    var autopilot = false
}

class AppNav {
    val stack = mutableStateListOf<Screen>(Screen.Splash)
    var tab by mutableStateOf(Tab.HOME)
    var toast by mutableStateOf<String?>(null)
    var toastId by mutableIntStateOf(0)
    var boosters by mutableStateOf(setOf<String>())
    private var nonce = 0

    val top: Screen get() = stack.last()
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.size - 1) }
    fun replaceAll(s: Screen) { stack.clear(); stack.add(s) }
    fun startRace(req: RaceRequest) {
        nonce++
        while (stack.size > 1 && stack.last() !is Screen.Main) stack.removeAt(stack.size - 1)
        stack.add(Screen.Race(req, nonce))
    }
    fun showToast(text: String) { toast = text; toastId++ }
}

val LocalNav = staticCompositionLocalOf<AppNav> { error("No nav") }

@Composable
fun SplashDashApp(c: AppContainer, activity: Activity) {
    val nav = remember { AppNav() }
    CompositionLocalProvider(LocalContainer provides c, LocalNav provides nav) {
        val top = nav.top
        LaunchedEffect(top, nav.tab) {
            when (top) {
                Screen.Splash -> { c.stage.showMenu(MenuMode.SPLASH); c.stage.playMusic(Music.MENU) }
                Screen.Onboarding -> c.stage.playMusic(Music.MENU)
                Screen.Main -> {
                    c.stage.playMusic(Music.MENU)
                    when (nav.tab) {
                        Tab.HOME -> c.stage.showMenu(MenuMode.HOME)
                        Tab.SHOP -> c.stage.showMenu(MenuMode.SHOP)
                        Tab.PROFILE -> c.stage.showMenu(MenuMode.PROFILE)
                        Tab.LEVELS, Tab.EVENTS -> { delay(350); c.stage.setRendering(false) }
                    }
                }
                Screen.Settings, is Screen.Legal -> { delay(350); c.stage.setRendering(false) }
                is Screen.Race -> Unit
            }
        }
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = top,
                transitionSpec = { transitionFor(initialState, targetState) },
                label = "screens",
                contentKey = { s -> if (s is Screen.Race) "race${s.nonce}" else s.toString() },
            ) { screen ->
                when (screen) {
                    Screen.Splash -> SplashScreen()
                    Screen.Onboarding -> OnboardingScreen()
                    Screen.Main -> MainScreen()
                    Screen.Settings -> SettingsScreen(activity)
                    is Screen.Legal -> LegalScreen(screen.doc)
                    is Screen.Race -> RaceScreen(screen.request)
                }
            }
            (c.ads as? MockAdProvider)?.let { MockAdOverlay(it) }
            ToastHost(nav)
        }
        BackHandler(enabled = nav.stack.size > 1 && top !is Screen.Race && top !is Screen.Onboarding) { c.back(); nav.pop() }
    }
}

private fun transitionFor(from: Screen, to: Screen): ContentTransform = when {
    to is Screen.Race || from is Screen.Race -> fadeIn(tween(380)) togetherWith fadeOut(tween(320))
    to is Screen.Settings || to is Screen.Legal -> (slideInVertically(tween(380)) { it / 3 } + fadeIn(tween(300))) togetherWith fadeOut(tween(250))
    from is Screen.Settings || from is Screen.Legal -> fadeIn(tween(300)) togetherWith (slideOutVertically(tween(320)) { it / 3 } + fadeOut(tween(260)))
    else -> (fadeIn(tween(450)) + scaleIn(tween(450), initialScale = 1.04f)) togetherWith fadeOut(tween(350))
}

@Composable
private fun ToastHost(nav: AppNav) {
    var visible by remember { mutableStateOf(false) }
    val text = nav.toast
    LaunchedEffect(nav.toastId) { if (text != null) { visible = true; delay(2200); visible = false } }
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 90.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(visible, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
            Box(
                Modifier.padding(horizontal = 32.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xE60B2740))
                    .border(2.dp, Palette.Lagoon, RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
            ) { OutlinedText(text ?: "", style = Type.Label, outlineWidth = 3.dp, maxLines = 2) }
        }
    }
}
