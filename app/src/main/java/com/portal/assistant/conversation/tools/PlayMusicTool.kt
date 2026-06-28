package com.portal.assistant.conversation.tools

import org.json.JSONObject

/**
 * Starts playing music on Spotify by name (song, artist, album, or playlist) via a `spotify:search:` deep
 * link. The device's Spotify is **Free tier**, which can't on-demand-play an exact track — so a broad,
 * artist-level query that Free can shuffle-play is what actually starts playback. Prefer the artist over a
 * hyper-specific track title for that reason (see [MediaControl.play]).
 */
class PlayMusicTool(private val media: MediaControl) : Tool {

    override val name = "portal.play_music"

    override val declaration: JSONObject = JSONObject(
        """{"name":"portal.play_music",
           "description":"Start playing music on Spotify by name. Set 'query' to what to play — a song, artist, album, or playlist, e.g. 'Here Comes the Sun', 'Taylor Swift', 'some jazz', or 'Discover Weekly'. When the user asks for a specific song, prefer including the ARTIST in the query (e.g. 'play Bohemian Rhapsody' -> 'Bohemian Rhapsody Queen') — Spotify Free can't play one exact track on demand, so an artist-level query is what actually starts playback. Don't add words like 'live', 'cover', 'remix', or 'karaoke' unless the user asked for that. For resume/pause/skip of the current track use portal.media_control instead.",
           "parameters":{"type":"OBJECT",
             "properties":{
               "query":{"type":"STRING","description":"What to play — a song (ideally with its artist), artist, album, or playlist."}},
             "required":["query"]}}""",
    )

    override fun invoke(args: JSONObject): JSONObject = media.play(args.optString("query")) // play() owns the blank-query check + error
}
