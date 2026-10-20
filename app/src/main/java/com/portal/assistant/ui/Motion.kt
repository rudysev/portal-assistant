package com.portal.assistant.ui

import android.provider.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
 * fade, the notice/FAB fade) are content reveal, not perpetual motion; audio-reactive feedback (the
 * visualizer / orb scaling to the live level) is information, not decoration; and so is a loading spinner
 * ([LoadingSpinner]). Don't gate-all or gate-none.
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

/** One full turn of [LoadingSpinner]. */
internal const val SPINNER_PERIOD_MS = 1_000L

/** The spinner's angle [elapsedNanos] after it started — always in [0, 360). */
internal fun spinnerDegrees(elapsedNanos: Long): Float {
    val periodNanos = SPINNER_PERIOD_MS * 1_000_000L
    return (elapsedNanos % periodNanos) * 360f / periodNanos
}

/**
 * A three-quarter arc turning at a steady rate — "working on it" that keeps turning even under "remove
 * animations". Material's indeterminate [CircularProgressIndicator] runs on an infinite transition, which
 * that setting (animator duration scale 0) freezes on its first frame — a near-closed ring that reads as
 * stuck. This is driven by the raw frame clock instead, which the setting doesn't scale. The angle is read in
 * the draw layer, so turning doesn't recompose.
 */
@Composable
fun LoadingSpinner(color: Color, modifier: Modifier = Modifier, strokeWidth: Dp = 2.dp) {
    var degrees by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { degrees = spinnerDegrees(it - start) }
    }
    CircularProgressIndicator(
        progress = { 0.75f },
        modifier = modifier.graphicsLayer { rotationZ = degrees },
        color = color,
        strokeWidth = strokeWidth,
        trackColor = Color.Transparent,
        gapSize = 0.dp,
    )
}
