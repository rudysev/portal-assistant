package com.portal.assistant.ui

import android.provider.Settings
import androidx.compose.animation.core.Spring
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlin.math.E
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

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
 * visualizer / orb scaling to the live level, eased by [animateFloatOnFrameClock]) is information, not
 * decoration; and so is a loading spinner ([LoadingSpinner]). Both run on the raw frame clock, because the
 * setting would otherwise freeze or snap them too. Don't gate-all or gate-none.
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

private const val FULL_TURN_DEGREES = 360f

/** The spinner's angle [elapsedNanos] after it started — always in [0, 360). */
internal fun spinnerDegrees(elapsedNanos: Long): Float {
    val periodNanos = SPINNER_PERIOD_MS * 1_000_000L
    return (elapsedNanos % periodNanos) * FULL_TURN_DEGREES / periodNanos
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

/**
 * Like [androidx.compose.animation.core.animateFloatAsState] with its default spring, but stepped on the raw
 * frame clock — so the audio-reactive orb keeps easing under "remove animations", where animator duration
 * scale 0 would make that spring finish instantly and the orb jump between level readings. Asks for frames only
 * while moving; at rest it waits for the next target (no idle redraws on the always-on display).
 */
@Composable
fun animateFloatOnFrameClock(targetValue: Float): State<Float> {
    val target = rememberUpdatedState(targetValue)
    val shown = remember { mutableFloatStateOf(targetValue) }
    LaunchedEffect(Unit) {
        var spring = FrameClockSpring(shown.floatValue)
        while (true) {
            snapshotFlow { target.value }.first { !spring.isSettledAt(it) }
            var lastFrame = withFrameNanos { it }
            while (!spring.isSettledAt(target.value)) {
                withFrameNanos { now ->
                    spring = spring.step(target.value, now - lastFrame)
                    lastFrame = now
                    shown.floatValue = spring.value
                }
            }
        }
    }
    return shown
}

/**
 * Compose's default spring (critically damped, [Spring.StiffnessMedium]) stepped by hand on the frame clock, so
 * it eases the same whatever the animator duration scale. [value] is where it is; [velocity] is in units/second.
 */
internal data class FrameClockSpring(val value: Float, val velocity: Float = 0f) {

    /** Where the spring is [elapsedNanos] after this, heading for [target]. */
    fun step(target: Float, elapsedNanos: Long): FrameClockSpring {
        val seconds = elapsedNanos / 1e9
        // Closed form of a critically damped spring: x(t) = target + (offset + slope·t)·e^(−ωt).
        val offset = value.toDouble() - target
        val slope = velocity + OMEGA * offset
        val decay = exp(-OMEGA * seconds)
        val displacement = (offset + slope * seconds) * decay
        val nextVelocity = (slope - OMEGA * (offset + slope * seconds)) * decay
        // Settle as Compose does: once all the motion left is under the display threshold, land on the target.
        // From here the curve never strays further than |offset| + |slope|/(ω·e) (the peak of t·e^(−ωt)).
        val furthestStillToGo = abs(displacement) + abs(nextVelocity + OMEGA * displacement) / (OMEGA * E)
        if (furthestStillToGo < Spring.DefaultDisplacementThreshold) return FrameClockSpring(target)
        return FrameClockSpring(value = (target + displacement).toFloat(), velocity = nextVelocity.toFloat())
    }

    /** At rest on [target] — nothing left to draw until the target moves. */
    fun isSettledAt(target: Float): Boolean = value == target && velocity == 0f

    private companion object {
        val OMEGA = sqrt(Spring.StiffnessMedium.toDouble())
    }
}
