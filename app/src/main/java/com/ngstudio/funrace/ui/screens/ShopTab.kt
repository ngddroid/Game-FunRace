package com.ngstudio.funrace.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ngstudio.funrace.R
import com.ngstudio.funrace.audio.Sfx
import com.ngstudio.funrace.data.Catalog
import com.ngstudio.funrace.data.Currency
import com.ngstudio.funrace.data.Progression
import com.ngstudio.funrace.data.Rarity
import com.ngstudio.funrace.game.render.MenuMode
import com.ngstudio.funrace.ui.LocalNav
import com.ngstudio.funrace.ui.components.BodyText
import com.ngstudio.funrace.ui.components.Buttons
import com.ngstudio.funrace.ui.components.GameButton
import com.ngstudio.funrace.ui.components.LocalContainer
import com.ngstudio.funrace.ui.components.OutlinedText
import com.ngstudio.funrace.ui.components.Panel
import com.ngstudio.funrace.ui.components.UiIcon
import com.ngstudio.funrace.ui.components.formatNum
import com.ngstudio.funrace.ui.components.pressable
import com.ngstudio.funrace.ui.theme.Palette
import com.ngstudio.funrace.ui.theme.Type

private enum class ShopCat(val label: String, val icon: Int) { CHARS("Racers", R.drawable.ui_character), HATS("Hats", R.drawable.ui_hat), TRAILS("Trails", R.drawable.ui_trail), DANCES("Dances", R.drawable.ui_dance) }

private class ShopItem(val key: String, val name: String, val rarity: Rarity, val price: Int, val currency: Currency, val color: Int, val color2: Int, val icon: Int)

@Composable
fun ShopTab() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val d by c.save.state.collectAsState()
    var cat by remember { mutableStateOf(ShopCat.CHARS) }
    var sel by remember { mutableStateOf<String?>(null) }
    val items = remember(cat) {
        when (cat) {
            ShopCat.CHARS -> Catalog.characters.map { ShopItem(it.id, it.name, it.rarity, it.price, it.currency, it.primary, it.secondary, 0) }
            ShopCat.HATS -> Catalog.hats.map { ShopItem("hat:" + it.id, it.name, it.rarity, it.price, it.currency, it.color, it.color2, R.drawable.ui_hat) }
            ShopCat.TRAILS -> Catalog.trails.map { ShopItem("trail:" + it.id, it.name, it.rarity, it.price, it.currency, it.colors.firstOrNull() ?: 0xFFFFFF, it.colors.lastOrNull() ?: 0xFFFFFF, R.drawable.ui_trail) }
            ShopCat.DANCES -> Catalog.dances.map { ShopItem("dance:" + it.id, it.name, it.rarity, it.price, it.currency, 0x9B6BFF, 0xFF5FA8, R.drawable.ui_dance) }
        }
    }
    val selected = items.firstOrNull { it.key == sel } ?: items.firstOrNull { isEquipped(d.profile, it.key) } ?: items.first()
    LaunchedEffect(selected.key) {
        when {
            selected.key.startsWith("hat:") -> c.stage.showMenu(MenuMode.SHOP, hatId = selected.key.removePrefix("hat:"))
            selected.key.startsWith("dance:") -> c.stage.showMenu(MenuMode.SHOP, dance = selected.key.removePrefix("dance:"))
            selected.key.startsWith("trail:") -> c.stage.showMenu(MenuMode.SHOP)
            else -> c.stage.showMenu(MenuMode.SHOP, characterId = selected.key)
        }
    }
    Column(Modifier.fillMaxSize()) {
        // 3D preview area (drag to rotate)
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.4f).pointerInput(Unit) { detectDragGestures { _, drag -> c.stage.drag(drag.x * 0.015f) } }, contentAlignment = Alignment.BottomCenter) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 4.dp)) {
                RarityTag(selected.rarity)
                OutlinedText(selected.name, style = Type.Title)
            }
        }
        Panel(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 10.dp).padding(bottom = 96.dp).navigationBarsPadding(), padding = 10.dp, radius = 24.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (k in ShopCat.entries) {
                    val on = k == cat
                    Row(
                        Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(14.dp)).background(if (on) Palette.Pink else Color(0x2213304A))
                            .border(if (on) 3.dp else 2.dp, if (on) Palette.Ink else Color(0x3313304A), RoundedCornerShape(14.dp)).pressable { cat = k; sel = null },
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                    ) {
                        UiIcon(k.icon, 22.dp); Spacer(Modifier.width(2.dp))
                        OutlinedText(k.label, style = Type.Small.copy(fontSize = 11.sp), outlineWidth = 2.dp, color = if (on) Color.White else Palette.Ink.copy(alpha = 0f).let { Color.White })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyVerticalGrid(GridCells.Fixed(4), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                items(items, key = { it.key }) { it ->
                    ItemCard(it, owned = it.key in d.collection.owned, equipped = isEquipped(d.profile, it.key), selected = it.key == selected.key, isNew = it.key in d.collection.unseen) {
                        sel = it.key; c.audio.play(Sfx.POP, 0.6f)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            val owned = selected.key in d.collection.owned
            val equipped = isEquipped(d.profile, selected.key)
            when {
                equipped -> GameButton("Equipped", {}, Modifier.fillMaxWidth(), colors = Buttons.Grey, height = 52.dp, icon = R.drawable.ui_check)
                owned -> GameButton("Equip", { Progression.equip(c.save, selected.key); c.audio.play(Sfx.UNLOCK) }, Modifier.fillMaxWidth(), colors = Buttons.Lagoon, height = 52.dp)
                selected.currency == Currency.REWARD -> GameButton("Reward only", {}, Modifier.fillMaxWidth(), colors = Buttons.Grey, height = 52.dp, icon = R.drawable.ui_lock)
                else -> {
                    val gems = selected.currency == Currency.GEMS
                    val afford = if (gems) d.wallet.gems >= selected.price else d.wallet.coins >= selected.price
                    GameButton(formatNum(selected.price), {
                        if (Progression.buy(c.save, selected.key, selected.price, gems)) {
                            Progression.equip(c.save, selected.key); c.audio.play(Sfx.UNLOCK); c.audio.play(Sfx.CHEER, 0.4f)
                        } else nav.showToast(if (gems) "Not enough gems" else "Not enough coins")
                    }, Modifier.fillMaxWidth(), colors = if (afford) Buttons.Sun else Buttons.Grey, height = 52.dp, icon = if (gems) R.drawable.ui_gem else R.drawable.ui_coin)
                }
            }
        }
    }
}

private fun isEquipped(p: com.ngstudio.funrace.data.Profile, key: String) = when {
    key.startsWith("hat:") -> p.hat == key.removePrefix("hat:")
    key.startsWith("trail:") -> p.trail == key.removePrefix("trail:")
    key.startsWith("dance:") -> p.dance == key.removePrefix("dance:")
    else -> p.character == key
}

@Composable
private fun RarityTag(r: Rarity) {
    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(r.color)).border(2.dp, Palette.Ink, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 1.dp)) {
        OutlinedText(r.label.uppercase(), style = Type.Small, outlineWidth = 2.dp)
    }
}

