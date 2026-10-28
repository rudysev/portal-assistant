package com.portal.assistant.system

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.portal.assistant.gemini.GeminiModel
import com.portal.assistant.gemini.GeminiSpeaker
import com.portal.assistant.local.LocalVoiceHost
import com.portal.commons.DebugLog
import java.io.File

/**
 * Single owner of all [SharedPreferences] keys for the app. Every feature reads/writes through here so
 * there is one canonical view of what the app persists. [prefs] is the only call site for
 * [Context.getSharedPreferences] in the whole app.
 *
 * Key naming convention: `<feature>_<field>` (e.g. `loc_override`, future `tools_enabled_pkgs`).
 */
// One getter/setter pair per persisted preference, deliberately in one object so every key and its read contract
// sit together (see above); splitting it by feature would scatter that single view.
@Suppress("TooManyFunctions")
object AppPrefs {

    private const val NAME = "assistant"

    // Location — managed by LocationProvider
    const val KEY_LOC_JSON = "loc_json" // raw IP-geo API response body
    const val KEY_LOC_OVERRIDE = "loc_override" // explicit user-set location (free text); never auto-written

    // Timers — managed by TimerScheduler
    const val KEY_TIMERS = "timers_json" // JSON array of active TimerEntry
    const val KEY_TIMER_NEXT_ID = "timer_next_id" // monotonic int; also the PendingIntent requestCode

    // External tool providers (Phase 3.d) — packages the user has enabled to contribute tools. Off by
    // default: an installed provider grants nothing until its package is added here.
    const val KEY_TOOLS_ENABLED = "tools_enabled_pkgs" // Set<String> of enabled provider package names

    // Model — the Gemini model the assistant runs (Settings → Model). Defaults to GeminiModel.DEFAULT.
    const val KEY_MODEL = "model_id"

    // Speaker — the Chosen speaker the Gemini voice backend talks with (Settings → Voice). Unset = Server
    // default (Gemini picks its own voice). Holds only GeminiSpeaker catalog names; the Local backend ignores it.
    const val KEY_SPEAKER = "gemini_speaker"

    // Default music app (Settings → Default music app) — the package play_music targets when the user
    // doesn't name an app. Unset → resolved at play time (sole music app, else Spotify). Self-heals if the
    // pick is uninstalled.
    const val KEY_DEFAULT_MUSIC_PKG = "music_default_pkg"

    // Gemini API key — the user's own key (Settings → API key). BYOD: a broadly-released build has no key
    // baked in, so each user supplies their own here. Falls back to BuildConfig.GEMINI_API_KEY (dev builds)
    // at the injection site; this stores only the user-entered value.
    const val KEY_API = "gemini_api_key"

    // Listening tone — whether a voice conversation's start plays the Listening tone (Settings → Sounds).
    // On by default: an absent key means on.
    const val KEY_LISTENING_TONE = "sounds_listening_tone"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** The selected model id, or [GeminiModel.DEFAULT]'s if unset or no longer offered. Self-heals: a saved id that
     *  fell out of the catalog is cleared so prefs never retain an orphan. */
    fun modelId(context: Context): String {
        val saved = prefs(context).getString(KEY_MODEL, null)
        val offered = saved?.takeIf { GeminiModel.find(it) != null }
        if (saved != null && offered == null) {
            prefs(context).edit {
                remove(KEY_MODEL)
            } // orphan from a catalog change — drop it
        }
        return offered ?: GeminiModel.DEFAULT.id
    }

    /** Persist the chosen model (applies to the next conversation). Ignores ids outside the catalog so prefs
     *  always match the read contract. */
    fun setModelId(context: Context, id: String) {
        if (GeminiModel.find(id) == null) return
        prefs(context).edit { putString(KEY_MODEL, id) }
    }

