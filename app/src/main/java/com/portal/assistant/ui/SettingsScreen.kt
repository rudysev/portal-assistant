package com.portal.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.portal.assistant.conversation.tools.ExternalToolProvider
import com.portal.assistant.gemini.GeminiKeyCheck
import com.portal.assistant.gemini.GeminiModel
import com.portal.assistant.system.AppPrefs
import com.portal.assistant.system.LocationProvider
import com.portal.assistant.ui.theme.Accent
import com.portal.assistant.ui.theme.Dims
import com.portal.assistant.ui.theme.Measure
import com.portal.assistant.ui.theme.Radii
import com.portal.assistant.ui.theme.SecondaryAlpha
import com.portal.assistant.ui.theme.TextSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's settings surface: the **API key** (BYOD — the user's own Gemini key, paste or type + verify),
 * the **model** Jarvis runs (a dropdown over [GeminiModel.AVAILABLE]), the **location** used for weather /
 * "near me" answers, and the **external tools** allowlist (installed provider apps the user enables to add
 * abilities). Changes apply to the **next** conversation (device context, model, and tool declarations are
 * fixed at session start).
 *
 * Location is auto-detected once via IP geolocation on first install. The field is pre-filled from the
 * detected value immediately (or via a short poll if geo is still in flight). The detected value drives
 * the prompt with full coordinates until the user explicitly edits and saves — only then does an override
 * take effect. The field cannot be saved blank.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var place by remember {
        mutableStateOf(LocationProvider.override(context) ?: LocationProvider.detectedLabel(context).orEmpty())
    }
    // The last-persisted value — gates the Save button (disabled until the field differs) and re-disables it
    // after a save, so saving gives feedback without leaving the screen.
    var savedPlace by remember { mutableStateOf(place) }
    // Helper/caption text raised to clear AA at viewing distance.
    val subtle = MaterialTheme.colorScheme.onBackground.copy(alpha = SecondaryAlpha)

    // If geo is still in flight when the screen opens, poll until the result lands (up to 10 s).
    // Stops early if the user starts typing — their input wins over the auto-detected value.
    LaunchedEffect(Unit) {
        if (place.isNotEmpty()) return@LaunchedEffect
        LocationProvider.refreshIfStale(context) // no-op if already running; ensures a retry on failure
        for (i in 1..20) {
            delay(500)
            if (place.isNotEmpty()) break // user typed; don't overwrite
            val detected = LocationProvider.detectedLabel(context) ?: continue
            place = detected
            savedPlace = detected // a detected fill is the baseline, not an unsaved edit
            break
        }
    }

    // A form reads best in a constrained, centered column — never run edge-to-edge across the wide Portal
    // display (the conversation screen caps its measure the same way). The scroll lives on the full-bleed
    // Box; the content inside is width-capped and centered.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = Measure.Settings)
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .padding(top = 56.dp, bottom = Dims.BottomSafe),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.size(8.dp))
                Text("Settings", color = MaterialTheme.colorScheme.onBackground, fontSize = TextSize.ScreenTitle, fontWeight = FontWeight.SemiBold)
            }

            // Said once, here — every setting on this screen takes effect at the next conversation (the model,
            // device context, and tool declarations are all frozen when a session starts), so the per-section
            // repeats are gone.
            Spacer(Modifier.size(6.dp))
            Text("Changes take effect on your next conversation.", color = subtle, fontSize = TextSize.Body)

            Spacer(Modifier.size(28.dp))
            Text("Model", color = MaterialTheme.colorScheme.onBackground, fontSize = TextSize.SectionHeader, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(4.dp))
            Text("Which model Jarvis uses.", color = subtle, fontSize = TextSize.Body)
            Spacer(Modifier.size(14.dp))

            var modelExpanded by remember { mutableStateOf(false) }
            var model by remember { mutableStateOf(AppPrefs.modelId(context)) }
            ExposedDropdownMenuBox(
                expanded = modelExpanded,
                onExpandedChange = { modelExpanded = it },
            ) {
                OutlinedTextField(
                    value = model,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Model") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = modelExpanded,
                    onDismissRequest = { modelExpanded = false },
                ) {
                    GeminiModel.AVAILABLE.forEach { id ->
                        DropdownMenuItem(
                            text = { Text(id) },
                            onClick = {
                                model = id
                                AppPrefs.setModelId(context, id)
                                modelExpanded = false
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.size(36.dp))
            ApiKeySection(subtle = subtle)

            Spacer(Modifier.size(36.dp))
            Text("Location", color = MaterialTheme.colorScheme.onBackground, fontSize = TextSize.SectionHeader, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(4.dp))
            Text("Used for weather and other location-aware answers.", color = subtle, fontSize = TextSize.Body)
            Spacer(Modifier.size(14.dp))

            OutlinedTextField(
                value = place,
                onValueChange = { if (it.length <= 200) place = it },
                singleLine = true,
                label = { Text("Location (e.g. New York, NY)") },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.size(18.dp))
            Button(
                onClick = {
                    LocationProvider.setOverride(context, place)
                    savedPlace = place // persist only; the back arrow navigates away
                },
                enabled = place.isNotBlank() && place.trim() != savedPlace.trim(),
                colors = settingsButtonColors(),
            ) { Text("Save") }

            Spacer(Modifier.size(36.dp))
            Text("External tools", color = MaterialTheme.colorScheme.onBackground, fontSize = TextSize.SectionHeader, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(4.dp))
            Text(
                "Installed apps that add abilities to Jarvis. Off until you enable them.",
                color = subtle,
                fontSize = TextSize.Body,
            )
            Spacer(Modifier.size(14.dp))

            var providers by remember { mutableStateOf(ExternalToolProvider.providersForSettings(context)) }
            if (providers.isEmpty()) {
                Text("No tool provider apps installed.", color = subtle, fontSize = TextSize.Body)
            } else {
                providers.forEach { p ->
                    // Each provider is one bounded row (surface-tinted) so the label and its switch read as a
                    // single tappable unit across the wide gap — not a label stranded far from its control.
                    // Under the app name we say what it actually adds, in plain words — the provider's own
                    // required one-sentence summary (ToolContract.META_SUMMARY) — not the reverse-domain tool
                    // ids, which meant nothing to a user.
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
                            Text(p.label, color = MaterialTheme.colorScheme.onBackground, fontSize = TextSize.ToolLabel, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.size(2.dp))
                            Text(
                                p.summary,
                                color = subtle,
                                fontSize = TextSize.Meta,
                                lineHeight = TextSize.Body,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Switch(
                            checked = p.enabled,
                            onCheckedChange = { on ->
                                AppPrefs.setProviderEnabled(context, p.pkg, on)
                                providers = providers.map { if (it.pkg == p.pkg) it.copy(enabled = on) else it }
                            },
                            colors = SwitchDefaults.colors(
                                // On = warm orange track with a clean white thumb (the conventional, on-brand
                                // look). Off = muted surface, so the control reads against the near-black bg.
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
            }
        }
    }
}

/**
 * BYOD Gemini key entry. The key is normally provisioned at install time by `setup.sh`
 * ([AppPrefs.importProvisionedKey]); this field lets the user **add or change** it on-device — the only
 * path if they skipped the install step. **Save & verify** confirms the key against the API before storing,
 * so a non-technical user catches a typo or a disabled key immediately. The key is masked everywhere and
 * never logged. Stored via [AppPrefs.setApiKey]; falls back to the baked dev key at the injection site when
 * unset.
 */
@Composable
private fun ApiKeySection(subtle: Color) {
    val context = LocalContext.current
    // The Activity's scope, not a composition scope: navigating back (the section leaves composition) mid-
    // verify must NOT cancel the save, or a verified key would silently never persist.
    val scope = LocalLifecycleOwner.current.lifecycleScope
    val errorColor = MaterialTheme.colorScheme.error
    val okColor = Color(0xFF6FCF77)

    // Pre-fill with the stored key so it's visible (masked) — confirms the app has it loaded.
    var saved by remember { mutableStateOf(AppPrefs.apiKey(context).orEmpty()) }
    var draft by remember { mutableStateOf(saved) }
    var reveal by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageColor by remember { mutableStateOf(subtle) }
    var showSaveAnyway by remember { mutableStateOf(false) }

    Text("API key", color = MaterialTheme.colorScheme.onBackground, fontSize = TextSize.SectionHeader, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.size(4.dp))
    Text(
        "Your own Gemini key powers Jarvis. It's usually set up when the app is installed — enter it below to " +
            "add or change it.",
        color = subtle,
        fontSize = TextSize.Body,
    )
    Spacer(Modifier.size(6.dp))
    Text("Get a free key at aistudio.google.com/apikey.", color = subtle, fontSize = TextSize.Body)
    Spacer(Modifier.size(14.dp))

    OutlinedTextField(
        value = draft,
        onValueChange = {
            if (it.length <= 256) {
                draft = it
                message = null
                showSaveAnyway = false
            }
        },
        singleLine = true,
        label = { Text("Enter your key") },
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            if (draft.isNotEmpty()) {
                TextButton(onClick = { reveal = !reveal }) {
                    Text(if (reveal) "Hide" else "Show", color = Accent, fontSize = TextSize.Body)
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.size(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = {
                val candidate = draft
                scope.launch {
                    checking = true
                    message = null
                    showSaveAnyway = false
                    val result = withContext(Dispatchers.IO) { GeminiKeyCheck.validate(candidate) }
                    checking = false
                    when (result) {
                        GeminiKeyCheck.Result.Valid -> {
                            AppPrefs.setApiKey(context, candidate)
                            saved = candidate // re-disables the button until the next edit
                            reveal = false
                            message = "Key saved ✓"
                            messageColor = okColor
                        }

                        GeminiKeyCheck.Result.Invalid -> {
                            message = "That key didn't work — check you copied the whole thing."
                            messageColor = errorColor
                        }

                        GeminiKeyCheck.Result.NetworkError -> {
                            message = "Couldn't reach Google to check the key. Are you online?"
                            messageColor = subtle
                            showSaveAnyway = true
                        }
                    }
                }
            },
            enabled = draft.isNotBlank() && draft != saved && !checking,
            colors = settingsButtonColors(),
        ) { Text(if (checking) "Checking…" else "Save & verify") }
    }

    // Offered only after a network error: store the key without proof, since the device may just be offline.
    if (showSaveAnyway) {
        Spacer(Modifier.size(6.dp))
        TextButton(
            onClick = {
                AppPrefs.setApiKey(context, draft)
                saved = draft // re-disables the button until the next edit
                reveal = false
                showSaveAnyway = false
                message = "Key saved (not verified)."
                messageColor = subtle
            },
        ) { Text("Save anyway", color = Accent) }
    }

    message?.let {
        Spacer(Modifier.size(8.dp))
        Text(it, color = messageColor, fontSize = TextSize.Body)
    }
}

/**
 * Shared colors for the form's primary buttons. Enabled = the brand orange (filled, obvious). Disabled reads
 * as a recessed, switched-off button — a faint surface tint with dimmed text — clearly *off* rather than a
 * second filled style (the solid disabled fill could be mistaken for an active primary that does nothing),
 * and never Material's near-invisible default on near-black (which made "not yet" look like "broken").
 */
@Composable
private fun settingsButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Accent,
    contentColor = MaterialTheme.colorScheme.onPrimary,
    disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
    disabledContentColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
)
