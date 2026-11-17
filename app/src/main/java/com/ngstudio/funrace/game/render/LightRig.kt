package com.ngstudio.funrace.game.render

import com.ngstudio.funrace.level.SeaStyle
import com.ngstudio.funrace.level.TimeOfDay
import com.ngstudio.funrace.level.World
import kotlin.math.sqrt

/** Lighting / atmosphere constants for one scene, derived from world + time of day. */
class LightRig {
    val sunDir = floatArrayOf(-0.45f, 0.8f, -0.4f)
    val sunColor = floatArrayOf(0.62f, 0.55f, 0.42f)
    val ambSky = floatArrayOf(0.62f, 0.72f, 0.9f)
    val ambGround = floatArrayOf(0.52f, 0.5f, 0.5f)
    val rim = floatArrayOf(0.55f, 0.7f, 0.95f)
    val fog = floatArrayOf(0.85f, 0.92f, 1f)
    var fogStart = 70f
    var fogEnd = 320f
    val skyTop = floatArrayOf(0.24f, 0.55f, 0.94f)
    val skyHorizon = floatArrayOf(0.75f, 0.9f, 1f)
    val skyGlow = floatArrayOf(1f, 0.85f, 0.6f)
    var night = 0f
    val seaDeep = floatArrayOf(0.04f, 0.5f, 0.66f)
    val seaShallow = floatArrayOf(0.18f, 0.88f, 0.82f)
    val foam = floatArrayOf(0.96f, 1f, 1f)
    val skyRefl = floatArrayOf(0.75f, 0.9f, 1f)
    val waterTint = floatArrayOf(0.05f, 0.45f, 0.55f)
    var seaStyle = 0f
    var caustics = 1f
    var waveAmp = 1f
    val outline = floatArrayOf(0.07f, 0.19f, 0.29f)
    var saturation = 1.1f
    var contrast = 1.04f
    var brightness = 1.0f
    var vignette = 0.3f
    val gradeTint = floatArrayOf(1f, 1f, 1f)
    var lanternGlow = 0f

    companion object {
        fun rgb(c: Int, out: FloatArray) {
            out[0] = ((c shr 16) and 0xFF) / 255f; out[1] = ((c shr 8) and 0xFF) / 255f; out[2] = (c and 0xFF) / 255f
        }
        private fun set(out: FloatArray, r: Float, g: Float, b: Float) { out[0] = r; out[1] = g; out[2] = b }
        private fun mix(out: FloatArray, a: FloatArray, b: FloatArray, t: Float) { for (i in 0..2) out[i] = a[i] + (b[i] - a[i]) * t }
    }

