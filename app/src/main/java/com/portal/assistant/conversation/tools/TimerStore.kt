package com.portal.assistant.conversation.tools

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide observable mirror of the active timers, so the foreground UI can show live countdown cards
 * (same role [com.portal.assistant.conversation.ConversationHub] plays for the conversation).
 *
 * The canonical store is still SharedPreferences, owned by [TimerScheduler]; this object just holds the
 * latest list as a [StateFlow]. Every [TimerScheduler.mutate] (set / cancel / list / fire, from ANY
 * instance — the engine's per-conversation one or the alarm receiver) calls [publish], so the flow tracks
 * the store without the UI polling. [refresh] seeds it from prefs when the screen opens cold, and [cancel]
 * lets a tapped card cancel a timer — both via a lazily-created, shared [TimerScheduler].
 */
object TimerStore {

    private val _timers = MutableStateFlow<List<TimerEntry>>(emptyList())
    val timers: StateFlow<List<TimerEntry>> = _timers.asStateFlow()

    @Volatile private var scheduler: TimerScheduler? = null

    @Synchronized
    private fun scheduler(context: Context): TimerScheduler = scheduler ?: TimerScheduler(context.applicationContext).also { scheduler = it }

    /** Called by [TimerScheduler.mutate] after every store write. */
    internal fun publish(list: List<TimerEntry>) {
        _timers.value = list
    }

    /** Seed/refresh from the persisted store (a no-op-looking `list()` that also compacts + publishes). */
    fun refresh(context: Context) {
        scheduler(context).list()
    }

    /** Cancel a timer from a tapped card (publishes the new list via mutate). */
    fun cancel(context: Context, id: Int) {
        scheduler(context).cancel(id, null)
    }
}
