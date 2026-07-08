package com.portal.assistant.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.portal.assistant.BuildConfig
import com.portal.assistant.R
import com.portal.assistant.conversation.ConversationHub
import com.portal.assistant.conversation.ModelSetup
import com.portal.assistant.conversation.RevealProgress
import com.portal.assistant.conversation.Role
import com.portal.assistant.conversation.Turn
import com.portal.assistant.conversation.tools.TimerStore
import com.portal.assistant.conversation.tools.Timers
import com.portal.assistant.gemini.GeminiModel
import com.portal.assistant.system.AppPrefs
import com.portal.assistant.ui.theme.Accent
import com.portal.assistant.ui.theme.Dims
import com.portal.assistant.ui.theme.Measure
import com.portal.assistant.ui.theme.OrangeLight
import com.portal.assistant.ui.theme.Radii
import com.portal.assistant.ui.theme.SecondaryAlpha
import com.portal.assistant.ui.theme.TextSize
import com.portal.assistant.ui.theme.TurnSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Tuned for a large countertop display viewed from across the room — type is large, content is centered
// in a readable column.
private const val GREETING = "How can I help?"
private const val IDLE_HINT = "Tap or say “Hey Jarvis”"

// Example prompts shown on the idle home — discoverability (a first-timer otherwise has no idea Jarvis can
// run timers, play music, or control the home) and a low-friction way in (tapping one asks it). Curated to
// span categories rather than list every tool.
//
// Kept deliberately short — a calm, clock-first idle home, not a command menu. Two always-available prompts
// that span a grounded answer + a flagship built-in tool; the Kasa provider adds a third (home control) when
// it's installed. (Music, etc. are still one spoken request away — the chips teach, they don't enumerate.)
private val BASE_SUGGESTIONS = listOf(
    "What's the weather?",
    "Set a 10-minute timer",
)

// Smart-home control comes from an installed provider app. The Kasa provider exposes on/off plugs, so we
// offer "Turn off the lights" ONLY when it's enabled — otherwise the tool isn't declared to the model and the
// chip would dead-end. (The old "Dim the lights" never worked: plugs are on/off, not dimmable.)
private const val KASA_PKG = "com.portal.kasa"

private fun idleSuggestions(context: Context): List<String> = if (KASA_PKG in AppPrefs.enabledProviders(context)) BASE_SUGGESTIONS + "Turn off the lights" else BASE_SUGGESTIONS

// Older turns fade so the latest answer stands out — but stay readable at distance (raised from 0.45).
private const val OLDER_TURN_ALPHA = 0.62f

// How far from the newest content the user can be scrolled and still count as "following".
private const val BOTTOM_SLOP_PX = 160

// Paced reveal: the model's transcription text arrives faster than its 24 kHz speech plays. The engine
// stamps how many words have been "spoken" on the latest model turn (Turn.revealedWords, computed by
// RevealProgress from audio played ÷ received); this screen just shows that many words while SPEAKING and
// snaps to full text when it ends. Each freshly revealed word fades in over WORD_FADE_MS (device-tunable).
private const val WORD_FADE_MS = 140

// Below this playback level the model is "thinking" (no audible output yet); at/above it, audio is flowing
// so the thinking dots give way even before the first transcript word arrives.
private const val THINKING_AUDIO_EPS = 0.05f

/**
 * The foreground chat screen. Renders the live conversation [ConversationHub] publishes: an iOS-assistant
 * layout (user chips + large model text, word-by-word) in our warm-orange identity, voice-only.
 */
