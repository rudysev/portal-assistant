package com.portal.assistant.ui.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import com.portal.assistant.ui.LocalReduceMotion
import com.portal.assistant.ui.rememberReduceMotion

// Our own warm-orange identity — NOT a typical assistant blue, NOT the older portal-gemini-chat terracotta. Always
// dark (the Portal+ is an always-on countertop display). The accent harmonizes with the orange
// RecordingOverlay mic bar, so the foreground UI and the background indicator share one identity.
val Accent = Color(0xFFFF8A00) // signature orange (matches the recording bar)
val OrangeLight = Color(0xFFFFB74D) // gradient high end
val OrangeDeep = Color(0xFFFF6D00) // gradient low end
val Ink = Color(0xFF0A0A0C) // app background (a warm-orange glow is layered on top in the screen)
val UserChip = Color(0xFF3A332B) // warm-tinted surface for the user's spoken-turn chip (legible on near-black)

/** Opacity for secondary/caption text (status subtitle, the idle hint, settings captions, timer labels) over
 *  [onBackground]/[onSurface] — one token so the dimming is consistent and tunable in one place. */
val SubtleAlpha = 0.6f

/** Secondary text that must stay readable across the room (helper copy, field labels, captions, the idle
 *  hint, timer labels). Raised from [SubtleAlpha] to clear WCAG AA (~4.5:1) on [Ink] at viewing distance. */
val SecondaryAlpha = 0.80f

/** Least-important metadata only (e.g. package ids) — dim, but not invisible. (The idle-home date was
 *  promoted to [SecondaryAlpha] for across-the-room legibility, so it no longer uses this tier.) */
val TertiaryAlpha = 0.64f

private val DarkColors = darkColorScheme(
    background = Ink,
    surface = UserChip,
    onBackground = Color(0xFFF1ECE6),
    onSurface = Color(0xFFF1ECE6),
    primary = Accent,
    // Without this, onPrimary defaults to Material's baseline purple — which is what painted the navy
    // Switch thumb and would tint button labels off-brand. Near-black ink reads cleanly on the orange
    // primary (≈7:1) for button text and icons.
    onPrimary = Ink,
)

/** App-wide theme wrapper. */
@Composable
fun AssistantTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, typography = AssistantTypography) {
        // Bare Text composables (which pass only size/weight/color) read their family from LocalTextStyle,
        // not the Typography roles — so default it to Inter here. One seam makes every glyph in the app Inter.
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = Inter),
            LocalReduceMotion provides rememberReduceMotion(),
            content = content,
        )
    }
}
