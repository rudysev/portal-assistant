package com.portal.assistant.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.portal.assistant.ui.theme.Accent
import com.portal.assistant.ui.theme.OrangeLight

/**
 * The compact in-transcript audio indicator: a small **circular** orb (a radial Accent glow around an
 * OrangeLight→Accent core) that brightens and gently scales with the live audio [level] (0..1) — mic level
 * while listening, playback level while speaking. A circle, NOT a filled pill: it shares the [ListeningOrb]'s
 * vocabulary so the audio-reactive element is one shape app-wide, and it never reads as a tappable button
 * sitting next to the End control. Deliberately smaller than the End button (72 dp) so the two never read as
 * twin circles — the orb is an ambient indicator, End is the one control. Pure Canvas (no `Modifier.blur`,
 * which needs API 31; the Portal is API 28).
 */
@Composable
fun AudioVisualizer(level: Float, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val clamped = level.coerceIn(0f, 1f)
    val scale by animateFloatAsState(targetValue = 1f + clamped * 0.18f, label = "vizScale")
    Canvas(modifier = modifier.size(size).scale(scale)) {
        // Soft outer glow that swells with the audio level.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Accent.copy(alpha = 0.25f + 0.45f * clamped), Color.Transparent),
                center = center,
                radius = this.size.minDimension / 2f,
            ),
            radius = this.size.minDimension / 2f,
            center = center,
        )
        // Inner orb, brighter at the top.
        val innerR = this.size.minDimension * 0.30f
        drawCircle(
            brush = Brush.verticalGradient(
                colors = listOf(OrangeLight, Accent),
                startY = center.y - innerR,
                endY = center.y + innerR,
            ),
            radius = innerR,
            center = center,
        )
    }
}
