package com.ngstudio.funrace.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ngstudio.funrace.BuildConfig
import com.ngstudio.funrace.R
import com.ngstudio.funrace.data.Progression
import com.ngstudio.funrace.data.Settings
import com.ngstudio.funrace.level.LevelRepository
import com.ngstudio.funrace.ui.LaunchOptions
import com.ngstudio.funrace.ui.LocalNav
import com.ngstudio.funrace.ui.RaceRequest
import com.ngstudio.funrace.ui.Screen
import com.ngstudio.funrace.ui.components.Banner
import com.ngstudio.funrace.ui.components.BodyText
import com.ngstudio.funrace.ui.components.Buttons
import com.ngstudio.funrace.ui.components.GameBackground
import com.ngstudio.funrace.ui.components.GameButton
import com.ngstudio.funrace.ui.components.GameDialog
import com.ngstudio.funrace.ui.components.GameSlider
import com.ngstudio.funrace.ui.components.GameToggle
import com.ngstudio.funrace.ui.components.LocalContainer
import com.ngstudio.funrace.ui.components.OutlinedText
import com.ngstudio.funrace.ui.components.Panel
import com.ngstudio.funrace.ui.components.UiIcon
import com.ngstudio.funrace.ui.components.pressable
import com.ngstudio.funrace.ui.theme.Palette
import com.ngstudio.funrace.ui.theme.Type

enum class LegalDoc(val title: String, val file: String) { TERMS("Terms of Service", "terms.html"), PRIVACY("Privacy Policy", "privacy.html") }

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).pressable(onClick = onBack)) { UiIcon(R.drawable.ui_back, 46.dp) }
        Spacer(Modifier.width(8.dp))
        OutlinedText(title, style = Type.Title)
    }
}

