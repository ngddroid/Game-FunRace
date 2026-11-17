package com.ngstudio.funrace.game.render

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import com.ngstudio.funrace.BuildConfig
import com.ngstudio.funrace.game.RaceController
import com.ngstudio.funrace.level.TimeOfDay
import com.ngstudio.funrace.level.World
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10

/** Describes what the 3D layer should show; survives EGL context loss. */
sealed class SceneSpec {
    data class Menu(val look: Look, val mode: MenuMode, val world: World, val time: TimeOfDay, val dance: String = "hop") : SceneSpec()
    data class Race(val controller: RaceController) : SceneSpec()
    data object None : SceneSpec()
}

class GameRenderer(
    private val context: Context,
    private val requestedTier: Int, // 0 auto, 1 low, 2 medium, 3 high
) : GLSurfaceView.Renderer {
    private val commands = ConcurrentLinkedQueue<() -> Unit>()
    private var res: RenderResources? = null
    private var scene: Scene? = null
    @Volatile private var spec: SceneSpec = SceneSpec.None
    private var last = 0L
    private var width = 1
    private var height = 1
    private var frames = 0
    private var fpsClock = 0L
    private var worstFrameNs = 0L

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()
    private val _fps = MutableStateFlow(0)
    val fps: StateFlow<Int> = _fps.asStateFlow()
    @Volatile var tier: QualityTier = QualityTier.MEDIUM
        private set
    @Volatile var gpuName: String = ""
        private set

    fun show(s: SceneSpec) {
        spec = s
        commands.add { buildScene() }
    }

    /** Updates the menu diorama in place when the world/time didn't change. */
    fun updateMenu(s: SceneSpec.Menu) {
        val prev = spec
        spec = s
        commands.add {
            val sc = scene
            if (sc is MenuScene && prev is SceneSpec.Menu && prev.world == s.world && prev.time == s.time) {
                sc.look = s.look; sc.mode = s.mode; sc.danceId = s.dance; sc.hatPreview = null
            } else buildScene()
        }
    }

    fun menuDrag(dYaw: Float) { commands.add { (scene as? MenuScene)?.let { it.dragYaw += dYaw } } }
    fun menuWave() { commands.add { (scene as? MenuScene)?.wave() } }

    private fun buildScene() {
        val r = res ?: return
        scene?.release()
        scene = null
        scene = try {
            when (val s = spec) {
                is SceneSpec.Menu -> MenuScene(r, s.world, s.time).also { it.look = s.look; it.mode = s.mode; it.danceId = s.dance }
                is SceneSpec.Race -> RaceScene(context, r, s.controller)
                SceneSpec.None -> null
            }
        } catch (t: Throwable) {
            Log.e("GameRenderer", "Scene build failed", t)
            null
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        gpuName = GLES30.glGetString(GLES30.GL_RENDERER) ?: ""
        tier = when (requestedTier) { 1 -> QualityTier.LOW; 2 -> QualityTier.MEDIUM; 3 -> QualityTier.HIGH; else -> autoTier(gpuName) }
        Log.i("GameRenderer", "GPU=$gpuName tier=$tier")
        res = try { RenderResources(context, tier) } catch (t: Throwable) {
            Log.e("GameRenderer", "Resource init failed at $tier, falling back to LOW", t)
            tier = QualityTier.LOW
            RenderResources(context, QualityTier.LOW)
        }
        scene = null
        buildScene()
        last = SystemClock.elapsedRealtimeNanos()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        res?.width = w; res?.height = h
        GLES30.glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        while (true) { val c = commands.poll() ?: break; c() }
        val now = SystemClock.elapsedRealtimeNanos()
        if (now - last > worstFrameNs) worstFrameNs = now - last
        val dt = ((now - last) / 1e9f).coerceIn(0f, 0.05f)
        last = now
        val r = res
        val s = scene
        if (r == null || s == null) {
            GLES30.glClearColor(0.1f, 0.55f, 0.75f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            if (r != null && spec is SceneSpec.None) _ready.value = true
            return
        }
        try {
            s.update(dt)
            s.render()
        } catch (t: Throwable) {
            Log.e("GameRenderer", "Frame failed", t)
        }
        if (!_ready.value) _ready.value = true
        frames++
        if (now - fpsClock > 1_000_000_000L) {
            _fps.value = frames
            (spec as? SceneSpec.Race)?.controller?.fps = frames
            if (BuildConfig.DEBUG) Log.i("SplashFps", "fps=$frames worst=${worstFrameNs / 1_000_000}ms scene=${s.javaClass.simpleName}")
            worstFrameNs = 0L
            frames = 0
            fpsClock = now
        }
    }

    private fun autoTier(gpu: String): QualityTier {
        val g = gpu.lowercase()
        fun num(prefix: String): Int = Regex("$prefix(\\d+)").find(g)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        return when {
            g.contains("adreno") -> { val n = num("adreno \\(tm\\) "); val m = if (n < 0) num("adreno ") else n; if (m >= 640) QualityTier.HIGH else if (m >= 505) QualityTier.MEDIUM else QualityTier.LOW }
            g.contains("mali-g") -> { val n = num("mali-g"); when { n >= 610 || n in 76..99 -> QualityTier.HIGH; n >= 51 -> QualityTier.MEDIUM; else -> QualityTier.LOW } }
            g.contains("mali-t") || g.contains("mali-4") -> QualityTier.LOW
            g.contains("powervr") -> if (g.contains("ge83") || g.contains("ge8100") || g.contains("ge8300")) QualityTier.LOW else QualityTier.MEDIUM
            g.contains("xclipse") || g.contains("immortalis") -> QualityTier.HIGH
            else -> QualityTier.MEDIUM
        }
    }
}

/** EGL config chooser that prefers 4x MSAA and falls back gracefully. */
class MsaaConfigChooser(private val msaa: Boolean) : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        val es3 = 0x40
        fun attempt(samples: Int): EGLConfig? {
            val attrs = intArrayOf(
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 0,
                EGL10.EGL_DEPTH_SIZE, 24, EGL10.EGL_STENCIL_SIZE, 0,
                EGL10.EGL_RENDERABLE_TYPE, es3,
                EGL10.EGL_SAMPLE_BUFFERS, if (samples > 0) 1 else 0,
                EGL10.EGL_SAMPLES, samples,
                EGL10.EGL_NONE,
            )
            val n = IntArray(1)
            if (!egl.eglChooseConfig(display, attrs, null, 0, n) || n[0] <= 0) return null
            val configs = arrayOfNulls<EGLConfig>(n[0])
            egl.eglChooseConfig(display, attrs, configs, n[0], n)
            return configs.firstOrNull()
        }
        return (if (msaa) attempt(4) else null) ?: attempt(0) ?: run {
            val attrs = intArrayOf(EGL10.EGL_RENDERABLE_TYPE, es3, EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_NONE)
            val n = IntArray(1)
            egl.eglChooseConfig(display, attrs, null, 0, n)
            val configs = arrayOfNulls<EGLConfig>(n[0].coerceAtLeast(1))
            egl.eglChooseConfig(display, attrs, configs, configs.size, n)
            configs[0]!!
        }
    }
}

/**
 * The 3D surface. [renderScale] < 1 renders at a lower resolution that the display hardware
 * upscales for free (keeps 60 fps on modest GPUs).
 */
class GameSurfaceView(context: Context, val renderer: GameRenderer, msaa: Boolean, private val renderScale: Float) : GLSurfaceView(context) {
    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(MsaaConfigChooser(msaa))
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (renderScale < 0.99f && w > 0 && h > 0) holder.setFixedSize((w * renderScale).toInt(), (h * renderScale).toInt())
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        super.surfaceCreated(holder)
        if (Build.VERSION.SDK_INT >= 30) {
            try { holder.surface.setFrameRate(60f, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE) } catch (_: Throwable) {}
        }
    }
}
