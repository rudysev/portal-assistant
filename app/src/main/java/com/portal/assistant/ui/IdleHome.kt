package com.portal.assistant.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.portal.assistant.conversation.ConversationOpening
import com.portal.assistant.conversation.backend.Backends
import com.portal.assistant.system.AppPrefs
import com.portal.assistant.ui.theme.Accent
import com.portal.assistant.ui.theme.Radii
import com.portal.assistant.ui.theme.SECONDARY_ALPHA
import com.portal.assistant.ui.theme.TextSize
import com.portal.assistant.wake.ModelSetup
import com.portal.assistant.wake.WakeSetupStatus
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Tuned for a large countertop display viewed from across the room — type is large, content is centered
// in a readable column.
private const val GREETING = "How can I help?"
internal const val IDLE_HINT = "Tap or say “Hey Jarvis”"

// Example prompts shown on the idle home — discoverability (a first-timer otherwise has no idea Jarvis can
// run timers, play music, or control the home) and a low-friction way in (tapping one asks it). Curated to
// span categories rather than list every tool.
//
// Kept deliberately short — a calm, clock-first idle home, not a command menu. Two prompts that span a
// grounded answer + a flagship built-in tool. (Music, home control, etc. are still one spoken request away.)
private val SUGGESTIONS = listOf(
    "What's the weather?",
    "Set a 10-minute timer",
)

// The two-pane landscape home needs a genuinely wide canvas; this is the floor below which even a landscape
// layout is too cramped for it and we stack instead. NOTE: orientation is decided by width-vs-height, not
// this absolute value — at the Portal+'s 160 dpi (1 px = 1 dp) portrait is still 1080 dp wide, so a pure
// width threshold would never flip to portrait. See [IdleHome].
private val WIDE_HOME_MIN = 720.dp

// The idle home's ambient glow, scaled by its breathing intensity: peak alpha, and the radius (a fraction of the
// screen's short side) it grows from and by.
private const val AMBIENT_GLOW_ALPHA = 0.10f
private const val AMBIENT_GLOW_RADIUS = 0.42f
private const val AMBIENT_GLOW_GROWTH = 0.10f

/**
 * The idle home (shown only at rest): an ambient clock/date glance (what a countertop display is *for*), the
 * greeting, and a couple of example prompts that teach what Jarvis can do and ask it on tap. No center brand
 * mark here; the header already carries it.
 *
 * Orientation-aware: **landscape** keeps the two balanced panes (clock | hairline | ask) that suit the wide
 * display; **portrait/narrow** collapses to one clock-first center column (clock hero, calmer ask band
 * beneath) for the tall aspect. Both keep the idle home deliberately sparse — a clock-led glance, not a
 * dashboard.
 */
@Composable
internal fun IdleHome(onSuggestion: (String) -> Unit, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Not configured → Jarvis can't answer. The nudge shows exactly when a conversation would be refused (the one
    // credential rule, [ConversationOpening.refusalFor]), so the idle home never shows chips that would fail.
    val backendChoice = Backends.resolve(context)
    val needsSetup = ConversationOpening.refusalFor(backendChoice) != null
    val setupMessage = when (backendChoice.kind) {
        AppPrefs.VoiceBackendKind.LOCAL -> "Add your local server address to get started"
        AppPrefs.VoiceBackendKind.GEMINI -> "Add your Gemini API key to get started"
    }
    BoxWithConstraints(modifier = modifier) {
        // Orientation is the real signal (width vs height), with WIDE_HOME_MIN only as a "wide enough" floor —
        // an absolute width threshold can't tell landscape from portrait here (both clear 720 dp at 160 dpi).
        val landscape = maxWidth > maxHeight
        if (landscape && maxWidth >= WIDE_HOME_MIN && !needsSetup) {
            // Landscape: two panes, each centered in its own half, separated by a hairline rule. Drifts as one.
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) { Clock() }
                Spacer(Modifier.size(40.dp))
                Box(
                    Modifier
                        .height(220.dp)
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f)),
                )
                Spacer(Modifier.size(40.dp))
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) { AskPanel(onSuggestion) }
            }
        } else {
            // Portrait / narrow: one clock-first center column — the clock leads, the ask band sits beneath.
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Clock()
                Spacer(Modifier.size(44.dp))
                if (needsSetup) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        GreetingHeader()
                        Text(
                            text = setupMessage,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = SECONDARY_ALPHA),
                            fontSize = TextSize.Body,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.size(14.dp))
                        SuggestionChip("Open Settings", onTap = { onOpenSettings() }, contentDescription = "Open Settings")
                    }
                } else {
                    AskPanel(onSuggestion)
                }
            }
        }
    }
}

/** The greeting line + its trailing gap — shared by the "ask" panel and the "add a key" CTA so the header
 *  styling lives in one place. */
