package com.ngstudio.funrace.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ngstudio.funrace.R
import com.ngstudio.funrace.audio.Sfx
import com.ngstudio.funrace.data.GameRules
import com.ngstudio.funrace.data.Progression
import com.ngstudio.funrace.data.Reward
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
import com.ngstudio.funrace.ui.theme.Palette
import com.ngstudio.funrace.ui.theme.Type

@Composable
fun ProfileTab() {
    val c = LocalContainer.current
    val d by c.save.state.collectAsState()
    var got by remember { mutableStateOf<Reward?>(null) }
    var gems by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(250.dp).pointerInput(Unit) { detectDragGestures { _, drag -> c.stage.drag(drag.x * 0.015f) } }, contentAlignment = Alignment.BottomCenter) {
            OutlinedText(d.profile.name, style = Type.Title, modifier = Modifier.padding(bottom = 4.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).navigationBarsPadding().padding(bottom = 110.dp)) {
            // stats
            Panel(Modifier.fillMaxWidth(), padding = 12.dp) {
                val s = d.stats
                val rows = listOf(
                    Triple(R.drawable.ui_flag, "Races", s.races.toString()), Triple(R.drawable.ui_crown, "Wins", s.wins.toString()),
                    Triple(R.drawable.ui_trophy, "Podiums", s.podiums.toString()), Triple(R.drawable.ui_turbo, "PERFECTs", s.perfects.toString()),
                    Triple(R.drawable.ui_wave, "Splashes", s.splashes.toString()), Triple(R.drawable.ui_star, "Stars", d.progress.totalStars().toString()),
                    Triple(R.drawable.ui_coin, "Coins earned", formatNum(s.coinsEarned.toInt())), Triple(R.drawable.ui_road, "Distance", "%.1f km".format(s.meters / 1000f)),
                )
                for (k in rows.indices step 2) {
                    Row(Modifier.fillMaxWidth()) {
                        for (j in 0..1) {
                            val (ic, label, v) = rows[k + j]
                            Row(Modifier.weight(1f).padding(4.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x1419C6C2)).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                UiIcon(ic, 30.dp)
                                Spacer(Modifier.width(6.dp))
                                Column { BodyText(label, style = Type.BodySmall, color = Palette.Muted); OutlinedText(v, style = Type.Label, outlineWidth = 3.dp) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            // trophy road
            Banner("Trophy Road", Modifier.fillMaxWidth(), color = Palette.Sun, dark = Palette.SunDeep)
            Panel(Modifier.fillMaxWidth(), padding = 12.dp) {
                val i = d.progress.trophyRoadClaimed
                val step = GameRules.trophyRoad.getOrNull(i)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UiIcon(R.drawable.ui_trophy, 44.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        OutlinedText("${d.progress.trophies} trophies", style = Type.Heading, outlineWidth = 3.dp)
                        if (step != null) {
                            val prev = GameRules.trophyRoad.getOrNull(i - 1)?.trophies ?: 0
                            GameProgressBar((d.progress.trophies - prev).toFloat() / (step.trophies - prev), Modifier.fillMaxWidth(), height = 14.dp)
                            BodyText("Next at ${step.trophies}: ${step.reward.label}", style = Type.BodySmall)
                        } else BodyText("Road complete! You're a legend.", style = Type.BodySmall)
                    }
                }
                if (step != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (k in i until minOf(i + 5, GameRules.trophyRoad.size)) {
                            val st = GameRules.trophyRoad[k]
                            val ready = d.progress.trophies >= st.trophies
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (ready) Palette.Lime.copy(alpha = 0.5f) else Color(0x1A13304A)).border(2.dp, Palette.Ink, RoundedCornerShape(12.dp)).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                UiIcon(rewardIcon(st.reward), 28.dp)
                                BodyText("${st.trophies}", style = Type.BodySmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val ready = d.progress.trophies >= step.trophies
                    GameButton(if (ready) "Claim ${step.reward.label}" else "Win races to earn trophies", {
                        Progression.claimRoad(c.save)?.let { got = it; c.audio.play(Sfx.CHEST) }
                    }, Modifier.fillMaxWidth(), colors = if (ready) Buttons.Sun else Buttons.Grey, enabled = ready, height = 50.dp)
                }
            }
            Spacer(Modifier.height(14.dp))
            // achievements
            Banner("Achievements", Modifier.fillMaxWidth(), color = Palette.Purple, dark = Palette.PurpleDeep)
            Panel(Modifier.fillMaxWidth(), padding = 12.dp) {
                for (a in GameRules.achievements) {
                    val tier = d.achievements[a.id] ?: 0
                    val done = tier >= a.tiers.size
                    val target = if (done) a.tiers.last() else a.tiers[tier]
                    val v = a.stat(d)
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(contentAlignment = Alignment.Center) {
                            UiIcon(if (done) R.drawable.ui_medal_gold else if (tier >= 2) R.drawable.ui_medal_silver else R.drawable.ui_medal_bronze, 38.dp, alpha = if (tier == 0) 0.45f else 1f)
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            BodyText(a.title + if (!done) "  ${tier + 1}/${a.tiers.size}" else "  ✓", style = Type.BodySmall)
                            GameProgressBar(v.toFloat() / target, Modifier.fillMaxWidth(), height = 10.dp)
                            BodyText("${formatNum(minOf(v, target).toInt())} / ${formatNum(target.toInt())} ${a.unit}", style = Type.BodySmall.copy(color = Palette.Muted))
                        }
                        if (!done) {
                            Spacer(Modifier.width(6.dp))
                            GameButton("${GameRules.achievementReward(tier)}", {
                                val g = Progression.claimAchievement(c.save, a.id)
                                if (g > 0) { gems = g; c.audio.play(Sfx.UNLOCK) }
                            }, Modifier.width(84.dp), colors = if (v >= target) Buttons.Purple else Buttons.Grey, enabled = v >= target, height = 40.dp, icon = R.drawable.ui_gem)
                        }
                    }
                }
            }
        }
    }
    got?.let { r ->
        GameDialog("Trophy Road!", onDismiss = { got = null }, banner = Palette.Sun, bannerDark = Palette.SunDeep) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { UiIcon(rewardIcon(r), 90.dp) }
            OutlinedText(r.label, style = Type.Title, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            GameButton("Sweet!", { got = null }, Modifier.fillMaxWidth(), colors = Buttons.Green)
        }
    }
    if (gems > 0) GameDialog("Achievement!", onDismiss = { gems = 0 }, banner = Palette.Purple, bannerDark = Palette.PurpleDeep) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { UiIcon(R.drawable.ui_gem, 80.dp) }
        OutlinedText("+$gems Gems", style = Type.Title, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        GameButton("Nice!", { gems = 0 }, Modifier.fillMaxWidth(), colors = Buttons.Purple)
    }
    @Suppress("UNUSED_VARIABLE") val u = TextAlign.Center; @Suppress("UNUSED_VARIABLE") val u2 = Modifier.size(1.dp)
}
