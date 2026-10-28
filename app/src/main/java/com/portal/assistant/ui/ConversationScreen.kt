package com.portal.assistant.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.portal.assistant.R
import com.portal.assistant.conversation.ConversationHub
import com.portal.assistant.conversation.Turn
import com.portal.assistant.gemini.GeminiModel
import com.portal.assistant.system.AppPrefs
import com.portal.assistant.ui.theme.Accent
import com.portal.assistant.ui.theme.Dims
import com.portal.assistant.ui.theme.OrangeLight
import com.portal.assistant.ui.theme.Radii
import com.portal.assistant.ui.theme.SECONDARY_ALPHA
import com.portal.assistant.ui.theme.TextSize
import kotlinx.coroutines.flow.StateFlow

// The background stays flat down to here, then warms toward [BottomGlow] at the bottom edge.
private const val BOTTOM_GLOW_START = 0.72f
private val BottomGlow = Color(0xFF1A0F03)

// The status orb: a slow breathing pulse while connecting, then scale and glow that follow the mic level.
private const val ORB_PULSE_MS = 1100
private const val CONNECTING_GLOW = 0.35f
private const val LISTENING_SCALE_BOOST = 0.25f // at full level the orb is 25% larger
private const val LISTENING_GLOW_MIN = 0.3f
private const val LISTENING_GLOW_RANGE = 0.5f

/**
 * The foreground chat screen. Renders the live conversation [ConversationHub] publishes: an iOS-assistant
 * layout (user chips + large model text, word-by-word) in our warm-orange identity, voice-only.
 */
// A state-hoisted screen: the conversation state it renders and the six user actions it raises are its whole
// contract, all passed by name from MainActivity. Bundling them into a holder would only rename the list.
@Suppress("LongParameterList")
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
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.background,
                    BOTTOM_GLOW_START to MaterialTheme.colorScheme.background,
                    1f to BottomGlow, // subtle warm glow along the bottom edge
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
                        IdleHome(
                            onSuggestion = onSuggestion,
                            onOpenSettings = onOpenSettings,
                            modifier = Modifier.fillMaxSize(),
                        )
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
    // The backend + model the app will run — keyed on prefs so a backend/model change (e.g. returning from
    // Settings) refreshes the subtitle without requiring a full screen teardown.
    val context = LocalContext.current
    val backend = AppPrefs.voiceBackendKind(context)
    val modelId = AppPrefs.modelId(context)
    val modelLabel = remember(backend, modelId) {
        if (backend == AppPrefs.VoiceBackendKind.LOCAL) {
            "Local server"
        } else {
            GeminiModel.of(modelId).name
        }
    }
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
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = SECONDARY_ALPHA),
                fontSize = TextSize.Body,
            )
        }
        Spacer(Modifier.weight(1f))
        // "New conversation" is additive — it appears only once there's a transcript to clear, to the LEFT of
        // Settings. Settings stays present in every state (it used to be replaced by "+", stranding the user
        // mid-conversation). Both are sized for an imprecise far-tap and carry TalkBack labels.
        if (showNewConversation) {
            CircleButton(
                onClick = onNewConversation,
                bg = MaterialTheme.colorScheme.surface,
                size = Dims.TopIconButton,
            ) {
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
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
            modifier = Modifier.padding(end = 18.dp).size(22.dp),
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
        Crossfade(targetState = statusLabel(phase).orEmpty(), label = "statusWord") { label ->
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
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = ORB_PULSE_MS),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "pulse",
            ).value
        }
        glow = CONNECTING_GLOW
    } else {
        val level by audioLevel.collectAsStateWithLifecycle()
        val clamped = level.coerceIn(0f, 1f)
        scale = animateFloatOnFrameClock(1f + clamped * LISTENING_SCALE_BOOST).value
        glow = LISTENING_GLOW_MIN + LISTENING_GLOW_RANGE * clamped
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
internal fun DrawScope.drawAccentGlow(alphaAtCenter: Float, radius: Float = size.minDimension / 2f) {
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