@Composable
fun ConversationScreen(
    phase: ConversationHub.UiPhase,
    turns: List<Turn>,
    audioLevel: StateFlow<Float>,
    notice: String?,
    onDismissNotice: () -> Unit,
    onMicTap: () -> Unit,
    onSuggestion: (String) -> Unit,
    onEnd: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.background,
                    0.72f to MaterialTheme.colorScheme.background,
                    1f to Color(0xFF1A0F03), // subtle warm glow along the bottom edge
                ),
            )
            .padding(horizontal = 32.dp)
            // Top strip reserved for the Portal's persistent system overlay (back/home + Wi-Fi); a safe inset
            // at the bottom keeps the mic/End cluster — and any streamed text — clear of the device bezel and
            // the RecordingOverlay bar.
            .padding(top = 56.dp, bottom = Dims.BottomSafe),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // The "New conversation" reset only makes sense once there's a transcript to clear.
            TopBar(
                phase,
                showNewConversation = turns.isNotEmpty(),
                onNewConversation = onNewConversation,
                onOpenSettings = onOpenSettings,
            )
            NoticeSlot(notice, onDismissNotice)
            // Live timer cards sit above the content in BOTH states — a most-wanted at-a-glance readout
            // shouldn't vanish the moment a transcript is on screen. Renders nothing when no timers are active.
            TimerCards(
                hasTranscript = turns.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    // Has content, OR the model has taken the turn (SPEAKING) but hasn't emitted its first
                    // delta yet — both go to the transcript, so the pre-output gap shows the thinking dots
                    // rather than the listening orb (SPEAKING can briefly precede the first Turn).
                    turns.isNotEmpty() || phase == ConversationHub.UiPhase.SPEAKING ->
                        Transcript(turns, phase, audioLevel)

                    // At rest → the idle home, behind a slow breathing warmth so the always-on screen reads as
                    // awake, not frozen (glow drawn first → sits behind the clock/greeting).
                    phase == ConversationHub.UiPhase.IDLE -> {
                        AmbientGlow()
                        IdleHome(onSuggestion = onSuggestion, onOpenSettings = onOpenSettings, modifier = Modifier.fillMaxSize())
                    }

                    // Connecting / listening with nothing said yet → put the state front-and-center (a big label
                    // + a reactive orb) so it reads from across the room, instead of a stale greeting.
                    else -> ConversationStatus(phase, audioLevel, modifier = Modifier.align(Alignment.Center))
                }
            }
            BottomBar(phase, audioLevel, hasTranscript = turns.isNotEmpty(), onMicTap = onMicTap, onEnd = onEnd)
        }
    }
}

@Composable
private fun TopBar(
    phase: ConversationHub.UiPhase,
    showNewConversation: Boolean,
    onNewConversation: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    // The model the app will run (the chosen one, falling back to the default) — captured at screen entry.
    val context = LocalContext.current
    val modelLabel = remember { GeminiModel.prettyName(AppPrefs.modelId(context)) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(size = 38.dp)
        Spacer(Modifier.size(12.dp))
        Column {
            Text(
                text = "Jarvis",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
            )
            // The chosen model + a live status while a conversation runs (display only — no picker).
            Text(
                text = statusLabel(phase)?.let { "$modelLabel · $it" } ?: modelLabel,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = SecondaryAlpha),
                fontSize = TextSize.Body,
            )
        }
        Spacer(Modifier.weight(1f))
        // "New conversation" is additive — it appears only once there's a transcript to clear, to the LEFT of
        // Settings. Settings stays present in every state (it used to be replaced by "+", stranding the user
        // mid-conversation). Both are sized for an imprecise far-tap and carry TalkBack labels.
        if (showNewConversation) {
            CircleButton(onClick = onNewConversation, bg = MaterialTheme.colorScheme.surface, size = Dims.TopIconButton) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "New conversation",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.size(8.dp))
        }
        CircleButton(onClick = onOpenSettings, bg = MaterialTheme.colorScheme.surface, size = Dims.TopIconButton) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/** The in-app brand mark (header + greeting) — the gear/vortex badge, distinct from the launcher icon. */
@Composable
private fun AppIcon(size: Dp) {
    Image(
        painter = painterResource(id = R.drawable.ic_brand),
        contentDescription = null,
        modifier = Modifier.size(size),
    )
}

private fun statusLabel(phase: ConversationHub.UiPhase): String? = when (phase) {
    ConversationHub.UiPhase.CONNECTING -> "Connecting…"
    ConversationHub.UiPhase.LISTENING -> "Listening…"
    ConversationHub.UiPhase.SPEAKING -> "Speaking…"
    ConversationHub.UiPhase.IDLE -> null
}

/**
 * Renders the failure/offline/permission [notice] (or nothing). Fades in/out and keeps the last message
 * around through the exit animation so the banner doesn't blank out as it leaves. Centered and width-capped
 * so a short message doesn't stretch across the wide display.
 */
