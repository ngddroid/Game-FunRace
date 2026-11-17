package com.ngstudio.funrace.game.render

import android.opengl.GLES30
import com.ngstudio.funrace.core.Mat4
import com.ngstudio.funrace.core.PI_F
import com.ngstudio.funrace.core.Quat
import com.ngstudio.funrace.core.Rng
import com.ngstudio.funrace.core.TAU
import com.ngstudio.funrace.core.damp
import com.ngstudio.funrace.level.TimeOfDay
import com.ngstudio.funrace.level.World
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class MenuMode { HOME, SHOP, PROFILE, ONBOARD_RUN, ONBOARD_DODGE, ONBOARD_FLY, ONBOARD_STYLE, SPLASH }

/**
 * The lagoon diorama behind menus: the player's Dashling on a floating platform, with the
 * world's scenery around it. Also stages the four live onboarding scenes.
 */
class MenuScene(private val res: RenderResources, world: World, time: TimeOfDay) : Scene {
    var look: Look? = null
    var hatPreview: Look? = null
    var mode = MenuMode.HOME
    var danceId = "hop"
    var dragYaw = 0f
    private var yaw = 0f
    private var waveT = 0f
    private val rig = LightRig().also { it.configure(world, time) }
    private val cam = Camera()
    private val drawer = CharacterDrawer(res)
    private val pose = Pose()
    private val target = Pose()
    private val danceOut = FloatArray(3)
    private val particles = ParticleSystem(300)
    private var t = 0f
    private val scenery: Mesh
    private val shoreTex: Int
    private val shoreRect = floatArrayOf(-60f, -60f, 120f, 120f)
    private val q = FloatArray(4)
    private val m = FloatArray(16)
    private val world = world
    private val clouds = ArrayList<FloatArray>()
    private var camX = 0f; private var camY = 3f; private var camZ = 6f
    private var camTX = 0f; private var camTY = 1f; private var camTZ = 0f

    init {
        val md = MeshData(8192, 16384)
        fun place(name: String, x: Float, y: Float, z: Float, yaw: Float, s: Float, tA: Int = -1, tB: Int = -1) {
            Quat.euler(q, yaw, 0f, 0f)
            Mat4.trs(m, 0, x, y, z, q[0], q[1], q[2], q[3], s, s, s)
            md.append(ModelLibrary.get(name).mesh, m, tA, tB, 1f)
        }
        // floating stage
        place("turntable", 0f, 0.55f, 0f, 0f, 1f)
        Quat.euler(q, 0f, 0f, 0f)
        Mat4.trs(m, 0, 0f, 0.55f, 0f, q[0], q[1], q[2], q[3], 1.9f, 1f, 1.9f)
        md.append(ModelLibrary.get("turntable").mesh, m, world.hazA, world.hazB)
        val props = when (world) {
            World.CANDY -> listOf("candy_island" to 1f, "lollipop" to 1f, "gumdrop" to 1.4f, "donut_float" to 1f)
            World.JUNGLE -> listOf("island_b" to 1f, "jungle_tree" to 1f, "ruin_column" to 1f, "bush" to 1f)
            World.PIRATE -> listOf("island_a" to 1f, "ship" to 1f, "treasure_chest" to 1f, "dock" to 1f)
            World.FROSTY -> listOf("ice_floe" to 1.4f, "pine_snow" to 1f, "igloo" to 1f, "penguin_prop" to 1.4f)
            World.VOLCANO -> listOf("volcano" to 0.6f, "basalt" to 1f, "rock_a" to 1.6f, "basalt" to 0.8f)
            World.SKY -> listOf("cloud_island" to 1f, "hot_air_balloon" to 1f, "floating_rock" to 0.8f, "hot_air_balloon" to 0.7f)
            World.NEON -> listOf("ferris_wheel" to 1f, "neon_arch" to 1f, "lamp_post" to 1f, "buoy" to 1f)
            World.LAGOON -> listOf("island_a" to 1f, "flamingo_float" to 1f, "island_c" to 0.8f, "donut_float" to 1f)
        }
        place(props[0].first, -14f, 0f, -26f, 0.6f, props[0].second)
        place(props[1].first, 5.5f, 0f, -6f, 2.2f, props[1].second, world.hazA, world.hazB)
        place(props[2].first, 22f, 0f, -48f, 1.8f, props[2].second)
        place(props[3].first, -5f, 0f, -3.5f, 0.4f, props[3].second, world.hazA, world.hazB)
        place("buoy", 3.5f, 0f, 2.5f, 0f, 0.8f)
        place("palm", -9f, -0.6f, -16f, 0.3f, 1f)
        scenery = Mesh(md)
        val buf = java.nio.ByteBuffer.allocateDirect(64 * 64)
        for (j in 0 until 64) for (i in 0 until 64) {
            val x = shoreRect[0] + (i + 0.5f) / 64f * shoreRect[2]
            val z = shoreRect[1] + (j + 0.5f) / 64f * shoreRect[3]
            val prints = arrayOf(floatArrayOf(0f, 0f, 2.2f), floatArrayOf(-14f, -26f, 11f), floatArrayOf(5.5f, -6f, 1.4f), floatArrayOf(-5f, -3.5f, 1.3f), floatArrayOf(22f, -48f, 12f), floatArrayOf(3.5f, 2.5f, 0.5f))
            var d = 99f
            for (pp in prints) d = min(d, kotlin.math.sqrt((x - pp[0]) * (x - pp[0]) + (z - pp[1]) * (z - pp[1])) - pp[2])
            buf.put(((1f - max(0f, d) / 5f).coerceIn(0f, 1f) * 255).toInt().toByte())
        }
        buf.position(0)
        shoreTex = TextureUtil.r8(64, 64, buf)
        val rng = Rng(77)
        for (i in 0 until 12) {
            val a = rng.range(PI_F * 0.9f, PI_F * 2.1f)
            val r = rng.range(120f, 200f)
            clouds += floatArrayOf(cos(a) * r, rng.range(25f, 60f), sin(a) * r, rng.range(30f, 60f), rng.range(0.4f, 1f))
        }
    }

