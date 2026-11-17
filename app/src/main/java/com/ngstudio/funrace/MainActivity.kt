package com.ngstudio.funrace

import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
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
import com.ngstudio.funrace.game.render.GameSurfaceView
import com.ngstudio.funrace.ui.LaunchOptions
import com.ngstudio.funrace.ui.SplashDashApp

class MainActivity : ComponentActivity() {
    private var surface: GameSurfaceView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val c = container
        if (BuildConfig.DEBUG) {
            LaunchOptions.autoLevel = intent.getIntExtra("autoLevel", 0)
            LaunchOptions.autopilot = intent.getBooleanExtra("autopilot", false)
        }
        val settings = c.save.data.settings
        val renderer = GameRenderer(applicationContext, settings.quality)
        // render scale per requested quality (auto keeps native for strong GPUs, decided by tier later)
        val scale = when (settings.quality) { 1 -> 0.7f; 2 -> 0.85f; 3 -> 1f; else -> 0.85f }
        val gl = GameSurfaceView(this, renderer, msaa = settings.quality != 1, renderScale = scale)
        surface = gl
        c.stage.attach(gl, renderer)
        val compose = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { SplashDashApp(c, this@MainActivity) }
        }
        val root = FrameLayout(this)
        root.addView(gl, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(compose, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        surface?.onResume()
        container.audio.onResume()
    }

    override fun onPause() {
        surface?.onPause()
        container.audio.onPause()
        container.save.flush()
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing) container.stage.detach()
        super.onDestroy()
    }
}
