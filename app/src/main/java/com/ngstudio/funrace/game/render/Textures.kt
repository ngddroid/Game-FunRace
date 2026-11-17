package com.ngstudio.funrace.game.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils
import androidx.core.content.res.ResourcesCompat
import com.ngstudio.funrace.R
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.sin

object TextureUtil {
    fun fromBitmap(bmp: Bitmap, mipmap: Boolean = true, repeat: Boolean = false): Int {
        val id = Gl.genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, if (mipmap) GLES30.GL_LINEAR_MIPMAP_LINEAR else GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        val wrap = if (repeat) GLES30.GL_REPEAT else GLES30.GL_CLAMP_TO_EDGE
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrap)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrap)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        if (mipmap) GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        return id
    }

    /** Single-channel texture (R8), linear filtered, clamped. */
    fun r8(width: Int, height: Int, data: ByteBuffer?): Int {
        val id = Gl.genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, width, height, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, data)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return id
    }

    fun delete(id: Int) { if (id != 0) GLES30.glDeleteTextures(1, intArrayOf(id), 0) }
}

/** UV rectangle inside an atlas (u0,v0 top-left; u1,v1 bottom-right) plus aspect ratio (w/h). */
class UvRect(val u0: Float, val v0: Float, val u1: Float, val v1: Float, val aspect: Float = 1f)

/** Procedurally drawn particle sprites (white shapes, tinted per particle). 8x4 cells. */
class ParticleAtlas {
    val texture: Int
    private val cols = 8
    private val rows = 4
    val rects: Array<UvRect>

    companion object {
        const val SOFT = 0; const val STAR = 1; const val HEART = 2; const val FLAKE = 3
        const val SPARKLE = 4; const val NOTE = 5; const val RING = 6; const val PUFF = 7
        const val SQUARE = 8; const val STREAK = 9; const val CLOUD = 10; const val BLOB = 11
        const val DROP = 12; const val GLOW = 13; const val ARROW = 14; const val CIRCLE = 15
        const val BUBBLE = 16; const val PETAL = 17; const val LEAF = 18; const val FLAME = 19
        const val COIN = 20; const val SWIRL = 21; const val FOAM = 22; const val BOLT = 23
        const val CLOUD_BIG = 24; const val SPEED = 25; const val SPLAT = 26; const val CHEVRON = 27
    }

