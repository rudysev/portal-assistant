package com.portal.assistant.conversation.tools.music

import com.portal.assistant.conversation.tools.PackageCatalog
import com.portal.assistant.conversation.tools.Tool
import com.portal.assistant.conversation.tools.ToolOutput
import com.portal.commons.DebugLog
import org.json.JSONObject

/**
 * `portal.play_music`: starts music by name (song, artist, album or playlist) on the app the user named, else their
 * default. [MediaRouting] picks the app and the strategy (Spotify's search deep link, play-from-search, or just
 * opening the app); [apps] fires it. A start that worked is a **final turn**; an opened app (`searchUnsupported`: the
 * model must say it only opened it) or an error keeps the conversation open.
 *
 * Spotify gets a *search* deep link, not an exact `spotify:track:<id>`: the Portal's Spotify is Free-tier (can't
 * on-demand-play one track, a precise URI just opens the page) and blocks play-from-search and media keys, so a broad
 * search via the app's own link is what actually starts playback (device-verified).
 */
internal class PlayMusic(private val apps: MusicApps) : Tool {

    override val name = "portal.play_music"

    override val declaration: JSONObject = JSONObject(
        """{"name":"portal.play_music",
           "description":"Start playing a song, artist, album or playlist on a music app. If the result has 'searchUnsupported', the app was only opened: tell the user you opened it rather than claiming it's playing. For resume/pause/skip of the current track use portal.media_control instead.",
           "parameters":{"type":"OBJECT",
             "properties":{
               "query":{"type":"STRING","description":"What to play, e.g. 'Here Comes the Sun', 'Taylor Swift', 'some jazz' or 'Discover Weekly'. Add the artist only for a song clearly by one artist (e.g. 'Bohemian Rhapsody Queen'); otherwise use the user's words. Don't add words like 'live', 'cover', 'remix' or 'karaoke' unless the user asked for that."},
               "app":{"type":"STRING","description":"The music app to play on, only when the user names one (e.g. 'play Zanarkand on TIDAL' -> 'TIDAL'). Omit it to use their default music app."},
               "type":{"type":"STRING","description":"What kind to play, only when the user says so: 'song', 'artist', 'album' or 'playlist' (e.g. 'play the album Thriller' -> 'album'). Omit it otherwise."}},
             "required":["query"]}}""",
    )

    override fun invoke(args: JSONObject): ToolOutput {
        val query = args.optString("query").trim()
        if (query.isEmpty()) return ToolOutput.error("no song or artist specified")

        // The fallback set (search-play handlers + Spotify) is a targeted query; pass it as a thunk so it only runs
        // on the paths that need it: a named app missing from the music list, or an undiscovered Spotify default.
        val target = MediaRouting.resolveTarget(
            args.optString("app").ifBlank { null },
            apps.defaultPkg(),
            apps.musicApps(),
            { apps.searchPlayableApps() },
            PackageCatalog.SPOTIFY_PKGS,
        )
        return target.pkg?.let { pkg ->
            playOn(pkg, target.label ?: apps.appLabel(pkg), query, MediaRouting.playType(args.optString("type")))
        } ?: ToolOutput.error(target.reason ?: "couldn't find a music app to play on")
    }

    private fun playOn(pkg: String, label: String, query: String, type: PlayType?): ToolOutput =
        when (MediaRouting.strategyFor(pkg, PackageCatalog.SPOTIFY_PKGS) { apps.hasPlayFromSearch(pkg) }) {
            MediaRouting.PlayStrategy.DEEP_LINK -> deepLink(pkg, query, label)
            MediaRouting.PlayStrategy.PLAY_FROM_SEARCH -> playFromSearch(pkg, query, label, type)
            MediaRouting.PlayStrategy.LAUNCH_ONLY -> launchOnly(pkg, query, label)
        }

    private fun deepLink(pkg: String, query: String, label: String): ToolOutput =
        when (val start = apps.searchDeepLink(pkg, query)) {
            Start.Started -> {
                DebugLog.log("play_music → spotify:search \"$query\"")
                playing(query, label)
            }

            is Start.Failed -> {
                DebugLog.log("play_music spotify failed: ${start.reason}")
                ToolOutput.error("couldn't start $label: ${start.reason}")
            }
        }

    private fun playFromSearch(pkg: String, query: String, label: String, type: PlayType?): ToolOutput =
        when (val start = apps.playFromSearch(pkg, query, type)) {
            Start.Started -> {
                DebugLog.log("play_music → play_from_search $pkg \"$query\" type=${type?.name?.lowercase() ?: "any"}")
                playing(query, label)
            }

            is Start.Failed -> {
                // The activity resolved but failed to start: fall back to just opening the app.
                DebugLog.log("play_music play_from_search failed for $pkg: ${start.reason}")
                launchOnly(pkg, query, label)
            }
        }

    private fun launchOnly(pkg: String, query: String, label: String): ToolOutput =
        when (val start = apps.launch(pkg)) {
            Start.Started -> {
                DebugLog.log("play_music → launch-only $pkg (no search support)")
                ToolOutput(
                    JSONObject()
                        .put("opened", true)
                        .put("searchUnsupported", true) // couldn't start the exact search: the model should say so
                        .put("query", query)
                        .put("app", label),
                )
            }

            is Start.Failed -> {
                DebugLog.log("play_music launch failed for $pkg: ${start.reason}")
                ToolOutput.error("couldn't open $label" + start.reason?.let { ": $it" }.orEmpty())
            }
        }

    private fun playing(query: String, label: String) =
        musicCommandWorked(JSONObject().put("playing", true).put("query", query).put("app", label))
}