    /** The Chosen speaker's name, or null for Server default — also when the saved name is no longer in
     *  [GeminiSpeaker.AVAILABLE]. Self-heals: an orphaned name is cleared so prefs never retain it. */
    fun speakerId(context: Context): String? {
        val saved = prefs(context).getString(KEY_SPEAKER, null) ?: return null
        val speaker = GeminiSpeaker.find(saved)
        if (speaker == null) prefs(context).edit { remove(KEY_SPEAKER) } // orphan from a catalog change — drop it
        return speaker?.name
    }

    /** Persist the Chosen speaker (applies to the next conversation), or return to Server default with null.
     *  Ignores names outside the catalog so prefs always match the read contract. */
    fun setSpeakerId(context: Context, name: String?) {
        if (name == null) {
            prefs(context).edit { remove(KEY_SPEAKER) }
            return
        }
        if (GeminiSpeaker.find(name) == null) return
        prefs(context).edit { putString(KEY_SPEAKER, name) }
    }

    /** The user's chosen default music app package, or null if unset — or if the pick is no longer installed
     *  (self-heals: an uninstalled default is cleared so prefs never point at a gone app, and play routing
     *  falls back to its next choice). */
    fun defaultMusicPkg(context: Context): String? {
        val saved = prefs(context).getString(KEY_DEFAULT_MUSIC_PKG, null)?.trim()?.ifBlank { null } ?: return null
        val installed = context.packageManager.getLaunchIntentForPackage(saved) != null
        if (!installed) prefs(context).edit { remove(KEY_DEFAULT_MUSIC_PKG) } // uninstalled pick — drop it
        return saved.takeIf { installed }
    }

    /** Set the default music app package, or clear it with a null/blank value (applies to the next play). */
    fun setDefaultMusicPkg(context: Context, pkg: String?) {
        prefs(context).edit {
            if (pkg.isNullOrBlank()) remove(KEY_DEFAULT_MUSIC_PKG) else putString(KEY_DEFAULT_MUSIC_PKG, pkg)
        }
    }

