package com.ngstudio.funrace.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ngstudio.funrace.R
import com.ngstudio.funrace.ads.AdConfig
import com.ngstudio.funrace.audio.Sfx
import com.ngstudio.funrace.data.GameRules
import com.ngstudio.funrace.data.Progression
import com.ngstudio.funrace.data.Reward
import com.ngstudio.funrace.ui.LocalNav
import com.ngstudio.funrace.ui.PlayMode
import com.ngstudio.funrace.ui.RaceRequest
import com.ngstudio.funrace.ui.components.Banner
import com.ngstudio.funrace.ui.components.BodyText
import com.ngstudio.funrace.ui.components.Buttons
import com.ngstudio.funrace.ui.components.GameBackground
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
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun rewardIcon(r: Reward): Int = when (r) {
    is Reward.Coins -> R.drawable.ui_coins_pile
    is Reward.Gems -> R.drawable.ui_gem
    is Reward.Booster -> when (r.id) { "sneakers" -> R.drawable.ui_sneaker; "shield" -> R.drawable.ui_bubble; else -> R.drawable.ui_magnet }
    is Reward.Item -> if (r.id.startsWith("hat:")) R.drawable.ui_hat else if (r.id.startsWith("trail:")) R.drawable.ui_trail else if (r.id.startsWith("dance:")) R.drawable.ui_dance else R.drawable.ui_character
}