    fun wave() { waveT = 1.6f }

    override fun update(dt: Float) {
        t += dt
        res.time = t
        if (waveT > 0f) waveT -= dt
        yaw = damp(yaw, dragYaw, 8f, dt)
        if (mode != MenuMode.SHOP && mode != MenuMode.PROFILE) dragYaw = damp(dragYaw, 0f, 1.5f, dt)
        danceOut[0] = 0f; danceOut[1] = 0f; danceOut[2] = 0f
        when (mode) {
            MenuMode.ONBOARD_RUN -> Anim.run(target, t * 9f, 0.9f)
            MenuMode.ONBOARD_DODGE -> if ((t % 3f) < 1.6f) Anim.idle(target, t, 0f) else Anim.run(target, t * 9f, 1f)
            MenuMode.ONBOARD_FLY -> Anim.zip(target, t)
            MenuMode.ONBOARD_STYLE -> Anim.dance(target, "spin", t, danceOut)
            MenuMode.SPLASH -> Anim.dance(target, "cheer", t, danceOut)
            else -> if (waveT > 0f) Anim.wave(target, t) else if (mode == MenuMode.SHOP) Anim.dance(target, danceId, t, danceOut).also { if (danceId == "backflip" || danceId == "spin") { danceOut[1] = 0f; danceOut[2] = 0f } } else Anim.idle(target, t, 0f)
        }
        pose.blendTo(target, 0.25f)
        if (mode == MenuMode.ONBOARD_STYLE && particles.rng.chance(dt * 10f)) {
            val a = particles.rng.range(0f, TAU)
            particles.emit(cos(a) * 1.2f, 2.6f, sin(a) * 1.2f, 0f, -0.5f, 0f, 1.8f, 0.2f, 0.18f,
                intArrayOf(0xFFFF6B57.toInt(), 0xFFFFD23F.toInt(), 0xFF7BE36B.toInt(), 0xFF2E9BFF.toInt(), 0xFFFF5FA8.toInt())[particles.rng.nextInt(5)], ParticleAtlas.SQUARE, gravity = 1.5f, rotation = a, rotSpeed = 6f)
        }
        if (particles.rng.chance(dt * 1.5f)) particles.emit(particles.rng.range(-3f, 3f), 0.1f, particles.rng.range(-2f, 2f), 0f, 0.4f, 0f, 2.5f, 0.18f, 0.25f, 0x88FFFFFF.toInt(), ParticleAtlas.BUBBLE)
        particles.update(dt)
        // camera framing per mode (character sits in the upper half of the screen)
        val aspect = res.width.toFloat() / max(1, res.height)
        cam.aspect = aspect
        val (tx, ty, dist, h) = when (mode) {
            MenuMode.SHOP -> floatArrayOf(0f, -0.95f, 5.2f, 0.9f)
            MenuMode.PROFILE -> floatArrayOf(0f, -1.45f, 6.4f, 0.9f)
            MenuMode.SPLASH -> floatArrayOf(0f, -0.2f, 7.4f, 1.6f)
            MenuMode.HOME -> floatArrayOf(0f, -0.62f, 6.8f, 1.4f)
            else -> floatArrayOf(0f, -0.35f, 6.0f, 1.4f)
        }
        val camA = 0.35f + sin(t * 0.15f) * 0.06f
        camTX = damp(camTX, tx, 4f, dt); camTY = damp(camTY, ty + 1.2f, 4f, dt); camTZ = damp(camTZ, 0f, 4f, dt)
        camX = damp(camX, sin(camA) * dist, 3f, dt); camY = damp(camY, 1.2f + h, 3f, dt); camZ = damp(camZ, cos(camA) * dist, 3f, dt)
        cam.x = camX; cam.y = camY; cam.z = camZ
        cam.tx = camTX; cam.ty = camTY; cam.tz = camTZ
        cam.fovDeg = 46f
        cam.near = 0.2f; cam.far = 400f
        cam.update()
    }