@Composable
fun SettingsScreen(activity: Activity) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val d by c.save.state.collectAsState()
    val s = d.settings
    var confirmReset by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(false) }
    fun set(f: (Settings) -> Settings) { c.save.update { it.copy(settings = f(it.settings)) }; c.applySettings(c.save.data.settings) }
    GameBackground(deep = true) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Settings") { c.back(); nav.pop() }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).navigationBarsPadding().padding(bottom = 24.dp)) {
                Panel(Modifier.fillMaxWidth(), padding = 14.dp) {
                    SettingRow(R.drawable.ui_music, "Music") { GameToggle(s.music, { v -> set { it.copy(music = v) } }) }
                    GameSlider(s.musicVolume, { v -> set { it.copy(musicVolume = v) } }, Modifier.fillMaxWidth().padding(start = 48.dp), enabled = s.music)
                    SettingRow(R.drawable.ui_sound, "Sound Effects") { GameToggle(s.sfx, { v -> set { it.copy(sfx = v) } }) }
                    GameSlider(s.sfxVolume, { v -> set { it.copy(sfxVolume = v) } }, Modifier.fillMaxWidth().padding(start = 48.dp), enabled = s.sfx)
                    SettingRow(R.drawable.ui_vibrate, "Haptics") { GameToggle(s.haptics, { v -> set { it.copy(haptics = v) } }) }
                    SettingRow(R.drawable.ui_graphics, "Graphics") {}
                    Row(Modifier.fillMaxWidth().padding(start = 48.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((i, label) in listOf("Auto", "Low", "Med", "High").withIndex()) {
                            val on = s.quality == i
                            Box(Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Palette.Lagoon else Color(0x2213304A)).border(2.dp, Palette.Ink, RoundedCornerShape(12.dp)).pressable { set { it.copy(quality = i) }; nav.showToast("Graphics change applies after restarting the game") }, contentAlignment = Alignment.Center) {
                                OutlinedText(label, style = Type.Small, outlineWidth = 2.dp)
                            }
                        }
                    }
                    SettingRow(R.drawable.ui_wave, "Reduced motion") { GameToggle(s.reducedMotion, { v -> set { it.copy(reducedMotion = v) } }) }
                }
                Spacer(Modifier.height(14.dp))
                Panel(Modifier.fillMaxWidth(), padding = 14.dp) {
                    LinkRow(R.drawable.ui_edit, "Edit name") { editName = true }
                    LinkRow(R.drawable.ui_hand, "Replay tutorial") { c.save.update { it.copy(profile = it.profile.copy(onboardingDone = false)) }; nav.replaceAll(Screen.Onboarding) }
                    LinkRow(R.drawable.ui_doc, "Terms of Service") { nav.push(Screen.Legal(LegalDoc.TERMS)) }
                    LinkRow(R.drawable.ui_privacy, "Privacy Policy") { nav.push(Screen.Legal(LegalDoc.PRIVACY)) }
                    LinkRow(R.drawable.ui_trash, "Reset progress") { confirmReset = true }
                }
                if (BuildConfig.DEBUG) {
                    Spacer(Modifier.height(14.dp))
                    Banner("Debug", Modifier.fillMaxWidth(), color = Palette.Purple, dark = Palette.PurpleDeep)
                    Panel(Modifier.fillMaxWidth(), padding = 14.dp) {
                        LinkRow(R.drawable.ui_coin, "+5,000 coins & 200 gems") { c.save.update { it.copy(wallet = it.wallet.copy(coins = it.wallet.coins + 5000, gems = it.wallet.gems + 200)) } }
                        LinkRow(R.drawable.ui_lock, "Unlock levels 1–60") { c.save.update { it.copy(progress = it.progress.copy(highestUnlocked = maxOf(it.progress.highestUnlocked, 60))) } }
                        LinkRow(R.drawable.ui_turbo, "Autopilot: " + if (LaunchOptions.autopilot) "ON" else "OFF") { LaunchOptions.autopilot = !LaunchOptions.autopilot; nav.showToast("Autopilot " + if (LaunchOptions.autopilot) "on" else "off") }
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            var lv by remember { mutableStateOf("100") }
                            BodyText("Jump to level", Modifier.weight(1f))
                            Box(Modifier.width(90.dp).clip(RoundedCornerShape(10.dp)).background(Color.White).border(2.dp, Palette.Ink, RoundedCornerShape(10.dp)).padding(8.dp)) {
                                BasicTextField(lv, { v -> lv = v.filter { it.isDigit() }.take(4) }, textStyle = Type.Body.copy(color = Palette.Ink, fontSize = 16.sp))
                            }
                            Spacer(Modifier.width(6.dp))
                            GameButton("Go", { lv.toIntOrNull()?.coerceIn(1, LevelRepository.MAX_LEVEL)?.let { nav.startRace(RaceRequest(it)) } }, Modifier.width(70.dp), height = 40.dp)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                BodyText("Splash Dash v${BuildConfig.VERSION_NAME}", color = Color.White, align = TextAlign.Center, modifier = Modifier.fillMaxWidth(), style = Type.BodySmall)
            }
        }
    }
    if (confirmReset) GameDialog("Reset?", onDismiss = { confirmReset = false }) {
        BodyText("This erases all levels, coins, gems and unlocked items on this device. It can't be undone.", align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(14.dp))
        GameButton("Erase Everything", { c.save.resetAll(); c.applySettings(c.save.data.settings); confirmReset = false; nav.replaceAll(Screen.Main) }, Modifier.fillMaxWidth(), colors = Buttons.Coral)
        Spacer(Modifier.height(10.dp))
        GameButton("Keep Playing", { confirmReset = false }, Modifier.fillMaxWidth(), colors = Buttons.Green)
    }
    if (editName) {
        var name by remember { mutableStateOf(d.profile.name) }
        GameDialog("Your Name", onDismiss = { editName = false }, banner = Palette.Ocean, bannerDark = Palette.OceanDeep) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White).border(3.dp, Palette.Ink, RoundedCornerShape(14.dp)).padding(12.dp)) {
                BasicTextField(name, { v -> name = v.filter { it.isLetterOrDigit() || it == ' ' || it == '_' }.take(14) }, singleLine = true, textStyle = Type.Body.copy(color = Palette.Ink, fontSize = 18.sp), modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(14.dp))
            GameButton("Save", { c.save.update { it.copy(profile = it.profile.copy(name = name.trim().ifEmpty { "Player" })) }; editName = false }, Modifier.fillMaxWidth(), colors = Buttons.Green)
        }
    }
    @Suppress("UNUSED_VARIABLE") val u = activity; @Suppress("UNUSED_VARIABLE") val p = Progression
}

@Composable
private fun SettingRow(icon: Int, label: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        UiIcon(icon, 38.dp)
        Spacer(Modifier.width(10.dp))
        BodyText(label, Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun LinkRow(icon: Int, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x1419C6C2)).pressable(onClick = onClick).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        UiIcon(icon, 34.dp)
        Spacer(Modifier.width(10.dp))
        BodyText(label, Modifier.weight(1f))
        UiIcon(R.drawable.ui_play, 22.dp)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LegalScreen(doc: LegalDoc) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val dark = isSystemInDarkTheme()
    Column(Modifier.fillMaxSize().background(if (dark) Color(0xFF0A1A2F) else Color(0xFFE9FAF8))) {
        Box(Modifier.fillMaxWidth().background(Palette.bgGradient)) { ScreenHeader(doc.title) { c.back(); nav.pop() } }
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(if (dark) 0xFF0A1A2F.toInt() else 0xFFE9FAF8.toInt())
                    settings.javaScriptEnabled = false
                    settings.allowFileAccess = true
                    settings.allowContentAccess = false
                    settings.blockNetworkLoads = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val u = request.url
                            if (u.scheme == "mailto") {
                                try { ctx.startActivity(Intent(Intent.ACTION_SENDTO, u)) } catch (_: Throwable) {}
                                return true
                            }
                            return u.scheme != "file"
                        }
                    }
                    loadUrl("file:///android_asset/legal/${doc.file}")
                }
            },
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        )
    }
    @Suppress("UNUSED_VARIABLE") val u = Uri.EMPTY
}
