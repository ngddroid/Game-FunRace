package com.ngstudio.funrace.game.render

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The launch sequence: the system launch screen (plain white), then the NG Studio intro, then the
 * game's own splash where the racer drops onto the stage.
 *
 * [systemSplash] is true while the system launch screen still covers the app, so the studio intro
 * waits for it. [down] stays true until the studio intro has finished, so the game splash holds its
 * logo drop and the 3D landing until they can play in view.
 */
object LaunchCurtain {
    val systemSplash = MutableStateFlow(true)
    val down = MutableStateFlow(true)

    fun lower() { systemSplash.value = true; down.value = true }
    fun systemGone() { systemSplash.value = false }
    fun lift() { systemSplash.value = false; down.value = false }
}