@Composable
fun EventsTab() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val d by c.save.state.collectAsState()
    var claimed by remember { mutableStateOf<Reward?>(null) }
    var trialPick by remember { mutableStateOf(false) }
    val today = Progression.today()
    GameBackground(deep = true) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 60.dp, bottom = 0.dp).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).navigationBarsPadding().padding(bottom = 110.dp)) {
            // ---------------- daily challenge
            Banner("Daily Challenge", Modifier.fillMaxWidth(), color = Palette.Pink, dark = Palette.PinkDeep)
            Panel(Modifier.fillMaxWidth(), padding = 14.dp) {
                val mod = GameRules.challengeModifier(today)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UiIcon(R.drawable.ui_daily, 56.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        OutlinedText(mod.title, style = Type.Heading, color = Palette.Pink)
                        BodyText(mod.desc, style = Type.BodySmall)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BodyText(if (d.daily.challengeDone) "Done for today! New course tomorrow." else "Clear today's course for ${GameRules.CHALLENGE_GEMS}", Modifier.weight(1f), style = Type.BodySmall)
                    if (!d.daily.challengeDone) UiIcon(R.drawable.ui_gem, 24.dp)
                }
                Spacer(Modifier.height(8.dp))
                GameButton(if (d.daily.challengeDone) "Play Again" else "Play Challenge", { nav.startRace(RaceRequest(1, PlayMode.DAILY)) }, Modifier.fillMaxWidth(), colors = Buttons.Pink, icon = R.drawable.ui_play, height = 52.dp)
            }
            Spacer(Modifier.height(16.dp))
            // ---------------- time trials
            Banner("Time Trials", Modifier.fillMaxWidth(), color = Palette.Ocean, dark = Palette.OceanDeep)
            Panel(Modifier.fillMaxWidth(), padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UiIcon(R.drawable.ui_stopwatch, 52.dp)
                    Spacer(Modifier.width(10.dp))
                    BodyText("Race any cleared level against the clock. Earn Bronze, Silver and Gold medals!", Modifier.weight(1f), style = Type.BodySmall)
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    val golds = d.progress.medals.values.count { it == 3 }
                    val silvers = d.progress.medals.values.count { it == 2 }
                    val bronzes = d.progress.medals.values.count { it == 1 }
                    MedalCount(R.drawable.ui_medal_gold, golds); MedalCount(R.drawable.ui_medal_silver, silvers); MedalCount(R.drawable.ui_medal_bronze, bronzes)
                }
                Spacer(Modifier.height(8.dp))
                GameButton("Choose Level", { if (d.progress.highestUnlocked > 1) trialPick = true else nav.showToast("Clear Level 1 first!") }, Modifier.fillMaxWidth(), colors = Buttons.Ocean, height = 52.dp)
            }
            Spacer(Modifier.height(16.dp))
            // ---------------- calendar
            Banner("Daily Rewards", Modifier.fillMaxWidth(), color = Palette.Sun, dark = Palette.SunDeep)
            Panel(Modifier.fillMaxWidth(), padding = 12.dp) {
                val canClaim = GameRules.calendarAvailable(d.daily, today)
                val streakBroken = d.daily.loginLastDay >= 0 && today - d.daily.loginLastDay > 1
                val nextIdx = if (streakBroken) 0 else d.daily.loginIndex % 7
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (i in 0 until 7) {
                        val done = i < nextIdx
                        val cur = i == nextIdx && canClaim
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (cur) Palette.Sun else if (done) Palette.Lime.copy(alpha = 0.5f) else Color(0x2213304A))
                                .border(if (cur) 3.dp else 2.dp, Palette.Ink, RoundedCornerShape(12.dp)).padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            BodyText("D${i + 1}", style = Type.BodySmall)
                            UiIcon(rewardIcon(GameRules.calendar[i]), 26.dp, alpha = if (done) 0.5f else 1f)
                            if (done) UiIcon(R.drawable.ui_check, 16.dp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                GameButton(if (canClaim) "Claim: " + GameRules.calendar[nextIdx].label else "Come back tomorrow", {
                    Progression.claimCalendar(c.save)?.let { claimed = it; c.audio.play(Sfx.CHEST) }
                }, Modifier.fillMaxWidth(), colors = if (canClaim) Buttons.Sun else Buttons.Grey, enabled = canClaim, height = 52.dp)
            }
            Spacer(Modifier.height(16.dp))
            // ---------------- wheel
            Banner("Lucky Wheel", Modifier.fillMaxWidth(), color = Palette.Lagoon, dark = Palette.LagoonDeep)
            Panel(Modifier.fillMaxWidth(), padding = 12.dp) { LuckyWheel(onWin = { claimed = it }) }
            Spacer(Modifier.height(16.dp))
            // ---------------- missions
            Banner("Missions", Modifier.fillMaxWidth(), color = Palette.Coral, dark = Palette.CoralDeep)
            Panel(Modifier.fillMaxWidth(), padding = 12.dp) {
                for (m in d.daily.missions) {
                    val def = GameRules.missionDef(m.id)
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        UiIcon(R.drawable.ui_mission, 36.dp)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            BodyText(def.text.format(m.target), style = Type.BodySmall)
                            GameProgressBar(m.progress / m.target.toFloat(), Modifier.fillMaxWidth(), height = 12.dp)
                        }
                        Spacer(Modifier.width(8.dp))
                        if (m.claimed) UiIcon(R.drawable.ui_check, 34.dp)
                        else GameButton("${GameRules.missionReward(m)}", {
                            val got = Progression.claimMission(c.save, m.id)
                            if (got > 0) { claimed = Reward.Coins(got); c.audio.play(Sfx.COIN) }
                        }, Modifier.width(96.dp), colors = if (m.progress >= m.target) Buttons.Green else Buttons.Grey, enabled = m.progress >= m.target, height = 42.dp, icon = R.drawable.ui_coin)
                    }
                }
                if (d.daily.missions.isEmpty()) BodyText("New missions arrive every day.")
            }
        }
    }
    claimed?.let { r ->
        GameDialog("You got!", onDismiss = { claimed = null }, banner = Palette.Lime, bannerDark = Palette.LimeDeep) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { UiIcon(rewardIcon(r), 90.dp) }
            OutlinedText(r.label, style = Type.Title, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            GameButton("Yay!", { claimed = null }, Modifier.fillMaxWidth(), colors = Buttons.Green)
        }
    }
    if (trialPick) {
        GameDialog("Time Trial", onDismiss = { trialPick = false }, banner = Palette.Ocean, bannerDark = Palette.OceanDeep) {
            BodyText("Pick a cleared level:", style = Type.BodySmall)
            Spacer(Modifier.height(8.dp))
            val maxL = (d.progress.highestUnlocked - 1).coerceAtLeast(1)
            val picks = listOf(maxL, maxL - 1, maxL - 2, maxL - 5, 10, 5, 1).filter { it in 1..maxL }.distinct().take(6)
            for (lv in picks) {
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x1A13304A)).pressable { trialPick = false; nav.startRace(RaceRequest(lv, PlayMode.TIME_TRIAL)) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedText("Level $lv", style = Type.Label, outlineWidth = 3.dp, modifier = Modifier.weight(1f), align = TextAlign.Start)
                    val m = d.progress.medals[lv] ?: 0
                    if (m > 0) UiIcon(listOf(R.drawable.ui_medal_bronze, R.drawable.ui_medal_silver, R.drawable.ui_medal_gold)[m - 1], 28.dp)
                    d.progress.bestTimesMs[lv]?.let { BodyText(" %.1fs".format(it / 1000f), style = Type.BodySmall) }
                }
            }
        }
    }
}