@Composable
private fun NoticeSlot(notice: String?, onDismiss: () -> Unit) {
    var lastMessage by remember { mutableStateOf("") }
    LaunchedEffect(notice) { if (notice != null) lastMessage = notice }
    AnimatedVisibility(visible = notice != null, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            NoticeBanner(lastMessage, onDismiss, modifier = Modifier.widthIn(max = 720.dp))
        }
    }
}

@Composable
private fun NoticeBanner(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    // Auto-dismiss lives in MainActivity (so it survives opening Settings); here the banner just clears on tap.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.Card))
            .background(MaterialTheme.colorScheme.surface)
            .height(IntrinsicSize.Min)
            .clickable(onClick = onDismiss)
            // Announce to TalkBack as soon as it appears — it's an error/status, not a passive label.
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A warm accent strip flags it as a notice (no extra icon font needed on this minimal icon set).
        Box(Modifier.fillMaxHeight().width(4.dp).background(Accent))
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = TextSize.Body,
            lineHeight = 24.sp,
            modifier = Modifier.weight(1f).padding(start = 18.dp, end = 12.dp, top = 16.dp, bottom = 16.dp),
        )
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Dismiss",
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = SecondaryAlpha),
            modifier = Modifier.padding(end = 18.dp).size(22.dp),
        )
    }
}

