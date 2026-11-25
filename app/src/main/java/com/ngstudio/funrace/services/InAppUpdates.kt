package com.ngstudio.funrace.services

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.ngstudio.funrace.data.TimeUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Google Play in-app updates.
 *
 * - Update priority 4-5 (set when the release is rolled out through the Play Developer API): an
 *   immediate, full-screen update the player has to take.
 * - Anything else, once the update is a day old: Play's flexible prompt, at most every 3 days. It
 *   downloads while the player keeps playing; then the main menu offers a restart
 *   ([readyToInstall]). It is never installed by itself, so a race left in the background is not
 *   lost to a restart.
 *
 * Only builds installed from Google Play can update this way (internal testing, closed testing or
 * production tracks, or internal app sharing); for a sideloaded debug build Play answers "not owned"
 * and nothing happens.
 */
class InAppUpdates(private val activity: ComponentActivity) {
    private val manager = AppUpdateManagerFactory.create(activity)
    private val prefs = activity.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val _ready = MutableStateFlow(false)
    /** A flexible update finished downloading and waits for a restart. */
    val readyToInstall: StateFlow<Boolean> = _ready.asStateFlow()
    private var checked = false
    private var immediate = false

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val kind = if (immediate) "immediate" else "flexible"
        Telemetry.event("update_prompt_result", "type" to kind, "accepted" to (r.resultCode == Activity.RESULT_OK))
    }

    private val listener = InstallStateUpdatedListener { s ->
        if (s.installStatus() == InstallStatus.DOWNLOADED) _ready.value = true
    }

    init {
        manager.registerListener(listener)
    }

    /** Once per launch, from the main menu (never in the middle of a race). */
    fun check() {
        if (checked) return
        checked = true
        manager.appUpdateInfo
            .addOnSuccessListener { info -> decide(info) }
            .addOnFailureListener { Log.i(LOG, "no Play update check: ${it.message}") }
    }

    private fun decide(info: AppUpdateInfo) {
        if (info.installStatus() == InstallStatus.DOWNLOADED) { _ready.value = true; return }
        if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) return
        val stale = info.clientVersionStalenessDays() ?: 0
        val urgent = info.updatePriority() >= 4
        when {
            urgent && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) -> start(info, AppUpdateType.IMMEDIATE)
            info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) && (stale >= 1 || info.updatePriority() >= 2) -> {
                val today = TimeUtil.today()
                if (today - prefs.getLong(KEY_ASKED, -100L) < ASK_EVERY_DAYS) return
                prefs.edit().putLong(KEY_ASKED, today).apply()
                start(info, AppUpdateType.FLEXIBLE)
            }
        }
    }

    private fun start(info: AppUpdateInfo, type: Int) {
        immediate = type == AppUpdateType.IMMEDIATE
        Telemetry.event("update_prompt", "type" to if (immediate) "immediate" else "flexible", "version" to info.availableVersionCode())
        try {
            manager.startUpdateFlowForResult(info, launcher, AppUpdateOptions.newBuilder(type).build())
        } catch (t: Throwable) {
            Telemetry.nonFatal(t)
        }
    }

    fun onResume() {
        manager.appUpdateInfo.addOnSuccessListener { info ->
            if (info.installStatus() == InstallStatus.DOWNLOADED) _ready.value = true
            // an immediate update the player left (home button) has to be resumed
            if (immediate && info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) start(info, AppUpdateType.IMMEDIATE)
        }
    }

    /** "Restart" on the update-ready dialog: installs now and reopens the game. */
    fun installNow() {
        Telemetry.event("update_install")
        manager.completeUpdate()
    }

    fun dispose() = manager.unregisterListener(listener)

    private companion object {
        const val LOG = "InAppUpdates"
        const val KEY_ASKED = "flexibleAskedDay"
        const val ASK_EVERY_DAYS = 3
    }
}