@Composable
private fun MedalCount(icon: Int, n: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp)) {
        UiIcon(icon, 32.dp); Spacer(Modifier.width(2.dp)); OutlinedText("$n", style = Type.Label, outlineWidth = 3.dp)
    }
}

@Composable
private fun LuckyWheel(onWin: (Reward) -> Unit) {
    val c = LocalContainer.current
    val d by c.save.state.collectAsState()
    val scope = rememberCoroutineScope()
    val rot = remember { Animatable(0f) }
    var spinning by remember { mutableStateOf(false) }
    val slots = GameRules.wheel
    val seg = 360f / slots.size
    fun go(viaAd: Boolean) {
        if (spinning) return
        val idx = Progression.spin(c.save, viaAd)
        if (idx < 0) return
        spinning = true
        scope.launch {
            val target = rot.value - (rot.value % 360f) + 360f * 5f + (360f - (idx * seg + seg / 2f))
            var lastTick = 0
            rot.animateTo(target, tween(3600, easing = CubicBezierEasing(0.15f, 0.8f, 0.2f, 1f))) {
                val tick = (value / seg).toInt()
                if (tick != lastTick) { lastTick = tick; c.audio.play(Sfx.WHEEL_TICK, 0.7f) }
            }
            c.audio.play(Sfx.WHEEL_WIN)
            spinning = false
            onWin(slots[idx].reward)
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.size(230.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(220.dp).rotate(rot.value)) {
                val r = size.minDimension / 2f
                for ((i, s) in slots.withIndex()) {
                    drawArc(Color(s.color), -90f + i * seg, seg, true)
                    drawArc(Palette.Ink, -90f + i * seg, seg, true, style = Stroke(4f))
                }
                drawCircle(Palette.Ink, r, style = Stroke(8f))
                for (i in slots.indices) {
                    val a = Math.toRadians((-90f + i * seg + seg / 2f).toDouble())
                    drawCircle(Color.White, 6f, Offset(center.x + cos(a).toFloat() * r * 0.92f, center.y + sin(a).toFloat() * r * 0.92f))
                }
            }
            // labels
            for ((i, s) in slots.withIndex()) {
                val a = Math.toRadians((-90f + i * seg + seg / 2f + rot.value).toDouble())
                Box(Modifier.size(230.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.padding(start = (115 + cos(a) * 70).toInt().coerceAtLeast(0).dp * 0, top = 0.dp)) {}
                }
                @Suppress("UNUSED_VARIABLE") val unused = s
            }
            Box(Modifier.size(64.dp).clip(CircleShape).background(Palette.Sun).border(4.dp, Palette.Ink, CircleShape).pressable(enabled = !spinning && !d.daily.wheelFreeUsed) { go(false) }, contentAlignment = Alignment.Center) {
                OutlinedText("SPIN", style = Type.Label, outlineWidth = 3.dp)
            }
            Canvas(Modifier.size(230.dp)) {
                val p = Path().apply { moveTo(size.width / 2f - 16f, 0f); lineTo(size.width / 2f + 16f, 0f); lineTo(size.width / 2f, 30f); close() }
                drawPath(p, Palette.Coral); drawPath(p, Palette.Ink, style = Stroke(4f))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            for (s in slots) UiIcon(rewardIcon(s.reward), 24.dp)
        }
        Spacer(Modifier.height(8.dp))
        if (!d.daily.wheelFreeUsed) GameButton("Free Spin!", { go(false) }, Modifier.fillMaxWidth(), colors = Buttons.Lagoon, enabled = !spinning, height = 52.dp)
        else if (AdConfig.mockAds && d.daily.wheelAdSpins < 3) GameButton("Spin Again", { c.ads.showRewarded("wheel") { ok -> if (ok) go(true) } }, Modifier.fillMaxWidth(), colors = Buttons.Purple, icon = R.drawable.ui_ad, enabled = !spinning, height = 52.dp, sub = "Watch a (debug) ad")
        else BodyText("Free spin again tomorrow!", align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
    @Suppress("UNUSED_VARIABLE") val u = Size.Zero
}
