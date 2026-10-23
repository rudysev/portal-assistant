package com.portal.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.portal.assistant.conversation.backend.Backends
import com.portal.assistant.gemini.GeminiKeyCheck
import com.portal.assistant.local.LocalVoiceHost
import com.portal.assistant.system.AppPrefs
import com.portal.assistant.ui.theme.Accent
import com.portal.assistant.ui.theme.TextSize
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Strip the canonical `wss://` prefix for display in the host field. */
private fun displayLocalVoiceHost(stored: String?): String = stored?.removePrefix("wss://").orEmpty()

/**
 * Local voice-host entry. **Save** validates shape via [LocalVoiceHost] and stores a canonical `wss://` URL.
 * No live probe — the host may not be running yet, and the device may be offline while the address is still
 * valid.
 */
@Composable
internal fun LocalVoiceHostSection(subtle: Color) {
    val context = LocalContext.current
    val errorColor = MaterialTheme.colorScheme.error

    var saved by remember { mutableStateOf(displayLocalVoiceHost(AppPrefs.localVoiceHost(context))) }
    var draft by remember { mutableStateOf(saved) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageColor by remember { mutableStateOf(subtle) }

    Column {
        OutlinedTextField(
            value = draft,
            onValueChange = {
                if (it.length <= 200) {
                    draft = it
                    message = null
                }
            },
            singleLine = true,
            label = { Text("Local server address") },
            placeholder = { Text("192.168.1.5:8080") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.size(4.dp))
        Text(
            "The voice host on your LAN (TLS). It handles speech, the model, and web search.",
            color = subtle,
            fontSize = TextSize.Body,
        )
        Spacer(Modifier.size(12.dp))
        Button(
            onClick = {
                when (LocalVoiceHost.parse(draft.trim())) {
                    is LocalVoiceHost.ParseResult.Ok -> {
                        AppPrefs.setLocalVoiceHost(context, draft)
                        saved = displayLocalVoiceHost(AppPrefs.localVoiceHost(context))
                        draft = saved
                        Backends.warmDns(context)
                        message = "Address saved ✓"
                        messageColor = OK_COLOR
                    }

                    is LocalVoiceHost.ParseResult.Invalid -> {
                        message = "That address doesn't look right — try host:port (e.g. 192.168.1.5:8080) on your LAN."
                        messageColor = errorColor
                    }
                }
            },
            enabled = draft.isNotBlank() && draft.trim() != saved,
            colors = settingsButtonColors(),
        ) { Text("Save") }

        message?.let {
            Spacer(Modifier.size(8.dp))
            Text(it, color = messageColor, fontSize = TextSize.Body)
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
internal fun ApiKeySection(subtle: Color, ioDispatcher: CoroutineDispatcher) {
    val context = LocalContext.current
    // The Activity's scope, not a composition scope: navigating back (the section leaves composition) mid-
    // verify must NOT cancel the save, or a verified key would silently never persist.
    val scope = LocalLifecycleOwner.current.lifecycleScope
    val errorColor = MaterialTheme.colorScheme.error
    // Pre-fill with the stored key so it's visible (masked) — confirms the app has it loaded.
    val form = remember { ApiKeyForm(saved = AppPrefs.apiKey(context).orEmpty(), subtle = subtle) }

    Column {
        ApiKeyHeading(subtle)
        ApiKeyField(
            draft = form.draft,
            reveal = form.reveal,
            onDraftChange = form::edit,
            onToggleReveal = { form.reveal = !form.reveal },
        )

        Spacer(Modifier.size(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    val candidate = form.draft
                    scope.launch {
                        form.startCheck()
                        val result = withContext(ioDispatcher) { GeminiKeyCheck.validate(candidate) }
                        form.checked(result, candidate, errorColor) { AppPrefs.setApiKey(context, candidate) }
                    }
                },
                enabled = form.draft.isNotBlank() && form.draft != form.saved && !form.checking,
                colors = settingsButtonColors(),
            ) { Text(if (form.checking) "Checking…" else "Save & verify") }
        }

        // Offered only after a network error: store the key without proof, since the device may just be offline.
        if (form.showSaveAnyway) {
            Spacer(Modifier.size(6.dp))
            TextButton(
                onClick = {
                    AppPrefs.setApiKey(context, form.draft)
                    form.savedUnverified()
                },
            ) { Text("Save anyway", color = Accent) }
        }

        form.message?.let {
            Spacer(Modifier.size(8.dp))
            Text(it, color = form.messageColor, fontSize = TextSize.Body)
        }
    }
}

/** [ApiKeySection]'s form state: the stored and edited key, the check in flight, and the message under it. */
private class ApiKeyForm(saved: String, private val subtle: Color) {
    var saved by mutableStateOf(saved)
        private set
    var draft by mutableStateOf(saved)
        private set
    var reveal by mutableStateOf(false)
    var checking by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var messageColor by mutableStateOf(subtle)
        private set
    var showSaveAnyway by mutableStateOf(false)
        private set

    fun edit(text: String) {
        draft = text
        message = null
        showSaveAnyway = false
    }

    fun startCheck() {
        checking = true
        message = null
        showSaveAnyway = false
    }

    /** The check of [candidate] came back [result]; a valid key is stored through [store]. */
    fun checked(result: GeminiKeyCheck.Result, candidate: String, errorColor: Color, store: () -> Unit) {
        checking = false
        if (result == GeminiKeyCheck.Result.Valid) {
            store()
            saved = candidate // re-disables the button until the next edit
            reveal = false
        }
        message = keyCheckMessage(result)
        messageColor = when (result) {
            GeminiKeyCheck.Result.Valid -> OK_COLOR
            GeminiKeyCheck.Result.Invalid -> errorColor
            GeminiKeyCheck.Result.NetworkError -> subtle
        }
        showSaveAnyway = result == GeminiKeyCheck.Result.NetworkError
    }

    /** The draft was stored without a check (after a network error). */
    fun savedUnverified() {
        saved = draft // re-disables the button until the next edit
        reveal = false
        showSaveAnyway = false
        message = "Key saved (not verified)."
        messageColor = subtle
    }
}

/** What a key check tells the user. */
private fun keyCheckMessage(result: GeminiKeyCheck.Result): String = when (result) {
    GeminiKeyCheck.Result.Valid -> "Key saved ✓"
    GeminiKeyCheck.Result.Invalid -> "That key didn't work — check you copied the whole thing."
    GeminiKeyCheck.Result.NetworkError -> "Couldn't reach Google to check the key. Are you online?"
}

@Composable
private fun ApiKeyHeading(subtle: Color) {
    Column {
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
    }
}

/** The masked key field, with a Show/Hide toggle once something is typed. */
@Composable
private fun ApiKeyField(draft: String, reveal: Boolean, onDraftChange: (String) -> Unit, onToggleReveal: () -> Unit) {
    OutlinedTextField(
        value = draft,
        onValueChange = { if (it.length <= 256) onDraftChange(it) },
        singleLine = true,
        label = { Text("Enter your key") },
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            if (draft.isNotEmpty()) {
                TextButton(onClick = onToggleReveal) {
                    Text(if (reveal) "Hide" else "Show", color = Accent, fontSize = TextSize.Body)
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The "saved" message color, a calm green. */
private val OK_COLOR = Color(0xFF6FCF77)
