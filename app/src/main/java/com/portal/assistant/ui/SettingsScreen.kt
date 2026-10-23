package com.portal.assistant.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.portal.assistant.conversation.backend.Backends
import com.portal.assistant.conversation.tools.AppEntry
import com.portal.assistant.conversation.tools.ExternalToolProvider
import com.portal.assistant.conversation.tools.MediaRouting
import com.portal.assistant.conversation.tools.PackageCatalog
import com.portal.assistant.gemini.GeminiModel
import com.portal.assistant.system.AppPrefs
import com.portal.assistant.system.LocationProvider
import com.portal.assistant.ui.theme.Dims
import com.portal.assistant.ui.theme.Measure
import com.portal.assistant.ui.theme.SECONDARY_ALPHA
import com.portal.assistant.ui.theme.TextSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The app's settings surface: **backend** (Gemini cloud vs a local voice host on the LAN), the **API key**
 * when Gemini is selected (BYOD — paste or type + verify), the **model** picker and the **voice** (Speaker)
 * picker (Gemini only), the **local host address** (Local only), the **location** used for weather / "near me" answers, the default music app,
 * **sounds** (the Listening tone switch), and the **external tools** allowlist. Changes apply to the **next** conversation (device context, model, and tool
 * declarations are fixed at session start).
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
    val subtle = MaterialTheme.colorScheme.onBackground.copy(alpha = SECONDARY_ALPHA)

    // If geo is still in flight when the screen opens, poll until the result lands (up to 10 s).
    // Stops early if the user starts typing — their input wins over the auto-detected value.
    LaunchedEffect(Unit) {
        if (place.isNotEmpty()) return@LaunchedEffect
        LocationProvider.refreshIfStale(context) // no-op if already running; ensures a retry on failure
        var detected: String? = null
        var polls = 0
        while (detected == null && polls++ < GEO_POLLS && place.isEmpty()) {
            delay(GEO_POLL_MS)
            if (place.isEmpty()) detected = LocationProvider.detectedLabel(context) // user typed: don't overwrite
        }
        if (detected != null) {
            place = detected
            savedPlace = detected // a detected fill is the baseline, not an unsaved edit
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

            // Said once, here — the settings that shape a conversation's opening (backend, key, model, voice, sound,
            // location, tools) are read once when it starts, so they apply from the next one. The default music app
            // is read when Jarvis plays music, so it applies right away.
            Spacer(Modifier.size(6.dp))
            Text(
                "Changes take effect on your next conversation; the default music app applies right away.",
                color = subtle,
                fontSize = TextSize.Body,
            )

            Spacer(Modifier.size(28.dp))
            Text("Backend", color = MaterialTheme.colorScheme.onBackground, fontSize = TextSize.SectionHeader, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(4.dp))
            Text("Where Jarvis runs — Gemini in the cloud, or a local server on your network.", color = subtle, fontSize = TextSize.Body)
            Spacer(Modifier.size(14.dp))

            var backendExpanded by remember { mutableStateOf(false) }
            var backend by remember { mutableStateOf(AppPrefs.voiceBackendKind(context)) }
            ExposedDropdownMenuBox(
                expanded = backendExpanded,
                onExpandedChange = { backendExpanded = it },
            ) {
                OutlinedTextField(
                    value = backendLabel(backend),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Backend") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = backendExpanded) },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = backendExpanded,
                    onDismissRequest = { backendExpanded = false },
                ) {
                    AppPrefs.VoiceBackendKind.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(backendLabel(option)) },
                            onClick = {
                                backend = option
                                AppPrefs.setVoiceBackendKind(context, option)
                                Backends.warmDns(context)
                                backendExpanded = false
                            },
                        )
                    }
                }
            }

            if (backend == AppPrefs.VoiceBackendKind.LOCAL) {
                Spacer(Modifier.size(28.dp))
                Text("Local server", color = MaterialTheme.colorScheme.onBackground, fontSize = TextSize.SectionHeader, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.size(4.dp))
                Text("Where the voice host runs on your network.", color = subtle, fontSize = TextSize.Body)
                Spacer(Modifier.size(14.dp))
                LocalVoiceHostSection(subtle = subtle)
            }

            if (backend == AppPrefs.VoiceBackendKind.GEMINI) {
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
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
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

                Spacer(Modifier.size(28.dp))
                Text("Voice", color = MaterialTheme.colorScheme.onBackground, fontSize = TextSize.SectionHeader, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.size(4.dp))
                Text("Which voice Jarvis speaks with.", color = subtle, fontSize = TextSize.Body)
                Spacer(Modifier.size(14.dp))
                SpeakerSection()

                Spacer(Modifier.size(36.dp))
                ApiKeySection(subtle = subtle)
            }

            Spacer(Modifier.size(36.dp))
            SoundsSection(subtle = subtle)

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
            Text("Default music app", color = MaterialTheme.colorScheme.onBackground, fontSize = TextSize.SectionHeader, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(4.dp))
            Text("Choose the default app to play music.", color = subtle, fontSize = TextSize.Body)
            Spacer(Modifier.size(14.dp))

            // Enumerating installed apps + the saved default's install check are PackageManager IPCs — load
            // them off the main thread so opening Settings on a cold cache can't jank/ANR the UI.
            // The discovered set and the union (selectable list) — effectiveDefault needs both to render the
            // same default routing plays.
            val apps by produceState(emptyList<AppEntry>() to emptyList<AppEntry>()) {
                value = withContext(Dispatchers.IO) { PackageCatalog.musicAppChoices(context) }
            }
            val discovered = apps.first
            val selectableApps = apps.second
            var musicExpanded by remember { mutableStateOf(false) }
            var musicPkg by remember { mutableStateOf<String?>(null) }
            // Label for a default not (yet) in the loaded list — also resolved off-main below.
            var savedMusicLabel by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                val pkg = withContext(Dispatchers.IO) { AppPrefs.defaultMusicPkg(context) }
                musicPkg = pkg
                savedMusicLabel = pkg?.let { withContext(Dispatchers.IO) { PackageCatalog.labelFor(context, it) } }
            }
            // Render the *effective* default: the explicit pick, else what a bare "play music" resolves to —
            // the SAME set + rule routing uses (effectiveDefault), so the field never lies. No vague "Auto".
            val effectivePkg = musicPkg
                ?: MediaRouting.effectiveDefault(discovered, { selectableApps }, PackageCatalog.SPOTIFY_PKGS)?.pkg
            val musicLabel = when (effectivePkg) {
                null -> "Choose a music app"
                else -> selectableApps.firstOrNull { it.pkg == effectivePkg }?.label ?: savedMusicLabel ?: effectivePkg
            }
            ExposedDropdownMenuBox(
                expanded = musicExpanded,
                onExpandedChange = { musicExpanded = it },
            ) {
                OutlinedTextField(
                    value = musicLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Default music app") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = musicExpanded) },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = musicExpanded,
                    onDismissRequest = { musicExpanded = false },
                ) {
                    selectableApps.forEach { entry ->
                        DropdownMenuItem(
                            text = { Text(entry.label) },
                            onClick = {
                                musicPkg = entry.pkg
                                AppPrefs.setDefaultMusicPkg(context, entry.pkg)
                                musicExpanded = false
                            },
                        )
                    }
                }
            }

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
                    // Under the app name we say what it actually adds, in plain words — the provider's own
                    // required one-sentence summary (ToolContract.META_SUMMARY) — not the reverse-domain tool
                    // ids, which meant nothing to a user.
                    SettingsSwitchRow(
                        title = p.label,
                        subtitle = p.summary,
                        checked = p.enabled,
                        onCheckedChange = { on ->
                            AppPrefs.setProviderEnabled(context, p.pkg, on)
                            providers = providers.map { if (it.pkg == p.pkg) it.copy(enabled = on) else it }
                        },
                        subtle = subtle,
                    )
                }
            }
        }
    }
}

/** Human label for the backend picker. */
private fun backendLabel(kind: AppPrefs.VoiceBackendKind): String = when (kind) {
    AppPrefs.VoiceBackendKind.GEMINI -> "Gemini (cloud)"
    AppPrefs.VoiceBackendKind.LOCAL -> "Local server (LAN)"
}

// The location field waits up to GEO_POLLS × GEO_POLL_MS (10 s) for an in-flight geolocation.
private const val GEO_POLLS = 20
private const val GEO_POLL_MS = 500L