// The two-pane landscape home needs a genuinely wide canvas; this is the floor below which even a landscape
// layout is too cramped for it and we stack instead. NOTE: orientation is decided by width-vs-height, not
// this absolute value — at the Portal+'s 160 dpi (1 px = 1 dp) portrait is still 1080 dp wide, so a pure
// width threshold would never flip to portrait. See [IdleHome].
private val WIDE_HOME_MIN = 720.dp

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
private fun IdleHome(onSuggestion: (String) -> Unit, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // No usable key (neither the user's own nor a baked dev key) → Jarvis can't answer. Point the first-time
    // user to Settings instead of showing chips that would only fail at connect.
    val needsKey = AppPrefs.apiKey(context) == null && BuildConfig.GEMINI_API_KEY.isBlank()
    BoxWithConstraints(modifier = modifier) {
        // Orientation is the real signal (width vs height), with WIDE_HOME_MIN only as a "wide enough" floor —
        // an absolute width threshold can't tell landscape from portrait here (both clear 720 dp at 160 dpi).
        val landscape = maxWidth > maxHeight
        if (landscape && maxWidth >= WIDE_HOME_MIN && !needsKey) {
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
                if (needsKey) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        GreetingHeader()
                        Text(
                            text = "Add your Gemini API key to get started",
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = SecondaryAlpha),
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
    Text(
        text = GREETING,
        color = MaterialTheme.colorScheme.onBackground,
        fontSize = TextSize.Greeting,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.size(20.dp))
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
 * A quiet one-line indicator for the one-time **gen2** wake-model download ([ConversationHub.modelSetup]):
 * while it fetches, "Setting up voice wake… NN%"; on failure, a soft note that tap-to-talk still works.
 * Renders nothing once the model is present — so it's invisible on gen1 and after the first download.
 */
@Composable
private fun WakeSetupIndicator() {
    val setup by ConversationHub.modelSetup.collectAsStateWithLifecycle()
    val text = when (val s = setup) {
        is ModelSetup.Downloading -> "Setting up voice wake… ${(s.progress * 100).toInt()}%"
        ModelSetup.Failed -> "Voice wake setup didn’t finish — tap the mic to talk"
        ModelSetup.Idle -> null
    }
    if (text != null) {
        Spacer(Modifier.size(16.dp))
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = SecondaryAlpha),
            fontSize = TextSize.Body,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Center-stage state while a turn is connecting or listening but nothing has been said yet (no transcript): a
 * large state word + a reactive orb, so what Jarvis is doing reads from across the room. The instant the user
 * speaks (or a chip is tapped) a turn appears and the transcript replaces this. The orb is the sole
 * audio-reactive element in this window — the bottom bar shows only the End button (see [BottomBar]).
 */
@Composable
private fun ConversationStatus(
    phase: ConversationHub.UiPhase,
    audioLevel: StateFlow<Float>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp)
            // Announce the state to TalkBack (and speak the change politely), so the center treatment carries
            // the same info the sighted user gets from the orb + label — not just the header subtitle.
            .semantics(mergeDescendants = true) {
                contentDescription = statusLabel(phase) ?: "Jarvis"
                liveRegion = LiveRegionMode.Polite
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ListeningOrb(phase, audioLevel)
        Spacer(Modifier.size(36.dp))
        // Cross-fade the word so Connecting→Listening doesn't pop. fillMaxWidth + centered text keeps both
        // states in the same full-width box — otherwise the narrower incoming word is left-aligned inside the
        // wider outgoing word's bounds, then jumps to center when the box remeasures (the offset/shift bug).
        Crossfade(targetState = statusLabel(phase) ?: "", label = "statusWord") { label ->
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = TextSize.StatusWord,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * A large central orb: a gentle indeterminate breathing pulse while CONNECTING (audio level is 0 then, and
 * this also acknowledges the tap on the same frame), and scaling/glowing with the live mic level while
 * LISTENING. Reuses the visualizer's vocabulary (outer Accent radial glow + inner OrangeLight→Accent fill).
 */
@Composable
private fun ListeningOrb(phase: ConversationHub.UiPhase, audioLevel: StateFlow<Float>) {
    val connecting = phase == ConversationHub.UiPhase.CONNECTING
    // Branch the composition by phase so the infinite breathing pulse (and its per-frame recomposition) runs
    // ONLY while connecting, and the audio level is observed ONLY while listening — neither does wasted work
    // in the other phase (matters on an always-on display).
    val scale: Float
    val glow: Float
    if (connecting) {
        scale = if (LocalReduceMotion.current) {
            1f // hold steady when the user has asked for no animation
        } else {
            rememberInfiniteTransition(label = "orbPulse").animateFloat(
                initialValue = 1f,
                targetValue = 1.06f, // gentle, so the hand-off to the listen scale isn't a visible pop
                animationSpec = infiniteRepeatable(animation = tween(1100), repeatMode = RepeatMode.Reverse),
                label = "pulse",
            ).value
        }
        glow = 0.35f
    } else {
        val level by audioLevel.collectAsStateWithLifecycle()
        val clamped = level.coerceIn(0f, 1f)
        scale = animateFloatAsState(targetValue = 1f + clamped * 0.25f, label = "orbScale").value
        glow = 0.3f + 0.5f * clamped
    }

    Canvas(modifier = Modifier.size(200.dp).scale(scale)) {
        drawAccentGlow(glow)
        val innerR = size.minDimension * 0.26f
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

/**
 * The brand glow primitive: a soft Accent radial fading to transparent, centered in the draw area. Shared by
 * [AmbientGlow] and [ListeningOrb] so the radial-glow vocabulary lives in one place (the AudioVisualizer pill
 * mirrors the same look in its own shape).
 */
private fun DrawScope.drawAccentGlow(alphaAtCenter: Float, radius: Float = size.minDimension / 2f) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Accent.copy(alpha = alphaAtCenter), Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

/**
 * A soft, slowly breathing warm glow behind the idle content. Subtle by design (peaks ~10% Accent over the
 * near-black background) — it should register as ambient life, not a light show. Pauses implicitly with the
 * idle screen (it isn't composed during a live conversation).
 */
@Composable
private fun AmbientGlow(modifier: Modifier = Modifier) {
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
        drawAccentGlow(0.10f * intensity, radius = size.minDimension * (0.42f + 0.10f * intensity))
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
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = SecondaryAlpha),
            fontSize = TextSize.ClockDate,
            letterSpacing = 0.5.sp,
        )
    }
}

/**
 * Live countdown cards for active timers — a most-wanted at-a-glance readout. Observes [TimerStore]
 * (kept in step with the canonical scheduler store) and ticks every second to recompute the remaining time
 * and drop any that just hit 0. Renders nothing when there are no active timers. Each card cancels on its ✕.
 */
private const val TIMER_IMMINENT_SEC = 60L // under a minute → flag the card so it leads the glance

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimerCards(hasTranscript: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val timers by TimerStore.timers.collectAsStateWithLifecycle()
    if (timers.isEmpty()) return // no per-second ticker (and no recomposition) when there's nothing to show
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    // ms precision (matches Timers.active) so a card stays until the alarm actually fires, not ~1s early.
    // Sorted soonest-to-fire first so the most time-critical countdown leads, not creation order.
    val active = timers.filter { it.fireAtMs > now }.sortedBy { it.fireAtMs }
    if (active.isEmpty()) return
    // With a transcript, left-align the cards on the SAME gutter as the reading column so they read as part
    // of the session, not a floating widget. On the idle home (no transcript) they stay centered.
    BoxWithConstraints(modifier = modifier) {
        val gutter = ((maxWidth - Measure.Reading) / 2).coerceAtLeast(Measure.MinGutter)
        FlowRow(
            modifier = if (hasTranscript) {
                Modifier.fillMaxWidth().padding(horizontal = gutter)
            } else {
                Modifier.fillMaxWidth().widthIn(max = Measure.Reading)
            },
            horizontalArrangement = Arrangement.spacedBy(
                12.dp,
                if (hasTranscript) Alignment.Start else Alignment.CenterHorizontally,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            active.forEach { t ->
                TimerCard(
                    label = t.label,
                    remaining = Timers.remainingSec(t, now),
                    onCancel = { TimerStore.cancel(context, t.id) },
                )
            }
        }
    }
}

@Composable
private fun TimerCard(label: String, remaining: Long, onCancel: () -> Unit) {
    val imminent = remaining in 1..TIMER_IMMINENT_SEC
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radii.Card))
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 22.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // One spoken node for the label+time so TalkBack reads it as a unit (clearAndSetSemantics suppresses
        // the child Texts so they aren't announced twice); the ✕ stays a separate "Cancel …" button. A
        // label-less timer's default label is literally "timer", so avoid reading "timer timer".
        val spoken = if (label.equals("timer", ignoreCase = true)) {
            "Timer, ${formatRemaining(remaining)} left"
        } else {
            "$label timer, ${formatRemaining(remaining)} left"
        }
        Column(modifier = Modifier.clearAndSetSemantics { contentDescription = spoken }) {
            Text(
                // Capitalize the first letter so a name (esp. the default "timer") reads as a label, not a
                // raw tool argument.
                text = label.replaceFirstChar { it.uppercase() },
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = SecondaryAlpha),
                fontSize = TextSize.TimerLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp), // a verbose voice label truncates instead of wrapping
            )
            Text(
                text = formatRemaining(remaining),
                // The about-to-fire card turns accent-orange so it leads the glance across the room.
                color = if (imminent) Accent else MaterialTheme.colorScheme.onSurface,
                fontSize = TextSize.TimerTime,
                fontWeight = FontWeight.Medium,
                // Tabular figures: every digit is the same width, so the card stops resizing each second as
                // the countdown ticks (Inter's default proportional digits reflow the card).
                style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
            )
        }
        CircleButton(onClick = onCancel, bg = Color.Transparent, size = 48.dp) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Cancel $label timer",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = SecondaryAlpha),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** mm:ss, or h:mm:ss past an hour. */
private fun formatRemaining(totalSec: Long): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionChips(onTap: (String) -> Unit) {
    val context = LocalContext.current
    // Keyed on the enabled providers so toggling Kasa in Settings updates the chips even if this screen
    // stays in composition (the prefs read is cheap and this composable recomposes rarely).
    val suggestions = remember(AppPrefs.enabledProviders(context)) { idleSuggestions(context) }
    FlowRow(
        modifier = Modifier.widthIn(max = 820.dp),
        // Generous gaps so a far reach onto an upright screen can't graze the neighbouring chip.
        horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        suggestions.forEach { text -> SuggestionChip(text, onTap) }
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

@Composable
private fun Transcript(turns: List<Turn>, phase: ConversationHub.UiPhase, audioLevel: StateFlow<Float>) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // reverseLayout anchors the conversation to the BOTTOM (newest just above the mic, history above it);
    // index 0 is the newest, so we feed the list reversed.
    val items = turns.asReversed()
    val latest = turns.lastOrNull()
    val latestId = latest?.id

    // The model has taken the turn (SPEAKING) but hasn't produced words OR audio yet — the Search/tool
    // "thinking" gap. Show animated dots where the answer will land so the latency reads as working, not stuck.
    // The audio gate matters: the server can stream audio before any transcript (latest still the USER turn),
    // and we must drop the dots the moment sound starts, not wait for the first transcribed word.
    val level by audioLevel.collectAsStateWithLifecycle()
    val thinking = phase == ConversationHub.UiPhase.SPEAKING &&
        (latest?.role != Role.MODEL || latest.text.isBlank()) &&
        level < THINKING_AUDIO_EPS

    // "At bottom" = pinned to the newest (index 0). Flips false when the user scrolls up into history,
    // pausing auto-follow so we never fight them reading back.
    val atBottom by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= BOTTOM_SLOP_PX
        }
    }

    // A new turn re-engages follow even if the user had scrolled up.
    LaunchedEffect(turns.size) { listState.animateScrollToItem(0) }
    // The newest turn grows as text streams (user chips) or words reveal (model, via revealedWords) — follow
    // while pinned.
    LaunchedEffect(latest?.text, latest?.revealedWords) { if (atBottom) listState.scrollToItem(0) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Cap the line measure for readability and center the column — queried against the real width at
        // runtime so it adapts to any Portal model / orientation (no hardcoded resolution). Both user
        // questions and model answers share this one left edge (single-column reading, no left/right split).
        val gutter = ((maxWidth - Measure.Reading) / 2).coerceAtLeast(Measure.MinGutter)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = gutter, vertical = 16.dp),
        ) {
            // reverseLayout → the first-declared item sits at the bottom (newest), just after the question.
            if (thinking) {
                item(key = "thinking") { ThinkingDots() }
            }
            items(items, key = { it.id }) { turn ->
                // The engine stamps revealedWords on the latest model turn while it speaks; reveal it word-by-
                // word only then. Every finished/older turn (and any turn once speaking ends) shows full text.
                val revealing = turn.id == latestId && phase == ConversationHub.UiPhase.SPEAKING
                TurnItem(
                    turn = turn,
                    dimmed = turn.id != latestId,
                    // `?: 0` is defensive: a SPEAKING turn reveals progressively, never dumps full text even
                    // if the engine hadn't stamped a count yet (it always does for the latest model turn).
                    revealedWords = if (revealing) (turn.revealedWords ?: 0) else null,
                )
            }
        }
        AnimatedVisibility(
            visible = !atBottom,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
        ) {
            ScrollToBottomButton { scope.launch { listState.animateScrollToItem(0) } }
        }
    }
}

