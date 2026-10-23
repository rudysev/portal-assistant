package com.portal.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.portal.assistant.conversation.tools.TimerStore
import com.portal.assistant.conversation.tools.Timers
import com.portal.assistant.ui.theme.Accent
import com.portal.assistant.ui.theme.Measure
import com.portal.assistant.ui.theme.Radii
import com.portal.assistant.ui.theme.SECONDARY_ALPHA
import com.portal.assistant.ui.theme.TextSize
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Live countdown cards for active timers — a most-wanted at-a-glance readout. Observes [TimerStore]
 * (kept in step with the canonical scheduler store) and ticks every second to recompute the remaining time
 * and drop any that just hit 0. Renders nothing when there are no active timers. Each card cancels on its ✕.
 */
private const val TIMER_IMMINENT_SEC = 60L // under a minute → flag the card so it leads the glance

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TimerCards(hasTranscript: Boolean, modifier: Modifier = Modifier) {
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
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
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
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
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
    // Locale.ROOT: a countdown is always ASCII digits, whatever the device locale.
    return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
}