    fun configure(world: World, time: TimeOfDay) {
        val bTop = FloatArray(3).also { rgb(world.skyTop, it) }
        val bHor = FloatArray(3).also { rgb(world.skyHorizon, it) }
        val bFog = FloatArray(3).also { rgb(world.fog, it) }
        rgb(world.seaDeep, seaDeep)
        rgb(world.seaShallow, seaShallow)
        rgb(world.foam, foam)
        seaStyle = world.seaStyle.code
        caustics = if (world.seaStyle == SeaStyle.WATER || world.seaStyle == SeaStyle.SODA) 1f else 0f
        waveAmp = when (world.seaStyle) { SeaStyle.WATER -> 1f; SeaStyle.SODA -> 0.7f; SeaStyle.LAVA -> 0.25f; SeaStyle.CLOUDS -> 0.15f }
        night = 0f
        lanternGlow = 0f
        when (time) {
            TimeOfDay.NOON -> {
                setDir(-0.42f, 0.86f, -0.36f)
                set(sunColor, 0.66f, 0.6f, 0.48f)
                set(ambSky, 0.62f, 0.74f, 0.92f)
                set(ambGround, 0.55f, 0.55f, 0.52f)
                set(rim, 0.6f, 0.78f, 1.0f)
                for (i in 0..2) { skyTop[i] = bTop[i]; skyHorizon[i] = bHor[i]; fog[i] = bFog[i] }
                set(skyGlow, 1f, 0.92f, 0.75f)
                set(gradeTint, 1f, 1f, 1f)
                saturation = 1.12f; contrast = 1.04f; brightness = 1.0f
            }
            TimeOfDay.MORNING -> {
                setDir(0.62f, 0.5f, -0.3f)
                set(sunColor, 0.66f, 0.56f, 0.44f)
                set(ambSky, 0.6f, 0.68f, 0.9f)
                set(ambGround, 0.55f, 0.5f, 0.55f)
                set(rim, 1.0f, 0.82f, 0.7f)
                mix(skyTop, bTop, floatArrayOf(0.45f, 0.62f, 0.95f), 0.4f)
                mix(skyHorizon, bHor, floatArrayOf(1.0f, 0.86f, 0.78f), 0.45f)
                mix(fog, bFog, floatArrayOf(1.0f, 0.9f, 0.86f), 0.45f)
                set(skyGlow, 1f, 0.8f, 0.6f)
                set(gradeTint, 1.02f, 1.0f, 0.98f)
                saturation = 1.1f; contrast = 1.04f; brightness = 1.02f
            }
            TimeOfDay.SUNSET -> {
                setDir(-0.78f, 0.36f, -0.2f)
                set(sunColor, 0.78f, 0.5f, 0.3f)
                set(ambSky, 0.56f, 0.5f, 0.76f)
                set(ambGround, 0.55f, 0.42f, 0.48f)
                set(rim, 1.0f, 0.62f, 0.45f)
                mix(skyTop, bTop, floatArrayOf(0.3f, 0.3f, 0.66f), 0.6f)
                mix(skyHorizon, bHor, floatArrayOf(1.0f, 0.66f, 0.45f), 0.68f)
                mix(fog, bFog, floatArrayOf(0.98f, 0.72f, 0.6f), 0.6f)
                set(skyGlow, 1f, 0.55f, 0.3f)
                set(gradeTint, 1.04f, 0.98f, 0.95f)
                saturation = 1.14f; contrast = 1.06f; brightness = 1.0f
                lanternGlow = 0.5f
            }
            TimeOfDay.NIGHT -> {
                setDir(-0.3f, 0.78f, 0.25f)
                set(sunColor, 0.32f, 0.36f, 0.52f)
                set(ambSky, 0.36f, 0.42f, 0.72f)
                set(ambGround, 0.3f, 0.3f, 0.48f)
                set(rim, 0.5f, 0.9f, 1.0f)
                mix(skyTop, bTop, floatArrayOf(0.04f, 0.05f, 0.18f), 0.75f)
                mix(skyHorizon, bHor, floatArrayOf(0.16f, 0.18f, 0.42f), 0.7f)
                mix(fog, bFog, floatArrayOf(0.14f, 0.16f, 0.36f), 0.72f)
                set(skyGlow, 0.5f, 0.6f, 1f)
                for (i in 0..2) { seaDeep[i] *= 0.5f; seaShallow[i] *= 0.6f; foam[i] *= 0.8f }
                set(gradeTint, 0.97f, 1.0f, 1.06f)
                saturation = 1.12f; contrast = 1.08f; brightness = 1.08f
                night = 1f
                lanternGlow = 1f
            }
        }
        for (i in 0..2) skyRefl[i] = skyHorizon[i] * 0.65f + skyTop[i] * 0.35f
        for (i in 0..2) waterTint[i] = seaDeep[i] * 0.8f + seaShallow[i] * 0.2f
        set(outline, 0.07f + skyTop[0] * 0.04f, 0.17f + skyTop[1] * 0.04f, 0.27f + skyTop[2] * 0.04f)
    }

    private fun setDir(x: Float, y: Float, z: Float) {
        val l = sqrt(x * x + y * y + z * z)
        sunDir[0] = x / l; sunDir[1] = y / l; sunDir[2] = z / l
    }
}