@Composable
private fun TurnItem(turn: Turn, dimmed: Boolean, revealedWords: Int?) {
    val alpha = if (dimmed) OLDER_TURN_ALPHA else 1f
    when (turn.role) {
        // The user's question reads as a quiet, left-aligned inset ABOVE its answer — same reading edge as
        // the model text (one column, no right-side bubble), smaller and dimmer so the answer stays the star.
        // A larger gap above groups each question with the answer that follows (a Q&A pair). An accent ›
        // marker distinguishes a question from an answer without a chip.
        Role.USER -> Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = Accent.copy(alpha = if (dimmed) OLDER_TURN_ALPHA else 1f))) {
                    append("›  ")
                }
                append(turn.text)
            },
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (dimmed) OLDER_TURN_ALPHA else SecondaryAlpha),
            fontSize = TextSize.UserEcho,
            modifier = Modifier.fillMaxWidth().padding(top = TurnSpacing.PairGap, bottom = TurnSpacing.Couple),
        )

        Role.MODEL -> {
            val modifier = Modifier.fillMaxWidth().padding(top = TurnSpacing.Couple, bottom = TurnSpacing.Trailing)
            if (revealedWords != null) {
                RevealingModelText(turn.text, revealedWords, modifier)
            } else {
                Text(
                    text = turn.text,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = alpha),
                    fontSize = TextSize.ModelText,
                    lineHeight = TextSize.ModelLine,
                    modifier = modifier,
                )
            }
        }
    }
}

