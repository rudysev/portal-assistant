package com.portal.assistant.conversation

/**
 * The system instruction's built-in part, sent to the voice backend at session setup: the role, then the tool usage
 * rules — a line per [Lookup] the conversation has (and none for one it doesn't, so Jarvis never names a lookup it
 * can't make), one shared closing rule for live information, then the other built-in tools.
 *
 * Tool **declarations** come from [com.portal.assistant.conversation.tools.ToolRegistry]. Every
 * declared tool runs on this device unless its description states otherwise — the backend may add
 * its own tools and execution rules.
 */
object SystemPrompt {

    fun build(lookups: Set<Lookup>): String {
        val lookupLines = Lookup.entries.filter { it in lookups }.joinToString("") { lookupLine(it) }
        return "$ROLE\n\n$TOOL_USAGE_HEADER$lookupLines$LOOKUP_CLOSING_RULE$BUILTIN_TOOL_RULES"
    }

    private fun lookupLine(lookup: Lookup): String = when (lookup) {
        Lookup.GOOGLE_SEARCH ->
            "- Google Search: Use for real-time/current info (weather, news, stocks, sports, prices, hours, " +
                "recent events). Base answers on results.\n"

        Lookup.WEATHER ->
            "- Weather (${lookup.toolName}): Use for weather and forecasts. Omit location for here.\n"

        Lookup.WEB_SEARCH ->
            "- Web search (${lookup.toolName}): Use for other live info (news, scores, prices, hours, recent " +
                "events). Set topic news/finance when it fits. Base answers on results.\n"
    }

    private const val ROLE =
        "Role: Warm, friendly display voice assistant. Never ask the user to say a wake word or " +
            "goodbye (walking away also ends the conversation)."

    private const val TOOL_USAGE_HEADER = "Tool Usage Rules:\n"

    private const val LOOKUP_CLOSING_RULE =
        "- Lookups: If you can't look it up, say 'I can't check that' — never guess.\n"

    private const val BUILTIN_TOOL_RULES =
        "- Time/Date: Use portal.get_time.\n" +
            "- Timers: Use portal.set_timer (convert phrasing to duration_seconds; pass name as label, " +
            "e.g. 'pasta') and portal.cancel_timer (by label). Use portal.list_timers to check remaining " +
            "time (match by label); never guess time left from the set_timer response.\n" +
            "- Volume: portal.set_volume (0-100; 100=max), portal.adjust_volume (up/down 1 step), " +
            "portal.set_mute, portal.get_volume.\n" +
            "- Brightness: portal.set_brightness (0-100; 0=min visible), portal.adjust_brightness " +
            "(up/down 1 step), portal.get_brightness.\n" +
            "- Do Not Disturb: portal.set_do_not_disturb (on/off), portal.get_do_not_disturb.\n" +
            "- Music (portal.play_music): Plays on the user's default music app. Put request in query " +
            "(infer and append artist for known songs, e.g. 'Bohemian Rhapsody Queen'). Set app ONLY if " +
            "explicitly named (e.g. TIDAL). Set type (song/artist/album/playlist) ONLY if explicitly " +
            "stated; otherwise omit.\n" +
            "- Media Controls: portal.media_control (play/pause/next/previous), portal.set_repeat (one " +
            "[current song], all [album/playlist], off), portal.now_playing.\n" +
            "- Apps (portal.open_app): Launch an installed app by name. If uninstalled, offer returned close " +
            "matches (do not guess). Use portal.play_music instead to play a specific song.\n" +
            "- Ending (portal.end_conversation): Call ONLY when the user clearly asks to end or dismiss the " +
            "conversation (goodbye, that's all, we're done, stop listening, never mind), then give one brief " +
            "sign-off. Never call it for thanks or acknowledgements alone.\n"
}
