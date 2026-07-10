package com.portal.assistant.conversation.backend

import android.content.Context
import com.portal.assistant.BuildConfig
import com.portal.assistant.conversation.backend.local.LocalBackend
import com.portal.assistant.gemini.GeminiBackend
import com.portal.assistant.system.AppPrefs
import com.portal.assistant.system.LocalVoiceHost
import java.util.concurrent.Executors

/**
 * The single composition point that selects the concrete [VoiceBackend] implementation — deliberately
 * the *only* place in [conversation][com.portal.assistant.conversation] that imports `gemini` (and
 * `local`), so the engine and the pure reducer stay backend-agnostic.
 *
 * [resolve] is intentionally narrow: it picks **which factory** and **which credential** (API key vs
 * `wss://` host URL) from [AppPrefs]. Per-backend wire knobs ([GeminiWireOptions], [LocalWireOptions])
 * and session inputs (system prompt, tool declarations, model id) are assembled into [BackendConfig] at
 * connect time — each factory reads only its slice, so parameters can grow without changing [resolve].
 */
object Backends {
    val gemini: VoiceBackendFactory = GeminiBackend.Factory
    val local: VoiceBackendFactory = LocalBackend.Factory

    private val dnsWarmExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "backend-dns-warm").apply { isDaemon = true }
    }

    /** User-facing notice when [resolve] yields a blank credential — see [CredentialMessages]. */
    fun missingCredentialMessage(kind: AppPrefs.VoiceBackendKind): String = CredentialMessages.missing(kind)

    /** The shipping default factory — Gemini. Used by tests and any code that needs a concrete backend. */
    val default: VoiceBackendFactory = gemini

    /**
     * Result of [resolve]: which backend, its factory, and the credential string that backend expects.
     * Wire/session options are **not** set here — the engine merges [defaultWire] into [BackendConfig]
     * alongside the system prompt and tool declarations.
     */
    data class Choice(
        val kind: AppPrefs.VoiceBackendKind,
        val factory: VoiceBackendFactory,
        val credential: String,
        val defaultWire: BackendWireDefaults = BackendWireDefaults.forKind(kind),
    ) {
        val credentialMissing: Boolean get() = credential.isBlank()
    }

    /** Default wire-option slices per backend — factories may override via [BackendConfig] at create time. */
    data class BackendWireDefaults(
        val gemini: GeminiWireOptions = GeminiWireOptions(),
        val local: LocalWireOptions = LocalWireOptions(),
    ) {
        companion object {
            fun forKind(kind: AppPrefs.VoiceBackendKind): BackendWireDefaults = when (kind) {
                AppPrefs.VoiceBackendKind.GEMINI -> BackendWireDefaults()
                AppPrefs.VoiceBackendKind.LOCAL -> BackendWireDefaults(local = LocalWireOptions())
            }
        }

        /** Fold into a [BackendConfig] builder alongside shared session fields. */
        fun applyTo(config: BackendConfig): BackendConfig = config.copy(gemini = gemini, local = local)
    }

    /**
     * Resolve the user's backend pref into a [Choice], reading the matching credential per call so a change
     * in Settings applies to the next conversation with no restart:
     *  - GEMINI: BYOD API key, else the baked dev key.
     *  - LOCAL: canonical `wss://` host URL from prefs (null/invalid → blank credential).
     */
    fun resolve(context: Context): Choice = resolveChoice(
        selected = AppPrefs.voiceBackendKind(context),
        storedApiKey = AppPrefs.apiKey(context),
        storedLocalWssUrl = AppPrefs.localVoiceHost(context),
        devApiKey = BuildConfig.GEMINI_API_KEY,
    )

    /**
     * Pure resolution of backend + credential — extracted for unit tests (no Android [Context] needed).
     */
    internal fun resolveChoice(
        selected: AppPrefs.VoiceBackendKind,
        storedApiKey: String?,
        storedLocalWssUrl: String?,
        devApiKey: String,
    ): Choice = when (selected) {
        AppPrefs.VoiceBackendKind.LOCAL -> Choice(
            kind = selected,
            factory = local,
            credential = storedLocalWssUrl.orEmpty(),
        )

        AppPrefs.VoiceBackendKind.GEMINI -> Choice(
            kind = selected,
            factory = gemini,
            credential = storedApiKey ?: devApiKey,
        )
    }

    /**
     * Pre-resolve DNS for whichever backend is selected — best-effort, off the hot path. Call after the user
     * changes backend or local host in Settings (and from service prewarm at boot) so the next connect doesn't
     * wait on lookup. Hostnames come from each backend's own wire layer ([GeminiBackend.dnsHost], [LocalVoiceHost]).
     */
    fun warmDns(context: Context) {
        val appContext = context.applicationContext
        dnsWarmExecutor.execute {
            runCatching {
                val hostname = dnsHostFor(appContext) ?: return@runCatching
                java.net.InetAddress.getByName(hostname)
            }
        }
    }

    /** DNS hostname to pre-warm for the selected backend, or null when there is nothing to look up. */
    internal fun dnsHostFor(context: Context): String? = when (AppPrefs.voiceBackendKind(context)) {
        AppPrefs.VoiceBackendKind.LOCAL ->
            AppPrefs.localVoiceHost(context)?.let { LocalVoiceHost.dnsName(it) }

        AppPrefs.VoiceBackendKind.GEMINI ->
            GeminiBackend.dnsHost()
    }
}