/**
 * The active model answer, revealed word-by-word in step with its speech: the first [revealedWords] words
 * of the *original* [text] are shown (so whitespace/newlines are preserved verbatim — no reflow when the
 * turn later snaps to the full plain text) and the most-recently revealed word fades in (alpha 0→1 over
 * [WORD_FADE_MS]).
 */
@Composable
private fun RevealingModelText(text: String, revealedWords: Int, modifier: Modifier = Modifier) {
    val ranges = remember(text) { RevealProgress.wordRanges(text) }
    val shown = revealedWords.coerceIn(0, ranges.size)
    val color = MaterialTheme.colorScheme.onBackground

    // A FRESH fade per newly revealed word: keyed on [shown], so each trailing word starts at 0 on its very
    // first composition (no full-alpha frame → no blink) and fades in exactly once. Already-revealed words
    // render at full alpha and are never re-animated ("once rendered, don't touch the word").
    val fade = remember(shown) { Animatable(0f) }
    LaunchedEffect(shown) { if (shown > 0) fade.animateTo(1f, tween(WORD_FADE_MS)) }

    val annotated = buildAnnotatedString {
        if (shown > 0) {
            val last = ranges[shown - 1]
            // Everything up to the newest word (prior words + their original whitespace) at full alpha…
            withStyle(SpanStyle(color = color)) { append(text.substring(0, last.first)) }
            // …then the newest word fades in. At full reveal, include the original trailing text too so this
            // matches the plain-Text snap exactly.
            val end = if (shown >= ranges.size) text.length else last.last + 1
            withStyle(SpanStyle(color = color.copy(alpha = fade.value))) { append(text.substring(last.first, end)) }
        }
    }
    Text(text = annotated, fontSize = TextSize.ModelText, lineHeight = TextSize.ModelLine, modifier = modifier)
}

