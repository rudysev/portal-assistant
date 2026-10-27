package com.portal.assistant.conversation.tools.music

/**
 * An in-memory [MediaSessions]: the device's active [sessions] (null = session access not granted) and the app
 * [labels] by package (a package with no label reads as itself, like the Android adapter).
 */
internal class FakeMediaSessions(
    var sessions: List<ActiveSession>? = emptyList(),
    private val labels: Map<String, String> = emptyMap(),
) : MediaSessions {

    override fun active(): List<ActiveSession>? = sessions

    override fun appLabel(pkg: String): String = labels[pkg] ?: pkg
}
