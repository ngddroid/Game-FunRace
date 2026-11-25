package com.ngstudio.funrace.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ngstudio.funrace.R
import com.ngstudio.funrace.app.AppContainer
import com.ngstudio.funrace.services.InAppUpdates
import com.ngstudio.funrace.services.Notifications
import com.ngstudio.funrace.services.Telemetry
import com.ngstudio.funrace.ui.LocalNav
import com.ngstudio.funrace.ui.components.BodyText
import com.ngstudio.funrace.ui.components.Buttons
import com.ngstudio.funrace.ui.components.GameButton
import com.ngstudio.funrace.ui.components.GameDialog
import com.ngstudio.funrace.ui.theme.Palette
import kotlinx.coroutines.delay

/**
 * Asks for Android 13's notification permission and turns the game's notifications on.
 * Returns the function to call; [onDone] gets whether notifications can now be shown. When Android
 * won't ask any more, or notifications are blocked in the system settings, it opens those settings.
 */
@Composable
fun rememberNotificationEnabler(c: AppContainer, onDone: (Boolean) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Telemetry.event("notif_permission", "granted" to granted)
        c.save.update { it.copy(settings = it.settings.copy(notifAsked = true)) }
        if (granted) c.applyNotifications(true)
        onDone(granted)
    }
    return {
        c.save.update { it.copy(settings = it.settings.copy(notifications = true)) }
        c.applyNotifications(true)
        // Android stops showing its dialog after two "Don't allow"s; then only the system settings can
        val canAsk = !c.save.data.settings.notifAsked ||
            (ctx as? Activity)?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true
        when {
            Notifications.allowed(ctx) -> onDone(true)
            Notifications.needsPermission(ctx) && canAsk -> launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> {
                val i = if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", ctx.packageName, null))
                try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
                onDone(false)
            }
        }
    }
}

/**
 * The main menu's one-off prompts: "an update is ready, restart?" and, after the player's second
 * race, a friendly ask before Android 13's notification permission dialog. Never shown in a race.
 */
@Composable
fun ServicePrompts(c: AppContainer, updates: InAppUpdates?, onMain: Boolean) {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val d by c.save.state.collectAsState()
    val ready by (updates?.readyToInstall ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsState()
    var updateLater by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf(false) }
    val enable = rememberNotificationEnabler(c) { on -> ask = false; if (on) nav.showToast("Notifications on!") }

    LaunchedEffect(onMain, d.stats.races) {
        if (onMain && !d.settings.notifAsked && d.settings.notifications && d.stats.races >= 2 && Notifications.needsPermission(ctx)) {
            delay(900)
            ask = true
        }
    }
    if (!onMain) return
    if (ready && !updateLater) {
        GameDialog("Update Ready!", onDismiss = { updateLater = true }, banner = Palette.Lime, bannerDark = Palette.LimeDeep) {
            BodyText("A new version of Splash Dash has downloaded. Restart to play it. Your progress is saved.", align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            GameButton("Restart", { c.save.flush(); updates?.installNow() }, Modifier.fillMaxWidth(), colors = Buttons.Green, icon = R.drawable.ui_restart)
            Spacer(Modifier.height(10.dp))
            GameButton("Later", { updateLater = true }, Modifier.fillMaxWidth(), colors = Buttons.Grey)
        }
    } else if (ask) {
        GameDialog("Never Miss a Gift!", onDismiss = null, banner = Palette.Ocean, bannerDark = Palette.OceanDeep) {
            BodyText("Turn on notifications and we'll tell you when your daily gift and free spin are ready.", align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            GameButton("Turn On", { c.tap(); enable() }, Modifier.fillMaxWidth(), colors = Buttons.Green, icon = R.drawable.ui_gift)
            Spacer(Modifier.height(10.dp))
            GameButton("Not Now", {
                c.back()
                ask = false
                c.save.update { it.copy(settings = it.settings.copy(notifAsked = true)) }
                Telemetry.event("notif_soft_ask", "accepted" to false)
            }, Modifier.fillMaxWidth(), colors = Buttons.Grey)
        }
    }
}
