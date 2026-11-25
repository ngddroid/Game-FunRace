package com.ngstudio.funrace.game.render

import android.content.Context
import android.opengl.GLES30

enum class QualityTier { LOW, MEDIUM, HIGH }

/** Everything GL that lives as long as the EGL context: programs, atlases, shared meshes. */
class RenderResources(val context: Context, val tier: QualityTier) {
    val soft = tier == QualityTier.HIGH
    val lit = ShaderProgram("lit", Shaders.litVs(false), Shaders.litFs(false, soft))
    /** Same as [lit] but dither-fades geometry close to the camera (dynamic hazards). */
    val litFade = ShaderProgram("litFade", Shaders.litVs(false), Shaders.litFs(false, soft, fade = true))
    val litOutline = ShaderProgram("litOutline", Shaders.litVs(true), Shaders.litFs(true, false))
    val skin = ShaderProgram("skin", Shaders.skinVs(false), Shaders.skinFs(false, soft))
    val skinOutline = ShaderProgram("skinOutline", Shaders.skinVs(true), Shaders.skinFs(true, false))
    val depth = ShaderProgram("depth", Shaders.depthVs(), Shaders.depthFs())
    val skinDepth = ShaderProgram("skinDepth", Shaders.skinDepthVs(), Shaders.depthFs())
    val decal = ShaderProgram("decal", Shaders.decalVs(), Shaders.decalFs())
    val water = ShaderProgram("water", Shaders.waterVs(), Shaders.waterFs(soft))
    val sky = ShaderProgram("sky", Shaders.skyVs(), Shaders.skyFs())
    val bb = ShaderProgram("billboard", Shaders.bbVs(), Shaders.bbFs())

    val shadowEnabled = tier != QualityTier.LOW
    val shadow = ShadowMap(when (tier) { QualityTier.HIGH -> 2048; QualityTier.MEDIUM -> 1024; QualityTier.LOW -> 16 })

    val particleAtlas = ParticleAtlas()
    val iconAtlas = IconAtlas(context)
    val meshes = MeshCache()

    val bill = BillboardBatch(768)
    val billAdd = BillboardBatch(256)
    val ground = BillboardBatch(128)
    val sky3d = BillboardBatch(48)
    val iconBill = BillboardBatch(32)
    val labelBill = BillboardBatch(32)

    /** Inner wave grid follows the camera focus; spacing is constant so vertices never slide. */
    val waterSpacing = if (tier == QualityTier.LOW) 2.4f else 1.6f
    val waterCells = if (tier == QualityTier.LOW) 70 else 110
    val waterGrid: Mesh
    val waterOuter: Mesh

    private val skyVao: Int
    val identity = FloatArray(16).also { com.ngstudio.funrace.core.Mat4.identity(it) }

    init {
        skyVao = Gl.genVao()
        val vbo = Gl.genBuffer()
        GLES30.glBindVertexArray(skyVao)
        val b = Gl.floatBuffer(9)
        b.put(floatArrayOf(-1f, -1f, 0f, 3f, -1f, 0f, -1f, 3f, 0f)).position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 36, b, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        GLES30.glBindVertexArray(0)

        val n = waterCells
        val half = n * waterSpacing / 2f
        val g = MeshData((n + 1) * (n + 1), n * n * 6)
        for (j in 0..n) for (i in 0..n) g.v(-half + i * waterSpacing, 0f, -half + j * waterSpacing, 0f, 1f, 0f, 0xFFFFFF)
        for (j in 0 until n) for (i in 0 until n) {
            val a = j * (n + 1) + i
            g.tri(a, a + n + 1, a + 1); g.tri(a + 1, a + n + 1, a + n + 2)
        }
        waterGrid = Mesh(g)
        // outer skirt: flat ring from the grid edge to the horizon (waves faded to zero there)
        val o = MeshData(16, 48)
        val far = 2200f
        val rings = floatArrayOf(half - 0.01f, far)
        val corners = arrayOf(floatArrayOf(-1f, -1f), floatArrayOf(1f, -1f), floatArrayOf(1f, 1f), floatArrayOf(-1f, 1f))
        for (r in rings) for (c in corners) o.v(c[0] * r, 0f, c[1] * r, 0f, 1f, 0f, 0xFFFFFF)
        for (k in 0 until 4) {
            val a = k; val b2 = (k + 1) % 4
            o.quad(a, b2, b2 + 4, a + 4)
        }
        waterOuter = Mesh(o)
    }

    val waterHalf: Float get() = waterCells * waterSpacing / 2f

    var time = 0f
    var width = 1
    var height = 1

