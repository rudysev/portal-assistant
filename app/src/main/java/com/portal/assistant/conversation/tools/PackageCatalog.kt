package com.portal.assistant.conversation.tools

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import com.portal.commons.DebugLog

/**
 * PackageManager enumeration + labelling for [AppLauncher] (`open_app`) and [MediaControl] (`play_music`),
 * kept in one file. All packages are visible without a `<queries>` block only because `targetSdk = 29`;
 * raising it past 30 would need a `<queries>` element (launcher intent + [MEDIA_BROWSER_SERVICE] +
 * `CATEGORY_APP_MUSIC`) or these lists silently go empty.
 */
object PackageCatalog {

    // Portal's standalone Spotify first, then the standard app as a fallback for other devices. Used by
    // [MediaRouting] to pick the deep-link play strategy — NOT a discovery seed (Spotify is found at runtime
    // like any other music app, below).
    val SPOTIFY_PKGS = listOf("com.facebook.aloha.spotifystandalone", "com.spotify.music")

    private const val MEDIA_BROWSER_SERVICE = "android.media.browse.MediaBrowserService"

    /** All launchable apps as (label, package): launcher activities, deduped, self excluded. */
    fun launchable(context: Context): List<AppEntry> = runCatching {
        val pm = context.applicationContext.packageManager
        val own = context.applicationContext.packageName
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0).asSequence()
            .map { it.activityInfo }
            .filter { it.packageName != own && it.applicationInfo.enabled }
            .map { AppEntry(it.loadLabel(pm).toString().trim(), it.packageName) }
            .filter { it.label.isNotEmpty() && pm.getLaunchIntentForPackage(it.pkg) != null }
            .distinctBy { it.pkg }
            .toList()
    }.getOrElse {
        DebugLog.log("app enumeration failed: ${it.message}")
        emptyList()
    }

    /**
     * Apps we can actually start a music search on but that music discovery may have missed: activities
     * handling the generic `MEDIA_PLAY_FROM_SEARCH` intent, plus installed Spotify (which uses its own deep
     * link, not that intent). Used only as the **named-app fallback** in [MediaRouting] so "play … on <app>"
     * resolves an undetected music app (e.g. TIDAL) without matching an arbitrary non-music launcher like
     * Chrome — narrower than [launchable] on purpose.
     */
    fun searchPlayableApps(context: Context): List<AppEntry> = runCatching {
        val pm = context.applicationContext.packageManager
        val own = context.applicationContext.packageName
        val pkgs = LinkedHashSet<String>()
        pm.queryIntentActivities(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH), 0)
            .mapNotNullTo(pkgs) { it.activityInfo?.packageName }
        pkgs.addAll(SPOTIFY_PKGS)
        pkgs.asSequence()
            .filter { it != own && pm.getLaunchIntentForPackage(it) != null }
            .map { AppEntry(labelFor(context, it), it) }
            .distinctBy { it.pkg }
            .sortedBy { it.label }
            .toList()
    }.getOrElse {
        DebugLog.log("search-playable app enumeration failed: ${it.message}")
        emptyList()
    }

    /**
     * For the Settings picker: the discovered music apps and the wider *selectable* set (discovered ∪
     * [searchPlayableApps]), returned together so the discovered enumeration runs once. The selectable set
     * lets a play-from-search app missed by discovery be picked as the default (trusted directly by
     * [MediaRouting]); the discovered set feeds [MediaRouting.effectiveDefault] so the picker renders the
     * default routing plays.
     */
    fun musicAppChoices(context: Context): Pair<List<AppEntry>, List<AppEntry>> {
        val discovered = musicApps(context)
        return discovered to (discovered + searchPlayableApps(context)).distinctBy { it.pkg }.sortedBy { it.label }
    }

    /**
     * Installed music apps, detected at runtime: apps exposing a MediaBrowserService ∪ CATEGORY_APP_MUSIC
     * launchers, each launchable so the launch-only fallback always has a target. No hardcoded seed — a
     * named-but-undetected music app is still reachable via [searchPlayableApps] in [MediaRouting].
     *
     * Cached process-wide (warmed by [warmMusicApps] off the wake path). Same staleness caveat as
     * [ExternalToolProvider]'s snapshot: a music app installed *after* the process warmed won't appear until
     * the next process start — accepted on this appliance, where apps are pre-installed and rarely change.
     */
    fun musicApps(context: Context): List<AppEntry> = cachedMusicApps ?: queryMusicApps(context.applicationContext)?.also { cachedMusicApps = it } ?: emptyList()

    /** Pre-compute the music-app list off the main thread (service prewarm). Idempotent; safe anytime. */
    fun warmMusicApps(context: Context) {
        if (cachedMusicApps == null) queryMusicApps(context.applicationContext)?.let { cachedMusicApps = it }
    }

    @Volatile private var cachedMusicApps: List<AppEntry>? = null

    /** The app's on-device label, trimmed, else the raw package if it can't be resolved. Shared by the
     *  music/speech paths (music picker, spoken now_playing/play_music) so they all name a package the same. */
    fun labelFor(context: Context, pkg: String): String {
        val pm = context.applicationContext.packageManager
        return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().trim() }
            .getOrNull()?.takeIf { it.isNotEmpty() } ?: pkg
    }

    /** Enumerate music apps, or **null on a PackageManager failure** so a transient error isn't cached as a
     *  permanent empty list (a genuinely-empty result is a real empty list and is safe to cache). */
    private fun queryMusicApps(context: Context): List<AppEntry>? = runCatching {
        val pm = context.packageManager
        val own = context.packageName
        val pkgs = LinkedHashSet<String>()
        pm.queryIntentServices(Intent(MEDIA_BROWSER_SERVICE), 0).mapNotNullTo(pkgs) { it.serviceInfo?.packageName }
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC), 0)
            .mapNotNullTo(pkgs) { it.activityInfo?.packageName }
        pkgs.asSequence()
            .filter { it != own && pm.getLaunchIntentForPackage(it) != null }
            .map { AppEntry(labelFor(context, it), it) }
            .distinctBy { it.pkg }
            .sortedBy { it.label }
            .toList()
    }.getOrElse {
        DebugLog.log("music app discovery failed: ${it.message}")
        null
    }
}
