package com.portal.assistant.conversation.tools.music

/**
 * An in-memory [MediaSessions]: the device's active [sessions] (null = session access not granted) and the app
 * [labels] by package (a package with no label reads as itself, like the Android adapter). Records every transport
 * and repeat call in [calls]; a set [repeatFailure] is thrown by [setRepeat], like a session that rejects it.
 */
internal class FakeMediaSessions(
    var sessions: List<ActiveSession>? = emptyList(),
    private val labels: Map<String, String> = emptyMap(),
) : MediaSessions {

    var repeatFailure: Exception? = null

    val calls = mutableListOf<String>()

    override fun active(): List<ActiveSession>? = sessions

    override fun appLabel(pkg: String): String = labels[pkg] ?: pkg

    override fun transport(index: Int, action: MediaAction) {
        calls += "${action.name.lowercase()} $index"
    }

    override fun setRepeat(index: Int, mode: RepeatMode) {
        calls += "repeat ${mode.name.lowercase()} $index"
        repeatFailure?.let { throw it }
    }
}
