package com.portal.assistant.ui

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Reduce-motion, read once and provided by [com.portal.assistant.ui.theme.AssistantTheme] so every composable
 * reads it as `LocalReduceMotion.current` instead of each re-reading the setting. `static` because the value
 * is fixed for the session (a change applies on the next launch — see [rememberReduceMotion]). Defaults to
 * false (animations on) for previews / any composition outside the theme.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * True when the user has the system "remove animations" accessibility setting on (animator duration scale
 * 0).
 *
 * Scope: gate only **looping ambient decoration** on this — the listening-orb pulse, ambient glow, mic-button
 * pulse, and thinking dots — so the always-on display isn't perpetually moving for users who've asked it not
 * to. Deliberately NOT gated: brief one-shot transitions (the status-word crossfade, the word-by-word reveal
 * fade, the notice/FAB fade) are content reveal, not perpetual motion; and audio-reactive feedback (the
 * visualizer / orb scaling to the live level) is information, not decoration. Don't gate-all or gate-none.
 *
 * Read once per composition (via [remember]) — re-reading every recomposition would be wasted work for a
 * setting that rarely changes. A toggle made while the app is open is not picked up live; it takes effect on
 * the next launch, which is fine for an accessibility preference.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