    init {
        val c = 128
        val bmp = Bitmap.createBitmap(c * cols, c * rows, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun cx(i: Int) = (i % cols) * c + c / 2f
        fun cy(i: Int) = (i / cols) * c + c / 2f
        fun radial(i: Int, r: Float, colors: IntArray, stops: FloatArray? = null, dx: Float = 0f, dy: Float = 0f) {
            p.shader = RadialGradient(cx(i) + dx, cy(i) + dy, r, colors, stops, Shader.TileMode.CLAMP)
            cv.drawCircle(cx(i) + dx, cy(i) + dy, r, p)
            p.shader = null
        }
        val W = Color.WHITE
        val T = Color.TRANSPARENT
        radial(SOFT, c * 0.48f, intArrayOf(W, T))
        p.color = W
        cv.drawPath(starPath(cx(STAR), cy(STAR), c * 0.44f, c * 0.2f, 5), p)
        cv.drawPath(heartPath(cx(HEART), cy(HEART), c * 0.4f), p)
        p.strokeWidth = c * 0.06f; p.strokeCap = Paint.Cap.ROUND; p.style = Paint.Style.STROKE
        for (k in 0 until 6) {
            val a = k * Math.PI / 3
            val ex = cx(FLAKE) + cos(a).toFloat() * c * 0.4f; val ey = cy(FLAKE) + sin(a).toFloat() * c * 0.4f
            cv.drawLine(cx(FLAKE), cy(FLAKE), ex, ey, p)
            val mx = cx(FLAKE) + cos(a).toFloat() * c * 0.24f; val my = cy(FLAKE) + sin(a).toFloat() * c * 0.24f
            for (s in listOf(-0.6, 0.6)) cv.drawLine(mx, my, mx + cos(a + s).toFloat() * c * 0.12f, my + sin(a + s).toFloat() * c * 0.12f, p)
        }
        p.style = Paint.Style.FILL
        radial(SPARKLE, c * 0.3f, intArrayOf(Color.argb(160, 255, 255, 255), T))
        p.color = W
        cv.drawPath(starPath(cx(SPARKLE), cy(SPARKLE), c * 0.46f, c * 0.07f, 4), p)
        cv.drawOval(cx(NOTE) - c * 0.26f, cy(NOTE) + c * 0.08f, cx(NOTE) + c * 0.02f, cy(NOTE) + c * 0.3f, p)
        cv.drawRect(cx(NOTE) - c * 0.03f, cy(NOTE) - c * 0.36f, cx(NOTE) + c * 0.03f, cy(NOTE) + c * 0.2f, p)
        cv.drawRect(cx(NOTE) - c * 0.03f, cy(NOTE) - c * 0.36f, cx(NOTE) + c * 0.22f, cy(NOTE) - c * 0.26f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = c * 0.07f
        cv.drawCircle(cx(RING), cy(RING), c * 0.38f, p)
        p.style = Paint.Style.FILL
        for ((dx, dy, r) in listOf(Triple(-0.14f, 0.06f, 0.24f), Triple(0.13f, 0.05f, 0.25f), Triple(0f, -0.1f, 0.27f), Triple(0f, 0.12f, 0.22f))) {
            radial(PUFF, r * c, intArrayOf(Color.argb(235, 255, 255, 255), Color.argb(0, 255, 255, 255)), floatArrayOf(0.55f, 1f), dx * c, dy * c)
        }
        p.color = W
        cv.drawRoundRect(cx(SQUARE) - c * 0.3f, cy(SQUARE) - c * 0.2f, cx(SQUARE) + c * 0.3f, cy(SQUARE) + c * 0.2f, c * 0.06f, c * 0.06f, p)
        p.shader = LinearGradient(cx(STREAK) - c * 0.45f, 0f, cx(STREAK) + c * 0.45f, 0f, intArrayOf(T, W, T), null, Shader.TileMode.CLAMP)
        cv.drawRoundRect(cx(STREAK) - c * 0.45f, cy(STREAK) - c * 0.05f, cx(STREAK) + c * 0.45f, cy(STREAK) + c * 0.05f, c * 0.05f, c * 0.05f, p)
        p.shader = null
        for ((dx, dy, r) in listOf(Triple(-0.2f, 0.08f, 0.2f), Triple(0.18f, 0.08f, 0.22f), Triple(-0.02f, -0.04f, 0.27f), Triple(0.05f, 0.12f, 0.2f))) {
            radial(CLOUD, r * c, intArrayOf(W, Color.argb(0, 255, 255, 255)), floatArrayOf(0.65f, 1f), dx * c, dy * c)
        }
        radial(BLOB, c * 0.48f, intArrayOf(W, Color.argb(160, 255, 255, 255), Color.argb(0, 255, 255, 255)), floatArrayOf(0f, 0.5f, 1f))
        p.color = W
        val drop = Path()
        drop.moveTo(cx(DROP), cy(DROP) - c * 0.4f)
        drop.quadTo(cx(DROP) + c * 0.3f, cy(DROP) + c * 0.05f, cx(DROP), cy(DROP) + c * 0.32f)
        drop.quadTo(cx(DROP) - c * 0.3f, cy(DROP) + c * 0.05f, cx(DROP), cy(DROP) - c * 0.4f)
        cv.drawPath(drop, p)
        radial(GLOW, c * 0.5f, intArrayOf(W, Color.argb(90, 255, 255, 255), T), floatArrayOf(0f, 0.35f, 1f))
        p.color = W
        val ar = Path()
        ar.moveTo(cx(ARROW) - c * 0.3f, cy(ARROW) - c * 0.2f); ar.lineTo(cx(ARROW) + c * 0.3f, cy(ARROW) - c * 0.2f); ar.lineTo(cx(ARROW), cy(ARROW) + c * 0.3f); ar.close()
        cv.drawPath(ar, p)
        cv.drawCircle(cx(CIRCLE), cy(CIRCLE), c * 0.44f, p)
        // BUBBLE: rim + highlight
        radial(BUBBLE, c * 0.44f, intArrayOf(Color.argb(30, 255, 255, 255), Color.argb(60, 255, 255, 255), Color.argb(230, 255, 255, 255), T), floatArrayOf(0f, 0.7f, 0.92f, 1f))
        p.color = Color.argb(230, 255, 255, 255)
        cv.drawOval(cx(BUBBLE) - c * 0.24f, cy(BUBBLE) - c * 0.26f, cx(BUBBLE) - c * 0.06f, cy(BUBBLE) - c * 0.14f, p)
        // PETAL
        p.color = W
        val pet = Path()
        pet.moveTo(cx(PETAL), cy(PETAL) - c * 0.4f)
        pet.cubicTo(cx(PETAL) + c * 0.38f, cy(PETAL) - c * 0.2f, cx(PETAL) + c * 0.2f, cy(PETAL) + c * 0.35f, cx(PETAL), cy(PETAL) + c * 0.4f)
        pet.cubicTo(cx(PETAL) - c * 0.2f, cy(PETAL) + c * 0.35f, cx(PETAL) - c * 0.38f, cy(PETAL) - c * 0.2f, cx(PETAL), cy(PETAL) - c * 0.4f)
        cv.drawPath(pet, p)
        // LEAF
        val leaf = Path()
        leaf.moveTo(cx(LEAF) - c * 0.42f, cy(LEAF) + c * 0.3f)
        leaf.quadTo(cx(LEAF) - c * 0.2f, cy(LEAF) - c * 0.45f, cx(LEAF) + c * 0.42f, cy(LEAF) - c * 0.3f)
        leaf.quadTo(cx(LEAF) + c * 0.2f, cy(LEAF) + c * 0.45f, cx(LEAF) - c * 0.42f, cy(LEAF) + c * 0.3f)
        cv.drawPath(leaf, p)
        // FLAME
        val fl = Path()
        fl.moveTo(cx(FLAME), cy(FLAME) - c * 0.44f)
        fl.cubicTo(cx(FLAME) + c * 0.42f, cy(FLAME) - c * 0.05f, cx(FLAME) + c * 0.3f, cy(FLAME) + c * 0.42f, cx(FLAME), cy(FLAME) + c * 0.42f)
        fl.cubicTo(cx(FLAME) - c * 0.3f, cy(FLAME) + c * 0.42f, cx(FLAME) - c * 0.42f, cy(FLAME) - c * 0.05f, cx(FLAME), cy(FLAME) - c * 0.44f)
        p.shader = RadialGradient(cx(FLAME), cy(FLAME) + c * 0.2f, c * 0.45f, intArrayOf(W, Color.argb(200, 255, 255, 255)), null, Shader.TileMode.CLAMP)
        cv.drawPath(fl, p)
        p.shader = null
        // COIN: disc with rim
        p.color = W
        cv.drawCircle(cx(COIN), cy(COIN), c * 0.42f, p)
        p.color = Color.argb(255, 205, 205, 205)
        p.style = Paint.Style.STROKE; p.strokeWidth = c * 0.06f
        cv.drawCircle(cx(COIN), cy(COIN), c * 0.3f, p)
        p.style = Paint.Style.FILL
        // SWIRL (dizzy)
        p.color = W; p.style = Paint.Style.STROKE; p.strokeWidth = c * 0.07f; p.strokeCap = Paint.Cap.ROUND
        val sw = Path()
        for (k in 0..60) {
            val a = k / 60f * 4.5f * Math.PI.toFloat()
            val r = c * 0.04f + k / 60f * c * 0.36f
            val x = cx(SWIRL) + cos(a) * r; val y = cy(SWIRL) + sin(a) * r
            if (k == 0) sw.moveTo(x, y) else sw.lineTo(x, y)
        }
        cv.drawPath(sw, p)
        p.style = Paint.Style.FILL
        // FOAM ring (soft)
        radial(FOAM, c * 0.48f, intArrayOf(T, Color.argb(40, 255, 255, 255), W, T), floatArrayOf(0f, 0.6f, 0.82f, 1f))
        // BOLT
        p.color = W
        val bolt = Path()
        bolt.moveTo(cx(BOLT) + c * 0.08f, cy(BOLT) - c * 0.45f); bolt.lineTo(cx(BOLT) - c * 0.25f, cy(BOLT) + c * 0.05f)
        bolt.lineTo(cx(BOLT) - c * 0.02f, cy(BOLT) + c * 0.05f); bolt.lineTo(cx(BOLT) - c * 0.12f, cy(BOLT) + c * 0.45f)
        bolt.lineTo(cx(BOLT) + c * 0.26f, cy(BOLT) - c * 0.08f); bolt.lineTo(cx(BOLT) + c * 0.03f, cy(BOLT) - c * 0.08f); bolt.close()
        cv.drawPath(bolt, p)
        // CLOUD_BIG: fluffy cumulus with soft flat base and shaded underside
        val cb = CLOUD_BIG
        for ((dx, dy, r) in listOf(
            Triple(-0.28f, 0.1f, 0.17f), Triple(-0.12f, -0.02f, 0.22f), Triple(0.08f, -0.08f, 0.25f),
            Triple(0.27f, 0.06f, 0.18f), Triple(0.0f, 0.12f, 0.2f), Triple(-0.38f, 0.16f, 0.1f), Triple(0.38f, 0.15f, 0.1f),
        )) {
            radial(cb, r * c, intArrayOf(W, Color.argb(250, 236, 242, 252), Color.argb(0, 230, 238, 250)), floatArrayOf(0f, 0.72f, 1f), dx * c, dy * c)
        }
        // SPEED line
        p.shader = LinearGradient(cx(SPEED), cy(SPEED) - c * 0.48f, cx(SPEED), cy(SPEED) + c * 0.48f, intArrayOf(T, W, T), floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP)
        cv.drawRoundRect(cx(SPEED) - c * 0.04f, cy(SPEED) - c * 0.48f, cx(SPEED) + c * 0.04f, cy(SPEED) + c * 0.48f, c * 0.04f, c * 0.04f, p)
        p.shader = null
        // SPLAT (impact burst)
        p.color = W
        cv.drawPath(starPath(cx(SPLAT), cy(SPLAT), c * 0.46f, c * 0.24f, 8), p)
        // CHEVRON (direction arrow on track)
        val ch = Path()
        ch.moveTo(cx(CHEVRON) - c * 0.36f, cy(CHEVRON) + c * 0.2f); ch.lineTo(cx(CHEVRON), cy(CHEVRON) - c * 0.22f)
        ch.lineTo(cx(CHEVRON) + c * 0.36f, cy(CHEVRON) + c * 0.2f); ch.lineTo(cx(CHEVRON) + c * 0.2f, cy(CHEVRON) + c * 0.32f)
        ch.lineTo(cx(CHEVRON), cy(CHEVRON) + c * 0.06f); ch.lineTo(cx(CHEVRON) - c * 0.2f, cy(CHEVRON) + c * 0.32f); ch.close()
        cv.drawPath(ch, p)

        texture = TextureUtil.fromBitmap(bmp)
        bmp.recycle()
        val iu = 1f / (c * cols); val iv = 1f / (c * rows)
        rects = Array(cols * rows) { i ->
            val u = (i % cols).toFloat() / cols; val v = (i / cols).toFloat() / rows
            UvRect(u + iu, v + iv, u + 1f / cols - iu, v + 1f / rows - iv)
        }
    }

    private fun starPath(x: Float, y: Float, outer: Float, inner: Float, points: Int): Path {
        val path = Path()
        for (k in 0 until points * 2) {
            val r = if (k % 2 == 0) outer else inner
            val a = Math.PI * k / points - Math.PI / 2
            val px = x + cos(a).toFloat() * r; val py = y + sin(a).toFloat() * r
            if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        return path
    }

    private fun heartPath(x: Float, y: Float, s: Float): Path {
        val path = Path()
        path.moveTo(x, y + s * 0.85f)
        path.cubicTo(x - s * 1.3f, y - s * 0.1f, x - s * 0.6f, y - s * 1.05f, x, y - s * 0.4f)
        path.cubicTo(x + s * 0.6f, y - s * 1.05f, x + s * 1.3f, y - s * 0.1f, x, y + s * 0.85f)
        path.close()
        return path
    }
}

/** Game UI icons packed into one texture for in-world billboards (power-ups, markers). */
class IconAtlas(context: Context) {
    val texture: Int
    val rects: Array<UvRect>

    companion object {
        const val TURBO = 0; const val BUBBLE = 1; const val MAGNET = 2; const val SNEAKER = 3
        const val COIN = 4; const val CROWN = 5; const val FLAG = 6; const val STAR = 7
        private val names = listOf("ui_turbo", "ui_bubble", "ui_magnet", "ui_sneaker", "ui_coin", "ui_crown", "ui_flag", "ui_star")
    }

    init {
        val cell = 256
        val bmp = Bitmap.createBitmap(cell * 4, cell * 2, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        names.forEachIndexed { i, n ->
            val x = (i % 4) * cell; val y = (i / 4) * cell
            @Suppress("DiscouragedApi")
            val id = context.resources.getIdentifier(n, "drawable", context.packageName)
            val icon = if (id != 0) BitmapFactory.decodeResource(context.resources, id, BitmapFactory.Options().apply { inScaled = false }) else null
            if (icon != null) {
                cv.drawBitmap(icon, null, android.graphics.Rect(x + 8, y + 8, x + cell - 8, y + cell - 8), p)
                icon.recycle()
            } else {
                p.color = listOf(0xFFFFD23F, 0xFF9FE7FF, 0xFFFF5FA8, 0xFF19C6C2, 0xFFFFD23F, 0xFFFFD23F, 0xFFFF6B57, 0xFFFFD23F)[i].toInt()
                cv.drawCircle(x + cell / 2f, y + cell / 2f, cell * 0.38f, p)
            }
        }
        texture = TextureUtil.fromBitmap(bmp)
        bmp.recycle()
        val inset = 1f / 1024f
        rects = Array(8) { i -> UvRect((i % 4) / 4f + inset, (i / 4) / 2f + inset, (i % 4 + 1) / 4f - inset, (i / 4 + 1) / 2f - inset) }
    }
}

/** Text labels (racer names, popups) rendered with the game font into a texture. */
class TextAtlas(context: Context, labels: List<Pair<String, Int>>, outline: Int = 0xFF13304A.toInt(), textSize: Float = 54f) {
    val texture: Int
    val rects: Array<UvRect>

    init {
        val w = 1024
        val rowH = (textSize * 1.34f).toInt()
        val font: Typeface = try { ResourcesCompat.getFont(context, R.font.lilita_one) ?: Typeface.DEFAULT_BOLD } catch (_: Throwable) { Typeface.DEFAULT_BOLD }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font; this.textSize = textSize; textAlign = Paint.Align.LEFT }
        val stroke = Paint(fill).apply { style = Paint.Style.STROKE; strokeWidth = textSize * 0.19f; strokeJoin = Paint.Join.ROUND; color = outline }
        val placed = ArrayList<IntArray>()
        var x = 0; var y = 0
        for ((text, _) in labels) {
            val tw = (fill.measureText(text) + textSize * 0.55f).toInt().coerceAtMost(w)
            if (x + tw > w) { x = 0; y += rowH }
            placed += intArrayOf(x, y, tw)
            x += tw
        }
        var h = 64
        while (h < y + rowH) h *= 2
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        labels.forEachIndexed { i, (text, color) ->
            val px = placed[i][0]; val py = placed[i][1]
            val baseline = py + rowH * 0.74f
            cv.drawText(text, px + textSize * 0.27f, baseline, stroke)
            fill.color = color
            cv.drawText(text, px + textSize * 0.27f, baseline, fill)
        }
        texture = TextureUtil.fromBitmap(bmp)
        bmp.recycle()
        rects = Array(labels.size) { i ->
            val pl = placed[i]
            UvRect(pl[0].toFloat() / w, pl[1].toFloat() / h, (pl[0] + pl[2]).toFloat() / w, (pl[1] + rowH).toFloat() / h, pl[2].toFloat() / rowH)
        }
    }
}