@Composable
private fun GreetingHeader() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = GREETING,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = TextSize.Greeting,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(20.dp))
    }
}

/** Greeting + the suggestion chips, center-aligned — shared by the stacked and two-pane homes. The "Try
 *  asking" caption was dropped to keep the idle home calm: the chips already read as prompts. */
@Composable
private fun AskPanel(onSuggestion: (String) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        GreetingHeader()
        SuggestionChips(onSuggestion)
        WakeSetupIndicator()
    }
}

/**
 * A quiet one-line indicator for the one-time **gen2** wake-model download ([WakeSetupStatus]):
 * while it fetches, "Setting up voice wake… NN%"; on failure, a soft note that tap-to-talk still works.
 * Renders nothing once the model is present — so it's invisible on gen1 and after the first download.
 */
@Composable
private fun WakeSetupIndicator() {
    val setup by WakeSetupStatus.setup.collectAsStateWithLifecycle()
    val text = when (val s = setup) {
        is ModelSetup.Downloading -> "Setting up voice wake… ${(s.progress * 100).toInt()}%"
        ModelSetup.Failed -> "Voice wake setup didn’t finish — tap the mic to talk"
        ModelSetup.Idle -> null
    }
    if (text != null) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.size(16.dp))
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = SECONDARY_ALPHA),
                fontSize = TextSize.Body,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * A soft, slowly breathing warm glow behind the idle content. Subtle by design (peaks ~10% Accent over the
 * near-black background) — it should register as ambient life, not a light show. Pauses implicitly with the
 * idle screen (it isn't composed during a live conversation).
 */
@Composable
internal fun AmbientGlow(modifier: Modifier = Modifier) {
    val intensity = if (LocalReduceMotion.current) {
        0.7f // a steady, gentle glow instead of breathing
    } else {
        rememberInfiniteTransition(label = "ambientGlow").animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(animation = tween(5200), repeatMode = RepeatMode.Reverse),
            label = "glowIntensity",
        ).value
    }
    Canvas(modifier = modifier.fillMaxSize()) {
        drawAccentGlow(AMBIENT_GLOW_ALPHA * intensity, radius = size.minDimension * (AMBIENT_GLOW_RADIUS + AMBIENT_GLOW_GROWTH * intensity))
    }
}

/** Big ambient time + date, re-read on each minute boundary (no seconds shown, so no faster tick needed). */
@Composable
private fun Clock() {
    val context = LocalContext.current
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000 - (value % 60_000)) // sleep to the next minute so the display flips on time
        }
    }
    // android's time format honors the device's 12/24-hour setting; the date is spelled out for glanceability.
    val time = remember(now) { android.text.format.DateFormat.getTimeFormat(context).format(Date(now)) }
    val date = remember(now) { SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date(now)) }
    // Wrapped as ONE unit so a SpaceBetween parent treats time+date as a single block (otherwise the two
    // Texts get distributed apart and the date floats away from the time).
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = time,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = TextSize.ClockTime,
            fontWeight = FontWeight.Light,
            letterSpacing = (-1).sp, // tighten the large numerals into one composed block
            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"), // equal-width digits, no reflow
        )
        Spacer(Modifier.size(8.dp))
        Text(
            // Prime ambient data — raised to the readable secondary tier (was the dim metadata tier, which
            // whispered the date from across the room).
            text = date,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = SECONDARY_ALPHA),
            fontSize = TextSize.ClockDate,
            letterSpacing = 0.5.sp,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionChips(onTap: (String) -> Unit) {
    FlowRow(
        modifier = Modifier.widthIn(max = 820.dp),
        // Generous gaps so a far reach onto an upright screen can't graze the neighbouring chip.
        horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        SUGGESTIONS.forEach { text -> SuggestionChip(text, onTap) }
    }
}

@Composable
private fun SuggestionChip(
    text: String,
    onTap: (String) -> Unit,
    // TalkBack label — defaults to the "ask Jarvis" action; overridden for non-ask chips (e.g. Open Settings).
    contentDescription: String = "Ask: $text",
) {
    val shape = RoundedCornerShape(Radii.Pill)
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.92f),
        fontSize = TextSize.Chip,
        modifier = Modifier
            .clip(shape)
            // A solid surface fill + a warm hairline border so the chip reads as a tappable control across the
            // room — the old near-invisible 0.6α fill with no edge looked like decorative text, not a button.
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, Accent.copy(alpha = 0.35f), shape)
            .clickable(onClick = { onTap(text) })
            .semantics { this.contentDescription = contentDescription }
            // Roomy padding → a tall, easy far-tap target on an arm's-length upright screen.
            .padding(horizontal = 26.dp, vertical = 18.dp),
    )
}
