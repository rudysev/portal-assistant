package com.portal.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.portal.assistant.conversation.ConversationHub
import com.portal.assistant.conversation.ListeningTone
import com.portal.assistant.conversation.ListeningTonePlayer
import com.portal.assistant.system.AppPrefs
import com.portal.assistant.ui.theme.Accent
import com.portal.assistant.ui.theme.Radii
import com.portal.assistant.ui.theme.TextSize
import com.portal.commons.DebugLog

/**
 * The **Sounds** section: one switch for the **Listening tone** ([AppPrefs.listeningToneEnabled], default on; the
 * engine reads it at each conversation's start, so a change applies from the next one). Switching it on plays the
 * tone once as a preview; off is silent; and no preview plays while a conversation is live
 * ([ListeningTone.previewsOnSwitch]) — the mic is open, and the preview would be recorded and sent.
 */
@Composable
internal fun SoundsSection(subtle: Color) {
    val context = LocalContext.current
    var toneEnabled by remember { mutableStateOf(AppPrefs.listeningToneEnabled(context)) }
    // Plain collectAsState, like the Speaker preview: a conversation that starts while Settings is open (gen2 wake
    // word) must still suppress the preview.
    val conversation by ConversationHub.session.collectAsState()
    val conversationLive = conversation.phase != ConversationHub.UiPhase.IDLE

    Column {
        SectionHeading("Sounds", "Cues Jarvis plays, at the same volume as its voice.", subtle)
        SettingsSwitchRow(
            title = "Listening tone",
            subtitle = "Play a soft tone when Jarvis starts listening",
            checked = toneEnabled,
            onCheckedChange = { on ->
                AppPrefs.setListeningToneEnabled(context, on)
                toneEnabled = on
                if (ListeningTone.previewsOnSwitch(switchedOn = on, conversationLive = conversationLive)) {
                    ListeningTonePlayer.play(
                        onStarted = { DebugLog.log("listening tone: preview playing (Settings)") },
                        onFailed = {}, // the player logs it; a preview has nothing to undo
                    )
                } else if (on) {
                    DebugLog.log("listening tone: preview skipped (conversation live)")
                }
            },
            subtle = subtle,
        )
    }
}

/**
 * One labelled switch. A bounded, surface-tinted row so the label and its switch read as a single tappable unit
 * across the wide gap — not a label stranded far from its control.
 */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtle: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(Radii.Card))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            .padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = TextSize.ToolLabel, fontWeight = FontWeight.Medium)
            Spacer(Modifier.size(2.dp))
            Text(
                subtitle,
                color = subtle,
                fontSize = TextSize.Meta,
                lineHeight = TextSize.Body,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                // On = warm orange track with a clean white thumb (the conventional, on-brand look). Off = muted
                // surface, so the control reads against the near-black bg.
                checkedThumbColor = Color.White,
                checkedTrackColor = Accent,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                uncheckedTrackColor = MaterialTheme.colorScheme.surface,
                uncheckedBorderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f),
            ),
        )
    }
}

/**
 * Shared colors for the form's primary buttons. Enabled = the brand orange (filled, obvious). Disabled reads
 * as a recessed, switched-off button — a faint surface tint with dimmed text — clearly *off* rather than a
 * second filled style (the solid disabled fill could be mistaken for an active primary that does nothing),
 * and never Material's near-invisible default on near-black (which made "not yet" look like "broken").
 */
@Composable
internal fun settingsButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Accent,
    contentColor = MaterialTheme.colorScheme.onPrimary,
    disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
    disabledContentColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
)

/** A section's title and one-line caption, spaced the same everywhere on the screen. */
@Composable
internal fun SectionHeading(title: String, caption: String, subtle: Color) {
    Column {
        Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = TextSize.SectionHeader, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(4.dp))
        Text(caption, color = subtle, fontSize = TextSize.Body)
        Spacer(Modifier.size(14.dp))
    }
}

/** A read-only field that opens a menu of [options]; picking one calls [onSelect] and closes the menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> SettingsDropdown(
    label: String,
    value: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
