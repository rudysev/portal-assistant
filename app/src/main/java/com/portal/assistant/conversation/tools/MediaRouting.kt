package com.portal.assistant.conversation.tools

/**
 * Pure play-routing logic for [MediaControl.play] — Android-free so it's unit-tested (same split as
 * [MediaSelection] / [VolumeMath]). Decides **which app** a play targets and **how** to start it, given the
 * installed apps and the user's default. The shell ([MediaControl]) enumerates apps + fires the intent.
 *
 * Two independent decisions:
 * - [resolveTarget] — the package to play on: an app the user *named* (honoured over the default), else the
 *   saved default, else a sensible fallback. Returns a null [MusicTarget.pkg] + a [MusicTarget.reason] when
 *   there's nothing to play (named app not installed, or no default resolvable).
 * - [strategyFor] — how to start playback on that package: Spotify's deep link, the generic
 *   `MEDIA_PLAY_FROM_SEARCH` intent, or (last resort) just launching the app.
 *
 * [fallbackApps] is a thunk (a PackageManager query) so it's only run when the fallback is actually needed;
 * the shell scopes it to music-capable apps, not every launcher.
 */
object MediaRouting {

    /** How to start playback on a chosen package; see [MediaControl] for the firing side of each. */
    enum class PlayStrategy { DEEP_LINK, PLAY_FROM_SEARCH, LAUNCH_ONLY }

    /** The kind the user asked to play, parsed from the model's free-form `type` hint (see [PlayMusicTool]). */
    enum class PlayType { SONG, ARTIST, ALBUM, PLAYLIST }

    /**
     * The resolved play target. [pkg] null ⇒ nothing to play; [reason] carries a speakable explanation.
     * [label] may be null when the target came straight from a validated [defaultPkg] (not from an
     * [AppEntry]); the shell resolves the speakable name in that case.
     */
    data class MusicTarget(val pkg: String?, val label: String?, val reason: String? = null)

    /**
     * Pick the package to play on.
     *
     * - **[spokenApp] given** — the user named an app, which always wins over the default: match it against
     *   the [musicApps] first (the curated music list), then fall back to the music-capable [fallbackApps]
     *   (so "on TIDAL" still works if music detection missed it, without matching a non-music launcher). No
     *   confident match ⇒ a null target naming the app — we never silently substitute a different app.
     * - **no [spokenApp]** — use [defaultPkg] if set; it is trusted directly (the caller,
     *   `AppPrefs.defaultMusicPkg`, has already self-healed it to an installed+launchable package), so this
     *   does **not** depend on the app being present in the [musicApps] snapshot. Else the [effectiveDefault]
     *   (sole/Spotify discovered app first, then over the discovered ∪ [fallbackApps] union) — the same set +
     *   rule Settings renders — else a null target asking the user.
     */
    fun resolveTarget(
        spokenApp: String?,
        defaultPkg: String?,
        musicApps: List<AppEntry>,
        fallbackApps: () -> List<AppEntry>,
        spotifyPkgs: List<String>,
    ): MusicTarget {
        if (!spokenApp.isNullOrBlank()) {
            val pick = AppMatch.best(spokenApp, musicApps).pick
                ?: AppMatch.best(spokenApp, fallbackApps()).pick
            return if (pick != null) {
                MusicTarget(pick.pkg, pick.label)
            } else {
                MusicTarget(null, null, reason = "can't play music on \"${spokenApp.trim()}\"")
            }
        }

        // No app named — resolve the default. A saved default is pre-validated as installed by the caller, so
        // trust it directly (label resolved by the shell); no dependency on the music-app snapshot.
        if (defaultPkg != null) return MusicTarget(defaultPkg, null)
        // The same effective default Settings renders, over the same inputs — the picker can't show a default
        // that play won't honor.
        effectiveDefault(musicApps, { (musicApps + fallbackApps()).distinctBy { it.pkg } }, spotifyPkgs)
            ?.let { return MusicTarget(it.pkg, it.label) }
        return MusicTarget(
            null,
            null,
            reason = "no default music app set — say which app to play on, or pick a favorite in Settings",
        )
    }

    /**
     * The concrete app a bare "play some music" resolves to when no app is named and no explicit default is
     * saved — the sole app, else Spotify if present; null when the user must choose (several apps, no
     * Spotify). Shared by [resolveTarget]'s no-default branch and the Settings picker so the rendered and the
     * played default can't drift — which requires it to be **order-independent**: the two call sites pass the
     * same members but in different orders (Settings sorts by label), so the Spotify tiebreak resolves by
     * [spotifyPkgs] priority, not by list position.
     */
    fun autoDefault(apps: List<AppEntry>, spotifyPkgs: List<String>): AppEntry? = apps.singleOrNull() ?: spotifyPkgs.firstNotNullOfOrNull { pk -> apps.firstOrNull { it.pkg == pk } }

    /**
     * The default a bare "play some music" resolves to (no app named, no saved default): the sole/Spotify
     * **discovered** music app wins first (so your one real music app isn't outranked by a fallback-only app),
     * else [autoDefault] over the full [all] union (discovered ∪ search-playable) so an app missed by
     * discovery still counts. [all] is a thunk — the union (a PackageManager query in the shell) is built only
     * when [discovered] doesn't resolve. [resolveTarget] and Settings both call this over the same inputs, so
     * the rendered and the played default can't drift.
     */
    fun effectiveDefault(discovered: List<AppEntry>, all: () -> List<AppEntry>, spotifyPkgs: List<String>): AppEntry? = autoDefault(discovered, spotifyPkgs) ?: autoDefault(all(), spotifyPkgs)

    /**
     * How to start playback on [pkg]: Spotify → its deep link (see [MediaControl] for why), an app handling
     * `MEDIA_PLAY_FROM_SEARCH` → that intent, else launch. [hasPlayFromSearch] is a thunk, so its
     * resolveActivity probe is skipped for the Spotify path.
     */
    fun strategyFor(pkg: String, spotifyPkgs: List<String>, hasPlayFromSearch: () -> Boolean): PlayStrategy = when {
        pkg in spotifyPkgs -> PlayStrategy.DEEP_LINK
        hasPlayFromSearch() -> PlayStrategy.PLAY_FROM_SEARCH
        else -> PlayStrategy.LAUNCH_ONLY
    }

    /**
     * Normalize the model's free-form `type` hint (case/whitespace/singular-plural/simple synonyms) to a
     * [PlayType], or null when it's absent or unrecognized — the caller then leaves the kind unpinned.
     */
    fun playType(raw: String?): PlayType? = when (raw?.trim()?.lowercase()) {
        "song", "songs", "track", "tracks" -> PlayType.SONG
        "artist", "artists" -> PlayType.ARTIST
        "album", "albums" -> PlayType.ALBUM
        "playlist", "playlists" -> PlayType.PLAYLIST
        else -> null
    }
}
