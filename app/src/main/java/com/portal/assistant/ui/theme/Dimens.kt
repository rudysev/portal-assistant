package com.portal.assistant.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// One place for the spacing, control sizes, reading measures, and type scale this countertop UI uses. The
// app is viewed from across a room on every Portal model, so the type scale is deliberately large and the
// small tiers are bumped well above Material's phone defaults. The reading measures cap line length (the
// transcript/forms stay a comfortable column and never run edge-to-edge on the wide display); the actual
// gutter is computed against the real width at runtime (BoxWithConstraints), so this adapts to portrait and
// to smaller models like the Portal Go without per-device branches.

/** Capped line measures + the minimum side gutter. Centered against the real screen width at runtime. */
object Measure {
    val Reading = 920.dp // transcript: ~optimal line length, not the full 1920 px
    val Settings = 760.dp // forms read best narrow
    val MinGutter = 32.dp
}

/** Corner-radius scale. One place so every surface shares a deliberate radius instead of scattered literals:
 *  [Pill] for fully-rounded interactive chips, [Card] for the app's tinted surfaces (notice banner, timer +
 *  tool rows). Circular controls use [androidx.compose.foundation.shape.CircleShape]; text fields keep
 *  Material's own field radius. */
object Radii {
    val Pill = 28.dp // suggestion chips — read as buttons
    val Card = 20.dp // every surface-tinted card/row shares one radius
}

/** Spacing + control sizes. [BottomSafe] keeps controls and streamed text clear of the device bezel. */
object Dims {
    val BottomSafe = 48.dp // safe inset above the bottom edge (nothing rides the bezel)
    val MicSize = 112.dp // the primary CTA — bigger than the old 96 dp so it leads the idle home
    val MicIcon = 50.dp
    val EndButton = 72.dp // the live "stop" control — the functional element, sized to lead the bottom bar
    val EndIcon = 34.dp
    val TopIconButton = 56.dp // settings / new-conversation, large enough for an imprecise far-tap
}

/** Transcript vertical rhythm. Each question is coupled tightly to the answer that follows it (a Q&A
 *  pair), and pairs are separated by a larger gap above each question. The values are scaled to the 30 sp
 *  answer ([TextSize.ModelText]/[TextSize.ModelLine]) so the grouping reads even on the active, undimmed
 *  pair where the older-turn fade isn't doing the separating. dp throughout (no per-device branching), so
 *  the rhythm holds across Portal models and both orientations. */
object TurnSpacing {
    val PairGap = 34.dp // above each question — separates this Q&A pair from the previous answer
    val Couple = 6.dp // each side of the question↔answer seam (applied above + below → a 12 dp coupling)
    val Trailing = 8.dp // below each answer, before the pair gap
}

/** Distance-legible type scale (sp). Bottom tiers are ~30–40 % above Material defaults so labels, captions,
 *  and metadata read from across the room. Centralized so the whole scale is tunable in one place. */
object TextSize {
    val ClockTime = 64.sp // demoted from 72 so it stops out-competing the mic CTA
    val ClockDate = 18.sp
    val ScreenTitle = 26.sp
    val StatusWord = 44.sp
    val Greeting = 36.sp
    val SectionHeader = 21.sp
    val ModelText = 30.sp
    val ModelLine = 40.sp // line height for model text
    val UserEcho = 19.sp // the inset question above each answer — secondary to the answer
    val Chip = 21.sp
    val Body = 17.sp // helper copy, captions, the idle hint (was 15–16)
    val Meta = 15.sp // least-important metadata (package ids)
    val TimerLabel = 16.sp
    val TimerTime = 32.sp
    val ToolLabel = 18.sp
}
