package com.ngstudio.funrace.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ngstudio.funrace.R

/** Tropical lagoon palette shared by every screen. */
object Palette {
    val Ink = Color(0xFF13304A)
    val DeepSea = Color(0xFF0B2740)
    val Ocean = Color(0xFF2E9BFF)
    val OceanDeep = Color(0xFF1B6FD1)
    val Lagoon = Color(0xFF19C6C2)
    val LagoonDeep = Color(0xFF0E8F9E)
    val Coral = Color(0xFFFF6B57)
    val CoralDeep = Color(0xFFE04A3A)
    val Sun = Color(0xFFFFD23F)
    val SunDeep = Color(0xFFF2A90F)
    val Lime = Color(0xFF7BE36B)
    val LimeDeep = Color(0xFF34B54A)
    val Pink = Color(0xFFFF5FA8)
    val PinkDeep = Color(0xFFD93A86)
    val Purple = Color(0xFF9B6BFF)
    val PurpleDeep = Color(0xFF6B3FD9)
    val Sand = Color(0xFFFFE9B8)
    val Foam = Color(0xFFF6FFFE)
    val Panel = Color(0xFFF4FFFE)
    val PanelEdge = Color(0xFFBFEDEA)
    val Muted = Color(0xFF6E8AA0)
    val Scrim = Color(0xB30B2740)

    val bgGradient = Brush.verticalGradient(listOf(Color(0xFF2FD3D0), Color(0xFF1FA8D8), Color(0xFF1B6FD1)))
    val deepGradient = Brush.verticalGradient(listOf(Color(0xFF1B6FD1), Color(0xFF13457E), Color(0xFF0B2740)))
    val panelGradient = Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFE6FBF8)))
    val sunGradient = Brush.verticalGradient(listOf(Color(0xFFFFE27A), Color(0xFFFFC62E)))
}

@OptIn(ExperimentalTextApi::class)
object Fonts {
    val Display = FontFamily(Font(R.font.lilita_one))
    val Body = FontFamily(
        Font(R.font.nunito, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(R.font.nunito, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
        Font(R.font.nunito, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
        Font(R.font.nunito, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
        Font(R.font.nunito, FontWeight.Black, variationSettings = FontVariation.Settings(FontVariation.weight(900))),
    )
}

object Type {
    val Huge = TextStyle(fontFamily = Fonts.Display, fontSize = 64.sp, letterSpacing = 1.sp)
    val Title = TextStyle(fontFamily = Fonts.Display, fontSize = 30.sp, letterSpacing = 0.5.sp)
    val Heading = TextStyle(fontFamily = Fonts.Display, fontSize = 22.sp, letterSpacing = 0.4.sp)
    val Button = TextStyle(fontFamily = Fonts.Display, fontSize = 22.sp, letterSpacing = 0.6.sp)
    val Label = TextStyle(fontFamily = Fonts.Display, fontSize = 15.sp, letterSpacing = 0.4.sp)
    val Small = TextStyle(fontFamily = Fonts.Display, fontSize = 12.sp, letterSpacing = 0.3.sp)
    val Body = TextStyle(fontFamily = Fonts.Body, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 21.sp)
    val BodySmall = TextStyle(fontFamily = Fonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp)
}
