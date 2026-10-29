package com.portal.assistant.conversation

import com.portal.assistant.conversation.backend.BackendConfig
import com.portal.assistant.conversation.backend.Backends
import com.portal.assistant.system.AppPrefs
import com.portal.assistant.system.DeviceLocation
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * No conversation opens — the selected backend has no credential ([ConversationOpening.refusalFor]). [notice] is the
 * backend's "isn't set up yet".
 */
data class Refusal(val notice: String)

/** Everything a conversation opens with. */
data class Opening(
    val transcriptSeed: TranscriptSeed,
    /**
     * The conversation's Lookups ([Lookup.decide], less any whose tool isn't registered): the only lookups the
     * backend config asks for or declares (and the session prompt names Google Search only when it's one).
     */
    val lookups: Set<Lookup>,
    /** Credential, model, Chosen speaker, the session prompt, the tool declarations and whether to ask for Google
     *  Search. */
    val backendConfig: BackendConfig,
    /** The first turn is spoken — false only for a suggestion chip, whose text is the first turn. */
    val voiceQuery: Boolean,
    /** The mic open plays the Listening tone: the setting is on and this is a voice query. */
    val listeningTonePlays: Boolean,
    /** Sent as the user's first turn once the session is ready (the chip's text), or null. */
    val firstText: String?,
) {
    /** The finished session system instruction, as the backend is told it. */
    val sessionPrompt: String get() = backendConfig.systemPrompt
}

/** What the transcript starts from: empty, or the conversation still on screen. */
sealed interface TranscriptSeed {
    data object Fresh : TranscriptSeed

    /** Continue [turnsShown] on screen; [turnsReplayed] (the recent ones within the budget) go to the model. */
    data class Resume(val turnsShown: List<Turn>, val turnsReplayed: List<Turn>) : TranscriptSeed
}

/** The plain-value snapshot a conversation opens from, gathered by the engine at its start. */
data class OpeningInputs(
    /** Only [Backends.Choice.kind] and [Backends.Choice.credential] are read; its factory is never touched. */
    val backendChoice: Backends.Choice,
    val modelId: String,
    /** The model offers Google Search ([Backends.offersGoogleSearch]). */
    val modelOffersGoogleSearch: Boolean,
    /** A Search key is set (Web search on a model without Google Search). */
    val searchKeyPresent: Boolean,
    /** The Chosen speaker, or null for Server default. */
    val speakerId: String?,
    val listeningToneEnabled: Boolean,
    val now: ZonedDateTime,
    val locationOverride: String?,
    val deviceLocation: DeviceLocation?,
    val onScreenTurns: List<Turn>,
    /** Every registered tool; [ConversationOpening.open] drops the lookup tools it didn't decide on. */
    val toolDeclarations: List<JSONObject>,
)

/**
 * Decides whether a conversation can open ([refusalFor], from the backend choice alone) and then how it opens
 * ([open], from its [Trigger] and a plain-value snapshot, [OpeningInputs]): resume or fresh and what is replayed, voice
 * query or not, whether the Listening tone plays, the first text, its Lookups, and everything the voice backend is
 * told up front (the session prompt, via [SessionPrompt], and its [BackendConfig]). Every opening decision lives
 * here; the engine asks [refusalFor] before it builds anything, then gathers the snapshot and runs the I/O, and the
 * service routes triggers and reacts to a [Refusal].
 *
 * Pure: no Android, no I/O, no clock reads (the clock is [OpeningInputs.now]) — so it's unit-tested end to end.
 */
object ConversationOpening {

    // When resuming (a mic tap with a transcript on screen), the size budget (chars ≈ token proxy) of recent history
    // replayed to the model as context — bounds the prompt as a continued chat grows. Device-tunable.
    const val RESUME_MAX_CHARS = 4_000

    /** How a conversation that [refusalFor] let through opens for [trigger]. */
    fun open(trigger: Trigger, inputs: OpeningInputs): Opening {
        val onScreenTurns = inputs.onScreenTurns
        val transcriptSeed = if (trigger == Trigger.MicTap && onScreenTurns.isNotEmpty()) {
            TranscriptSeed.Resume(onScreenTurns, ResumeContext.recentContext(onScreenTurns, RESUME_MAX_CHARS))
        } else {
            TranscriptSeed.Fresh
        }
        val resumeTurns = (transcriptSeed as? TranscriptSeed.Resume)?.turnsReplayed.orEmpty()
        val decided = Lookup.decide(inputs.backendChoice.kind, inputs.modelOffersGoogleSearch, inputs.searchKeyPresent)
        // A lookup tool is declared only when decided; a decided lookup counts only once its tool is registered.
        val declarations = inputs.toolDeclarations.filter { declaration ->
            val name = declaration.optString("name")
            name !in Lookup.toolNames || decided.any { it.toolName == name }
        }
        val declaredNames = declarations.map { it.optString("name") }.toSet()
        val lookups = decided.filterTo(LinkedHashSet()) { it.toolName == null || it.toolName in declaredNames }
        val sessionPrompt = SessionPrompt.build(
            lookups = lookups,
            deviceContextLines = deviceContextLines(inputs),
            resumeTurns = resumeTurns,
        )
        val backendConfig = BackendConfig(
            credential = inputs.backendChoice.credential,
            model = inputs.modelId,
            speaker = inputs.speakerId, // Local ignores it
            systemPrompt = sessionPrompt,
            functionDeclarations = declarations,
            kind = inputs.backendChoice.kind,
            googleSearch = Lookup.GOOGLE_SEARCH in lookups,
        )
        val firstText = (trigger as? Trigger.SuggestionChip)?.text
        val voiceQuery = firstText == null
        return Opening(
            transcriptSeed = transcriptSeed,
            lookups = lookups,
            backendConfig = backendConfig,
            voiceQuery = voiceQuery,
            listeningTonePlays = inputs.listeningToneEnabled && voiceQuery,
            firstText = firstText,
        )
    }

    /**
     * Device context for the session prompt: the snapshot's clock in its zone (the portal.get_time tool gives the
     * live value — the system instruction is frozen at session setup) and the location: the user's override, else the
     * IP-geo guess, else unknown.
     */
    private fun deviceContextLines(inputs: OpeningInputs): List<String> {
        val localDateTime = inputs.now.format(
            DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a", Locale.getDefault()),
        )
        val location = when {
            inputs.locationOverride != null -> SystemContext.overrideLine(inputs.locationOverride)
            inputs.deviceLocation != null -> SystemContext.locationLine(inputs.deviceLocation)
            else -> SystemContext.LOCATION_UNKNOWN
        }
        return listOf(SystemContext.timeLine(localDateTime, inputs.now.zone.id), location)
    }

    /**
     * The only credential rule: a blank credential refuses, with the selected backend's notice. Asked before a
     * conversation builds anything, and by the idle home's setup nudge.
     */
    fun refusalFor(backendChoice: Backends.Choice): Refusal? {
        if (backendChoice.credential.isNotBlank()) return null
        return Refusal(
            when (backendChoice.kind) {
                AppPrefs.VoiceBackendKind.LOCAL ->
                    "Jarvis isn’t set up yet — add your local server address in Settings."

                AppPrefs.VoiceBackendKind.GEMINI -> "Jarvis isn’t set up yet — a Gemini API key is missing."
            },
        )
    }
}
