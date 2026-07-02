package com.portal.assistant.conversation.tools

import org.json.JSONObject

/**
 * Starts playing music by name (song, artist, album, or playlist). Plays on the user's default music app
 * unless they name one ("play … on TIDAL"); [MediaControl.play] resolves the target and picks the start
 * mechanism (Spotify deep link, generic `MEDIA_PLAY_FROM_SEARCH`, or a launch-only fallback). When the
 * target is Spotify Free, an artist-level query is what actually starts playback (see [MediaControl.play]).
 */
class PlayMusicTool(private val media: MediaControl) : Tool {

    override val name = "portal.play_music"

    override val declaration: JSONObject = JSONObject(
        """{"name":"portal.play_music",
           "description":"Start playing music by name on a music app. Set 'query' to what to play — a song, artist, album, or playlist, e.g. 'Here Comes the Sun', 'Taylor Swift', 'some jazz', or 'Discover Weekly'. Set 'app' ONLY when the user names a specific app to play on (e.g. 'play Zanarkand on TIDAL' -> app 'TIDAL'); otherwise omit it and their default music app is used. If the target app is Spotify Free, prefer including the ARTIST in the query (e.g. 'play Bohemian Rhapsody' -> 'Bohemian Rhapsody Queen') — Spotify Free can't on-demand-play one exact track, so an artist-level query is what actually starts playback there. Don't add words like 'live', 'cover', 'remix', or 'karaoke' unless the user asked for that. If the result has 'searchUnsupported', the app was only opened (it couldn't start the exact search) — tell the user you opened it rather than claiming it's playing. For resume/pause/skip of the current track use portal.media_control instead.",
           "parameters":{"type":"OBJECT",
             "properties":{
               "query":{"type":"STRING","description":"What to play — a song (ideally with its artist), artist, album, or playlist."},
               "app":{"type":"STRING","description":"Optional. The music app to play on, only when the user names one (e.g. 'Spotify', 'TIDAL', 'Apple Music'). Omit to use the user's default music app."},
               "type":{"type":"STRING","description":"Optional. The KIND to play, only when the user makes it explicit — one of 'song', 'artist', 'album', 'playlist'. E.g. 'play the album Thriller' -> 'album'; 'play the artist Adele' -> 'artist'. Omit for a general request or when unsure, and the app picks the best match."}},
             "required":["query"]}}""",
    )

    override fun invoke(args: JSONObject): JSONObject = media.play(
        args.optString("query"),
        args.optString("app").ifBlank { null },
        args.optString("type").ifBlank { null },
    ) // play() owns the blank-query check + error
}