@Composable
private fun ItemCard(item: ShopItem, owned: Boolean, equipped: Boolean, selected: Boolean, isNew: Boolean, onClick: () -> Unit) {
    val rc = Color(item.rarity.color)
    Box(
        Modifier.aspectRatio(0.82f).clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(Color.White, rc.copy(alpha = 0.35f))))
            .border(if (selected) 4.dp else 2.5.dp, if (selected) Palette.Pink else Palette.Ink, RoundedCornerShape(16.dp)).pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (item.icon == 0) Avatar(item.key, 46.dp)
            else Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF000000.toInt() or item.color), Color(0xFF000000.toInt() or item.color2)))).border(2.dp, Palette.Ink, CircleShape))
                UiIcon(item.icon, 34.dp)
            }
            Spacer(Modifier.height(2.dp))
            BodyText(item.name, style = Type.BodySmall.copy(fontSize = 10.sp), align = TextAlign.Center, maxLines = 1)
            if (!owned) Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.currency != Currency.REWARD) {
                    UiIcon(if (item.currency == Currency.GEMS) R.drawable.ui_gem else R.drawable.ui_coin, 14.dp)
                    BodyText(formatNum(item.price), style = Type.BodySmall.copy(fontSize = 10.sp))
                } else UiIcon(R.drawable.ui_lock, 16.dp)
            }
        }
        if (equipped) Box(Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp)) { UiIcon(R.drawable.ui_check, 22.dp) }
        if (isNew) Box(Modifier.align(Alignment.TopStart).offset(x = (-2).dp).clip(RoundedCornerShape(6.dp)).background(Palette.Coral).padding(horizontal = 4.dp)) { OutlinedText("NEW", style = Type.Small.copy(fontSize = 9.sp), outlineWidth = 2.dp) }
    }
}

@Suppress("unused")
private val keepImports = listOf(Modifier.offset(0.dp))
