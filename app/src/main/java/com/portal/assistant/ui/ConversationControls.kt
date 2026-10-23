package com.portal.assistant.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.portal.assistant.conversation.ConversationHub
import com.portal.assistant.ui.theme.Accent
import com.portal.assistant.ui.theme.Dims
import com.portal.assistant.ui.theme.SECONDARY_ALPHA
import com.portal.assistant.ui.theme.TextSize
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun BottomBar(
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
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = SECONDARY_ALPHA),
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
internal fun ScrollToBottomButton(onClick: () -> Unit) {
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
internal fun CircleButton(
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