    /** Whether the Listening tone plays when a voice conversation starts; true unless the user turned it off. */
    fun listeningToneEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_LISTENING_TONE, true)

    /** Turn the Listening tone on or off (applies to the next conversation). */
    fun setListeningToneEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_LISTENING_TONE, enabled) }
    }

    /** The user's stored Gemini API key, trimmed, or null if unset/blank. The injection site
     *  ([AssistantService]) falls back to the baked dev key when this is null. */
    fun apiKey(context: Context): String? = prefs(context).getString(KEY_API, null)?.trim()?.ifBlank { null }

    /** Store the user's Gemini API key (applies to the next conversation). A blank value clears it. */
    fun setApiKey(context: Context, key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            clearApiKey(context)
        } else {
            prefs(context).edit { putString(KEY_API, trimmed) }
        }
    }

    /** Forget the user's stored key (reverts to the baked dev key, if any). */
    fun clearApiKey(context: Context) {
        prefs(context).edit { remove(KEY_API) }
    }

    // Voice backend — which model powers a conversation (Settings → Backend). Ships defaulting to GEMINI
    // (the cloud Gemini Live API); LOCAL is an opt-in path for users running their own model on a
    // voice host on the home LAN (Settings → host address).
    const val KEY_VOICE_BACKEND = "voice_backend" // VoiceBackendKind.name
    const val KEY_LOCAL_VOICE_HOST = "local_voice_host" // canonical wss://host:port from [LocalVoiceHost]

    /**
     * Which voice backend powers a conversation. See [Backends][com.portal.assistant.conversation.backend.Backends].
     */
    enum class VoiceBackendKind { GEMINI, LOCAL }

    /** The selected backend, defaulting to [VoiceBackendKind.GEMINI] if unset/unknown. */
    fun voiceBackendKind(context: Context): VoiceBackendKind {
        val saved = prefs(context).getString(KEY_VOICE_BACKEND, null)
        return if (saved == VoiceBackendKind.LOCAL.name) VoiceBackendKind.LOCAL else VoiceBackendKind.GEMINI
    }

    /** Persist the chosen backend (applies to the next conversation). */
    fun setVoiceBackendKind(context: Context, kind: VoiceBackendKind) {
        prefs(context).edit { putString(KEY_VOICE_BACKEND, kind.name) }
    }

    /** Canonical `wss://host:port` for the local voice host, or null if unset/invalid. Self-heals corrupt prefs. */
    fun localVoiceHost(context: Context): String? {
        val saved = prefs(context).getString(KEY_LOCAL_VOICE_HOST, null)?.trim()?.ifBlank { null } ?: return null
        return when (val parsed = LocalVoiceHost.parse(saved)) {
            is LocalVoiceHost.ParseResult.Ok -> parsed.wssUrl

            is LocalVoiceHost.ParseResult.Invalid -> {
                prefs(context).edit { remove(KEY_LOCAL_VOICE_HOST) }
                null
            }
        }
    }

    /** Set the local voice host (`host:port` or `wss://host:port`); invalid input is ignored. Null/blank clears. */
    fun setLocalVoiceHost(context: Context, host: String?) {
        val wssUrl = if (host.isNullOrBlank()) {
            null
        } else {
            when (val parsed = LocalVoiceHost.parse(host)) {
                is LocalVoiceHost.ParseResult.Ok -> parsed.wssUrl
                is LocalVoiceHost.ParseResult.Invalid -> return // same contract as setModelId — reject silently
            }
        }
        prefs(context).edit {
            if (wssUrl == null) remove(KEY_LOCAL_VOICE_HOST) else putString(KEY_LOCAL_VOICE_HOST, wssUrl)
        }
    }

    /** Name of the one-shot provisioning file the install script drops in the app's external files dir. */
    const val PROVISION_FILE = "api_key.txt"

    /**
     * Import a key provisioned at install time and delete the file (import-once). `setup.sh` writes the
     * user's key to `<externalFilesDir>/api_key.txt` (adb can write there without root); on the next app
     * start we move it into prefs and remove the file, so the key never lingers in plaintext on disk. The
     * value is never logged. Called from [AssistantService] and [MainActivity] onCreate (idempotent — a
     * no-op once the file is gone). An existing user key is not overwritten by an empty provisioning file.
     */
    // UseKtx: the core-ktx edit {} returns Unit, and this needs commit()'s result to know the key is on disk.
    @SuppressLint("UseKtx")
    fun importProvisionedKey(context: Context) {
        val f = File(context.getExternalFilesDir(null), PROVISION_FILE)
        // Keep the file if the read fails (transient FS error) so the next launch can retry — don't delete a
        // key we never imported.
        val provisioned = (if (f.exists()) runCatching { f.readText().trim() }.getOrNull() else null) ?: return
        if (provisioned.isEmpty()) {
            f.delete() // nothing to import — drop the empty file
            return
        }
        // commit() (synchronous, durable) BEFORE deleting the source, so a crash/kill right after can't leave
        // the key in neither prefs nor file. Delete only once the write is on disk.
        if (prefs(context).edit().putString(KEY_API, provisioned).commit()) {
            DebugLog.log("imported provisioned api key")
            f.delete()
        }
    }

    /** Provider packages the user has enabled to contribute external tools. A defensive copy — the set
     *  SharedPreferences returns must never be mutated. */
    fun enabledProviders(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_TOOLS_ENABLED, null)?.toSet().orEmpty()

    /** Add/remove [pkg] from the external-tool allowlist (applies to the next conversation). */
    fun setProviderEnabled(context: Context, pkg: String, enabled: Boolean) {
        val updated = enabledProviders(context).toMutableSet().apply { if (enabled) add(pkg) else remove(pkg) }
        prefs(context).edit { putStringSet(KEY_TOOLS_ENABLED, updated) }
    }
}