    /** Uploads lighting / camera / grading uniforms shared by the lit family of shaders. */
    fun common(p: ShaderProgram, cam: Camera, rig: LightRig, waterY: Float, viewProj: FloatArray = cam.viewProj) {
        p.use()
        p.mat4("uViewProj", viewProj)
        p.mat4("uLightVP", shadow.lightViewProj)
        p.f("uTime", time)
        p.f("uTimeF", time)
        p.vec3("uCamPos", cam.x, cam.y, cam.z)
        p.vec3("uCamPosF", cam.x, cam.y, cam.z)
        p.vec3("uSunDir", rig.sunDir)
        p.vec3("uSunColor", rig.sunColor)
        p.vec3("uAmbSky", rig.ambSky)
        p.vec3("uAmbGround", rig.ambGround)
        p.vec3("uRim", rig.rim)
        p.vec3("uFogColor", rig.fog)
        p.vec2("uFog", rig.fogStart, rig.fogEnd)
        p.vec4("uGrade", rig.saturation, rig.contrast, rig.brightness, rig.vignette)
        p.vec3("uGradeTint", rig.gradeTint)
        p.vec2("uScreen", width.toFloat(), height.toFloat())
        p.f("uWaterY", waterY)
        p.vec3("uWaterTint", rig.waterTint)
        p.f("uCaustics", if (tier == QualityTier.LOW) 0f else rig.caustics)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadow.texture)
        p.i("uShadow", 1)
        p.f("uShadowOn", if (shadowEnabled) 1f else 0f)
        p.f("uShadowTexel", 1f / shadow.size)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    fun drawSky(cam: Camera, rig: LightRig, waterY: Float) {
        common(sky, cam, rig, waterY)
        sky.mat4("uInvViewProj", cam.invViewProj)
        sky.vec3("uSkyTop", rig.skyTop)
        sky.vec3("uSkyHorizon", rig.skyHorizon)
        sky.vec3("uSkyGlow", rig.skyGlow)
        sky.f("uNight", rig.night)
        GLES30.glDepthMask(false)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glBindVertexArray(skyVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glDepthMask(true)
        GLES30.glDepthFunc(GLES30.GL_LESS)
    }

    /**
     * Draws the sea. [shoreTex] is an R8 distance mask over [shoreRect] (x0, z0, w, h).
     * The wave grid is centred on (fx, fz) snapped to the grid spacing.
     */
    fun drawWater(cam: Camera, rig: LightRig, waterY: Float, fx: Float, fz: Float, shoreTex: Int, shoreRect: FloatArray) {
        common(water, cam, rig, waterY)
        val sx = kotlin.math.floor(fx / waterSpacing) * waterSpacing
        val sz = kotlin.math.floor(fz / waterSpacing) * waterSpacing
        water.vec3("uOrigin", sx, waterY, sz)
        water.vec4("uWaveA", 1f, 0.35f, 0.11f, 14f)
        water.vec4("uWaveB", -0.4f, 1f, 0.08f, 9f)
        water.vec4("uWaveC", 0.7f, -0.6f, 0.055f, 5.5f)
        water.f("uWaveAmp", rig.waveAmp)
        water.f("uGridHalf", waterHalf)
        water.vec3("uDeep", rig.seaDeep)
        water.vec3("uShallow", rig.seaShallow)
        water.vec3("uFoam", rig.foam)
        water.vec3("uSkyRefl", rig.skyRefl)
        water.f("uSeaStyle", rig.seaStyle)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shoreTex)
        water.i("uShore", 2)
        water.vec4("uShoreRect", shoreRect[0], shoreRect[1], shoreRect[2], shoreRect[3])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        waterGrid.draw()
        waterOuter.draw()
    }

    fun beginOutline(cam: Camera, rig: LightRig, waterY: Float, width: Float = 0.022f) {
        common(litOutline, cam, rig, waterY)
        litOutline.f("uOutline", width)
        litOutline.vec3("uOutlineColor", rig.outline)
        GLES30.glCullFace(GLES30.GL_FRONT)
    }

    fun endOutline() { GLES30.glCullFace(GLES30.GL_BACK) }

    fun beginShadowPass(): Boolean {
        if (!shadowEnabled || !shadow.ok) return false
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        shadow.begin()
        depth.use()
        depth.mat4("uViewProj", shadow.lightViewProj)
        depth.mat4("uLightVP", shadow.lightViewProj)
        depth.f("uTime", time)
        depth.f("uSwayAll", 0f)
        GLES30.glCullFace(GLES30.GL_BACK)
        return true
    }

    fun endShadowPass() { shadow.end(width, height) }

    fun beginBillboards(cam: Camera, ground: Boolean) {
        bb.use()
        bb.mat4("uViewProj", cam.viewProj)
        bb.vec3("uRight", cam.right[0], cam.right[1], cam.right[2])
        bb.vec3("uUp", cam.up[0], cam.up[1], cam.up[2])
        bb.f("uGround", if (ground) 1f else 0f)
        bb.i("uTex", 0)
    }

    fun release() {
        shadow.release()
        meshes.release()
        waterGrid.release(); waterOuter.release()
        TextureUtil.delete(particleAtlas.texture)
        TextureUtil.delete(iconAtlas.texture)
        for (p in listOf(lit, litFade, litOutline, skin, skinOutline, depth, skinDepth, decal, water, sky, bb)) p.delete()
    }
}