    override fun render() {
        val lk = hatPreview ?: look ?: return
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_BLEND)
        res.meshes.clearBatches()
        val baseY = 0.62f + sin(t * 1.4f) * 0.03f
        val faceYaw = 0.35f + yaw + danceOut[1]
        drawer.setModel(0f, baseY + danceOut[0], 0f, faceYaw, danceOut[2], 0f, 1f, 1f, 1f)
        drawer.computeSkin(pose)
        drawer.queueParts(lk, if (mode == MenuMode.SPLASH || mode == MenuMode.ONBOARD_STYLE || waveT > 0f) Expression.JOY else Expression.HAPPY, blinkAt(), 1f)
        if (res.beginShadowPass()) {
            res.shadow.update(rig.sunDir[0], rig.sunDir[1], rig.sunDir[2], 0f, 0.6f, 0f, 4f)
            scenery.draw()
            for (b in res.meshes.allBatches()) b.draw()
            CharacterPass.beginSkinDepth(res)
            drawer.drawBodyDepth(res.skinDepth)
            res.endShadowPass()
        }
        GLES30.glViewport(0, 0, res.width, res.height)
        GLES30.glClearColor(rig.skyHorizon[0], rig.skyHorizon[1], rig.skyHorizon[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        res.common(res.lit, cam, rig, 0f)
        res.lit.f("uSwayAll", 1f)
        scenery.draw()
        res.lit.f("uSwayAll", 0f)
        for (b in res.meshes.allBatches()) b.draw()
        CharacterPass.beginSkinOutline(res, cam, rig, 0f, 0.014f)
        drawer.drawBody(res.skinOutline, lk)
        CharacterPass.endOutline()
        res.beginOutline(cam, rig, 0f, 0.012f)
        for (b in res.meshes.allBatches()) if (b.count > 0) b.draw()
        res.endOutline()
        CharacterPass.beginSkin(res, cam, rig, 0f)
        drawer.drawBody(res.skin, lk)
        res.drawWater(cam, rig, 0f, 0f, -10f, shoreTex, shoreRect)
        res.drawSky(cam, rig, 0f)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        res.sky3d.clear()
        for (c in clouds) res.sky3d.add(c[0] + (t * 0.6f) % 200f * c[4], c[1], c[2], c[3], if (rig.night > 0.5f) 0xFF9AA6C8.toInt() else 0xFFFFFFFF.toInt(), 0.9f, res.particleAtlas.rects[ParticleAtlas.CLOUD_BIG], 0f, 1.8f)
        res.bill.clear(); res.ground.clear()
        particles.fill(res.particleAtlas, res.bill, res.ground)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, res.particleAtlas.texture)
        res.beginBillboards(cam, false)
        res.sky3d.draw()
        res.bill.draw()
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun blinkAt(): Float { val k = (t % 3.3f); return if (k < 0.12f) 1f - k / 0.12f else 0f }

    override fun release() {
        scenery.release()
        TextureUtil.delete(shoreTex)
    }
}
