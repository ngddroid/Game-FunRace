package com.ngstudio.funrace.app

import android.app.Application
import android.content.Context
import com.ngstudio.funrace.ads.AdConfig
import com.ngstudio.funrace.ads.AdProvider
import com.ngstudio.funrace.audio.AudioEngine
import com.ngstudio.funrace.audio.Haptic
import com.ngstudio.funrace.audio.Haptics
import com.ngstudio.funrace.audio.Music
import com.ngstudio.funrace.audio.Sfx
import com.ngstudio.funrace.data.Catalog
import com.ngstudio.funrace.data.SaveRepository
import com.ngstudio.funrace.data.Settings
import com.ngstudio.funrace.game.RaceController
import com.ngstudio.funrace.game.render.GameRenderer
import com.ngstudio.funrace.game.render.GameSurfaceView
import com.ngstudio.funrace.game.render.Look
import com.ngstudio.funrace.game.render.MenuMode
import com.ngstudio.funrace.game.render.ModelLibrary
import com.ngstudio.funrace.game.render.SceneSpec
import com.ngstudio.funrace.level.LevelRepository
import com.ngstudio.funrace.services.FirebaseTelemetry
import com.ngstudio.funrace.services.Notifications
import com.ngstudio.funrace.services.Push
import com.ngstudio.funrace.services.Reminders
import com.ngstudio.funrace.services.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Manual dependency container living as long as the process. */
class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val save = SaveRepository(app)
    val audio = AudioEngine(app)
    val haptics = Haptics(app)
    val ads: AdProvider = AdConfig.create()
    val stage = Stage(this)
    private val _loadProgress = MutableStateFlow(0f)
    val loadProgress: StateFlow<Float> = _loadProgress.asStateFlow()
    @Volatile var assetsReady = false

    private var loading = false

    init {
        ModelLibrary.assets = app.assets
        applySettings(save.data.settings)
    }

    /**
     * Loads sounds, level salts and models. Called when the game's screen opens, not at process start,
     * so a reminder or a push message that wakes the app in the background doesn't load the game.
     */
    fun startLoading() {
        if (loading) return
        loading = true
        CoroutineScope(Dispatchers.IO).launch {
            audio.loadAll()
            try {
                val bytes = app.assets.open("levels/salts.bin").use { it.readBytes() }
                LevelRepository.installSalts(bytes)
            } catch (t: Throwable) { Telemetry.nonFatal(t) }
            val names = app.assets.list("models")?.map { it.removeSuffix(".dmesh") } ?: emptyList()
            names.forEachIndexed { i, n ->
                ModelLibrary.get(n)
                _loadProgress.value = (i + 1f) / names.size.coerceAtLeast(1)
            }
            assetsReady = true
            _loadProgress.value = 1f
        }
    }

    fun applySettings(s: Settings) {
        audio.sfxEnabled = s.sfx
        audio.sfxVolume = s.sfxVolume
        audio.setMusicEnabled(s.music)
        audio.setMusicVolume(s.musicVolume)
        haptics.enabled = s.haptics
        telemetry?.setCollectionEnabled(s.shareData)
    }

    /** Firebase analytics + Crashlytics (null in builds or processes without Firebase). */
    var telemetry: FirebaseTelemetry? = null

    /** The Settings switch for reminders and push messages. */
    fun applyNotifications(on: Boolean) {
        Push.setEnabled(on)
        if (!on) Reminders.cancel(app)
    }

    fun tap() { audio.play(Sfx.UI_TAP); haptics.play(Haptic.TICK) }
    fun back() { audio.play(Sfx.UI_BACK); haptics.play(Haptic.TICK) }

    fun playerLook(characterId: String? = null, hatId: String? = null): Look {
        val p = save.data.profile
        return Look.of(Catalog.character(characterId ?: p.character), Catalog.hat(hatId ?: p.hat))
    }
}

/** Controls the persistent 3D layer behind the Compose UI. */
class Stage(private val c: AppContainer) {
    var surface: GameSurfaceView? = null
        private set
    var renderer: GameRenderer? = null
        private set
    private var rendering = true
    private var music: Music? = null

    fun attach(s: GameSurfaceView, r: GameRenderer) { surface = s; renderer = r }
    fun detach() { surface = null; renderer = null }

    fun showMenu(mode: MenuMode, characterId: String? = null, hatId: String? = null, dance: String? = null) {
        val d = c.save.data
        val lvl = d.progress.highestUnlocked
        // the menus are always a sunny day: your current world at noon, or the lagoon for the worlds
        // that only exist at sunset or night (Pirate Cove, Volcano Isle, Neon Pier)
        val current = LevelRepository.world(lvl)
        val world = if (current.forcedTime == null) current else com.ngstudio.funrace.level.World.LAGOON
        val time = com.ngstudio.funrace.level.TimeOfDay.NOON
        renderer?.updateMenu(SceneSpec.Menu(c.playerLook(characterId, hatId), mode, world, time, dance ?: Catalog.dance(d.profile.dance).id))
        setRendering(true)
    }

    fun showRace(controller: RaceController) {
        renderer?.show(SceneSpec.Race(controller))
        setRendering(true)
    }

    fun setRendering(on: Boolean) {
        if (rendering == on) return
        rendering = on
        surface?.renderMode = if (on) android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY else android.opengl.GLSurfaceView.RENDERMODE_WHEN_DIRTY
    }

    fun drag(dYaw: Float) = renderer?.menuDrag(dYaw)
    fun wave() = renderer?.menuWave()

    fun playMusic(m: Music?) {
        if (music == m) return
        music = m
        c.audio.playMusic(m)
    }
}

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val t = try { FirebaseTelemetry(this) } catch (e: Throwable) { android.util.Log.w("App", "Firebase unavailable", e); null }
        if (t != null) Telemetry.sink = t
        container = AppContainer(this)
        container.telemetry = t
        container.applySettings(container.save.data.settings)
        Notifications.createChannels(this)
        if (container.save.data.settings.notifications) Push.setEnabled(true)
    }
}

val Context.container: AppContainer get() = (applicationContext as App).container
