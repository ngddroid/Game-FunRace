package com.ngstudio.funrace

import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ngstudio.funrace.app.container
import com.ngstudio.funrace.game.render.GameRenderer
import com.ngstudio.funrace.game.render.LaunchCurtain
import com.ngstudio.funrace.game.render.GameSurfaceView
import com.ngstudio.funrace.services.GameEvents
import com.ngstudio.funrace.services.InAppUpdates
import com.ngstudio.funrace.services.Notifications
import com.ngstudio.funrace.services.Reminders
import com.ngstudio.funrace.services.Telemetry
import com.ngstudio.funrace.ui.LaunchOptions
import com.ngstudio.funrace.ui.SplashDashApp

class MainActivity : ComponentActivity() {
    private var surface: GameSurfaceView? = null
    private lateinit var updates: InAppUpdates

    override fun onCreate(savedInstanceState: Bundle?) {
        // a fresh launch shows the system launch screen; a recreated activity (rotation etc.) does not
        if (savedInstanceState == null) LaunchCurtain.lower() else LaunchCurtain.lift()
        val launch = installSplashScreen()
        super.onCreate(savedInstanceState)
        // the system launch screen is plain white like the studio intro after it: drop it at once
        launch.setOnExitAnimationListener { p -> p.remove(); LaunchCurtain.systemGone() }
        // a game: never let the screen dim or lock while it is open (races are played hands-off at times)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val c = container
        c.startLoading()
        updates = InAppUpdates(this)
        if (savedInstanceState == null) trackNotificationTap(intent)
        if (BuildConfig.DEBUG) {
            LaunchOptions.autoLevel = intent.getIntExtra("autoLevel", 0)
            LaunchOptions.autopilot = intent.getBooleanExtra("autopilot", false)
        }
        val settings = c.save.data.settings
        val renderer = GameRenderer(applicationContext, settings.quality)
        // Render as close to native resolution as the GPU allows (capped at 1080 px wide): strong
        // GPUs get every pixel, mid-range ones 92 % (still crisp, a steady 60 fps), weak ones less.
        // The detected GPU tier is remembered so later launches start at the right size.
        val dm = resources.displayMetrics
        val shortSide = minOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(1)
        val native = (1080f / shortSide).coerceAtMost(1f)
        val gpuPrefs = getSharedPreferences("gpu", MODE_PRIVATE)
        val cachedTier = gpuPrefs.getInt("tier", -1)
        fun scaleFor(quality: Int, tier: Int): Float = native * when (quality) {
            1 -> 0.75f
            2 -> 0.92f
            3 -> 1f
            else -> when (tier) { 2 -> 1f; 0 -> 0.8f; else -> 0.92f }
        }
        val gl = GameSurfaceView(this, renderer, msaa = settings.quality != 1, renderScale = scaleFor(settings.quality, cachedTier))
        Telemetry.context("quality", settings.quality.toString())
        renderer.onTierDetected = { t ->
            Telemetry.context("gpu_tier", t.name)
            if (t.ordinal != cachedTier) {
                gpuPrefs.edit().putInt("tier", t.ordinal).apply()
                if (settings.quality == 0) gl.post { gl.setRenderScale(scaleFor(0, t.ordinal)) }
            }
        }
        surface = gl
        c.stage.attach(gl, renderer)
        val compose = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { SplashDashApp(c, this@MainActivity, updates) }
        }
        val root = FrameLayout(this)
        root.addView(gl, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(compose, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        trackNotificationTap(intent)
    }

    /** A tap on one of the game's own notifications (Firebase counts its push opens by itself). */
    private fun trackNotificationTap(i: android.content.Intent?) {
        val source = i?.getStringExtra(Notifications.EXTRA_SOURCE) ?: return
        i.removeExtra(Notifications.EXTRA_SOURCE)
        GameEvents.notificationOpened(source)
    }

    override fun onStart() {
        super.onStart()
        // playing now: no "come back" reminders while the game is open
        Reminders.cancel(this)
    }

    override fun onResume() {
        super.onResume()
        surface?.onResume()
        container.audio.onResume()
        updates.onResume()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && container.save.data.settings.notifications) Reminders.schedule(this)
    }

    override fun onPause() {
        surface?.onPause()
        container.audio.onPause()
        container.save.flush()
        super.onPause()
    }

    override fun onDestroy() {
        updates.dispose()
        if (isFinishing) container.stage.detach()
        super.onDestroy()
    }
}
