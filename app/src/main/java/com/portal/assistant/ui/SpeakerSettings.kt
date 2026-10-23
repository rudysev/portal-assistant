package com.portal.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.portal.assistant.conversation.ConversationHub
import com.portal.assistant.conversation.backend.Backends
import com.portal.assistant.gemini.GeminiSpeaker
import com.portal.assistant.gemini.SpeakerPreview
import com.portal.assistant.gemini.SpeakerPreviewPlayer
import com.portal.assistant.system.AppPrefs
import com.portal.assistant.ui.theme.Accent
import com.portal.assistant.ui.theme.SECONDARY_ALPHA
import com.portal.assistant.ui.theme.TextSize

/** What the Speaker field and the dialog's pinned top row show for Server default (no Speaker chosen). */
private const val SERVER_DEFAULT_LABEL = "Default"

/** The Voice dialog's hint line when previews are available (no paused / missing-key hint to show). */
private const val PREVIEW_PROMPT = "Tap a voice to hear a preview."

/** Rows left visible above the Chosen speaker when the dialog opens, so it reads in context. */
private const val SPEAKER_LIST_CONTEXT_ROWS = 3

/** The Speaker list row the dialog opens scrolled to, so the Chosen speaker is on screen (the list holds 30
 *  and shows ~8 — a later pick would otherwise open hidden). Server default is the pinned row → top. Pure. */
internal fun speakerListStartIndex(chosen: GeminiSpeaker.Speaker?): Int = (GeminiSpeaker.AVAILABLE.indexOf(chosen) - SPEAKER_LIST_CONTEXT_ROWS).coerceAtLeast(0)

/**
 * The Chosen speaker picker (Gemini only). A read-only field like Model shows [SERVER_DEFAULT_LABEL] or e.g.
 * "Charon · Male"; tapping it opens [SpeakerDialog]. The choice lives in [AppPrefs.speakerId] and is re-read
 * whenever this section enters composition, so hiding it (Local backend) and showing it again keeps it.
 */
@OptIn(ExperimentalMaterial3Api::class) // ExposedDropdownMenuDefaults.TrailingIcon, so the field matches Model
@Composable
internal fun SpeakerSection() {
    val context = LocalContext.current
    var chosen by remember { mutableStateOf(GeminiSpeaker.find(AppPrefs.speakerId(context))) }
    var picking by remember { mutableStateOf(false) }

    Box {
        OutlinedTextField(
            value = chosen?.label ?: SERVER_DEFAULT_LABEL,
            onValueChange = {},
            readOnly = true,
            label = { Text("Speaker") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = picking) },
            modifier = Modifier.fillMaxWidth(),
        )
        // A read-only field still takes taps for focus/selection; an overlay owns the click so a tap anywhere
        // on the field opens the dialog.
        Box(
            Modifier
                .matchParentSize()
                .clickable(onClickLabel = "Choose a speaker") { picking = true },
        )
    }

    if (picking) {
        SpeakerDialog(
            chosen = chosen,
            onUse = { speaker ->
                AppPrefs.setSpeakerId(context, speaker?.name)
                chosen = speaker
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * Pick a Speaker: a [SERVER_DEFAULT_LABEL] row pinned at the top, then every [GeminiSpeaker.AVAILABLE] entry in
 * one alphabetical list, `Name · Gender` per row, a check on the current [chosen]. Tapping a row **highlights**
 * it and **previews** it ([SpeakerPreviewPlayer] — Jarvis introduces itself in that Speaker's voice; the Default
 * row previews Server default); **Use this voice** hands the highlighted row (null = Server default) to [onUse].
 * Cancel, back, and an outside tap all go to [onDismiss] — nothing is saved. However the dialog closes, leaving
 * composition stops the preview. A live conversation pauses previews (rows stay selectable). A preview heard to
 * the end replays instantly on re-tap; those clips belong to this dialog's player and go when it closes.
 */
@Composable
private fun SpeakerDialog(
    chosen: GeminiSpeaker.Speaker?,
    onUse: (GeminiSpeaker.Speaker?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var highlighted by remember { mutableStateOf(chosen) }
    // Plain collectAsState (not lifecycle-aware) on purpose: a conversation that starts while the app is
    // backgrounded must still stop a preview — a conversation always wins.
    val conversation by ConversationHub.session.collectAsState()
    val conversationLive = conversation.phase != ConversationHub.UiPhase.IDLE
    val preview = remember {
        SpeakerPreviewPlayer(
            apiKey = Backends.geminiApiKey(context),
            model = AppPrefs.modelId(context),
            conversationLive = conversationLive,
        )
    }
    LaunchedEffect(conversationLive) { preview.setConversationLive(conversationLive) }
    DisposableEffect(preview) { onDispose { preview.dismiss() } }
    val previewState by preview.state.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Voice") },
        text = {
            Column {
                // Always one line, so a hint appearing (a conversation starting, no key) never resizes the dialog.
                Text(
                    previewState.hint?.text ?: PREVIEW_PROMPT,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = SECONDARY_ALPHA),
                    fontSize = TextSize.Body,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                SpeakerRow(
                    label = SERVER_DEFAULT_LABEL,
                    isChosen = chosen == null,
                    isHighlighted = highlighted == null,
                    preview = previewState.rowStatus(null),
                    onClick = {
                        highlighted = null
                        preview.tap(null)
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                LazyColumn(
                    state = rememberLazyListState(initialFirstVisibleItemIndex = speakerListStartIndex(chosen)),
                    modifier = Modifier.heightIn(max = 360.dp),
                ) {
                    items(GeminiSpeaker.AVAILABLE, key = { it.name }) { speaker ->
                        SpeakerRow(
                            label = speaker.label,
                            isChosen = chosen == speaker,
                            isHighlighted = highlighted == speaker,
                            preview = previewState.rowStatus(speaker),
                            onClick = {
                                highlighted = speaker
                                preview.tap(speaker)
                            },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onUse(highlighted) }) { Text("Use this voice", color = Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Accent) } },
    )
}

/**
 * One dialog row: tinted while [isHighlighted], with a check when it's the current Chosen speaker, and its
 * [preview] status — a spinner while loading, a speaker glyph while playing, "Couldn't play preview" on failure.
 */
@Composable
private fun SpeakerRow(
    label: String,
    isChosen: Boolean,
    isHighlighted: Boolean,
    preview: SpeakerPreview.RowStatus,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isHighlighted) Accent.copy(alpha = 0.18f) else Color.Transparent)
            .selectable(selected = isHighlighted, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            // As tall as a trailing icon even without one, so a check or preview indicator never resizes a row.
            .heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = TextSize.ToolLabel,
                maxLines = 1,
            )
            if (preview == SpeakerPreview.RowStatus.FAILED) {
                Text(
                    "Couldn't play preview",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = SECONDARY_ALPHA),
                    fontSize = TextSize.Meta,
                )
            }
        }
        // One fixed-size slot for both indicators, so the row (and every row below it) doesn't shift when a
        // preview goes from loading to playing.
        if (preview == SpeakerPreview.RowStatus.LOADING || preview == SpeakerPreview.RowStatus.PLAYING) {
            Box(modifier = Modifier.padding(start = 10.dp).size(24.dp), contentAlignment = Alignment.Center) {
                if (preview == SpeakerPreview.RowStatus.LOADING) {
                    LoadingSpinner(color = Accent, modifier = Modifier.size(20.dp))
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Playing preview",
                        tint = Accent,
                    )
                }
            }
        }
        if (isChosen) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Current voice",
                tint = Accent,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}
