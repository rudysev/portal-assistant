package com.portal.assistant.wake

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Foreground wake's one-time model setup, as the idle home shows it. */
sealed interface ModelSetup {
    /** Nothing to show: model installed/downloaded, or gen1 (no in-app detector). */
    data object Idle : ModelSetup

    /** The one-time model download is in flight; [progress] is 0f–1f. */
    data class Downloading(val progress: Float) : ModelSetup

    /** Setup didn't finish (a failed download, or a model that won't load); tap-to-talk still works. */
    data object Failed : ModelSetup
}

/**
 * Process-wide [ModelSetup] for the idle-home setup indicator. Written by [ForegroundWake] (via the hosting service)
 * on the main thread; observed by the UI.
 */
object WakeSetupStatus {
    private val _setup = MutableStateFlow<ModelSetup>(ModelSetup.Idle)
    val setup: StateFlow<ModelSetup> = _setup.asStateFlow()

    fun set(state: ModelSetup) {
        _setup.value = state
    }
}