/** Three staggered, breathing dots — the model's "thinking" placeholder during the pre-output gap. */
@Composable
private fun ThinkingDots(modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 6.dp)
            .semantics { contentDescription = "Jarvis is thinking" },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (reduceMotion) {
            // Three steady dots still read as "working", without the ripple.
            repeat(3) { Box(Modifier.size(14.dp).clip(CircleShape).background(Accent.copy(alpha = 0.7f))) }
        } else {
            val transition = rememberInfiniteTransition(label = "thinking")
            repeat(3) { i ->
                val alpha by transition.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600),
                        repeatMode = RepeatMode.Reverse,
                        initialStartOffset = StartOffset(i * 200), // stagger so the dots ripple
                    ),
                    label = "dot$i",
                )
                Box(Modifier.size(14.dp).clip(CircleShape).background(Accent.copy(alpha = alpha)))
            }
        }
    }
}

@Composable
private fun BottomBar(
    phase: ConversationHub.UiPhase,
    audioLevel: StateFlow<Float>,
    hasTranscript: Boolean,
    onMicTap: () -> Unit,
    onEnd: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            // At rest: the mic invitation.
            phase == ConversationHub.UiPhase.IDLE -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                MicButton(onMicTap)
                Spacer(Modifier.size(12.dp))
                Text(
                    text = IDLE_HINT,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = SecondaryAlpha),
                    fontSize = TextSize.Body,
                )
            }

            // A conversation with a transcript: the compact bottom visualizer alongside End.
            hasTranscript -> {
                val level by audioLevel.collectAsStateWithLifecycle()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    AudioVisualizer(level)
                    EndButton(onEnd)
                }
            }

            // Connecting / listening, nothing said yet: the center orb is the reactive element, so the bar is
            // just End (no duplicate visualizer).
            else -> EndButton(onEnd)
        }
    }
}

@Composable
private fun EndButton(onEnd: () -> Unit) {
    // The functional control in the speaking/listening bar — sized to lead, so the (ambient) audio orb beside
    // it never out-weighs the one element that actually does something.
    CircleButton(onClick = onEnd, bg = MaterialTheme.colorScheme.surface, size = Dims.EndButton) {
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "End conversation",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(Dims.EndIcon),
        )
    }
}

@Composable
private fun MicButton(onTap: () -> Unit) {
    // A slow breathing pulse invites a tap while idle (held still under reduce-motion).
    val pulse = if (LocalReduceMotion.current) {
        1f
    } else {
        rememberInfiniteTransition(label = "micPulse").animateFloat(
            initialValue = 1f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(animation = tween(1100), repeatMode = RepeatMode.Reverse),
            label = "pulse",
        ).value
    }
    Box(
        modifier = Modifier
            .size(Dims.MicSize)
            .scale(pulse)
            .clip(CircleShape)
            .background(Accent)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = "Start a conversation",
            tint = Color.White,
            modifier = Modifier.size(Dims.MicIcon),
        )
    }
}

@Composable
private fun ScrollToBottomButton(onClick: () -> Unit) {
    CircleButton(onClick = onClick, bg = MaterialTheme.colorScheme.surface, size = 52.dp) {
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = "Scroll to latest",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(30.dp),
        )
    }
}

@Composable
private fun CircleButton(
    onClick: () -> Unit,
    bg: Color,
    size: Dp = 64.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}
