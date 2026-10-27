package com.portal.assistant.conversation.tools.music

/**
 * An in-memory [MediaSessions]: the device's active [sessions] (null = session access not granted) and the app
 * [labels] by package (a package with no label reads as itself, like the Android adapter). Records every transport
 * and repeat call in [calls]; a set [repeatFailure] is thrown by [setRepeat], like a session that rejects it.
 *
 * Like the Android adapter, [transport] and [setRepeat] index into the list the last [active] returned, not the
 * current [sessions]: an index outside it (no read yet, or a read of a shorter list) throws
 * [IndexOutOfBoundsException] before anything is recorded, so a missing or misplaced [active] call fails a test.
 */
internal class FakeMediaSessions(
    var sessions: List<ActiveSession>? = emptyList(),
    private val labels: Map<String, String> = emptyMap(),
) : MediaSessions {

    var repeatFailure: Exception? = null

    val calls = mutableListOf<String>()

    private var lastRead: List<ActiveSession> = emptyList()

    override fun active(): List<ActiveSession>? = sessions?.also { lastRead = it }

    override fun appLabel(pkg: String): String = labels[pkg] ?: pkg

    override fun transport(index: Int, action: MediaAction) {
        lastRead[index]
        calls += "${action.name.lowercase()} $index"
    }

    override fun setRepeat(index: Int, mode: RepeatMode) {
        lastRead[index]
        calls += "repeat ${mode.name.lowercase()} $index"
        repeatFailure?.let { throw it }
    }
}
