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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.portal.assistant.conversation.backend.Backends
import com.portal.assistant.conversation.tools.AppEntry
import com.portal.assistant.conversation.tools.ExternalToolProvider
import com.portal.assistant.conversation.tools.PackageCatalog
import com.portal.assistant.conversation.tools.music.MediaRouting
import com.portal.assistant.gemini.GeminiModel
import com.portal.assistant.system.AppPrefs
import com.portal.assistant.system.LocationProvider
import com.portal.assistant.ui.theme.Dims
import com.portal.assistant.ui.theme.Measure
import com.portal.assistant.ui.theme.SECONDARY_ALPHA
import com.portal.assistant.ui.theme.TextSize
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The app's settings surface: **backend** (Gemini cloud vs a local voice host on the LAN), the **API key**
 * when Gemini is selected (BYOD — paste or type + verify), the **model** picker and the **voice** (Speaker)
 * picker (Gemini only), the **local host address** (Local only), the **location** used for weather / "near me" answers,
 * with the Weather data credit, the default music app,
 * **sounds** (the Listening tone switch), and the **external tools** allowlist. Changes apply to the **next**
 * conversation (device context, model, and tool
 * declarations are fixed at session start).
 *
 * Location is auto-detected once via IP geolocation on first install. The field is pre-filled from the
 * detected value immediately (or via a short poll if geo is still in flight). The detected value drives
 * the prompt with full coordinates until the user explicitly edits and saves — only then does an override
 * take effect. The field cannot be saved blank.
 */
// ioDispatcher: where the sections' PackageManager and network calls run, off the main thread.
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    // Helper/caption text raised to clear AA at viewing distance.
    val subtle = MaterialTheme.colorScheme.onBackground.copy(alpha = SECONDARY_ALPHA)

    // A form reads best in a constrained, centered column — never run edge-to-edge across the wide Portal
    // display (the conversation screen caps its measure the same way). The scroll lives on the full-bleed
    // Box; the content inside is width-capped and centered.
    Box(
        modifier = modifier
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
            SettingsTitle(onBack = onBack, subtle = subtle)
            BackendSections(subtle = subtle, ioDispatcher = ioDispatcher)

            Spacer(Modifier.size(36.dp))
            SoundsSection(subtle = subtle)

            Spacer(Modifier.size(36.dp))
            LocationSection(subtle = subtle)

            Spacer(Modifier.size(36.dp))
            MusicAppSection(subtle = subtle, ioDispatcher = ioDispatcher)

            Spacer(Modifier.size(36.dp))
            ExternalToolsSection(subtle = subtle)
        }
    }
}

/** The back arrow, the screen title and the one note on when changes apply. */
@Composable
private fun SettingsTitle(onBack: () -> Unit, subtle: Color) {
    Column {
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
            Text(
                "Settings",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = TextSize.ScreenTitle,
                fontWeight = FontWeight.SemiBold,
            )
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
    }
}

/** The backend picker, then what that backend needs: the local host address, or Gemini's model, voice and key. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackendSections(subtle: Color, ioDispatcher: CoroutineDispatcher) {
    val context = LocalContext.current
    var backend by remember { mutableStateOf(AppPrefs.voiceBackendKind(context)) }
    Column {
        Spacer(Modifier.size(28.dp))
        SectionHeading("Backend", "Where Jarvis runs — Gemini in the cloud, or a local server on your network.", subtle)
        SettingsDropdown(
            label = "Backend",
            value = backendLabel(backend),
            options = AppPrefs.VoiceBackendKind.entries,
            optionLabel = ::backendLabel,
            onSelect = { option ->
                backend = option
                AppPrefs.setVoiceBackendKind(context, option)
                Backends.warmDns(context)
            },
        )

        if (backend == AppPrefs.VoiceBackendKind.LOCAL) {
            Spacer(Modifier.size(28.dp))
            SectionHeading("Local server", "Where the voice host runs on your network.", subtle)
            LocalVoiceHostSection(subtle = subtle)
        }

        if (backend == AppPrefs.VoiceBackendKind.GEMINI) {
            Spacer(Modifier.size(28.dp))
            SectionHeading("Model", "Which model Jarvis uses.", subtle)
            var model by remember { mutableStateOf(AppPrefs.modelId(context)) }
            SettingsDropdown(
                label = "Model",
                value = GeminiModel.of(model).name,
                options = GeminiModel.AVAILABLE,
                optionLabel = { it.name },
                onSelect = { picked ->
                    model = picked.id
                    AppPrefs.setModelId(context, picked.id)
                },
            )

            Spacer(Modifier.size(28.dp))
            SectionHeading("Voice", "Which voice Jarvis speaks with.", subtle)
            SpeakerSection()

            Spacer(Modifier.size(36.dp))
            ApiKeySection(subtle = subtle, ioDispatcher = ioDispatcher)
        }
    }
}

/**
 * The location used for weather and other location-aware answers. Pre-filled from the override, else the
 * detected label; while geolocation is still in flight it polls briefly for the detected value.
 */
@Composable
private fun LocationSection(subtle: Color) {
    val context = LocalContext.current
    var place by remember {
        mutableStateOf(LocationProvider.override(context) ?: LocationProvider.detectedLabel(context).orEmpty())
    }
    // The last-persisted value — gates the Save button (disabled until the field differs) and re-disables it
    // after a save, so saving gives feedback without leaving the screen.
    var savedPlace by remember { mutableStateOf(place) }

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

    Column {
        SectionHeading("Location", "Used for weather and other location-aware answers.", subtle)
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

        Spacer(Modifier.size(18.dp))
        WeatherCredit(subtle = subtle)
    }
}

/** The Weather lookup's data credit, linked as its licence (CC BY 4.0) asks. */
@Composable
private fun WeatherCredit(subtle: Color) {
    val uriHandler = LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.primary
    val credit = remember(linkColor) {
        buildAnnotatedString {
            append("Weather data by ")
            // A Portal may have no browser: a link that can't open does nothing rather than crash.
            val link = LinkAnnotation.Clickable(
                tag = "open-meteo",
                styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
            ) { runCatching { uriHandler.openUri(OPEN_METEO_URL) } }
            withLink(link) { append("Open-Meteo.com") }
        }
    }
    Text(credit, color = subtle, fontSize = TextSize.Body)
}

/** The default music app picker, rendering the same effective default that play routing uses. */
@Composable
private fun MusicAppSection(subtle: Color, ioDispatcher: CoroutineDispatcher) {
    val context = LocalContext.current
    // Enumerating installed apps + the saved default's install check are PackageManager IPCs — load
    // them off the main thread so opening Settings on a cold cache can't jank/ANR the UI.
    // The discovered set and the union (selectable list) — effectiveDefault needs both to render the
    // same default routing plays.
    val apps by produceState(emptyList<AppEntry>() to emptyList<AppEntry>()) {
        value = withContext(ioDispatcher) { PackageCatalog.musicAppChoices(context) }
    }
    val discovered = apps.first
    val selectableApps = apps.second
    var musicPkg by remember { mutableStateOf<String?>(null) }
    // Label for a default not (yet) in the loaded list — also resolved off-main below.
    var savedMusicLabel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val pkg = withContext(ioDispatcher) { AppPrefs.defaultMusicPkg(context) }
        musicPkg = pkg
        savedMusicLabel = pkg?.let { withContext(ioDispatcher) { PackageCatalog.labelFor(context, it) } }
    }
    // Render the *effective* default: the explicit pick, else what a bare "play music" resolves to —
    // the SAME set + rule routing uses (effectiveDefault), so the field never lies. No vague "Auto".
    val effectivePkg = musicPkg
        ?: MediaRouting.effectiveDefault(discovered, { selectableApps }, PackageCatalog.SPOTIFY_PKGS)?.pkg
    val musicLabel = when (effectivePkg) {
        null -> "Choose a music app"
        else -> selectableApps.firstOrNull { it.pkg == effectivePkg }?.label ?: savedMusicLabel ?: effectivePkg
    }
    Column {
        SectionHeading("Default music app", "Choose the default app to play music.", subtle)
        SettingsDropdown(
            label = "Default music app",
            value = musicLabel,
            options = selectableApps,
            optionLabel = { it.label },
            onSelect = { entry ->
                musicPkg = entry.pkg
                AppPrefs.setDefaultMusicPkg(context, entry.pkg)
            },
        )
    }
}

/** The allowlist of installed tool-provider apps; each is off until the user enables it. */
@Composable
private fun ExternalToolsSection(subtle: Color) {
    val context = LocalContext.current
    var providers by remember { mutableStateOf(ExternalToolProvider.providersForSettings(context)) }
    Column {
        SectionHeading(
            "External tools",
            "Installed apps that add abilities to Jarvis. Off until you enable them.",
            subtle,
        )
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

/** Human label for the backend picker. */
private fun backendLabel(kind: AppPrefs.VoiceBackendKind): String = when (kind) {
    AppPrefs.VoiceBackendKind.GEMINI -> "Gemini (cloud)"
    AppPrefs.VoiceBackendKind.LOCAL -> "Local server (LAN)"
}

// The location field waits up to GEO_POLLS × GEO_POLL_MS (10 s) for an in-flight geolocation.
private const val GEO_POLLS = 20
private const val GEO_POLL_MS = 500L
private const val OPEN_METEO_URL = "https://open-meteo.com/"
